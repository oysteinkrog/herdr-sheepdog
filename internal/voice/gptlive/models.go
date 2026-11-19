package gptlive

import "strings"

// IsVoiceModel reports whether id is a GPT-Live model that can run a voice
// session: the gpt-live family minus its speech-to-text models
// (gpt-live-transcribe).
func IsVoiceModel(id string) bool {
	return strings.HasPrefix(id, "gpt-live") && !strings.Contains(id, "transcribe")
}
