# Spike C: ACP through the Claude Code adapter, under proot

Run 2026-11-08/09 inside mynx's Debian on the phone (arm64, proot).
Versions: adapter `@agentclientprotocol/claude-agent-acp` 0.101.0 (SDK
`@anthropic-ai/claude-agent-sdk` 0.3.318, bundled Claude CLI 2.1.318), ACP
protocolVersion 1, Node 24.22.2 (official linux-arm64 LTS tarball, SHA-256
checked against SHASUMS256; npm 11.19.1), Debian's Node 20.19.2, system Claude
Code 2.1.319. Model: Haiku 5.5 (config value `haiku`; `model_usage` reported
`claude-haiku-5-5`). About 37 tiny prompts in all.

## Verdict

**ACP through the adapter works under proot, end to end.** Streaming, tool
calls, Allow/Deny, three kinds of cancel, load/resume/list, sign-in state and
resuming the same session id with `claude --resume -p` all ran for real, with
no proot-specific failure.

**For Claude Code alone it costs more than the native route.** It adds a Node
>=22 runtime and an npm tree per install (77 MB at best, 399 MB by default),
about 115 MB RSS while a chat is open, and about 1.6 s on a cold start. Debian
trixie's own Node (20) is below the adapter's declared minimum and has no npm.

What the adapter buys: one protocol other agents also speak, and work the
client would otherwise redo: `session/list`, replay on load, merged tool cards
with `kind`/diffs, titles, model and mode menus in-band, sign-in state pushed.

Recommendation: decide on whether mynx wants one protocol for several agents.
If Claude is first and stream-json (spike a/b) reaches parity on permissions
and resume, go native for Claude and keep ACP for agents that speak it. I only
ran native for timings, so parity is theirs to confirm.

## 1. Runtime

| | `node -e 0` wall | max RSS |
|---|---|---|
| official 24.22.2 | 0.23-0.32 s | 42 MB |
| Debian 20.19.2 | 0.38 s | 47 MB |

Verified: Node runs cleanly under proot, no warnings or crashes in any run.
`npm` is not installed here (only `node`). The runtime unpacks to 205 MB.

Debian's Node 20 despite `engines: >=22`: `initialize` and `session/new`
succeeded (`run_node20.py`). No prompt was run on it, so that is not a proof
that it works; it is outside the declared support.

## 2. The package

- `@zed-industries/claude-code-acp` (0.16.2) is deprecated: "renamed to
  @agentclientprotocol/claude-agent-acp". That is the one I used (Apache-2.0).
  agentclientprotocol.com's agents page lists "Claude Agent (via Zed's SDK
  adapter)" linking `zed-industries/claude-agent-acp`; npm's `repository` says
  `agentclientprotocol/claude-agent-acp`. Read through a summarising fetch, so
  check the link before relying on it.
- Install (63 s, no install scripts): **399 MB, 107 packages**. 337 MB of it is
  a second full Claude CLI binary (`claude-agent-sdk-linux-arm64/claude`).
- `--omit=optional` plus `CLAUDE_CODE_EXECUTABLE=/root/.local/bin/claude`:
  **77 MB**, and a real turn worked using the user's own CLI. Without the
  variable, `session/new` fails with -32603 "Claude native binary not found
  for linux-arm64 ... or set CLAUDE_CODE_EXECUTABLE". This is the route that
  fits "mynx never bundles agent CLIs".

## 3. Protocol, run for real (client: `client/acp.py`, `client/run_*.py`)

Verified:
- **Streamed text:** `agent_message_chunk`s arrive token-sized (`samples/tour.jsonl`).
- **Tool call:** `tool_call` then `tool_call_update`s, merged by `toolCallId`.
  Starts vague (title "Terminal", "Preparing file…", empty `rawInput`) and fills in.
- **Allow:** `allowed.txt` created. **Deny:** `denied.txt` not created, tool
  `failed`, model replied "Refused".
- **Options vary:** Bash `allow-once`/`reject`, sometimes `allow-with-updates`
  ("don't ask again for python3 -c ... commands"); Write adds "allow all edits
  during this session". Always pick by `kind`, not id. Not answered with
  `allow_always`, so where it saves the rule is unverified.
- **Cancel during text:** `stopReason: cancelled` in 125 ms.
- **Cancel with a permission open:** adapter failed the tool and ended the turn
  itself, 302 ms (including my 300 ms wait); answering `{"outcome":"cancelled"}`
  afterwards was harmless.
- **Cancel a running tool:** ended in 134 ms; the bash and python processes were
  gone within 1 s.
- **Prompt while one runs** (`promptQueueing`): both finished, in order.
- **`session/load`:** 2.0-2.2 s, replays user and agent messages and tool calls
  as `session/update`, then continued with context ("note.txt").
  `session/resume`: 2.6 s, no replay. `session/list` returns ids, titles, times.
  Permission mode came back from the transcript; I set the model again each
  time, so whether the model is kept is untested.
- **`session/close`:** the claude child exits, node stays. **stdin EOF:** node
  exits rc 0 every time.
