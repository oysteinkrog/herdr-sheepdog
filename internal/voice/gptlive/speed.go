package gptlive

import "strings"

// GPT-Live has no speaking-speed setting: session.start takes only a
// voice under audio.output, and audio is fixed for the session. OpenAI's
// prompting guide ("Pace") sets pace in the system instructions instead,
// so a speed is one more paragraph at the end of the instructions. It is
// a request to the model, not an exact rate.

// Speeds are the speaking speeds the /voice page offers, slowest first.
var Speeds = []string{"relaxed", "normal", "brisk", "fast"}

// DefaultSpeed is the speed without a pick. Oystein found the model's
// own pace too slow (2026-11-21), so the default is one step faster.
const DefaultSpeed = "brisk"

// speedLines are the pacing paragraphs, worded after the guide's
// examples. "normal" adds nothing and leaves the model's own pace.
var speedLines = map[string]string{
	"relaxed": "Pace: speak at a calm, unhurried pace, with natural short pauses between sentences.",
	"normal":  "",
	"brisk":   "Pace: speak briskly, noticeably faster than your usual speaking speed, with short pauses. Keep every word clear; do not drop syllables.",
	"fast":    "Pace: speak at your fastest clear, fluent pace, with rapid connected articulation and minimal pauses.",
}

// IsSpeed reports whether s is one of Speeds.
func IsSpeed(s string) bool {
	_, ok := speedLines[s]
	return ok
}

// WithSpeed adds the pacing paragraph for speed to instructions. An
// empty or unknown speed means DefaultSpeed.
func WithSpeed(instructions, speed string) string {
	if !IsSpeed(speed) {
		speed = DefaultSpeed
	}
	line := speedLines[speed]
	if line == "" {
		return instructions
	}
	if strings.TrimSpace(instructions) == "" {
		return line
	}
	return strings.TrimRight(instructions, "\n") + "\n\n" + line
}
