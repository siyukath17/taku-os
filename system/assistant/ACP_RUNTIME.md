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

## Protocol and records

ACP v1, newline-delimited JSON-RPC 2.0 on stdio, with one agent process per task:
`initialize` → `session/new` (declares `taku_phone`) → `session/prompt` while
`session/update` streams → `session/cancel` when `cancel` appears or the
15-minute budget runs out. If the agent does not confirm within the grace period,
its process is killed.

Files written to the task directory: `state.json`, `progress.json` (the Dynamic
Island summary: `phase`, `summary`, `tool`, tool and plan counters), `runtime-info.json`, `result.txt` (the task-result schema),
`done`, and on failure `diagnostic.json` (redacted stderr tail).

If the agent answers `auth_required` (-32000), the task ends `needs_user` and
`acp-auth.json` names `oauth-personal`. The broker never reads, copies or embeds
credentials, and never starts a login itself. The user signs in through the
agent's own flow in the phone's browser.

## Chat turns (`conversationId`)

The conversation's identity and metadata stay with the chat page's thread (created by
`agent_control thread/start`). The ACP agent's part lives in `AcpConversationStore`:
`files/runtime/acp-conversations/<conversationId>.json` (root-only, atomic replace) holds the
`conversationId ↔ ACP sessionId` binding and every ACP turn, stored directly in the native
thread/turn/item shape (`userMessage`, `agentMessage` with `phase`, `mcpToolCall` with `tool`,
`status`, `arguments`, `result`; turn `status` `inProgress|completed|failed|interrupted`).

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

## Phone tools over MCP

`PhoneMcpBridge` is the MCP core: `initialize`, `tools/list`, `tools/call`, JSON
Schema argument checks (-32602), and permission checks (`readOnly` and `ask`
deny non-observation tools as `PERMISSION_DENIED` tool errors). Execution goes to
`PhoneNativeTools.execute`, the dispatcher Codex uses. `ask_user` and
`confirm_action` are not exposed yet. `PhoneMcpHost` relays the agent's stdio
child (`app_process … PhoneMcpHost relay`) to the broker socket `taku-phone-mcp`.
That socket serves root and system peers only. The phone session is the conversation id for a chat
turn and a name-based UUID of the task id for a background task (`TAKU_MCP_THREAD`; the screen
registries reject non-UUID sessions with `INVALID_SCREEN_THREAD`), and the MCP connection is the turn
(`TAKU_MCP_TURN`): gestures start/end with it and its background screen is released when it closes.
The screen registry answers `thread/read` for ACP phone sessions as root threads instead of asking
Codex (which answered `thread not loaded`).

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
chat page's own `AgentConversation` parser), the remote T3 lifecycle (`RemoteT3RuntimeTest`: in-memory
remote host in virtual time, plus the `HttpURLConnection` binding against a loopback server), MCP
bridge, settings read/write and validation, sign-in URL capture
and browser hand-off (mock `IntentLauncher`), and island projections checked against
SystemUI's own `IslandPayload` parser (host stand-ins for `android.util.JsonReader`).
