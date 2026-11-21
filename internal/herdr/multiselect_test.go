package herdr_test

import (
	"testing"

	"github.com/kccarlos/herdr-sheepdog/internal/herdr"
)

// Claude Code's multi-select AskUserQuestion: boxes before the labels and
// a Submit row under the options. A digit only ticks a box, so the prompt
// must say it is a multi-select, show the labels without the boxes, keep
// each box's state, and keep "Type something" a free-text row even when
// its box is ticked (2026-11-21: it was offered, and ticked, as an answer).
func TestParseMultiSelect(t *testing.T) {
	p, ok := herdr.ParsePrompt(screen(t, "claude-askuserquestion-multiselect-ticked.screen.txt"))
	if !ok {
		t.Fatal("not parsed")
	}
	if !p.MultiSelect {
		t.Fatal("MultiSelect = false")
	}
	want := []struct {
		label   string
		checked bool
		meta    bool
	}{{"Alpha", false, false}, {"Bravo", true, false}, {"Charlie", false, false}, {"Type something", true, true}}
	if len(p.Options) != len(want) {
		t.Fatalf("options = %+v", p.Options)
	}
	for i, w := range want {
		o := p.Options[i]
		if o.Label != w.label || o.Checked != w.checked || o.Meta != w.meta {
			t.Errorf("option %d = %+v, want %+v", i+1, o, w)
		}
	}
	if d := p.Options[3].Description; d != "" {
		t.Errorf("the Submit row became a description: %q", d)
	}
	if p.Default != 0 || len(p.YesKeys) != 0 || len(p.NoKeys) != 0 {
		t.Errorf("default %d, yes %v, no %v: one key must never answer a multi-select", p.Default, p.YesKeys, p.NoKeys)
	}
	if len(p.Answers()) != 3 {
		t.Errorf("answers = %+v", p.Answers())
	}
}

// A single-select question is unchanged.
func TestParseSingleSelectIsNotMulti(t *testing.T) {
	p, ok := herdr.ParsePrompt(screen(t, "claude-askuserquestion.screen.txt"))
	if !ok || p.MultiSelect {
		t.Fatalf("ok %v, multi %v", ok, p.MultiSelect)
	}
}
