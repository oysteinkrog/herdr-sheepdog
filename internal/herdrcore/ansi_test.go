package herdrcore

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/kccarlos/herdr-sheepdog/internal/herdr"
)

// A Claude Code screen as hc repaints it: alternate screen, cursor home,
// CRLF rows, the prompt box between rules.
func claudeRepaint(input string) []byte {
	return []byte("\x1b[?1049l\x1b[H\x1b[2Jold main screen\x1b]2;kpp\x1b\\\x1b[?1049h\x1b[H" +
		"  last answer line\r\n\r\n" +
		"\x1b[0m\x1b[38;2;136;136;136m────────────────────────────────\x1b[0m\r\n" +
		"\x1b[0m❯ " + input + "\r\n" +
		"\x1b[0m\x1b[38;2;136;136;136m────────────────────────────────\x1b[0m\r\n" +
		"  ? for shortcuts\r\n")
}

func TestRenderRepaintGhostSuggestionIsNotTyped(t *testing.T) {
	screen := RenderRepaint(claudeRepaint("\x1b[2mstop ovis and the old herdr server\x1b[0m"))
	if strings.Contains(screen, "old main screen") {
		t.Fatalf("alternate screen switch should drop the main screen:\n%q", screen)
	}
	typed, known := herdr.TypedInput(screen)
	if !known || typed {
		t.Fatalf("dim suggestion: typed=%v known=%v, want false true\n%q", typed, known, screen)
	}
	// The plain check is what failed before: it counts the suggestion.
	if typed, _ := herdr.TypedInputPlain(strings.NewReplacer("\x1b[0m", "", "\x1b[2m", "").Replace(screen)); !typed {
		t.Fatalf("plain check no longer sees the suggestion as text; test premise changed")
	}
}

func TestRenderRepaintTypedTextIsTyped(t *testing.T) {
	screen := RenderRepaint(claudeRepaint("half a sentence I typed"))
	typed, known := herdr.TypedInput(screen)
	if !known || !typed {
		t.Fatalf("typed text: typed=%v known=%v, want true true\n%q", typed, known, screen)
	}
}

func TestRenderRepaintCursorMovesAndErase(t *testing.T) {
	raw := []byte("\x1b[Habcdef\x1b[1;3HX\x1b[2;1Hrow2\x1b[2;3H\x1b[K\x1b[9Gz")
	got := strings.ReplaceAll(RenderRepaint(raw), "\x1b[0m", "")
	want := "abXdef\nro      z"
	if got != want {
		t.Fatalf("got %q want %q", got, want)
	}
}

func TestReadANSIFallsBack(t *testing.T) {
	b := New(nil, nil)
	b.ObserveRaw = func(context.Context, string) ([]byte, error) { return nil, errors.New("no hc") }
	if _, err := b.ReadANSI(context.Background(), "t1", herdr.SourceVisible, 0); !errors.Is(err, herdr.ErrANSIUnavailable) {
		t.Fatalf("observe failure: err=%v, want ErrANSIUnavailable", err)
	}
	b.ObserveRaw = func(context.Context, string) ([]byte, error) { return claudeRepaint("x"), nil }
	if _, err := b.ReadANSI(context.Background(), "t1", herdr.SourceRecent, 50); !errors.Is(err, herdr.ErrANSIUnavailable) {
		t.Fatalf("history read: err=%v, want ErrANSIUnavailable", err)
	}
	res, err := b.ReadANSI(context.Background(), "t1", herdr.SourceVisible, 0)
	if err != nil || !strings.Contains(res.Text, "❯ x") {
		t.Fatalf("visible read: %v %q", err, res.Text)
	}
}
