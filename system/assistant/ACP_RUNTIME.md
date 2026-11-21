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
`HTTPS_PROXY`, and auto-update disabled through `AGY_DISABLE_AUTO_UPDATE=1`,
`DISABLE_AUTO_UPDATE=1` and `NO_UPDATE_NOTIFIER=1`. The binary's own disable flag
still has to be confirmed on device; add it through `extraArgs`.
`"phoneMcp": null` turns off the built-in phone tool server.

## Protocol and records

ACP v1, newline-delimited JSON-RPC 2.0 on stdio, with one agent process per task:
`initialize` → `session/new` (declares `taku_phone`) → `session/prompt` while
`session/update` streams → `session/cancel` when `cancel` appears or the
15-minute budget runs out. If the agent does not confirm within the grace period,
its process is killed.

Files written to the task directory: `state.json`, `progress.json` (the Dynamic
Island summary), `runtime-info.json`, `result.txt` (the task-result schema),
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

`bash system/assistant/test-acp-runtime-host.sh` runs against an in-process fake ACP agent.
