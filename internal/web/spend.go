package web

import (
	"encoding/json"
	"math"
	"net/http"
	"sync"
	"time"

	"github.com/kccarlos/herdr-sheepdog/internal/eval"
	"github.com/kccarlos/herdr-sheepdog/internal/eventlog"
)

// GPTLivePerMinuteUSD is the GPT-Live list price per minute of open
// session (onboard.GPTLivePrice: "$0.05 per minute of open session").
// OpenAI bills the session's duration, idle or not, so the billed
// seconds from session.usage.updated / session.closed are the whole bill.
const GPTLivePerMinuteUSD = 0.05

// spendTapBuffer is the spend meter's event tap capacity.
const spendTapBuffer = 4096

// SpendPart is the estimated spend of one period (today, or this
// conversation): the GPT-Live minutes and the orchestrator's tokens, each
// priced at list price.
type SpendPart struct {
	// TotalUSD is VoiceUSD + BrainUSD.
	TotalUSD float64 `json:"total_usd"`
	// VoiceSeconds is the GPT-Live session time OpenAI billed (the open
	// session's latest usage included), VoiceUSD its price.
	VoiceSeconds float64 `json:"voice_seconds"`
	VoiceUSD     float64 `json:"voice_usd"`
	// BrainUSD is the orchestrator's token cost; the token counts are its
	// brain.step usage (In counts Cached).
	BrainUSD      float64 `json:"brain_usd"`
	BrainSteps    int     `json:"brain_steps"`
	BrainIn       int64   `json:"brain_in_tokens"`
	BrainCached   int64   `json:"brain_cached_tokens"`
	BrainOut      int64   `json:"brain_out_tokens"`
	BrainUnpriced bool    `json:"brain_unpriced,omitempty"`
}

// SpendResponse is the body of GET /api/spend.
type SpendResponse struct {
	// Day is the local date Today covers.
	Day   string    `json:"day"`
	Today SpendPart `json:"today"`
	// Conversation covers the time since the last New conversation, or
	// since local midnight when there was none today. Since is its start.
	Conversation SpendPart `json:"conversation"`
	Since        time.Time `json:"since"`
	// VoicePerMinuteUSD is the GPT-Live price used.
	VoicePerMinuteUSD float64 `json:"voice_per_minute_usd"`
	// Ready is false until today's event file has been read back.
	Ready bool `json:"ready"`
}

// spendMeter adds up today's estimated spend from the event log: GPT-Live
// billed seconds (voice.session_close billed_s) and orchestrator tokens
// (brain.step), priced with GPTLivePerMinuteUSD and eval.Prices. It reads
// today's day file once at start, then follows a tap.
type spendMeter struct {
	now func() time.Time
	loc *time.Location

	mu    sync.Mutex
	ready bool
	day   string // local date of today
	today spendAcc
	since time.Time
	conv  spendAcc
	// closedLive is the billed seconds of GPT-Live sessions that closed
	// while this meter followed the log: the switcher's BilledSeconds
	// minus this is the open session's time so far.
	closedLive float64
}

type spendAcc struct {
	voiceS   float64
	brainUSD float64
	steps    int
	in, out  int64
	cached   int64
	unpriced bool
}

func newSpendMeter(now func() time.Time, loc *time.Location) *spendMeter {
	if now == nil {
		now = time.Now
	}
	if loc == nil {
		loc = time.Local
	}
	m := &spendMeter{now: now, loc: loc}
	m.rollLocked(now())
	return m
}

// start follows l: it opens a tap first, reads back today's events, then
// applies the tap's events newer than the last one read.
// The tap is returned so the server can close it on shutdown.
func (m *spendMeter) start(l *eventlog.Log) *eventlog.Tap {
	tap := l.Tap(spendTapBuffer)
	go func() {
		var last int64
		now := m.now().In(m.loc)
		midnight := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, m.loc)
		days := []string{midnight.UTC().Format("2006-01-02")}
		if d := now.UTC().Format("2006-01-02"); d != days[0] {
			days = append(days, d)
		}
		for _, d := range days {
			evs, _ := l.ReadDay(d)
			for _, ev := range evs {
				m.add(ev, false)
				last = max(last, ev.ID)
			}
		}
		m.mu.Lock()
		m.ready = true
		m.mu.Unlock()
		for ev := range tap.C() {
			if ev.ID <= last {
				continue
			}
			m.add(ev, true)
		}
	}()
	return tap
}

