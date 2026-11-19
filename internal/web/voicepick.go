package web

import (
	"context"
	"net/http"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/kccarlos/herdr-sheepdog/internal/state"
	"github.com/kccarlos/herdr-sheepdog/internal/voice/gptlive"
)

// OpenAIVoiceDeps back GET and PUT /api/voice/openai: the GPT-Live model
// and output voice the /voice page picks. nil turns the routes off (404
// reason "no GPT-Live").
type OpenAIVoiceDeps struct {
	// Model and Voice are the configured voice.openai.model / .voice.
	Model string
	Voice string
	// ListModels lists the GPT-Live models the OpenAI key can use
	// (onboard.ListGPTLiveModels); nil or an error offers only the
	// configured and default models.
	ListModels func(ctx context.Context) ([]string, error)
}

// openAIModelsTTL is how long a GPT-Live model list is kept, and
// openAIModelsRetry how long after a failed list the next try waits.
const (
	openAIModelsTTL   = 10 * time.Minute
	openAIModelsRetry = time.Minute
)

// openAIModelCache caches ListModels.
type openAIModelCache struct {
	mu     sync.Mutex
	models []string
	at     time.Time
	failed time.Time
}

// VoiceOpenAIResponse is the body of GET and PUT /api/voice/openai.
type VoiceOpenAIResponse struct {
	// Provider is the current voice provider; the pick applies when it
	// is "openai".
	Provider string `json:"provider"`
	// Model and Voice are what the next GPT-Live session opens with: the
	// pick, or the configured value without one.
	Model string `json:"model"`
	Voice string `json:"voice"`
	// Picked says Model or Voice came from a pick (not the config).
	Picked           bool     `json:"picked"`
	ConfiguredModel  string   `json:"configured_model"`
	ConfiguredVoice  string   `json:"configured_voice"`
	Models           []string `json:"models"`
	Voices           []string `json:"voices"`
	PricePerMinuteUS float64  `json:"price_per_minute_usd"`
}

// SetVoiceOpenAIRequest is the body of PUT /api/voice/openai. An empty
// field goes back to the configured value.
type SetVoiceOpenAIRequest struct {
	Model string `json:"model"`
	Voice string `json:"voice"`
}

func (s *Server) openAIModels(ctx context.Context) []string {
	d := s.deps.OpenAIVoice
	base := []string{}
	for _, m := range []string{d.Model, gptlive.DefaultModel} {
		if m != "" && !slices.Contains(base, m) {
			base = append(base, m)
		}
	}
	if d.ListModels == nil {
		return base
	}
	c := &s.openAIList
	c.mu.Lock()
	defer c.mu.Unlock()
	now := s.deps.Now()
	if (c.models == nil || now.Sub(c.at) > openAIModelsTTL) && now.Sub(c.failed) > openAIModelsRetry {
		ctx, cancel := context.WithTimeout(ctx, catalogTimeout)
		ids, err := d.ListModels(ctx)
		cancel()
		if err == nil && len(ids) > 0 {
			c.models, c.at = ids, now
		} else {
			c.failed = now
		}
	}
	out := slices.Clone(c.models)
	for _, m := range base {
		if !slices.Contains(out, m) {
			out = append(out, m)
		}
	}
	return out
}

func (s *Server) voiceOpenAIResponse(ctx context.Context) VoiceOpenAIResponse {
	d := s.deps.OpenAIVoice
	ss := s.deps.Store.Snapshot().Session
	cm, cv := d.Model, d.Voice
	if cm == "" {
		cm = gptlive.DefaultModel
	}
	if cv == "" {
		cv = gptlive.DefaultVoice
	}
	resp := VoiceOpenAIResponse{
		Provider: s.voiceProvider(), Model: cm, Voice: cv, ConfiguredModel: cm, ConfiguredVoice: cv,
		Models: s.openAIModels(ctx), Voices: slices.Clone(gptlive.Voices), PricePerMinuteUS: GPTLivePerMinuteUSD,
	}
	if ss.OpenAIVoiceModel != "" {
		resp.Model, resp.Picked = ss.OpenAIVoiceModel, true
	}
	if ss.OpenAIVoice != "" {
		resp.Voice, resp.Picked = ss.OpenAIVoice, true
	}
	if !slices.Contains(resp.Models, resp.Model) {
		resp.Models = append(resp.Models, resp.Model)
	}
	if !slices.Contains(resp.Voices, resp.Voice) {
		resp.Voices = append(resp.Voices, resp.Voice)
	}
	return resp
}

// handleVoiceOpenAI answers the GPT-Live model and voice, and the
// choices.
func (s *Server) handleVoiceOpenAI(w http.ResponseWriter, r *http.Request) {
	if s.deps.OpenAIVoice == nil {
		writeError(w, http.StatusNotFound, "no GPT-Live voice in this daemon")
		return
	}
	writeJSON(w, http.StatusOK, s.voiceOpenAIResponse(r.Context()))
}

// handleSetVoiceOpenAI keeps the GPT-Live model and voice picked on the
// /voice page in the state file (session.openai_voice_model and
// openai_voice). They win over voice.openai.model / .voice from the next
// GPT-Live session on: a session open now keeps its model and voice
// until it closes (idle close, or New conversation). A model must be a
// GPT-Live model ("gpt-live…", the only family this provider speaks) and
// a voice one of gptlive.Voices; anything else is a 400.
func (s *Server) handleSetVoiceOpenAI(w http.ResponseWriter, r *http.Request) {
	if s.deps.OpenAIVoice == nil {
		writeError(w, http.StatusNotFound, "no GPT-Live voice in this daemon")
		return
	}
	var req SetVoiceOpenAIRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "bad body: "+err.Error())
		return
	}
	model, voiceName := strings.TrimSpace(req.Model), strings.ToLower(strings.TrimSpace(req.Voice))
	if model != "" && !strings.HasPrefix(model, "gpt-live") {
		writeError(w, http.StatusBadRequest, "model "+model+" is not a GPT-Live model")
		return
	}
	if voiceName != "" && !slices.Contains(gptlive.Voices, voiceName) {
		writeError(w, http.StatusBadRequest, "unknown voice "+voiceName)
		return
	}
	d := s.deps.OpenAIVoice
	if model == d.Model || (d.Model == "" && model == gptlive.DefaultModel) {
		model = ""
	}
	if voiceName == d.Voice || (d.Voice == "" && voiceName == gptlive.DefaultVoice) {
		voiceName = ""
	}
	if err := s.deps.Store.UpdateSession(func(ss *state.Session) { ss.OpenAIVoiceModel, ss.OpenAIVoice = model, voiceName }); err != nil {
		writeError(w, http.StatusInternalServerError, "persist: "+err.Error())
		return
	}
	s.log.Log("web", "web.voice_openai_pick", "", map[string]any{"model": model, "voice": voiceName})
	writeJSON(w, http.StatusOK, s.voiceOpenAIResponse(r.Context()))
}
