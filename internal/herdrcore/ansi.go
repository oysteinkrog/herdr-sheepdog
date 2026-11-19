package herdrcore

import (
	"bytes"
	"context"
	"io"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/kccarlos/herdr-sheepdog/internal/herdr"
)

// hc has no coloured read, but `hc attach --observe` starts with a full
// repaint of the terminal, attributes included (dim text stays SGR 2). ReadANSI
// watches that repaint for a moment, rebuilds the screen from it and returns it
// as SGR-coloured lines. The typed-input check needs this: without colours,
// Claude Code's dim prompt suggestion looks like text the user typed, and every
// prompt to that pane was refused as "terminal has unsent input" (upstream
// issue #679).

const (
	// observeQuiet ends the read once the repaint has stopped for this long.
	observeQuiet = 150 * time.Millisecond
	// observeMax bounds one read, repaint or not.
	observeMax = 1500 * time.Millisecond
)

// ReadANSI implements supervisor.ANSIReader for the visible screen. Anything
// else (history lines, no hc binary, no repaint) is herdr.ErrANSIUnavailable,
// and the caller falls back to Read.
func (b *Backend) ReadANSI(ctx context.Context, paneID string, source herdr.ReadSource, lines int) (herdr.ReadResult, error) {
	if source != herdr.SourceVisible || lines > 0 {
		return herdr.ReadResult{}, herdr.ErrANSIUnavailable
	}
	observe := b.ObserveRaw
	if observe == nil {
		observe = b.observeRaw
	}
	start := time.Now()
	raw, err := observe(ctx, paneID)
	text := ""
	if err == nil {
		text = RenderRepaint(raw)
	}
	b.logEvent("herdr.read_ansi", paneID, map[string]any{
		"bytes":  len(raw),
		"dur_ms": time.Since(start).Milliseconds(),
		"error":  errString(err),
	})
	if err != nil || strings.TrimSpace(text) == "" {
		return herdr.ReadResult{}, herdr.ErrANSIUnavailable
	}
	return herdr.ReadResult{PaneID: paneID, Source: source, Text: text}, nil
}

// observeRaw runs `hc attach --observe` on the pane and returns the bytes of
// its first repaint. The observer never types or resizes.
func (b *Backend) observeRaw(ctx context.Context, paneID string) ([]byte, error) {
	look := b.LookPath
	if look == nil {
		look = exec.LookPath
	}
	bin, err := look("hc")
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(ctx, observeMax)
	defer cancel()
	cmd := exec.CommandContext(ctx, bin, "attach", "--observe", paneID)
	cmd.Env = os.Environ()
	if b.client != nil && b.client.Socket != "" {
		cmd.Env = append(cmd.Env, "HC_SOCKET="+b.client.Socket)
	}
	out, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	defer func() { cancel(); _ = cmd.Wait() }()
	chunks := make(chan []byte, 16)
	go func() {
		defer close(chunks)
		buf := make([]byte, 32<<10)
		for {
			n, err := out.Read(buf)
			if n > 0 {
				chunks <- append([]byte(nil), buf[:n]...)
			}
			if err != nil {
				return
			}
		}
	}()
	var got bytes.Buffer
	quiet := time.NewTimer(observeMax)
	defer quiet.Stop()
	for {
		select {
		case c, ok := <-chunks:
			if !ok {
				return got.Bytes(), nil
			}
			got.Write(c)
			quiet.Reset(observeQuiet)
		case <-quiet.C:
			return got.Bytes(), nil
		case <-ctx.Done():
			if got.Len() > 0 {
				return got.Bytes(), nil
			}
			return nil, io.ErrUnexpectedEOF
		}
	}
}

type ansiCell struct {
	r   rune
	sgr string // the SGR parameters in force, "" for the default style
}

