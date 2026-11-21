package web

import (
	"context"
	"errors"
	"net/http"
	"slices"
	"testing"
)

// GET and PUT /api/voice/openai: the configured model and voice until a
// pick, the pick kept in the state file, refusals for a model outside
// the GPT-Live family and an unknown voice, and a pick equal to the
// config stored as no pick.
func TestVoiceOpenAIPick(t *testing.T) {
	h := newHarness(t, harnessOpts{voice: true, switcher: true, openAIVoice: &OpenAIVoiceDeps{
		Model: "gpt-live-1", Voice: "marin",
		ListModels: func(context.Context) ([]string, error) { return []string{"gpt-live-2", "gpt-live-1"}, nil },
	}})
	var got VoiceOpenAIResponse
	if code := h.getJSON("/api/voice/openai", &got); code != http.StatusOK || got.Model != "gpt-live-1" || got.Voice != "marin" || got.Picked {
		t.Fatalf("GET = %d %+v", code, got)
	}
	if !slices.Equal(got.Models, []string{"gpt-live-2", "gpt-live-1", "gpt-live-1.1"}) || !slices.Contains(got.Voices, "cedar") || got.PricePerMinuteUS != 0.05 {
		t.Errorf("choices = %v %v %v", got.Models, got.Voices, got.PricePerMinuteUS)
	}
	var e map[string]string
	if code, _ := h.sendJSON(http.MethodPut, "/api/voice/openai", map[string]any{"model": "gpt-realtime"}, &e); code != http.StatusBadRequest {
		t.Errorf("PUT gpt-realtime = %d %v", code, e)
	}
	if code, _ := h.sendJSON(http.MethodPut, "/api/voice/openai", map[string]any{"voice": "nobody"}, &e); code != http.StatusBadRequest {
		t.Errorf("PUT voice nobody = %d %v", code, e)
	}
	if code, raw := h.sendJSON(http.MethodPut, "/api/voice/openai", map[string]any{"model": "gpt-live-2", "voice": "Cedar"}, &got); code != http.StatusOK || got.Model != "gpt-live-2" || got.Voice != "cedar" || !got.Picked {
		t.Fatalf("PUT = %d %s", code, raw)
	}
	if ss := h.store.Snapshot().Session; ss.OpenAIVoiceModel != "gpt-live-2" || ss.OpenAIVoice != "cedar" {
		t.Errorf("state = %q %q", ss.OpenAIVoiceModel, ss.OpenAIVoice)
	}
	// Back to the configured values: stored as no pick.
	if code, raw := h.sendJSON(http.MethodPut, "/api/voice/openai", map[string]any{"model": "gpt-live-1", "voice": "marin"}, &got); code != http.StatusOK || got.Picked {
		t.Fatalf("PUT config = %d %s", code, raw)
	}
	if ss := h.store.Snapshot().Session; ss.OpenAIVoiceModel != "" || ss.OpenAIVoice != "" {
		t.Errorf("state after reset = %q %q", ss.OpenAIVoiceModel, ss.OpenAIVoice)
	}
}

// Without a model list (no key, a failed call) the configured and
// default models are offered; without OpenAIVoice the routes are 404.
func TestVoiceOpenAIFallbacks(t *testing.T) {
	h := newHarness(t, harnessOpts{voice: true, openAIVoice: &OpenAIVoiceDeps{
		Model:      "gpt-live-1-preview",
		ListModels: func(context.Context) ([]string, error) { return nil, errors.New("offline") },
	}})
	var got VoiceOpenAIResponse
	if code := h.getJSON("/api/voice/openai", &got); code != http.StatusOK || !slices.Equal(got.Models, []string{"gpt-live-1-preview", "gpt-live-1.1"}) || got.Voice != "marin" {
		t.Fatalf("GET = %d %+v", code, got)
	}
	h2 := newHarness(t, harnessOpts{voice: true})
	if code := h2.getJSON("/api/voice/openai", &got); code != http.StatusNotFound {
		t.Errorf("GET without GPT-Live = %d", code)
	}
}

// The speaking speed: brisk by default, a pick kept in the state file and
// left alone by a PUT without "speed" (an older client), "" back to the
// default, and a 400 for an unknown speed.
func TestVoiceOpenAISpeed(t *testing.T) {
	h := newHarness(t, harnessOpts{voice: true, switcher: true, openAIVoice: &OpenAIVoiceDeps{Model: "gpt-live-1", Voice: "marin"}})
	var got VoiceOpenAIResponse
	if code := h.getJSON("/api/voice/openai", &got); code != http.StatusOK || got.Speed != "brisk" || got.DefaultSpeed != "brisk" || !slices.Equal(got.Speeds, []string{"relaxed", "normal", "brisk", "fast"}) {
		t.Fatalf("GET = %d %+v", code, got)
	}
	var e map[string]string
	if code, _ := h.sendJSON(http.MethodPut, "/api/voice/openai", map[string]any{"speed": "ludicrous"}, &e); code != http.StatusBadRequest {
		t.Errorf("PUT speed ludicrous = %d %v", code, e)
	}
	if code, raw := h.sendJSON(http.MethodPut, "/api/voice/openai", map[string]any{"speed": "Fast"}, &got); code != http.StatusOK || got.Speed != "fast" {
		t.Fatalf("PUT fast = %d %s", code, raw)
	}
	if ss := h.store.Snapshot().Session; ss.OpenAIVoiceSpeed != "fast" {
		t.Errorf("state speed = %q, want fast", ss.OpenAIVoiceSpeed)
	}
	// A PUT that names only a voice keeps the speed.
	if code, raw := h.sendJSON(http.MethodPut, "/api/voice/openai", map[string]any{"voice": "cedar"}, &got); code != http.StatusOK || got.Speed != "fast" || got.Voice != "cedar" {
		t.Fatalf("PUT voice only = %d %s", code, raw)
	}
	// The default is stored as no pick; "" also means the default.
	if code, raw := h.sendJSON(http.MethodPut, "/api/voice/openai", map[string]any{"speed": "brisk"}, &got); code != http.StatusOK || got.Speed != "brisk" || h.store.Snapshot().Session.OpenAIVoiceSpeed != "" {
		t.Fatalf("PUT brisk = %d %s", code, raw)
	}
	if code, raw := h.sendJSON(http.MethodPut, "/api/voice/openai", map[string]any{"speed": "normal"}, &got); code != http.StatusOK || got.Speed != "normal" {
		t.Fatalf("PUT normal = %d %s", code, raw)
	}
	if code, raw := h.sendJSON(http.MethodPut, "/api/voice/openai", map[string]any{"speed": ""}, &got); code != http.StatusOK || got.Speed != "brisk" || h.store.Snapshot().Session.OpenAIVoiceSpeed != "" {
		t.Fatalf("PUT \"\" = %d %s", code, raw)
	}
}
