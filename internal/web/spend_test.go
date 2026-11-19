package web

import (
	"encoding/json"
	"math"
	"testing"
	"time"

	"github.com/kccarlos/herdr-sheepdog/internal/eventlog"
)

func spendEvent(t *testing.T, ts time.Time, id int64, typ string, payload map[string]any) eventlog.Event {
	t.Helper()
	raw, err := json.Marshal(payload)
	if err != nil {
		t.Fatal(err)
	}
	return eventlog.Event{ID: id, TS: ts, Type: typ, Payload: raw}
}

func near(a, b float64) bool { return math.Abs(a-b) < 1e-6 }

// The meter adds GPT-Live billed seconds at $0.05/min and orchestrator
// tokens at eval.Prices (cache reads at the cached price), splits off the
// time since the last New conversation, ignores other days, adds the open
// session's seconds from the switcher, and starts over at local midnight.
func TestSpendMeter(t *testing.T) {
	loc := time.FixedZone("CET", 3600)
	now := time.Date(2026, 11, 19, 12, 0, 0, 0, loc)
	m := newSpendMeter(func() time.Time { return now }, loc)
	yesterday := now.Add(-13 * time.Hour) // 2026-11-18 23:00 local
	m.add(spendEvent(t, yesterday, 1, "voice.session_close", map[string]any{"billed_s": 600}), false)
	m.add(spendEvent(t, now.Add(-3*time.Hour), 2, "voice.session_close", map[string]any{"billed_s": 120}), false)
	m.add(spendEvent(t, now.Add(-2*time.Hour), 3, "brain.step", map[string]any{
		"model": "claude-opus-5-5", "served_model": "claude-opus-5-5", "prompt_tokens": 10000, "cache_hit_tokens": 8000, "completion_tokens": 100,
	}), false)
	m.add(spendEvent(t, now.Add(-time.Hour), 4, "conversation.reset", map[string]any{}), false)
	m.add(spendEvent(t, now.Add(-30*time.Minute), 5, "voice.session_close", map[string]any{"billed_s": 60}), true)

	got := m.snapshot(60 + 30) // 60 s closed in this run, 30 s open now
	// Opus: 2000 x $5 + 8000 x $0.20 + 100 x $20 per million.
	brain := (2000*5.0 + 8000*0.20 + 100*20.0) / 1e6
	if got.Day != "2026-11-19" || got.Today.VoiceSeconds != 210 || !near(got.Today.VoiceUSD, 210.0/60*0.05) || !near(got.Today.BrainUSD, brain) {
		t.Errorf("today = %s %+v", got.Day, got.Today)
	}
	if !near(got.Today.TotalUSD, 210.0/60*0.05+brain) || got.Today.BrainSteps != 1 || got.Today.BrainCached != 8000 {
		t.Errorf("today total = %+v", got.Today)
	}
	if got.Conversation.VoiceSeconds != 90 || got.Conversation.BrainUSD != 0 || !got.Since.Equal(now.Add(-time.Hour)) {
		t.Errorf("conversation = %+v since %s", got.Conversation, got.Since)
	}
	m.add(spendEvent(t, now, 6, "brain.step", map[string]any{"model": "mystery-model", "prompt_tokens": 10, "completion_tokens": 1}), true)
	if got := m.snapshot(90); !got.Today.BrainUnpriced {
		t.Errorf("unpriced model not flagged: %+v", got.Today)
	}

	now = now.Add(13 * time.Hour) // the next local day
	got = m.snapshot(90)
	if got.Day != "2026-11-20" || got.Today.VoiceSeconds != 30 || got.Today.BrainUSD != 0 { // only the session still open
		t.Errorf("next day = %s %+v", got.Day, got.Today)
	}
}

// GET /api/spend answers through a server: the meter reads today's
// events back from the log.
func TestSpendEndpoint(t *testing.T) {
	h := newHarness(t, harnessOpts{voice: true})
	h.log.Log("voice", "voice.session_close", "", map[string]any{"billed_s": 60.0})
	var got SpendResponse
	waitUntil(t, "spend counted", func() bool {
		h.getJSON("/api/spend", &got)
		return got.Today.VoiceSeconds == 60
	})
	if !near(got.Today.VoiceUSD, 0.05) || got.VoicePerMinuteUSD != 0.05 {
		t.Errorf("spend = %+v", got)
	}
}
