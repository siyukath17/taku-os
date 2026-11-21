# ACP task runtime (phase 1)

`TaskBroker` runs each background task through a `TaskRuntime`. `RuntimeRouter`
selects it; **Codex (`CodexAppServer`) is the default and the fallback for every
doubtful case**. The ACP runtime is used only when a background task positively
selects it and an agent binary is configured.

## Selection

Precedence: `request.json "runtime"` > env `TAKU_TASK_RUNTIME` > `runtime.json "default"` > `codex`.

| Situation | Runtime | `runtime-route.json` reason |
| --- | --- | --- |
| no `runtime.json` | codex | `default` |
| unreadable / invalid `runtime.json` | codex | `config-invalid` |
| unknown name | codex | `unknown-runtime` |
| `acp` for a chat turn, panel update or agent control | codex | `acp-ineligible:*` |
| `acp` without `acp.binary` | codex | `acp-unconfigured` |
| `acp` for a background task, binary configured | acp | `selected-by-{request,env,config}` |

## `runtime.json`

`/data/user/0/org.agentos.assistant/files/runtime/runtime.json`:

```json
{
  "default": "acp",
  "acp": {
    "binary": "/data/local/agentos/acp/bin/agy",
    "home": "/data/local/agentos/acp/home",
    "appDataDir": "/data/local/agentos/acp/app-data",
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
`"phoneMcp": null` turns off the built-in phone tool server. Optional
`"authUrlHosts"` overrides the sign-in URL host allow-list (default `accounts.google.com`).

**Auto-update switch (checked read-only, 2026-11-21).** The official
`cli_android_arm64/antigravity` build (ELF aarch64, `/system/bin/linker64`, Go) contains
`AGY_CLI_DISABLE_AUTO_UPDATE` next to the log line `Auto-update disabled via environment
variable %s`, and the updater endpoint `antigravity-cli-auto-updater-…run.app`. No CLI flag
that disables updates was found in its strings, so the environment variable is the switch;
`extraArgs` stays empty. This was a `strings` check on macOS: confirm on the Pixel that no
update check runs (no request to the updater host) during the first device session.

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

Still to confirm on device: whether `agy` prints the URL (stdout, stderr, or a protocol
message) or only calls its local `http://127.0.0.1:<port>/open-url` helper. Loopback URLs are
deliberately not opened.

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

## Phone tools over MCP

`PhoneMcpBridge` is the MCP core: `initialize`, `tools/list`, `tools/call`, JSON
Schema argument checks (-32602), and permission checks (`readOnly` and `ask`
deny non-observation tools as `PERMISSION_DENIED` tool errors). Execution goes to
`PhoneNativeTools.execute`, the dispatcher Codex uses. `ask_user` and
`confirm_action` are not exposed yet. `PhoneMcpHost` relays the agent's stdio
child (`app_process … PhoneMcpHost relay`) to the broker socket `taku-phone-mcp`.
That socket serves root and system peers only, and the thread identity is
`acp-<taskId>`.

## Tests

`bash system/assistant/test-acp-runtime-host.sh` runs against an in-process fake ACP agent:
router, ACP lifecycle, MCP bridge, settings read/write and validation, sign-in URL capture
and browser hand-off (mock `IntentLauncher`), and island projections checked against
SystemUI's own `IslandPayload` parser (host stand-ins for `android.util.JsonReader`).
