package gptlive

import (
	"strings"
	"sync/atomic"
	"testing"
)

func TestWithSpeed(t *testing.T) {
	base := "You are Sheepdog."
	if got := WithSpeed(base, "normal"); got != base {
		t.Errorf("normal changed the instructions: %q", got)
	}
	if got := WithSpeed(base, ""); !strings.HasPrefix(got, base+"\n\n") || got != WithSpeed(base, DefaultSpeed) {
		t.Errorf("empty speed = %q, want the %s line", got, DefaultSpeed)
	}
	if got := WithSpeed(base, "nonsense"); got != WithSpeed(base, DefaultSpeed) {
		t.Errorf("unknown speed = %q, want the default", got)
	}
	fast := WithSpeed(base, "fast")
	if !strings.Contains(fast, "fastest clear") || fast == WithSpeed(base, "brisk") || fast == WithSpeed(base, "relaxed") {
		t.Errorf("fast = %q", fast)
	}
	if got := WithSpeed("", "brisk"); !strings.HasPrefix(got, "Pace:") {
		t.Errorf("empty instructions = %q", got)
	}
	for _, s := range Speeds {
		if !IsSpeed(s) {
			t.Errorf("IsSpeed(%q) = false", s)
		}
	}
	if !IsSpeed(DefaultSpeed) {
		t.Errorf("DefaultSpeed %q is not in Speeds", DefaultSpeed)
	}
}

// TestSpeedReachesSessionStart: Config.Speed is asked at open, and its
// pacing line ends the session.start instructions. nil adds no line; an
// empty or bad answer means the default.
func TestSpeedReachesSessionStart(t *testing.T) {
	var speed atomic.Value
	speed.Store("fast")
	cfg := testConfig()
	cfg.Instructions = func() (string, error) { return "You are Sheepdog.", nil }
	cfg.Speed = func() string { return speed.Load().(string) }
	s, d, _ := startSession(t, cfg)
	s.ClientAttached(true)
	if err := s.SendText("hello"); err != nil {
		t.Fatal(err)
	}
	waitFor(t, "open", func() bool { return d.last() != nil && len(d.last().events(evSessionStart)) == 1 })
	sess := d.last().events(evSessionStart)[0]["session"].(map[string]any)
	if got := sess["instructions"]; got != WithSpeed("You are Sheepdog.", "fast") {
		t.Errorf("instructions = %q", got)
	}

	cfg.Speed = nil
	if got := cfg.speed(); got != "normal" {
		t.Errorf("speed without Config.Speed = %q, want normal", got)
	}
	cfg.Speed = func() string { return "" }
	if got := cfg.speed(); got != DefaultSpeed {
		t.Errorf("speed without a pick = %q, want %q", got, DefaultSpeed)
	}
	cfg.Speed = func() string { return "bogus" }
	if got := cfg.speed(); got != DefaultSpeed {
		t.Errorf("speed for a bad pick = %q, want %q", got, DefaultSpeed)
	}
}
