package web

import (
	"net/http/httptest"
	"testing"
)

// Android 0.1.0 sends "Bearer <ticket>" without the sds_ prefix (2026-11-25).
func TestPresentedTicketAcceptsBareBearer(t *testing.T) {
	cases := map[string]string{
		"Bearer sds_abc": "abc",
		"Bearer abc":     "abc",
		"Bearer ":        "",
		"Basic abc":      "",
		"":               "",
	}
	for h, want := range cases {
		r := httptest.NewRequest("GET", "/api/state", nil)
		if h != "" {
			r.Header.Set("Authorization", h)
		}
		if got := presentedTicket(r); got != want {
			t.Errorf("%q: got %q, want %q", h, got, want)
		}
	}
}
