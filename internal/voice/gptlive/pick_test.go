package gptlive

import (
	"sync/atomic"
	"testing"
)

// TestPickSetsModelAndVoiceOfTheNextOpen: Config.Pick is asked at every
// open, so a runtime pick reaches the next session.start; an empty
// answer keeps the configured model and voice.
func TestPickSetsModelAndVoiceOfTheNextOpen(t *testing.T) {
	var picked atomic.Value
	picked.Store([2]string{"", ""})
	cfg := testConfig()
	cfg.Model, cfg.Voice = "gpt-live-1", "marin"
	cfg.Pick = func() (string, string) { p := picked.Load().([2]string); return p[0], p[1] }
	s, d, _ := startSession(t, cfg)
	s.ClientAttached(true)
	if err := s.SendText("hello"); err != nil {
		t.Fatal(err)
	}
	waitFor(t, "open", func() bool { return d.last() != nil && len(d.last().events(evSessionStart)) == 1 })
	sess := d.last().events(evSessionStart)[0]["session"].(map[string]any)
	if sess["model"] != "gpt-live-1" {
		t.Errorf("model = %v, want the configured gpt-live-1", sess["model"])
	}
	out := sess["audio"].(map[string]any)["output"].(map[string]any)
	if out["voice"] != "marin" {
		t.Errorf("voice = %v, want the configured marin", out["voice"])
	}

	picked.Store([2]string{"gpt-live-2", "cedar"})
	if m, v := cfg.modelVoice(); m != "gpt-live-2" || v != "cedar" {
		t.Errorf("modelVoice = %s %s, want gpt-live-2 cedar", m, v)
	}
	picked.Store([2]string{"", "ash"})
	if m, v := cfg.modelVoice(); m != "gpt-live-1" || v != "ash" {
		t.Errorf("modelVoice = %s %s, want gpt-live-1 ash", m, v)
	}
}
