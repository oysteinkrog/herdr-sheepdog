package herdr

import "errors"

// ErrANSIUnavailable is what a backend's ReadANSI returns when it cannot give
// a coloured screen for this read; callers then fall back to the plain read.
// supervisor.ErrANSIUnavailable is the same value.
var ErrANSIUnavailable = errors.New("ANSI read unavailable")
