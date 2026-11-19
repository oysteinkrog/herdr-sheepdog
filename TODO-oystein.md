# To do (oystein/main fork)

Backlog for this fork. Not upstream work.

## Show the orchestrator session in herdr

Make the Opus orchestrator behind the voice visible in herdr, as a pane or
a log you can watch.

Feasibility: easy for a read-only view, harder for a real agent pane.

- The data is already there. `internal/brain` appends every message of a
  daemon run to `~/.local/share/sheepdog/brain/<run-id>.jsonl` (one JSON
  object per line: role, kind, content, tool calls and results, model,
  tokens). `<run-id>.requests.jsonl` next to it holds the raw requests.
- `/debug` already renders it: the Supervisor tab reads
  `GET /api/brain/transcript` (loopback only) and shows it as a chat.
- Cheapest step (about an hour): a `sheepdog brain tail` command that
  follows the newest `<run-id>.jsonl` and prints one readable line per
  message (who, tool call, short text). Run it in a herdr pane by hand or
  from the layout. It needs no daemon change and works over the files
  alone. A new run id at each daemon restart means the command must
  switch files when a newer one appears.
- Next step (half a day): add that pane to `sheepdog-layout`, or have the
  herdr-app mod (`mods/sheepdog`) open the transcript as a panel view,
  using the same `/api/brain/transcript` the debug page uses.
- Not worth it: running the orchestrator as a real herdr agent pane. It is
  an API loop inside the daemon, not a terminal program, so herdr has no
  process to show; faking one would only duplicate the tail view.