- **SIGKILL of the process group mid-stream:** `session/load` works and it
  continues. The user's prompt is kept; the partial reply is lost.

Hand-off to a terminal:
- `sessionId` **is** the Claude session id:
  `~/.claude/projects/<cwd>/<id>.jsonl` exists, and `claude --resume <id> -p`
  from that folder answered from the conversation ("hi"), same `session_id`.
- Back again: `session/load` afterwards replays the turns made by `-p`.
- A session with no prompt leaves no transcript.
- **Interactive `claude --resume` not confirmed:** in a pty it stopped at the
  "do you trust this folder?" dialog, since this scratch folder was never
  trusted. I did not accept it (that changes the user's config), so I never saw
  the TUI render the session. ACP never showed that dialog, so a chat can work
  in a folder where the hand-off tab then asks. Plan for it.
- One `-p` attempt's output failed to parse in my pipeline (not captured); its
  turn did land in the transcript. The rerun was clean.

## 4. Costs against native stream-json

Clean env (`HOME`, `PATH`, `TERM`, `LANG`), 3 alternating rounds, one for
`acp-sys`, `logs/bench.txt`. Other agents were busy on the phone, so noisy.

| | native | ACP | ACP + system CLI |
|---|---|---|---|
| spawn to first text, ms | 5442 / 6196 / 7283 (mean 6307) | 7701 / 7402 / 8580 (mean 7894) | 8172 |
| warm turn, first text, ms | 1442 / 2664 / 2437 | 1312 / 1915 / 1958 | 1703 |
| peak RSS total, MB | 211 | 444-481 | 433 |

- Cold start: **about +1.6 s** (`initialize` 1.5-5.9 s, `session/new` 1-3.3 s).
- Warm first token: **no added latency visible** within the noise.
- Memory (`run_mem_kill.py`): node 102-119 MB + claude ~220 MB = **~335 MB
  steady, against ~211 MB native**. The peak is higher because the adapter runs
  `claude auth status --json` (~106 MB) beside the session at start.
  smaps_rollup Pss matched RSS within 2 MB.
- Install burden: Node >=22 plus npm from outside Debian's packages (205 MB
  unpacked), or `apt install nodejs npm` that doesn't satisfy `engines`; then
  77-399 MB of npm tree, updated separately from Claude Code, with an SDK
  pinned to one CLI version (mine ran 2.1.319 against SDK 2.1.318 fine, once).
  Native needs nothing beyond what the user already installed.
- CPU was measured but is **not comparable**: `claude` under node isn't always
  reaped into the rusage figure.

**Missing or lossy at the client:**
- **Bash output isn't live.** A 3 s loop delivered all output when it ended,
  with or without `_meta.terminal_output: true`. A long command looks hung.
- **`AskUserQuestion` is removed** unless the client advertises
  `elicitation: {form: {}}`. With it: `elicitation/create` with a JSON-schema
  form, answered `{action:"accept", content:{question_0:"Yes"}}`. Verified.
- **A denial carries no reason:** the model gets the fixed "User refused
  permission to run tool".
- **Default mode is `auto`**, even with empty HOME: Claude decides, so Allow/Deny
  never appears until the client sets `mode` to `default` ("Manual"). Silent.
- Claude-specific detail sits in `_meta.claudeCode` (`toolName`, structured
  `toolResponse`) and `_meta.quota`; `name` on tool calls and `_auth/status_update`
  are extensions, not guaranteed spec.
- Partial replies are not replayed after a kill (above).

Not exercised: thinking chunks (`agent_thought_chunk`), `plan` updates (TodoWrite
isn't in this CLI's tool set), subagents, images, MCP, client `fs/*` and
`terminal/*` (advertised false), Android killing it from outside, a real launch
from the app rather than a tab, and native parity features.

## 5. Sign-in

- Signed in: `authMethods: []` for a client with no auth capabilities.
- `HOME` empty, client advertising `auth.terminal` and `_meta["terminal-auth"]`:
  `claude-ai-login` and `console-login`, type `terminal`, with
  `args: ["--cli","auth","login","--claudeai"]`; plus `gateway`,
  `gateway-bedrock` when `auth._meta.gateway` is set. With `NO_BROWSER=1` one
  `claude-login` method (`--cli`, the TUI). Terminal-type fits a mynx tab.
- **Not run:** the login flow itself.
- `session/new` **succeeds** without credentials; `_auth/status_update`
  `{"kind":"none","label":"Not logged in"}` is pushed first, and only
  `session/prompt` fails: `{"code":-32000,"message":"Authentication required"}`.
  So the app can show sign-in before the first prompt.

## Messages a Kotlin client implements

Newline-delimited JSON-RPC 2.0 on stdio. Paths shortened, ids trimmed.