// RenderRepaint turns a terminal repaint (cursor moves, erases, SGR, text)
// into the screen it draws: one line per row, each starting with an SGR reset
// and carrying the SGR changes of its cells. It understands what hc's repaint
// uses (CUP, CHA, relative moves, ED, EL, CR, LF, SGR, the alternate screen,
// OSC strings) and ignores other controls. Wide characters count as one cell.
func RenderRepaint(raw []byte) string {
	var rows [][]ansiCell
	row, col := 0, 0
	sgr := ""
	put := func(r rune) {
		for len(rows) <= row {
			rows = append(rows, nil)
		}
		for len(rows[row]) <= col {
			rows[row] = append(rows[row], ansiCell{r: ' '})
		}
		rows[row][col] = ansiCell{r: r, sgr: sgr}
		col++
	}
	clearAll := func() { rows, row, col = nil, 0, 0 }
	eraseLine := func(mode int) {
		if row >= len(rows) {
			return
		}
		l := rows[row]
		switch mode {
		case 0:
			if col < len(l) {
				rows[row] = l[:col]
			}
		case 1:
			for i := 0; i <= col && i < len(l); i++ {
				l[i] = ansiCell{r: ' '}
			}
		case 2:
			rows[row] = nil
		}
	}
	num := func(p string, def int) int {
		if n, err := strconv.Atoi(p); err == nil && n > 0 {
			return n
		}
		return def
	}
	for i := 0; i < len(raw); {
		c := raw[i]
		switch {
		case c == 0x1b && i+1 < len(raw) && raw[i+1] == '[':
			j := i + 2
			for j < len(raw) && (raw[j] < 0x40 || raw[j] > 0x7e) {
				j++
			}
			if j >= len(raw) {
				i = len(raw)
				continue
			}
			params := string(raw[i+2 : j])
			private := strings.HasPrefix(params, "?") || strings.HasPrefix(params, ">") || strings.HasPrefix(params, "=") || strings.HasPrefix(params, "<")
			ps := strings.Split(params, ";")
			switch raw[j] {
			case 'H', 'f':
				if !private {
					row, col = num(ps[0], 1)-1, 0
					if len(ps) > 1 {
						col = num(ps[1], 1) - 1
					}
				}
			case 'G':
				col = num(ps[0], 1) - 1
			case 'A':
				row -= num(ps[0], 1)
				if row < 0 {
					row = 0
				}
			case 'B':
				row += num(ps[0], 1)
			case 'C':
				col += num(ps[0], 1)
			case 'D':
				col -= num(ps[0], 1)
				if col < 0 {
					col = 0
				}
			case 'J':
				if !private && (ps[0] == "2" || ps[0] == "3") {
					keepRow, keepCol := row, col
					clearAll()
					row, col = keepRow, keepCol
				} else if !private && (ps[0] == "" || ps[0] == "0") {
					eraseLine(0)
					if row+1 < len(rows) {
						rows = rows[:row+1]
					}
				}
			case 'K':
				if !private {
					m, _ := strconv.Atoi(ps[0])
					eraseLine(m)
				}
			case 'm':
				if !private {
					if params == "" || params == "0" {
						sgr = ""
					} else if strings.HasPrefix(params, "0;") {
						sgr = params[2:]
					} else if sgr == "" {
						sgr = params
					} else {
						sgr += ";" + params
					}
				}
			case 'h', 'l':
				if params == "?1049" || params == "?1047" || params == "?47" {
					clearAll()
				}
			}
			i = j + 1
		case c == 0x1b && i+1 < len(raw) && raw[i+1] == ']':
			// OSC: up to BEL or ST.
			j := i + 2
			for j < len(raw) && raw[j] != 0x07 && !(raw[j] == 0x1b && j+1 < len(raw) && raw[j+1] == '\\') {
				j++
			}
			if j < len(raw) && raw[j] == 0x1b {
				j++
			}
			i = j + 1
		case c == 0x1b:
			i += 2
		case c == '\r':
			col = 0
			i++
		case c == '\n':
			row++
			i++
		case c == '\b':
			if col > 0 {
				col--
			}
			i++
		case c < 0x20 || c == 0x7f:
			i++
		default:
			r, n := utf8.DecodeRune(raw[i:])
			put(r)
			i += n
		}
	}
	var b strings.Builder
	for k, l := range rows {
		if k > 0 {
			b.WriteByte('\n')
		}
		b.WriteString("\x1b[0m")
		cur := ""
		for _, cell := range l {
			if cell.sgr != cur {
				b.WriteString("\x1b[0m")
				if cell.sgr != "" {
					b.WriteString("\x1b[" + cell.sgr + "m")
				}
				cur = cell.sgr
			}
			b.WriteRune(cell.r)
		}
		if cur != "" {
			b.WriteString("\x1b[0m")
		}
	}
	return b.String()
}

// NoObserve is an ObserveRaw for tests over a fake hc server: no coloured
// reads, so the typed-input check uses the plain screen as before.
func NoObserve(context.Context, string) ([]byte, error) { return nil, herdr.ErrANSIUnavailable }
