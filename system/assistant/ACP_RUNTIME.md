# ACP task runtime

`TaskBroker` runs each background task through a `TaskRuntime`. `RuntimeRouter`
selects it; **Codex (`CodexAppServer`) is the default and the fallback for every
doubtful case**. The ACP runtime is used only when a background task positively
selects it and an agent binary is configured. Background tasks and chat turns (requests with
`conversationId`) can run on ACP; agent controls and panel updates stay on Codex.

## Selection

Precedence: `request.json "runtime"` > env `TAKU_TASK_RUNTIME` > `runtime.json "default"` > `codex`.

| Situation | Runtime | `runtime-route.json` reason |
| --- | --- | --- |
| no `runtime.json` | codex | `default` |
| unreadable / invalid `runtime.json` | codex | `config-invalid` |
| unknown name | codex | `unknown-runtime` |
| `acp` for a panel update or agent control | codex | `acp-ineligible:*` |
| `acp` without `acp.binary` | codex | `acp-unconfigured` |
| `acp` for a background task or chat turn, binary configured | acp | `selected-by-{request,env,config}` |
| `remote_t3` for a chat turn, panel update or agent control | codex | `remote-t3-ineligible:*` |
| `remote_t3` without `remoteT3` endpoint + token reference | codex | `remote-t3-unconfigured` |
| `remote_t3` with an invalid `remoteT3` (e.g. plain-text token, insecure endpoint) | codex | `remote-t3-invalid` |
| `request.remoteT3` naming anything but `projectId`/`environmentId`/`model` | codex | `remote-t3-request-invalid` |
| `remote_t3` for a background task, configured | remote_t3 | `selected-by-{request,env,config}` |