```
> {"id":1,"method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{"fs":{"readTextFile":false,"writeTextFile":false},"terminal":false,"elicitation":{"form":{}},"auth":{"terminal":true}},"clientInfo":{"name":"mynx","version":"0"}}}
> {"id":2,"method":"session/new","params":{"cwd":"/root/x","mcpServers":[]}}
< {"id":2,"result":{"sessionId":"2fdec226-…","modes":{…},"configOptions":[{"id":"mode","currentValue":"auto",…},{"id":"model",…}]}}
> {"id":3,"method":"session/set_config_option","params":{"sessionId":"…","configId":"mode","value":"default"}}
> {"id":4,"method":"session/prompt","params":{"sessionId":"…","prompt":[{"type":"text","text":"hi"}]}}
< {"method":"session/update","params":{"sessionId":"…","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Cats s"}}}}
< {"method":"session/update","params":{"update":{"sessionUpdate":"tool_call","toolCallId":"toolu_…","name":"Bash","status":"pending","title":"Terminal","kind":"execute","rawInput":{},"content":[]}}}
< {"method":"session/update","params":{"update":{"sessionUpdate":"tool_call_update","toolCallId":"toolu_…","title":"touch allowed.txt","rawInput":{"command":"touch allowed.txt"}}}}
< {"id":0,"method":"session/request_permission","params":{"sessionId":"…","toolCall":{"toolCallId":"toolu_…","title":"touch allowed.txt","kind":"execute","rawInput":{…},"locations":[{"path":"…"}]},"options":[{"optionId":"allow-once","name":"Yes","kind":"allow_once"},{"optionId":"reject","name":"No","kind":"reject_once"}]}}
> {"id":0,"result":{"outcome":{"outcome":"selected","optionId":"allow-once"}}}
< {"method":"session/update","params":{"update":{"sessionUpdate":"tool_call_update","toolCallId":"toolu_…","status":"completed","rawOutput":"line1\nline2","content":[{"type":"content","content":{"type":"text","text":"```console\nline1\n```"}}]}}}
< {"id":4,"result":{"stopReason":"end_turn","usage":{"inputTokens":2,"outputTokens":34,"cachedReadTokens":9076,"cachedWriteTokens":10088,"totalTokens":19200}}}
> {"method":"session/cancel","params":{"sessionId":"…"}}                     // notification; the prompt then answers stopReason "cancelled"
> {"id":5,"method":"session/load","params":{"sessionId":"…","cwd":"/root/x","mcpServers":[]}}
> {"id":6,"method":"session/list","params":{"cwd":"/root/x"}}
> {"id":7,"method":"session/close","params":{"sessionId":"…"}}
< {"id":9,"method":"elicitation/create","params":{"mode":"form","toolCallId":"…","message":"Do you like tea?","requestedSchema":{…}}}
> {"id":9,"result":{"action":"accept","content":{"question_0":"Yes"}}}
```

`session/update` kinds seen: `agent_message_chunk`, `user_message_chunk` (replay),
`tool_call`, `tool_call_update`, `usage_update` (`used`, `size`, `cost`),
`available_commands_update` (62-67 commands), `current_mode_update`,
`session_info_update` (title). Answer unknown agent requests with -32601.

## Risks

- **Moving target:** renamed once already, version 0.101, many `unstable_*` and
  `_meta` paths; `configOptions` values shift with the CLI.
- **Second copy of Claude Code** by default, 337 MB, on its own update path;
  `--omit=optional` is required for mynx's rule and depends on an env variable
  and CLI/SDK compatibility.
- **Tools run in their own session** (`setsid`; bash's sid differed from the
  adapter's group). Stopping the chat by killing the group would not reach a
  running tool. Use `session/cancel` first. Killing during a tool: not tested.
- **Hooks fire in adapter sessions:** the transcript records hook runs, so
  `mynx hook` notifications likely apply as in the terminal. Whether they
  double up with a chat's own: not observed on the phone.
- **`_auth/status_update` carries the account's email and organisation.** Never
  log it. (Scrubbed from every sample here.)
- Memory on a phone: ~335 MB per open chat, ~480 MB peak.

## Surprises

- The default npm install ships a whole Claude CLI.
- `auto` is the default mode; a client that forgets to set it shows no prompts.
- Claude Code itself refused a bare `sleep 47` ("blocked as a standalone wait"),
  so my first running-tool test had nothing to cancel. Not the adapter.
- My first runs inherited this session's `CLAUDE_*` variables. Tour, cancel
  and load ran that way; bench, memory/kill, no-credential, no-optional and
  Node 20 ran in a clean env. Behaviour matched where both were tried.

## Left behind, sizes

Removed after measuring (restore with the steps in section 1-2): `runtime` 205 MB,
`adapter` 399 MB, `adapter2` 77 MB, `dl` 30 MB, `emptyhome`. Kept: `client/`,
`samples/` (22 files, 2 MB), `logs/`.
Outside this folder: 15 small test transcripts (2.9 MB) in
`~/.claude/projects/-tmp-claude-0--root-mynx-…-touch-spike-c-work/`, which
appear in `/resume` for that folder only and are safe to delete.
No processes left running (scanned `/proc`). No credentials read or copied; no
email or `sk-ant-` strings in samples or logs.
