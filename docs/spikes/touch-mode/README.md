# Touch mode spike: driving an agent under proot

Run 2026-11-08/09 in mynx's Debian on the phone (arm64, proot), from a
tab, with Claude Code 2.1.319 and Haiku 5.5. The question from the
roadmap: does ACP or Claude's stream-json run under proot, and do
sign-in and resume work? Code from the spike is thrown away; this is
what it learned.

- `claude-stream-json.txt`: real lines of the native protocol, scrubbed.
- `acp-adapter.md`: the ACP adapter run in full.

## Verdict

Both work under proot with no proot-specific failure. **Recommended:
a thin `AgentSession` interface in `core`, its events named after
ACP's vocabulary, with Claude's native stream-json as the first
backend.** ACP's model (tool kinds, permission options, plans, cancel
rules) is worth copying; its adapter for Claude is not worth
installing. Later backends: Codex's `app-server` (from its docs:
JSON-RPC over stdio, no adapter) and a small ACP client for Gemini
(`gemini --acp`, from its docs). Only Claude was run.

**One thing to settle first:** see "Policy" below.

| | Native stream-json | ACP adapter |
|---|---|---|
| Needs | the `claude` already installed | Node 22+ (Debian has 20, no npm) and an npm package |
| Disk | nothing more | 399 MB; 77 MB with `--omit=optional` and `CLAUDE_CODE_EXECUTABLE` |
| RSS | 225-267 MB | about 335 MB, peak about 480 MB |
| Cold start | about 1.6 s less | about 1.6 s more (noisy, other agents ran too) |
| Allow / Deny, cancel, resume | verified | verified |
| Bash output while it runs | not checked | only when the command ends |
| A denial's reason reaches the model | yes | no |
| Asks by default | when `--permission-prompt-tool stdio` is set | not until the client sets `default` mode |
| Stability | the SDK's private protocol, and Claude Code auto-updates | versioned spec, but the adapter pins its own CLI |

## The native protocol (verified unless marked)

    claude -p --output-format stream-json --verbose --input-format stream-json \
      --include-partial-messages --permission-prompt-tool stdio \
      [--resume ID | --session-id UUID] [--model M] [--permission-mode M]

One JSON object per line each way. Spawn the real binary: the
messaging-socket wrapper isn't needed headless. `initialize` answered
in 1.3 s, the first text delta came at 2.3 s; idle CPU about 1.4%.

**In:** `user` messages; `control_request` with `initialize`,
`interrupt`, `set_permission_mode`, `set_model`; and `control_response`
answering a `can_use_tool` with `allow` (and `updatedInput`, always
sent in the tests) or `deny` (`message`, `interrupt`).

**Out**, dispatched on `type`, ignoring unknown ones: `system` (`init`
once per turn, `task_*`), `stream_event` (render from these, reconcile
by message id), `assistant` (once per content block), `user` (tool
results), `control_request` `can_use_tool`, `control_cancel_request`,
`rate_limit_event`, `result` (`is_error`, `terminal_reason`,
`total_cost_usd`, `permission_denials`, `session_id`).

**What decides the client's design:**
- `result.subtype` is `success` even when not signed in: read
  `is_error`.
- One line on stdin that isn't JSON kills the process: serialise
  writes.
- `AskUserQuestion` and `ExitPlanMode` arrive as `can_use_tool`, so
  they get screens of their own. With no permission tool they aren't
  offered at all. Answer a question with allow and
  `updatedInput: {questions, answers: {"<question>": "<label>"}}`.
- "Allow always" with `destination: "session"` worked; the suggestions
  offered often say `localSettings`, which writes a file.
- A background subagent ends with an extra, unprompted `result`.
- Three user lines sent during a turn gave two results: queued lines
  merge.
- `thinking_delta` text is empty, so only an indicator is possible.
- The user's hooks fire headless, so a chat triggers `mynx hook`
  (whether a notification appears wasn't checked). Pass
  `--setting-sources project,local`, or teach the hook about chats.

**Stopping:**

| Action | Result | Children |
|---|---|---|
| `interrupt` | turn ends in 0.1 s, session alive with context | killed |
| stdin closed | finishes the turn (and background jobs), exit 0 | finish |
| SIGTERM | 143 in 1-2 s, no `result` | killed |
| SIGINT | exit 0, `result` with `aborted_tools` | killed |
| SIGKILL | -9 | **left running** |

Stop button: `interrupt`. Leaving a chat: SIGTERM, wait for exit.

**Sign-in:** with no credentials the process starts and each turn
returns `is_error: true`, `result: "Not logged in · Please run
/login"` (one-shot exit 1). Check first with `claude auth status`
(exit 1, `loggedIn: false`). `claude auth login` in a tab printed the
URL and a paste prompt; it wasn't finished, so a completed login is
unseen.

**Resume and hand-off:** transcripts are
`~/.claude/projects/<cwd, / as ->/<id>.jsonl`. Resume by id worked
headless to headless, headless to interactive and back, also from
other folders.
- **Nothing stops two programs driving one session**: both ran at
  once and the transcript forked silently. The app must wait for the
  chat's process to exit before a tab resumes it, and the reverse.
- Headless sessions aren't in the `claude --resume` picker: the app
  keeps its own list.
- A never-trusted folder shows the trust screen in the tab, with "No,
  exit" preselected. Headless skips it, so the hand-off can land
  there.

**Cards:** Write/Edit events give `filePath` and `type`, but miss
whatever is made through Bash, as a `PostToolUse` hook would.
Proposed, not tested: an explicit `mynx` card command the agent is told
about with `--append-system-prompt`, plus a snapshot of `~/apps` and
`~/terminal-apps` at each `result`, with Write/Edit paths as a hint.

## Policy

The Agent SDK overview (code.claude.com/docs/en/agent-sdk/overview,
"Get started") says: "Anthropic does not allow third party developers
to offer claude.ai login or rate limits for their products", including
agents built on the Agent SDK, with API keys as the alternative. The
headless docs call `claude -p` "the Agent SDK via the CLI". Neither
page speaks of a user's own installed, self-signed-in Claude Code
driven by a host app, which is what Chat would be, so whether Chat may
use a subscription login is an open question for the maintainer, and
perhaps Anthropic, not something the spike settled. The ACP adapter
drives the same SDK, so it doesn't avoid the question. Terminal tabs
and Home aren't affected.

## Not tested

Starting the process from the Android app itself (everything ran
inside a tab's proot); SIGQUIT to a whole proot and phantom-process
kills; a finished login and expired tokens; a rate-limit rejection;
compaction; images. Gemini and Codex were only read about: their
adapters need accounts nobody could sign in to overnight.
