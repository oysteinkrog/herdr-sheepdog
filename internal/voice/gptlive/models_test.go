package gptlive

import "testing"

func TestIsVoiceModel(t *testing.T) {
	for id, want := range map[string]bool{
		"gpt-live-1": true, "gpt-live-1-2026-10-01": true,
		"gpt-live-transcribe": false, "gpt-realtime": false, "": false,
	} {
		if got := IsVoiceModel(id); got != want {
			t.Errorf("IsVoiceModel(%q) = %v, want %v", id, got, want)
		}
	}
}