// rollLocked starts a new day when t's local date is past the current one.
func (m *spendMeter) rollLocked(t time.Time) {
	lt := t.In(m.loc)
	day := lt.Format("2006-01-02")
	if day <= m.day {
		return
	}
	m.day = day
	m.today = spendAcc{}
	m.conv = spendAcc{}
	m.since = time.Date(lt.Year(), lt.Month(), lt.Day(), 0, 0, 0, 0, m.loc)
}

// add books one event. live marks an event seen on the tap (not read
// back from disk).
func (m *spendMeter) add(ev eventlog.Event, live bool) {
	switch ev.Type {
	case "voice.session_close", "brain.step", "conversation.reset":
	default:
		return
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.rollLocked(m.now())
	if ev.TS.In(m.loc).Format("2006-01-02") != m.day {
		return
	}
	switch ev.Type {
	case "conversation.reset":
		if ev.TS.After(m.since) {
			m.since, m.conv = ev.TS, spendAcc{}
		}
	case "voice.session_close":
		var p struct {
			BilledS float64 `json:"billed_s"`
		}
		if json.Unmarshal(ev.Payload, &p) != nil || p.BilledS <= 0 {
			return
		}
		m.today.voiceS += p.BilledS
		if !ev.TS.Before(m.since) {
			m.conv.voiceS += p.BilledS
		}
		if live {
			m.closedLive += p.BilledS
		}
	case "brain.step":
		var p struct {
			Model     string `json:"model"`
			Served    string `json:"served_model"`
			Prompt    int64  `json:"prompt_tokens"`
			Out       int64  `json:"completion_tokens"`
			Reasoning int64  `json:"reasoning_tokens"`
			Cached    int64  `json:"cache_hit_tokens"`
		}
		if json.Unmarshal(ev.Payload, &p) != nil || (p.Prompt == 0 && p.Out == 0) {
			return
		}
		model := p.Served
		if _, ok := eval.Prices[model]; !ok || model == "" {
			model = p.Model
		}
		_, priced := eval.Prices[model]
		usd := eval.Cost(model, eval.Usage{Calls: 1, In: int(p.Prompt), Out: int(p.Out), Reasoning: int(p.Reasoning), Cached: int(p.Cached)})
		m.today.addBrain(usd, p.Prompt, p.Cached, p.Out+p.Reasoning, priced)
		if !ev.TS.Before(m.since) {
			m.conv.addBrain(usd, p.Prompt, p.Cached, p.Out+p.Reasoning, priced)
		}
	}
}

func (a *spendAcc) addBrain(usd float64, in, cached, out int64, priced bool) {
	a.brainUSD += usd
	a.steps++
	a.in += in
	a.cached += cached
	a.out += out
	if !priced {
		a.unpriced = true
	}
}

func (a spendAcc) part(openS float64) SpendPart {
	voiceS := a.voiceS + openS
	voiceUSD := voiceS / 60 * GPTLivePerMinuteUSD
	return SpendPart{
		TotalUSD: round6(voiceUSD + a.brainUSD), VoiceSeconds: math.Round(voiceS), VoiceUSD: round6(voiceUSD),
		BrainUSD: round6(a.brainUSD), BrainSteps: a.steps, BrainIn: a.in, BrainCached: a.cached, BrainOut: a.out, BrainUnpriced: a.unpriced,
	}
}

func round6(f float64) float64 { return math.Round(f*1e6) / 1e6 }

// snapshot is the response, with billedNow the voice switch's billed
// seconds in this run (open session included), or a negative number
// when there is no per-minute voice.
func (m *spendMeter) snapshot(billedNow float64) SpendResponse {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.rollLocked(m.now())
	open := 0.0
	if billedNow > m.closedLive {
		open = billedNow - m.closedLive
	}
	return SpendResponse{
		Day: m.day, Today: m.today.part(open), Conversation: m.conv.part(open), Since: m.since,
		VoicePerMinuteUSD: GPTLivePerMinuteUSD, Ready: m.ready,
	}
}

// handleSpend answers GET /api/spend: today's and this conversation's
// estimated spend.
func (s *Server) handleSpend(w http.ResponseWriter, r *http.Request) {
	billed := 0.0
	if b, ok := s.deps.VoiceSwitch.(interface{ BilledSeconds() float64 }); ok {
		billed = b.BilledSeconds()
	}
	writeJSON(w, http.StatusOK, s.spend.snapshot(billed))
}
