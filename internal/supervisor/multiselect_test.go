package supervisor

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/kccarlos/herdr-sheepdog/internal/herdr"
	"github.com/kccarlos/herdr-sheepdog/internal/state"
)

// A multi-select question on the phone (2026-11-21): taps only ticked
// boxes and nothing pressed Submit, so the questions stayed open all
// afternoon. The phone now sends the ticked set; the keys make the boxes
// match it ("Type something" always unticked), move to Submit and press
// enter, and the review screen gets its own enter.

func multiAtPrompt(t *testing.T, h *harness, file string) string {
	t.Helper()
	h.addAgent(authPane, "auth", "claude", herdr.StateWorking)
	h.block(authPane, file)
	p, ok := h.pending()
	if !ok || !p.MultiSelect {
		t.Fatalf("pending = %+v, %v", p, ok)
	}
	h.clock.Advance(time.Second)
	return p.ID
}

func lastKeys(h *harness) string {
	r := h.sendKeysRequests()
	if len(r) == 0 {
		return ""
	}
	return r[0][1]
}

func TestAnswerChoicesTicksAndSubmits(t *testing.T) {
	h := newHarness(t)
	id := multiAtPrompt(t, h, "claude-askuserquestion-multiselect-ticked.screen.txt")
	if _, err := h.sup.AnswerChoices(context.Background(), id, []int{1, 3}, false); err != nil {
		t.Fatal(err)
	}
	// Alpha on, Bravo off, Charlie on, Type something off; four rows down
	// from the cursor on Alpha to Submit.
	if got, want := lastKeys(h), "1 2 3 4 down down down down enter"; got != want {
		t.Fatalf("keys = %q, want %q", got, want)
	}
}

func TestAnswerTapOnMultiSelectSubmitsThatOption(t *testing.T) {
	h := newHarness(t)
	id := multiAtPrompt(t, h, "claude-askuserquestion-multiselect.screen.txt")
	if _, err := h.sup.AnswerTap(context.Background(), id, 2); err != nil {
		t.Fatal(err)
	}
	if got, want := lastKeys(h), "2 down down down down enter"; got != want {
		t.Fatalf("keys = %q, want %q", got, want)
	}
}

func TestMultiSelectNeverAnswersTypeSomething(t *testing.T) {
	h := newHarness(t)
	id := multiAtPrompt(t, h, "claude-askuserquestion-multiselect.screen.txt")
	for _, o := range PendingOptions(mustQuestion(t, h, id)) {
		if o.Label == "Type something" {
			t.Fatalf("the phone card offers the free-text row: %+v", o)
		}
	}
	if _, err := h.sup.AnswerChoices(context.Background(), id, []int{4}, false); !errors.Is(err, ErrNoSuchOption) {
		t.Fatalf("err = %v, want ErrNoSuchOption", err)
	}
	if n := h.keysTo(authPane); n != 0 {
		t.Fatalf("%d key sends", n)
	}
}

func TestDismissPressesEsc(t *testing.T) {
	h := newHarness(t)
	id := multiAtPrompt(t, h, "claude-askuserquestion-multiselect.screen.txt")
	if _, err := h.sup.AnswerChoices(context.Background(), id, nil, true); err != nil {
		t.Fatal(err)
	}
	if got := lastKeys(h); got != "esc" {
		t.Fatalf("keys = %q, want esc", got)
	}
}

func TestConfirmReviewPressesEnterOnReviewScreen(t *testing.T) {
	h := newHarness(t)
	h.addAgent(authPane, "auth", "claude", herdr.StateWorking)
	h.srv.SetScreen(authPane, fixture(t, "claude-askuserquestion-review.screen.txt"))
	if !h.sup.confirmReview(context.Background(), authPane) {
		t.Fatal("review screen not confirmed")
	}
	if got := lastKeys(h); got != "enter" {
		t.Fatalf("keys = %q, want enter", got)
	}
}

func mustQuestion(t *testing.T, h *harness, id string) state.PendingAction {
	t.Helper()
	q, ok := h.store.Question(id)
	if !ok {
		t.Fatal("question gone")
	}
	return q
}