The remote T3 lane is described in [Remote T3 runtime](#remote-t3-runtime-second-lane). Lane
split: with `default: "acp"` both chat turns and background tasks run on ACP; `remote_t3` (from
`request.runtime`, env or `default`) only takes background tasks, and a chat turn that resolves to
it stays on Codex. Without `runtime.json` everything stays on Codex.

## `runtime.json`

`/data/user/0/org.agentos.assistant/files/runtime/runtime.json`:

```json
{
  "default": "acp",
  "acp": {
    "binary": "/data/local/agentos/acp/bin/agy",
    "home": "/data/local/agentos/acp/home",
    "appDataDir": "taku-acp",
    "httpsProxy": "http://127.0.0.1:7890",
    "cwd": "/data/local/agentos/work",
    "args": ["acp"],
    "extraArgs": [],
    "env": {},
    "mcpServers": [],
    "turnLimitMillis": 900000,
    "cancelGraceMillis": 10000
  }
}
```

The agent is started as `<binary> acp --app-data-dir <appDataDir>` with `HOME`,
`HTTPS_PROXY`, and auto-update disabled through `AGY_CLI_DISABLE_AUTO_UPDATE=1` (plus the
generic `AGY_DISABLE_AUTO_UPDATE`, `DISABLE_AUTO_UPDATE`, `NO_UPDATE_NOTIFIER`).
`appDataDir` is a plain name that agy resolves under `$HOME/.gemini/` (default `taku-acp`, i.e.
`/data/local/agentos/acp/home/.gemini/taku-acp`); agy refuses absolute paths ("language server failed to
start: must not be absolute", Pixel, 1.5.14), so absolute or `..`/`/` values fall back to the default.
`"phoneMcp": null` turns off the built-in phone tool server. Optional
`"authUrlHosts"` overrides the sign-in URL host allow-list (default `accounts.google.com`).

**Auto-update switch (checked read-only, 2026-11-21).** The official
`cli_android_arm64/antigravity` build (ELF aarch64, `/system/bin/linker64`, Go) contains
`AGY_CLI_DISABLE_AUTO_UPDATE` next to the log line `Auto-update disabled via environment
variable %s`, and the updater endpoint `antigravity-cli-auto-updater-…run.app`. No CLI flag
that disables updates was found in its strings, so the environment variable is the switch;
`extraArgs` stays empty. Pixel 9 (2026-11-22): the agent process carries the variable, its ACP log
shows no updater activity, and the binary hash was unchanged after several sessions.

## Reading and writing settings

`AcpRuntimeSettings` is the only reader/writer of `runtime.json`; the router uses its loader.
Agents and ADB use the standard provider (root/system/shell only), which forwards to the
broker op `acpRuntime`:

```sh
content call --uri content://org.agentos.assistant.acp-runtime --method acp --arg '{"action":"read"}'
content call --uri content://org.agentos.assistant.acp-runtime --method acp \
  --arg '{"action":"update","patch":{"default":"acp","acp":{"binary":"/data/local/agentos/acp/bin/agy"}}}'
```

Writable: `default` (`codex`|`acp`|null), `acp.binary`, `acp.httpsProxy`, `acp.extraArgs`;
other keys are preserved. The broker runs as root (`agentos-codex.rc`), so `acp.binary` must
be under `/data/local/agentos/`, `/system/`, `/system_ext/`, `/product/` or `/vendor/` (never
`/data/local/tmp` or shared storage), and ADB shell callers (uid 2000) cannot write
`extraArgs` — otherwise shell could run code or inject flags as root. Device check: confirm
`/data/local/agentos` is not writable by shell on the Pixel image. Unknown keys and invalid values are rejected before writing; a
file that does not parse is never overwritten. The write is temp + fsync + atomic rename,
owner-only, and `expectedRevision` (the SHA-256 that `read` returns) refuses lost updates.
`env` values are never read back. Full contract and error codes: the `taku-open-access`
skill, section *On-device ACP task runtime*.

## Sign-in (`oauth-personal`)

`AcpAuthHelper` (action `signIn`, or the island's **Sign in** action through the
non-exported `AcpSignInReceiver`) starts the agent with the same command line, `HOME` and
`--app-data-dir` as task turns, sends `initialize` then `authenticate {methodId:"oauth-personal"}`,
and scans the agent's stdout/stderr and protocol messages, in memory, for an `https` URL on
an allowed host. That URL goes to `IntentLauncher` (`am start -a android.intent.action.VIEW
-d <url>`, argv only), and the agent stays alive to receive its loopback redirect. Status goes
to `files/runtime/acp-login.json` (`state`, `method`, `urlHost`, `error`): never the URL,
agent output or tokens. Single flight, 10-minute budget, `signInCancel` ends it.

**How agy delivers the URL (Pixel 9, android_arm64 1.5.14).** agy prints nothing: it runs
`xdg-open <url>` from `PATH`, discards that child's output, and without one fails with
`couldn't open a web browser: exec: "xdg-open": executable file not found in $PATH`. For each
sign-in `AcpAuthHelper.BrowserBridge` creates `<home>/.taku-sign-in/` (owner-only) with a FIFO and an
`xdg-open` script that writes its first argument to the FIFO, prepends that directory to the agent's
`PATH` and sets `TAKU_ACP_OPEN_URL_FIFO`. The broker reads the FIFO in memory, applies the same host
allow-list and opens the URL; the directory is removed when the sign-in ends. Output scanning remains a
fallback. The URL is `accounts.google.com/o/oauth2/auth?…&redirect_uri=http://localhost:<port>/auth/callback`
(PKCE); agy listens on that port until the redirect arrives (no short timeout was seen; the helper's
10-minute budget applies). `signInCancel` closes the agent's stdin; agy answers `-32800 "Sign-in was
cancelled."`, which is recorded as `cancelled`. The phone browser must reach Google through the phone's own
network; the agent's own requests use `httpsProxy`. Loopback URLs are never opened by the broker.

## Dynamic Island

`AcpIslandProjection` maps `runtime-route.json` + `state.json` + `progress.json` +
`acp-auth.json` to a `taku.island.v1` payload (category `progress`, template `progress`):
phase title, current tool (`progress.json "tool"`, redacted, 80 chars), tool counter, plan
progress, stale after 120 s while running, and on `needs_user` with `oauth-personal` an
alerting card whose primary action is **Sign in**. `TaskNotificationReceiver` attaches it to
the existing background-task notification (the assistant APK now requests
`org.agentos.permission.POST_ISLAND`). Tasks whose route is not `acp`, and unreadable records,
project to null, so the Codex notification is unchanged. Tasks owned by
`BackgroundScreenTasks` keep their bottom surface and get no island card.

Each ACP/remote T3 task posts its notification under its own tag (`acp-task-<id>`, id 42) and
replaces the previous task's: SystemUI keeps an island's first-seen time, lifetime and dismissal
per notification key and an update never extends the lifetime, so a shared untagged key expired the
island for every task after the first hour (`cmd statusbar island list` → `activities:[]`).

## Protocol and records

ACP v1, newline-delimited JSON-RPC 2.0 on stdio: `initialize` → `session/new`
or `session/load` (declares `taku_phone`) → `session/prompt` while
`session/update` streams → `session/cancel` when `cancel` appears or the
15-minute budget runs out. If the agent does not confirm within the grace period,
its process is killed.

### Warm agent process (`AcpAgentPool`)

agy answers `initialize` in ~0.5s but boots its backend on the first session call
(~5s on the Pixel 9, agy 1.5.14); afterwards `session/new` takes ~0.2s. The broker
keeps **one** warm process (~240MB) between turns:

- **Prewarm** (`initialize` + a read-only `session/list`) runs in the background when
  new conversations route to ACP and `acp-login.json` says `signed_in`: 3s after the
  broker starts, and on `agent_control thread/start` ("new conversation") while the
  slot is empty. Reopening an existing ACP conversation (`agent_control thread/read` or native-RPC
  `thread/read` of a standalone one) also warms it (`AcpReadWarmup`, reason `thread/read`): only while
  the pool holds no process (idle, warming or in a turn), never for a removed conversation, at most
  once per 5s across chat-page refreshes, on its own daemon thread. It never blocks any caller.
- **Broker log.** init runs the broker with stdio on `/dev/null`; `BrokerLog` sends its stderr to
  `/data/local/agentos/acp/logs/broker.log` (timestamped lines; past 1.5 MiB it becomes
  `broker.log.1`, replacing the older one, so at most ~3 MiB is kept). Prewarm timings
  (`acp prewarm (<reason>) ready in <N>ms`) and runtime failures are read there.
- **Reuse.** A turn takes the warm process after a probe (agy answers an unknown
  method with -32601 at once); a dead or silent one is destroyed and the turn starts
  cold. `runtime-info.json` records `agentProcess: warm|cold`.
- **Validity.** Same command line, environment and cwd (fingerprint), and the same
  stamp: agy binary size/mtime, runtime.json and `acp-login.json`. A changed stamp
  retires the process; another permission mode (another command line) runs cold and
  leaves the warm one alone.
- **Release.** Back to the pool only after a turn that ended with the agent idle:
  prompt and steers answered, no subagents, not a budget timeout. Cancel sends
  `session/cancel` and keeps the process when agy confirms it; an unconfirmed stop
  kills it and empties the pool. Crashes, sign-in errors and automatic
  continuations never reuse a process.
- **Limits.** Idle TTL 13 minutes, 2 hours age, 40 turns, 8 distinct phone MCP
  declarations (agy keeps one MCP server per distinct declaration and stops none on
  `session/close`).

### Task start without the execution display

`TaskBroker.run` routes first. An ACP task skips the Codex execution display
(`phone-display start/id`, the activity snapshot) and the 700ms settle sleep (kept
only for a background task's context screenshot); its phone tools open dedicated
screens on demand. Task update broadcasts (`am broadcast`) go through
`CoalescingNotifier`: one daemon thread, in order, one pending update per task.

Files written to the task directory: `state.json`, `progress.json` (the Dynamic
Island summary: `phase`, `summary`, `tool`, tool and plan counters), `runtime-info.json`, `result.txt` (the task-result schema),
`done`, and on failure `diagnostic.json` (redacted stderr tail).

If the agent answers `auth_required` (-32000), the task ends `needs_user` and
`acp-auth.json` names `oauth-personal`. The broker never reads, copies or embeds
credentials, and never starts a login itself. The user signs in through the
agent's own flow in the phone's browser.

## Chat turns (`conversationId`)

With Codex selected, the conversation's identity and metadata stay with the chat page's Codex
thread (created by `agent_control thread/start`). The ACP agent's part lives in `AcpConversationStore`:
`files/runtime/acp-conversations/<conversationId>.json` (root-only, atomic replace) holds the
`conversationId ↔ ACP sessionId` binding and every ACP turn, stored directly in the native
thread/turn/item shape (`userMessage`, `agentMessage` with `phase`, `mcpToolCall` with `tool`,
`status`, `arguments`, `result`; turn `status` `inProgress|completed|failed|interrupted`).

- A new chat turn's prompt text starts with a short reply-pacing note (`AcpRuntime.CHAT_TURN_GUIDANCE`):
  before multi-step commands or tool investigations, say in one sentence what is about to be checked;
  at each milestone give a one- or two-sentence progress update; answer simple questions directly.
  It is not recorded in the chat (the record and any carried transcript keep the user's words) and is
  not added to steers or continuations.
- The user's message is recorded and published before the agent starts; agent text and tool calls
  stream into the turn (throttled to `min(250 ms, progressIntervalMillis)`).
- A later turn calls `session/load` with the bound session when the agent advertises
  `loadSession` (agy 1.5.14 does); history replayed by `session/load` is ignored. If loading fails
  or is unsupported, `session/new` is used and the earlier conversation (native and ACP turns, last
  12 000 chars) is prepended to the prompt as a transcript; `runtime-info.json` records
  `sessionResumed` and `sessionLoadFailure`.
- `AgentViews.overlay` (installed by `TaskBroker`) merges ACP turns into every save of the
  conversation's projection `agent-view/<id>.json`, by turn id, so native refreshes never drop them.
  The chat page and the session stream read that projection unchanged.
- `agent_control thread/read` for an ACP-owned conversation is answered from the store
  (`{threadId, loaded, freshEmpty, runtime:"acp", thread}`) without asking the app server; a
  conversation created by `thread/start` that never ran a Codex turn does not survive an app-server
  restart. `thread/list` adds ACP conversations and still lists them if the app server cannot start.
- Chat records follow the Codex chat contract: `result.txt` is the plain reply (completed turns
  only), `runtime-info.json` carries `threadId`, `conversationId`, `turnId`.
- **Standalone conversations.** When a new conversation's chat turns route to ACP
  (`RuntimeRouter.newConversation()`: env/`runtime.json` select `acp` with a binary),
  `agent_control thread/start` neither starts nor asks the Codex app server: the broker creates a
  random UUID conversation in the store (`origin:"acp"`), publishes its projection and answers
  `{thread, runtime:"acp"}`. `thread/read`/`thread/list` see it at once and its first turn runs on
  ACP. Because no Codex thread exists behind it, native-RPC `thread/read` (submit precheck, history
  refresh) is answered from the store and `state.ready` is true for it even without the app server.
  With Codex selected (the default), thread/start is unchanged. Renaming a standalone conversation
  (`agent_control thread/name/set`, `AcpCodexGate.renamesWithoutCodex`) stores the trimmed title
  (1–120 characters) in its store record (`name`) without starting or asking Codex, republishes the
  projection (so `thread/list`, `thread/read` and `thread/name/get` show it) and answers
  `{threadId, loaded:true, runtime:"acp", thread}`; every other conversation keeps the Codex path.
- **Stop.** The chat page derives the active turn from the streamed `inProgress` ACP turn
  (`acp-<uuid>`) and sends `interrupt`; the gateway hands an `acp-` turn to the broker op
  `acpInterrupt` instead of Codex `turn/interrupt` (which answered `TURN_CHANGED`). `liveTask`
  `cancel`/`replace` of a task routed to ACP (`runtime-route.json`, or an unrouted task of a
  standalone conversation) skips the Codex goal checks (`RUNTIME_UNAVAILABLE` before). Both validate
  the turn is still the conversation's running turn (else `TURN_CHANGED`) and write the task's
  `cancel` marker; `AcpRuntime` then sends `session/cancel`, reclaims the agent after
  `cancelGraceMillis` and closes the turn `interrupted`, the task `cancelled`.

## Steering a running turn

The composer's "add a requirement" while an ACP turn runs (`action:"steer"`, `expectedTurnId:"acp-…"`)
reaches the running agent session instead of waiting for the next turn.

**Measured protocol (Pixel 9, agy android_arm64 1.5.14, 2026-11-22, raw probes over `adb shell` stdio,
throwaway sessions).** A second `session/prompt` for the same session while one runs:

| Situation | agy's answers |
| --- | --- |
| prompt running (mid-text, 0.7 s in, or during a tool call) | the **running** prompt answers `end_turn` (no `usage`) ~40 ms later; the steer's prompt carries the rest of the merged turn (updates keep streaming) and answers `end_turn` + `usage` when it really ends |
| prompt waiting for a `session/request_permission` answer | steer answers `-32603 "Can't add this prompt to session …: the running prompt is waiting for a permission or question answer; …"`; the running prompt is unaffected |
| right after `session/cancel` | steer answers `-32603 "… the running prompt is being cancelled; …"`; the running prompt answers `cancelled` |

**Files and ownership.** Native `acpSteer {threadId, turnId, requestId, text, attachments, checkOnly}`
(root/system only, `NativeBridge` → `AcpTurnControl.steer`) checks that `turnId` is the conversation's
running ACP turn, its task is not `done` and has no `cancel` marker (`STEER_CANCELLING`), validates the
attachments like `ComposerInput` (≤8, in `composer-attachments`, ≤20 MB), then atomically writes
`steer-<requestId>.json` into the task directory and waits (≤8 s) for the outcome. `AcpRuntime`,
right after its cancel check, claims each request by atomic rename to `.claimed`, sends it as
`session/prompt` (text block; images inline as `image` blocks when the agent offers them, other files by
name and path), writes `.ack` and finally `.result` (`{state:"accepted"|"rejected", code}`). If the
runtime never claimed the request (the turn ended first), the caller renames it to `.withdrawn`: exactly
one side wins the rename, so `STEER_NOT_SUBMITTED` proves the agent never saw it and the gateway queues the
text as the next turn. A retried `requestId` answers from `.result`.

**Outcome.** Accepted when the earlier prompt answers (agy's handoff), when the steer's own prompt
answers, or when agy has not refused it within 1.5 s (refusals come at once). Refusals map to
`STEER_WAITING_ANSWER`, `STEER_CANCELLING` or `STEER_REJECTED`; the chat page explains them and keeps the
draft. Once the turn's `cancel` marker exists or `session/cancel` was sent, new steers are refused
without reaching agy.

**End of the turn.** The prompt's answer no longer ends the turn while steers are pending: the runtime
keeps reading (cancel and the fixed budget still apply) until every pending steer is answered, and the
last answer's `stopReason` decides the turn. Both answer orders are handled. While waiting for
subagents, a steer is a prompt of its own and the wait does not end before agy answers it. Steers never
accepted when the turn ends are recorded `TURN_CHANGED` and leave the chat again.

**Chat record.** Each accepted steer is a `userMessage` item (`<turnId>-steer-<requestId>`, its text plus
one `User-selected attachment: <name>` part per attachment, shown as a chip) inside the same `acp-…` turn, at the point it was sent; the agent's text after
it is a new `agentMessage`, and the last one is the turn's `final_answer`. The chat page shows them in
order (user → reply → user → reply). Transcripts for a fresh session carry every user message.

## Asynchronous subagents (`invoke_subagent`)

**Measured protocol (Pixel 9, agy android_arm64 1.5.14, 2026-11-22, raw probes over `adb shell` stdio).**
`invoke_subagent` reports `tool_call` → `tool_call_update completed` as soon as the child is
*created*; the child runs in the same agy process. When the children finish quickly, agy waits
inside the parent's turn and the summary arrives before the `session/prompt` reply. When they take
longer, the parent's `session/prompt` answers `end_turn` while they still run ("Waiting for both
results…"). Later, each child's report wakes the parent **by itself**: agy streams more
`session/update`s (`agent_thought_chunk`, `agent_message_chunk`, each wake-up ending with a
`usage_update`) with **no request, no `session/prompt` reply and no end marker**. If the client
closes stdin after `end_turn`, the children die with the process (the earlier failure: the summary
never came).

Second cause found by the same probe: agy routes only the *parent's* approvals through
`session/request_permission`. A child's `run_command` approval has no ACP route, so in `default` mode
the child waits forever in `waiting_for_input` (seen via `manage_subagents`) and never reports, even
across a follow-up prompt. With `--dangerously-skip-permissions` the same children finished and the
parent woke and summarized unprompted.

**What the runtime does.**

- `permissionMode: "full"` tasks start agy with `--dangerously-skip-permissions`
  (`runtime.json "acp.autoApprove": false` turns it off). Full mode already answers every parent
  approval with allow, so this only extends the same policy to subagents; agy still blocks its system
  command denylist. Restricted modes and sign-in/catalog launches never get the flag.
- After `end_turn`, if the task saw a completed `invoke_subagent`, the agent stays alive and its
  unsolicited updates keep streaming into the chat turn (still `inProgress`), `progress.json`
  (`phase: "subagents"`, `subagents`, `subagentsDone`) and the island ("Waiting for sub-tasks",
  "Sub-tasks 1/2"). Each wake-up starts a new chat message, so the summary is the final answer.
- Completion comes from agy's own store, read-only (`AcpSubagents`):
  `$HOME/.gemini/<appDataDir>/brain/<sessionId>/.system_generated/` → `subagents/<child>.json`
  (children; their `state` stays `SUBAGENT_STATE_ALIVE`, so it is not a completion signal),
  `messages/<id>.json` (`sender` = child: its report) and `messages/read.json` (`{id: true}` once the
  parent consumed it). Children present before the turn (resumed session) are ignored. The wait ends
  when every child started in this task has reported, every report was delivered, the parent wrote
  reply text after the newest report, and it then stayed silent for `subagentSettleMillis` (6 s).
- A report the parent has not answered after `subagentNudgeMillis` (20 s) of silence gets one
  follow-up `session/prompt` in the same session asking for the combined answer.
- Without a readable store (another agy layout) the wait ends after `subagentIdleMillis` (5 min) of
  parent silence.
- `cancel` sends `session/cancel` (no prompt is pending, so nothing confirms it), drains briefly and
  reclaims the process; the fixed `turnLimitMillis` budget from the turn's start does the same and
  ends the task as a timeout. Either way the process is destroyed (stdin closed, then killed after
  the grace period), so no agy process outlives the task.

### Subagent task cards

Each `invoke_subagent` chat item (`mcpToolCall`, `toolName: "invoke_subagent"`) carries an additive
`subagent` object (`AcpSubagentCards`) that the chat page draws as a task card instead of an
activity row:

| field | meaning |
| --- | --- |
| `id` | `subagent-<n>`, the dispatch ordinal in the turn (never agy's conversation id) |
| `title` / `role` | the `Role` the parent gave (`Device Status Inspector`) |
| `type`, `source` | agy's `TypeName` (`self`, `research`, …); `local` for an on-device child |
| `status` | `running` → `completed` (reported) / `failed` (dispatch failed) / `interrupted` (turn ended first) |
| `startedAt`, `completedAt`, `elapsedMs` | epoch ms; `completedAt`/`elapsedMs` are 0 while running (the page ticks from `startedAt`) |
| `model` | display name from the child's transcript (`gemini-4-neon-high` → `Gemini 4 Neon`), else the `Model` argument or the session model |
| `goal` | the dispatched `Prompt` |
| `activity` | while running: the child's latest step in the broker locale (`读取设备信息`, `思考中`, `回报结果`) |
| `summary`, `report` | the report's first plain line, and the report (Markdown, ≤4000 chars) |

Sources are read-only: the `invoke_subagent` arguments, `subagents/<child>.json` (role, type),
`messages/<id>.json` (report, `timestamp`) and the tail of the child's own
`brain/<child>/.system_generated/logs/transcript.jsonl` (`model`, latest `tool_calls`). Cards bind
to children by role, then by dispatch order. They refresh at most every `subagentPollMillis`, and the
turn is rewritten only when a card changed. Every text is redacted and sanitized as below. Records
without `subagent` (older turns, Codex) still get a card on the page from the call's `Role` and the
turn status.

## Tool labels and internal paths

`AcpToolLabels` turns agy's tool calls into display labels: chat items keep `tool` as a canonical
id (`taku_phone__<tool>` with `namespace: "taku_phone"`, or agy's bare name such as
`invoke_subagent`), add `toolName` (agy's own name, diagnostics only) and `title` (label in the
broker's locale). `progress.json "tool"` is the label and `"toolKey"` the id; the island shows the
label itself. The chat page (`agent-app` `session.ts`) translates the ids (e.g. `device_info` →
读取设备信息, `request_apps` → 申请后台专屏, `screenshot` → 查看屏幕, `tap` → 点击屏幕,
`release_apps` → 释放后台专屏, `invoke_subagent` → 派发子任务). agy's terminal-style titles
("Run mcp_taku_phone_tap", "File ../acp/home/.gemini/…/media_0.png") are never shown. Agent text,
tool arguments, results, errors and task results are sanitized: Markdown links to internal files or
`conversation://` subagent links keep only their text, and internal paths (`/data/local/agentos/…`,
`…/acp/home/…`, `.gemini/…`) become their file name or `[internal file]`.
Internal tool identifiers in prose (`mcp_taku_phone_device_info`, `mcp__taku_phone__tap`,
`taku_phone.open_app`, squashed `mcptakuphonedeviceinfo`) read as the tool's label in the
broker's locale: in subagent cards (goal, report, summary) together with agy's own tool keys
(`invoke_subagent` → 派发子任务), in chat messages only the unambiguous taku_phone ones. The agent's
words between tool calls (commentary `agentMessage`) show in the tool stream as a quiet
"Status update / 进度同步" note with their own first line, not as a counted step.

## Model picker without Codex

While new chat turns route to ACP (`RuntimeRouter.newConversation()`: env / `runtime.json`
`default: "acp"` with a binary), the broker never starts the Codex app server just to show the
picker, list conversations or send:

- Native-RPC `model/list` (used by `ComposerOptions.loadCatalog()` on picker open and on every
  send) is answered by `AcpModelCatalog` in Codex's shape (`model`, `displayName`, `isDefault`,
  `supportedReasoningEfforts`, `defaultReasoningEffort`, every entry `runtime: "acp"`; result
  `source: "agent" | "builtin"`).
- Source: agy's `session/new` `configOptions` (select `model`, select `thought_level`), saved by
  `AcpRuntime` after each session to `files/runtime/acp-models.json` (keyed by `acp.binary`). Until
  the first ACP turn it answers the built-in `Gemini 4 Neon` with low/medium/high/xhigh. No extra
  agent process is started to list models.
- The composer's `model` / `effort` travel in the task request; `AcpRuntime` applies them with
  `session/set_config_option` (`model`, `thought_level`) when the agent offers the value, records the
  effective `model` / `effort` in `runtime-info.json`, and lists refusals in `configRefusals` (the
  turn still runs on the agent's choice). A Codex model id saved earlier resolves to the ACP default.
- **Default thinking level: `medium`** (`AcpModelCatalog.DEFAULT_EFFORT`). agy reports
  `thought_level` `currentValue: "high"` on every `session/new`; the catalog used to mirror it as
  `defaultReasoningEffort`, so with no saved choice every send carried `effort: "high"`. The catalog
  now reports `medium` as the default when the agent offers it (else the agent's current level), and
  a request without `effort` (background tasks) applies `medium`. A level picked in the composer is
  sent explicitly and still wins.
- `agent_control thread/list` is built from the broker's projection index without starting Codex
  (`AcpCodexGate`); a Codex app server that is already running is still asked.
- Every Codex app-server cold start is recorded with its cause in
  `files/runtime/agent-view/codex-starts.log`.
- A thread-scoped call (`params.threadId`: native-RPC `thread/read`, `thread/resume`,
  `thread/name/set`, `turn/*`, …; `agent_control thread/read|thread/name/set|harness/*|skills/*`)
  naming an ACP conversation that session removal deleted (`acp-conversations/<id>.removed`) fails
  with `THREAD_UNAVAILABLE` (native error `code`, control `code`) before the Codex app server is
  reached, whether or not it runs. With ACP selected and Codex idle, the same holds for a thread that
  neither the ACP store nor the projection index (`agent-view/`) knows. A task (`Continue`, a queued
  turn) for a removed conversation fails `THREAD_UNAVAILABLE` before any display or runtime starts.
  `AcpCodexGate.unavailableWithoutCodex` is the single rule.

With Codex selected (the default) all of these keep the Codex path unchanged.

Verified on the Pixel 9 (agy 1.5.14, 2026-11-22): open app → picker → new chat → Chinese send →
full reply with `ps -A | grep -i codex` at 0 throughout; picking Gemini 3.8 Flash made the next
turn run on it.

## Phone tools over MCP

`PhoneMcpBridge` is the MCP core: `initialize`, `tools/list`, `tools/call`, JSON
Schema argument checks (-32602), and permission checks (`readOnly` and `ask`
deny non-observation tools as `PERMISSION_DENIED` tool errors). Execution goes to
`PhoneNativeTools.execute`, the dispatcher Codex uses. `ask_user` and
`confirm_action` are not exposed yet. `PhoneMcpHost` serves the agent's stdio child over two
root/system-only sockets:

- **native (default):** `sh -c '{ printf "%s\n" "$TAKU_MCP_HELLO"; exec cat; } | exec toybox nc -U
  "$TAKU_MCP_SOCKET"'` to the filesystem socket `/data/local/agentos/acp/run/phone-mcp.sock`
  (0600 in a 0700 directory, peer uid checked). No JVM start: a few ms instead of ~750ms of
  `app_process` per session. agy 1.5.14 advertises no `mcpCapabilities` (http/sse), so stdio stays.
- **fallback:** `app_process … PhoneMcpHost relay` to the abstract socket `taku-phone-mcp`, declared
  when the filesystem socket is missing or runtime.json sets its own `phoneMcp`
  (`"phoneMcpNative": false` forces it).

The phone session is the conversation id for a chat turn and a name-based UUID of the task id for a
background task (`TAKU_MCP_THREAD`; the screen registries reject non-UUID sessions with
`INVALID_SCREEN_THREAD`). The declaration names no turn, so a conversation keeps one relay in a warm
process. **A connection is not a turn:** `AcpRuntime` opens the turn in `AcpPhoneTurns` before the
agent starts and closes it exactly once when the task ends (completed, failed, cancelled, needs_user):
the gesture owner starts/ends and the background screen is released then, never on disconnect. Each
tool call runs in the thread's current turn.
The screen registry answers `thread/read` for ACP phone sessions as root threads instead of asking
Codex (which answered `thread not loaded`).

`device_info` needs no held screen: it always returns `android` and `model`, plus the held
execution screen's fields (`screen:true`, `displayId`, …) or `screen:false` with `screenNote`.
A failed tool call in a chat turn is stored with `error: {code, message, source}` (message redacted,
≤300 chars). For `taku_phone` tools the code comes from the broker's own bridge when the agent does
not forward the MCP error text (agy reports only `failed`); e.g. `APPS_NOT_REQUESTED`,
`PERMISSION_DENIED`, `TOOL_FAILED`.

## Remote T3 runtime (second lane)

`RemoteT3Runtime` hands a background task to a T3 Code host on a VPS or Mac and reconciles the
remote run into the same task records. The phone only dispatches and watches; the heavy work
runs remotely.

```json
{
  "default": "remote_t3",
  "remoteT3": {
    "endpoint": "https://t3.example.ts.net",
    "tokenEnv": "TAKU_REMOTE_T3_TOKEN",
    "projectId": "taku",
    "environmentId": "vps-1",
    "model": "claude-opus-5-5",
    "pollIntervalMs": 2000
  }
}
```

- `endpoint`: `https://…`, or plain `http` only to `127.0.0.1` or a Tailscale address
  (`100.64.0.0/10`, the tunnel encrypts). No credentials, query or fragment; redirects are never followed.
- Token: a **reference** only. Use `tokenEnv` (a variable in the broker's environment) or `tokenFile`
  (a single-token file under `/data/local/agentos/` or `/data/misc/agentos/`, ≤4 KiB), not both.
  `token`, `apiKey`, `secret`, `password`, `authorization`, `bearer`, `accessToken` keys are refused
  (`PLAINTEXT_TOKEN_NOT_ALLOWED`), both on write and in a hand-edited file (the router then falls back to Codex).
  The token is resolved per task, kept in memory, sent only as `Authorization: Bearer` to the endpoint,
  and scrubbed verbatim and by pattern from `diagnostic.json`, `progress.json`, `state.json` and `result.txt`.
- Optional, not writable through settings: `requestTimeoutMs` (15 s), `turnLimitMs` (60 min),
  `unreachableLimitMs` (3 min), `cancelGraceMs` (30 s), `progressIntervalMs` (1 s), `maxSubmitAttempts` (5).
- A task may set `request.runtime: "remote_t3"` and override `request.remoteT3.projectId|environmentId|model`.
  It can never override the endpoint or the token reference.

**Wire contract (v1, assumed; implement it as a thin bridge in front of T3 Code).** No public T3 Code
HTTP API was available to verify against, so `RemoteT3Transport.Http` speaks this small versioned contract:

| Call | Request | Response |
| --- | --- | --- |
| submit | `POST {endpoint}/api/taku/v1/runs`, header `Idempotency-Key: taku-task-<id>`, body `{clientTaskId,prompt,source,projectId?,environmentId?,model?,permissionMode?}` | `{runId,threadId?,status}` |
| status | `GET {endpoint}/api/taku/v1/runs/{runId}` | `{runId,status,phase?,summary?,tool?,progress?{done,total},result?{message},error?{code,message}}` |
| interrupt | `POST {endpoint}/api/taku/v1/runs/{runId}/interrupt` | `{status}` |

`status` is `queued|running|completed|failed|cancelled`; `phase` (`thinking|tool|replying`) and
`progress` drive the island. The screenshot and other local paths never leave the phone.

**Lifecycle and outcomes.**

| Situation | Task state | `diagnostic.json` type |
| --- | --- | --- |
| remote `completed` | `completed`, reply in `result.txt` | – |
| `cancel` marker → interrupt → remote `cancelled` | `cancelled` | – |
| interrupt not confirmed within `cancelGraceMs` | `failed` | `stop-unconfirmed` |
| `turnLimitMs` reached → interrupt (confirmed or not) | `failed` | – / `timeout` |
| transient submit failures (timeouts, 408/425/429/5xx) | retried with backoff 0.5→8 s, same idempotency key | `unreachable` after `maxSubmitAttempts` |
| transient status failures | keep polling | `unreachable` after `unreachableLimitMs` without contact |
| 401/403 (submit or poll), token missing/unreadable | `needs_user` (no retry; fix the token reference) | `auth-failed` / `token-missing` |
| other 4xx on submit / status, 404 on status | `failed` | `submit-rejected` / `status-rejected` / `run-lost` |
| remote `failed` | `failed`, message carries the redacted reason | `remote-failed` |

`runtime-info.json` records `runId`, `threadId`, `endpointHost`, `projectId`, `environmentId`, `model`.
`progress.json` has the same shape as ACP's (`runtime: "remote_t3"`, plus `remoteStatus`), throttled by
`progressIntervalMs` and refreshed at least every 60 s so a quiet but healthy run never looks stale.
`AcpIslandProjection` projects `remote_t3` tasks too (titles such as "Sending to remote T3" and
"Waiting for remote T3"); remote `needs_user` never offers the on-device **Sign in** action.

**Known limitation.** `TaskBroker` runs tasks one at a time, so a remote run occupies the broker
queue until it ends (the idle callback keeps native RPC and Codex chat serviced, as with ACP).
`TaskBroker.run` also still prepares the virtual display and context screenshot before routing; the
remote lane does not use them.

## Tests

`bash system/assistant/test-acp-runtime-host.sh` runs against an in-process fake ACP agent:
router, ACP lifecycle, chat turns (store, streaming, `session/load` and transcript fallback, the
chat page's own `AgentConversation` parser), standalone conversations and mid-turn stop
(`AcpStandaloneConversationTest`), the remote T3 lifecycle (`RemoteT3RuntimeTest`: in-memory
remote host in virtual time, plus the `HttpURLConnection` binding against a loopback server), MCP
bridge, settings read/write and validation, sign-in URL capture
and browser hand-off (mock `IntentLauncher`), and island projections checked against
SystemUI's own `IslandPayload` parser (host stand-ins for `android.util.JsonReader`).
