---
name: taku-open-access
description: Use Android commands to inspect and operate Taku OS conversations, drafts, accounts, approvals, skills, plugins, Memory, Audio, workspaces, Panels, Feed, widgets and the Dynamic Island. Also use it whenever the user asks to react to, like or put an emoji on their chat message (a reaction badge on the bubble, such as "add a 🎉 reaction" or "给这条消息点个赞") — reactions/set does that, so never just type the emoji or refuse.
---

# TakuOS open access

Run these commands on the device (from a host, enter `adb shell` first). Define these helpers once. Replace placeholder IDs and example revisions with values from the corresponding read before making a call.

```sh
D=content://org.agentos.assistant.open-access
A=content://org.agentos.agent.home-assistant
M=content://org.agentos.memory.open-access
F=content://org.agentos.agent.home-feed
L=content://org.agentos.agent.home-layout
V=content://org.agentos.audio.open-access
Q=content://org.agentos.assistant.permissions
taku_call() {
    if [ "$#" -eq 3 ]; then
        content call --uri "$1" --method "$2" --arg "$3"
    else
        content call --uri "$1" --method "$2"
    fi
}
```

Check the JSON in `Bundle.result` for `ok` before using `result`. Error shape varies by provider (`code,message` or `error`). If a mutation's reply is lost, read back state or the receipt before retrying. Root, system and ADB shell (UID 0/1000/2000) and trusted platform-signed components pass the ordinary app permission check. Operation-specific user, state and caller requirements still apply. Where App Permission Center policy applies, ordinary third-party apps use `$Q` (default `deny`). App callers may need the provider authorities in manifest `<queries>`. See [Taku system AI settings](../taku-system-ai-settings/SKILL.md) for permissions, model routing, usage and Jev settings, and [Taku Island](../taku-island/SKILL.md) for the SystemUI interface.

### Open-access permission boundary

On `$D`, third-party callers need an explicit `taku.root` grant for `manageAssistant`
(the whole endpoint, regardless of its inner `operation`), plugin installation/removal,
configuration writes/reloads, System AI mutations, and writes under `requests/`,
`accounts/`, `composer/` and `skills/`. This includes approval responses, account
login/cancel/refresh/key changes, draft/option changes and message submission.
All `capture/*`, `jev/*`, `account/login/*` operations (including reads), `interrupt`, `cancelQueued` and
`readTaskDetail` also require root: capture can analyze screenshots and start tasks,
and task control/detail must not be available through a control-only grant.
`taku.control` alone returns `PERMISSION_DENIED` before argument parsing or execution,
including in auto/yolo mode unless root access is separately enabled by policy.

The family read exceptions remain `taku.control`: `requests/list`, `requests/read`,
`accounts/read`, `accounts/keys`, `composer/read`, `composer/receipt`,
`composer/options/read`, `skills/read`, `skills/community/catalog`,
`skills/community/detail`, `skills/community/check-update` and
`skills/credentials/status`. `readSystemAi` also remains a control read. Existing
secret-request restrictions still apply even to third-party root grants.
UID 0/1000/2000 retain direct access; the first-party GUI uses the signature-protected
`AgentGatewayProvider` for these operations. Workspace calls remain local-agent-only.

## Default desktop on Taku images

```sh
getprop ro.taku.home.package
cmd role get-role-holders --user 0 android.app.role.HOME
cmd role get-role-holders --user 0 android.app.role.ASSISTANT
cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME
settings --user 0 get secure assistant
settings --user 0 get secure voice_interaction_service
```

On supported ROMs the read-only product property and both role holders are `org.agentos.agent`. HOME resolves to `org.agentos.agent/.home.HomeActivity`. Both secure settings point to `org.agentos.agent/.home.assistant.SystemAssistantService`. Shell/root can repair with `cmd package set-home-activity --user 0 org.agentos.agent/org.agentos.agent.home.HomeActivity` and `cmd role add-role-holder --user 0 android.app.role.ASSISTANT org.agentos.agent`. Check command errors and re-read holders and the resolved component. Use the target Android user's ID when inspecting another user. An empty property indicates older images without the policy. On policy-enabled ROMs, clearing HOME restores Taku if available, while a missing/disabled Home uses Android recovery. Setup wizard and locked-user FallbackHome remain platform-owned.

Open Home with `am start --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME`. Use `taku_call "$A" system/open` for system AI input and `assistant/get` to confirm. `UNAVAILABLE` or `TIMEOUT` can indicate a failed handoff. `am start -a android.intent.action.VOICE_ASSIST -n org.agentos.agent/.home.HomeActivity` can activate the microphone. Cancel with `assistant/voice-cancel`.

### Taku recent tasks (Quickstep presentation)

```sh
R=content://com.android.launcher3.taku.recents
taku_call "$R" list
taku_call "$R" show
taku_call "$R" open TASK_ID
taku_call "$R" remove TASK_ID
taku_call "$R" home
```

`list` returns `tasks[]` (`taskId,userId,component,label`) in Android recent order for the current user and visible profiles, excluding HOME/Recents. Restored tasks may have no live activity, labels may be null, and split tasks may be separate. The interface exposes neither thumbnails nor visual grouping. `show` queues the native overview animation (`queued:false` if visible); a disconnected SystemUI or full queue returns an error. Pass a current integer task ID to `open` or `remove`; invalid/stale IDs fail. `remove` dismisses a task and preserves app data. Check visibility with `dumpsys activity activities` and underlying tasks with `dumpsys activity recents`. Older images may lack the provider. Keep Quickstep as the system recents/gesture service.

### Advanced assistant management

Call `manageAssistant` on `$D` with a JSON `operation` and the fields below. Extra fields return `UNSUPPORTED_PARAMETER`; unsupported users return `USER_UNAVAILABLE`. Use native IDs from the relevant read.

```sh
taku_call "$D" manageAssistant '{"operation":"thread/goal/get","threadId":"THREAD_UUID"}'
```

| Operations | Arguments and behavior |
|---|---|
| `thread/name/get`, `thread/name/set` | `threadId`; set adds `name` (1–120 UTF-16 units after trim). Get returns `{threadId,name,title}`; set returns `{threadId,loaded:true}` and updates the title in chat and history. |
| `thread/goal/get`, `thread/goal/clear`, `thread/goal/set` | `threadId`; set adds `status:"active" or "paused"`, `objective` for active, optional positive integer or null `tokenBudget`. |
| `thread/queue/list`, `thread/queue/submit` | `threadId`; list takes optional `cursor`; submit takes UUID `requestId` and nonempty `text`, returns `submission:"queued" or "started"`. After a lost reply check queue and conversation before resubmitting. |
| `thread/queue/start`, `thread/queue/delete`, `thread/queue/reorder` | `threadId`; start takes optional `queuedSubmissionId`, delete requires it, reorder takes ordered `queuedSubmissionIds`. |
| `taskFiles/list`, `taskFiles/read`, `taskFiles/write` | Workspace-relative `path` (optional for list); write adds `text` (max 32,000 characters). Read returns `text`. To list published results pass `taskId`; to read pass `{taskId,path:"result.txt"}`. Results are read-only; optional `threadId` checks ownership (`TASK_THREAD_MISMATCH`). |
| `skills/read`, `skills/set-enabled`, `skills/select` | Read takes optional `threadId` and returns source commit (if known) and body hash. Enable takes `{path,enabled}` (boolean); select takes `{threadId,path}` and toggles selection. Use returned paths. A frozen catalog remains readable during a turn. |
| `skills/community/catalog`, `skills/community/detail`, `skills/community/install` | `sourceId`; detail and install add `name,commit` from the catalog. |
| `skills/community/add`, `skills/community/check-update`, `skills/community/upgrade`, `skills/community/rollback`, `skills/community/uninstall` | Add takes `repo` (`owner/repo`), optional relative `path` and boolean `private`. The others take installed `path`; upgrade also takes `commit` from check-update. |
| `skills/credentials/status`, `skills/credentials/remove`, `skills/credentials/set` | `repo`; set adds `token` (8–1024 printable ASCII characters, else `INVALID_CREDENTIAL`) or a read-only regular FD in `extras["credential"]`. Returns `configured`, never the token. |
| `harness/read`, `harness/refresh` | `threadId`; refresh uses current screenshot and response style preferences. |
| `plugin/list`, `plugin/install`, `plugin/uninstall`, `plugin/set-enabled` | List takes no fields; install takes `pluginName,marketplacePath`; uninstall takes `pluginId`; enable adds boolean `enabled`. `mcpReloaded:false` requires a separate reload. |
| `app/list`, `mcpServerStatus/list`, `mcpServer/tool/call`, `config/mcpServer/reload` | Lists take optional `cursor` (use returned `nextCursor`). Call takes `threadId,server,tool,arguments` (JSON object) and returns the result with `isError`. Use the conversation's catalog and policy. Reload takes no fields. |

Workspace text files are UTF-8 `.txt/.md/.csv/.tsv/.log`; reads are bounded to 128 KiB and reject traversal and symlinks. Invalid title or non-object tool arguments return `INVALID_ARGUMENT`; missing tool/server returns `MCP_TOOL_UNAVAILABLE`. See [Panels](#panels) for large responses via `resultUri`.

```sh
taku_call "$Q" read '{}'
taku_call "$Q" setMode '{"mode":"deny"}'
taku_call "$D" skills/read '{}'
taku_call "$D" plugin/list '{"cwds":["/data/local/agentos/work"]}'
taku_call "$D" plugin/installed '{}'
taku_call "$D" mcpServerStatus/list '{"limit":100}'
taku_call "$D" plugin/install '{"pluginName":"NAME","marketplacePath":"PATH"}'
taku_call "$D" plugin/uninstall '{"pluginId":"ID"}'
taku_call "$D" config/value/write '{"keyPath":"plugins.\"ID\".enabled","mergeStrategy":"upsert","value":false}'
taku_call "$D" config/mcpServer/reload '{}'
taku_call "$M" browse '{"limit":100,"offset":0}'
taku_call "$M" graph '{"limit":100,"offset":0}'
taku_call "$M" search '{"query":"meeting","limit":10}'
taku_call "$M" get '{"id":"ID","history":true}'
taku_call "$M" board '{}'
taku_call "$M" profile '{}'
taku_call "$M" agenda '{"days":3}'
taku_call "$M" nudges '{"days":7,"limit":30}'
taku_call "$M" status '{}'
taku_call "$M" configure '{"queryEnabled":true}'
taku_call "$M" update '{"id":"ID","revision":1,"title":"Title","body":"Text"}'
taku_call "$M" forget '{"id":"ID"}'
taku_call "$D" account/usage/read '{}'
taku_call "$D" account/rateLimits/read '{}'
taku_call "$D" thread/usage '{"threadId":"THREAD_ID"}'
```

App Permission Center `read` and `setMode` above use the same policy as Taku AI settings. In manual mode, per-app grants and mode escalation are reserved for its UI (`MANUAL_MODE_LOCKED`).

Direct `plugin/read` takes `pluginName` and optional `marketplacePath` or `remoteMarketplaceName`. Reload MCP after direct plugin changes. `/system_ext/bin/taku-plugin` shares the plugin GUI's native RPC: `list`, `status`, `install MARKETPLACE_PATH NAME`, `enable ID`, `disable ID`, `uninstall ID` (alias `remove`), `reload`. JSON results; exit 1 signals failure, exit 2 a successful change with failed MCP reload. List covers registered sources including the workspace.

God Mode has a separate root host and registry (`/data/local/agentos/godmode/registry.json`). `taku-plugin godmode` supports `list`, `show ID`, `install PATH`, `install-url '{"url":"HTTPS_URL","sha256":"64_HEX_CHARS","signature":"BASE64","publicKey":"BASE64_X509"}'`, `enable ID`, `disable ID`, `reload ID`, `rollback ID`, `remove ID`, `tools [TOOLSET]`, `tool ID NAME`, `call-tool ID NAME '{}'`, `checks [ID]`, `run-check ID NAME '{}'`, and `evidence [ID]`. `mcp` publishes live `PLUGIN_ID__TOOL_NAME` entries over stdio; discovery metadata does not install Codex skills. Default abstract socket `taku-godmode`; `TAKU_GODMODE_SOCKET` overrides it (absolute paths use filesystem sockets). Output is JSON. Check `show` after a failed change. Errors include `SERVICE_UNAVAILABLE`, `NOT_FOUND`, `INVALID_MANIFEST`, `INVALID_PACKAGE`, `ALREADY_INSTALLED`, `VERSION_DOWNGRADE`, `HOST_VERSION`, `LOAD_FAILED`, `STOP_FAILED`, `DISABLED`, `NO_ROLLBACK`, `RELOAD_FAILED`, `CHECK_FAILED`, `CHECK_TIMEOUT`, `CORRUPT_EVIDENCE` and `EVIDENCE_WRITE_FAILED`. Evidence may contain sensitive untrusted data; check operation state after an evidence write failure. Plugins run as root on the development host; use an isolated device.

`taku-plugin godmode local '{"target":"local","action":"apk-paths","package":"PACKAGE"}'` returns base/split APK paths. Other actions: `packages`, `read-file` (`path`, returns base64), `shell` (`command`), `processes`, `logcat`, `getprop` (`key`), `settings-get/settings-put` (`namespace,key`, plus `value` to write), `activity-start` (`component`), `activity-stop` (`package`), `display`, `window`, `device-info`, `root`. Command results include `exitCode,output`. Unknown action returns `USAGE`; external targets return `REMOTE_UNAVAILABLE`. REA and its Android runtime are separate installs.

### Memory

Use the `$M` calls above. `status` reports the saved policy; `browse` returns up to 100 records per page; `get` returns full text and optional revisions. Reads require `queryEnabled:true`. While off, `update` returns ID and revision only. `configure` accepts `enabled,analysisEnabled,queryEnabled,proactive,model,followSystem,sources,retentionDays,userName,autoOrganize,organizeModel,agentBoard,agentProfile,nudges,feedCards,feedDescribe`. `sources` is a package array; preserve existing selections and respect `neverObserved`. `retentionDays` is 0–30. Enable recommendations only when asked: `enabled,analysisEnabled,nudges,feedCards:true`, and selected `sources`; `feedDescribe` uses model calls for generated cards. Memory manages expiry/retraction of its own cards.

Page capture requires `org.agentos.memory/.ObservationService` in `settings get secure enabled_accessibility_services` and `accessibility_enabled=1`; preserve other enabled services. Notification capture needs `cmd notification allow_listener org.agentos.memory/.NotificationTap 0`; verify `settings get secure enabled_notification_listeners`. `pm grant org.agentos.memory android.permission.POST_NOTIFICATIONS` allows fallback reminders. Require `observationConnected:true` and a valid `model` in status; model unavailability queues analysis. Use Taku AI for model settings.

`nudges` takes `days` 1–7 (default 7) and `limit` 1–30 (default 30); returns newest first, with `card`, `feedback` (latest 20 entries), and `reactions` (asked/opened/pinned/removed). Feedback may have `cursor,at,op,origin,effect,phase,intentType`; selected means action accepted. Empty items means no reminders. `status.feedFeedbackCursor` is read-only, `0` before replay, otherwise the last processed checkpoint. `deleteBoardItem` takes `part,name`; `deleteProfileItem` takes `kind,item` and optional `note`; `clearDerived` and `maintain` take `{}`. The older Memory authority retains its signature/query policy. Account usage/costs may be absent; missing cost is unknown. There is no persistent Case registry or cost ledger.

## Assistant settings and authorization

```sh
am start -a org.agentos.action.AI_SETTINGS -p org.agentos.agent
am start -a org.agentos.action.AI_SETTINGS -p org.agentos.agent --es section account
taku_call "$D" readTaskDetail '{"taskId":"TASK_ID"}'
taku_call "$D" accounts/read
taku_call "$D" accounts/refresh
taku_call "$D" accounts/login '{"type":"chatgptDeviceCode"}'
taku_call "$D" accounts/cancel
taku_call "$D" accounts/keys
taku_call "$D" accounts/saveKey '{"key":"OPENROUTER_KEY"}'
taku_call "$D" accounts/saveKey '{"id":"PROVIDER_ID","ref":"KEY_REF","key":"OPENROUTER_KEY"}'
taku_call "$D" accounts/removeKey '{"id":"PROVIDER_ID","ref":"KEY_REF"}'
taku_call "$D" accounts/openMagpie
taku_call "$D" requests/list '{"threadId":"THREAD_UUID"}'
taku_call "$D" requests/read '{"threadId":"THREAD_UUID","key":"REQUEST_UUID"}'
taku_call "$D" requests/submit '{"threadId":"THREAD_UUID","key":"REQUEST_UUID","values":{"FIELD_ID":"VALUE"}}'
taku_call "$D" requests/cancel '{"threadId":"THREAD_UUID","key":"REQUEST_UUID"}'
```

Taku AI account UI is `/system-ai?section=account`. `accounts/login` also accepts `type:"chatgpt"` for browser sign-in. Read/refresh/login/cancel return `{connected,phase,kind,plan,code,url}`; phase is disconnected, starting, pending, verifying, connected or failed. While pending, root/system/shell can read the official `url` (only auth.openai.com or chatgpt.com) and one-time device `code`; third-party callers never receive either. Open the URL, enter the code where requested, then poll `accounts/read` until authorization settles. Avoid logging the challenge. Failure codes: `ACCOUNT_LOGIN_FAILED`, `ACCOUNT_CANCEL_FAILED`.

`accounts/keys` and save/remove use masked summaries. For new keys omit `id,ref`; for replacement/removal use both from the list. Instead of `key` in JSON, a `ContentResolver.call` client can pass a regular read-only UTF-8 FD in `extras["credential"]`. Errors: `KEY_SOURCE_AMBIGUOUS` (both), `KEY_SOURCE_REQUIRED` (neither), `INVALID_CREDENTIAL`, `INVALID_KEY_REFERENCE`, `KEY_OPERATION_FAILED`. Plaintext keys are never read back. `accounts/openMagpie` opens the browser without revealing the console URL; `MAGPIE_MANAGEMENT_UNAVAILABLE` or `BROWSER_UNAVAILABLE` on failure.

Pending requests use `/assistant-management?section=requests`. List returns `{threadId,key,method,secure,inline}`; `threadId:""` lists runtime-wide requests. Pass the exact listed thread and key. Inline questions are handled in chat. Read adds `kind,url,details,fields`. Kinds: attestation, accountAuthorization, mcp, questions, toolResult, permissionApproval, commandApproval, fileChangeApproval, action. Each field is `{id,label,secret,required,type,multiline,options}`; type is string, boolean, integer or number. `url` is an HTTPS MCP link or empty.

`requests/submit.values` maps IDs to strings (including booleans and numbers in text form); approvals without fields use `{}`. Secret fields can instead be `{fieldId:value}` JSON in a file descriptor under `extras["credential"]`. Duplicate sources return `KEY_SOURCE_AMBIGUOUS`; missing/unknown/wrongly typed values return `INVALID_ARGUMENT`. Cancel declines. `requests/respond` takes `{threadId,key,response}` for native payloads: normal question answer, `{text}` for tool result, or `{decision:"accept"}` for native approvals. Third-party apps cannot read or answer secret requests (`SECURE_INPUT_REQUIRED`). Attestation needs an upstream token; Taku cannot issue one. Wrong thread, stale key or replay returns `REQUEST_EXPIRED`; unconfirmed delivery returns `REQUEST_RESPONSE_FAILED`.

`readTaskDetail` takes a decimal string `taskId`, returns `{taskId,state,prompt,message}` or `TASK_NOT_FOUND/TASK_UNAVAILABLE`. `thread/usage` returns `{threadId,usage}`; null means upstream has not published it.

## On-device ACP task runtime

Background tasks run on Codex unless `runtime.json` selects the on-device ACP agent (`agy acp`).
Root, system and shell callers only; other apps get `SecurityException: PERMISSION_DENIED`.

```sh
A=content://org.agentos.assistant.acp-runtime
content call --uri $A --method acp --arg '{"action":"read"}'
content call --uri $A --method acp --arg '{"action":"update","patch":{"default":"acp","acp":{"binary":"/data/local/agentos/acp/bin/agy","httpsProxy":"http://127.0.0.1:7890","extraArgs":[]}}}'
content call --uri $A --method acp --arg '{"action":"update","expectedRevision":"REVISION","patch":{"default":null,"acp":{"httpsProxy":null}}}'
content call --uri $A --method acp --arg '{"action":"signIn"}'
content call --uri $A --method acp --arg '{"action":"signInStatus"}'
content call --uri $A --method acp --arg '{"action":"signInCancel"}'
```

The result is `Bundle[{result={"ok":true,"result":{...}}}]` or `{"ok":false,"message":CODE}`. `read` returns
`{path,exists,valid,revision,default,effectiveDefault,acp:{binary,httpsProxy,extraArgs,home,appDataDir,cwd,args,envNames,loginMethod}}`;
`effectiveDefault` is what a task without request/env overrides would use (`acp` only with a binary).
`acp.env` values are never returned, only `envNames`. `update` writes only `default` (`codex`|`acp`|null)
and `acp.binary` (absolute path under `/data/local/agentos/`, `/system/`, `/system_ext/`, `/product/` or
`/vendor/`, or ""; the broker runs it as root), `acp.httpsProxy` (`http|https|socks5|socks5h://host:port`, no
credentials, or ""), `acp.extraArgs` (≤16 strings, ≤256 chars, never `--app-data-dir`; root/system callers only, ADB shell gets
`PRIVILEGED_FIELD:acp.extraArgs`). `null` clears a
field; all other keys in the file are kept. The write is atomic; pass `revision` from `read` as
`expectedRevision` to refuse a concurrent change. Errors: `EMPTY_PATCH`, `INVALID_PATCH`, `UNKNOWN_FIELD:<key>`,
`INVALID_DEFAULT`, `INVALID_ACP`, `INVALID_BINARY`, `BINARY_LOCATION_NOT_ALLOWED`, `INVALID_PROXY`, `PROXY_CREDENTIALS_NOT_ALLOWED`,
`INVALID_EXTRA_ARGS`, `MANAGED_FLAG:<flag>`, `REVISION_CONFLICT`, `RUNTIME_CONFIG_INVALID` (a hand-edited file
that does not parse is never overwritten), `RUNTIME_CONFIG_TOO_LARGE`, `UNKNOWN_ACTION`.

A task that ends `needs_user` with `acp-auth.json` naming `oauth-personal` needs the agent's own Google
sign-in. `signIn` (or the island's **Sign in** action) starts `agy authenticate oauth-personal` with the
task runtime's private `HOME`/`--app-data-dir`. agy hands the official `https://accounts.google.com/…` URL
to `xdg-open`; Taku puts a private `xdg-open` first on the agent's `PATH` that passes it to the broker through
a FIFO, checks the host and opens it with `am start -a android.intent.action.VIEW`. The browser must reach
Google (the phone's own VPN/proxy); the redirect goes to the agent on `http://localhost:<port>/auth/callback`,
so finish sign-in while `signInStatus` is `browser_opened`: `{state,method,urlHost,error,updatedAt}`, state `idle`, `starting`,
`authenticating`, `browser_opened`, `signed_in`, `failed`, `timed_out` (10 min) or `cancelled`; errors
`ACP_UNCONFIGURED`, `start-failed`, `initialize-error`, `method-unavailable`, `browser-launch-failed`,
`authenticate-error`, `agent-cancelled`, `agent-exited`. Only one sign-in runs at a time (`alreadyRunning`). The login is
stored by the agent in its own directory; Taku never reads, returns or logs the URL or any token.
Resubmit the kept request afterwards.

While an ACP task runs, its background-task notification also carries a `taku.island.v1` progress card
(phase, current tool, plan progress; sign-in action on `needs_user`). Codex tasks are unchanged.

## Screen suggestions

```sh
taku_call "$D" capture/request '{"manual":true}'
taku_call "$D" capture/request '{"manual":true,"source":"invocation"}'
taku_call "$D" capture/read '{"captureId":"CAPTURE_UUID"}'
taku_call "$D" capture/accept '{"captureId":"CAPTURE_UUID"}'
taku_call "$D" capture/openConversation '{"captureId":"CAPTURE_UUID"}'
taku_call "$D" capture/dismiss '{"captureId":"CAPTURE_UUID"}'
taku_call "$D" manageSystemAi '{"operation":"setAssistScreenshots","enabled":true}'
```

Keep the source app visible. Default `source:"system"` takes a fresh capture; `"invocation"` reuses the latest Android assistant screenshot and requires `manual:true` (default). `manual:false` invokes the Jev gate (`JEV_DISABLED`). Invalid fields return `INVALID_ARGUMENT`; resolve the existing suggestion on `CAPTURE_BUSY`. Android needs both `assist_structure_enabled` and `assist_screenshot_enabled` (read with `settings --user 0 get secure KEY`, set to `1` or `0`). Secure screens can yield no image. The legacy manual trigger is `am broadcast -n org.agentos.assistant/.JevTestTrigger -a org.agentos.assistant.MANUAL_SCREEN_SUGGESTION`. `system/capture` is the signed handoff and requires a current backend token (`CAPTURE_EXPIRED`).

Use `captureId` from request. States: capturing, analyzing, ready, no_screenshot, no_suggestion, expired, accepted, dismissed, failed. Accept only ready (expires in two minutes); repeats keep the same published task ID. `openConversation` returns `{opened:true,threadId}` after acceptance or `THREAD_UNAVAILABLE/AGENT_UI_UNAVAILABLE`.

## Assistant drafts

```sh
taku_call "$D" composer/read '{"threadId":""}'
taku_call "$D" composer/write '{"threadId":"","revision":"CURRENT_REVISION","text":"A draft to review"}'
taku_call "$D" composer/createThread '{}'
taku_call "$D" composer/move '{"threadId":"","revision":"REVISION","targetThreadId":"FRESH_THREAD_UUID"}'
taku_call "$D" composer/removeAttachment '{"threadId":"THREAD_UUID","expectedRevision":"REVISION","attachmentId":"ATTACHMENT_UUID"}'
taku_call "$D" composer/options/read '{"threadId":"THREAD_UUID"}'
taku_call "$D" composer/options/write '{"threadId":"THREAD_UUID","options":{"responseStyle":"detailed"}}'
taku_call "$D" composer/submit '{"threadId":"THREAD_UUID","revision":"REVISION","requestId":"REQUEST_UUID","action":"sendMessage","attachmentIds":[]}'
taku_call "$D" composer/receipt '{"threadId":"THREAD_UUID","requestId":"REQUEST_UUID"}'
taku_call "$D" composer/settle '{"threadId":"THREAD_UUID","requestId":"REQUEST_UUID"}'
taku_call "$D" composer/release '{"threadId":"THREAD_UUID","requestId":"REQUEST_UUID"}'
taku_call "$D" composer/restore '{"threadId":"THREAD_UUID","expectedRevision":"REVISION","recoveryId":"RECOVERY_UUID"}'
taku_call "$D" composer/restoreTask '{"threadId":"THREAD_UUID","expectedRevision":"REVISION","taskId":"TASK_ID"}'
taku_call "$D" composer/preserveDraft '{"threadId":"THREAD_UUID","recoveryId":"RECOVERY_UUID","text":"An edit to keep"}'
taku_call "$D" composer/handoff '{"threadId":"THREAD_UUID","handoffId":"HANDOFF_UUID","text":"A draft to review"}'
taku_call "$D" interrupt '{"threadId":"THREAD_UUID","turnId":"ACTIVE_TURN_ID"}'
taku_call "$D" cancelQueued '{"threadId":"THREAD_UUID","taskId":"QUEUED_TASK_ID"}'
```

```sh
taku_call "$D" manageSystemAi '{"operation":"readComposerScreenshots"}'
taku_call "$D" manageSystemAi '{"operation":"setComposerScreenshots","enabled":true}'
```

Use a lowercase conversation UUID or `threadId:""` for the new-chat slot. Locked user 0 returns `USER_LOCKED` and other users return `USER_UNAVAILABLE`. Drafts return `{threadId,revision,text,attachments,recovery,pendingReceipts}`; attachments carry `{id,name,mime,size}`. Write uses the current revision (initial `""`), preserves up to 16,000 characters, clears on empty input, and leaves the revision unchanged for identical text. `DRAFT_REVISION_CONFLICT` returns the current revision; re-read then reconcile. Other errors: `INVALID_ARGUMENT`, `REQUEST_TOO_LARGE` (>48,000 characters), `DRAFT_SAVE_FAILED`.

`composer/attach` takes `{threadId,expectedRevision,name,mime}` and a read-only FD in `extras["attachment"]`; shell clients can add `uri` after granting the assistant read access to the `content:` URI. The shell command cannot carry an FD. Limit is eight files and 20,000,000 bytes each. Errors: `ATTACHMENT_LIMIT`, `ATTACHMENT_TOO_LARGE`, `ATTACHMENT_UNAVAILABLE`, `ATTACHMENT_ACCESS_DENIED`.

Submit attachment IDs in order; attachment-only messages are valid. For follow-ups use `action:"steer",expectedTurnId`; for Panel updates use `action:"continue",panelId,expectedSourceVersion`. Request IDs are replayable only for identical input (`REQUEST_ID_REUSED`). Check `composer/receipt` after IPC loss: queued/accepted means published, notSent means rejected, missing/deliveryUnknown require review. Settle applies a confirmed receipt to its original revision; release preserves content and unlocks editing. Handoff saves a displaced draft and deduplicates identical handoff IDs. Restore into empty drafts using a recovery entry or a same-thread failed/needs_user task. Re-read after `DRAFT_NOT_EMPTY`, `RECOVERY_UNAVAILABLE` or `TASK_FAILURE_CHANGED`.

Options accept `model,effort,serviceTier,multiAgentMode,permissionMode,responseStyle`; null clears the override. Read `options,effective,models` for available values. Errors: `MODEL_UNAVAILABLE`, `EFFORT_UNSUPPORTED`, `SERVICE_TIER_UNSUPPORTED`. The composer screenshot switch is distinct from Android privacy. Its read/write return `{enabled}`; setter needs boolean `enabled`, errors `INVALID_ARGUMENT/SETTINGS_WRITE_FAILED`. Authorized invocation images last two minutes; `SCREENSHOT_UNAVAILABLE` returns notSent and retains the draft. Reinvoke or turn the preference off. Receipt reads perform no capture or send.

Interrupt targets the exact active turn, pauses its goal, and returns `TURN_CHANGED` if stale. `cancelQueued` returns `cancellationRequested:true`; re-read task state. Errors: `TASK_THREAD_MISMATCH`, `TASK_FINISHED`, `TURN_STARTED`.

## Taku Audio settings

```sh
taku_call "$V" settings '{"action":"read"}'
taku_call "$V" settings '{"action":"setAudioEnabled","enabled":false}'
taku_call "$V" settings '{"action":"configureReading","language":"zh-CN","voiceId":"zh-CN-local-3","speed":1.0}'
taku_call "$V" settings '{"action":"resetReading","language":"zh-CN"}'
taku_call "$V" settings '{"action":"configureDictation","scope":"assistant-entry","primaryProfileId":"zipformer","fallbackProfileId":null,"language":"system"}'
taku_call "$V" settings '{"action":"resetDictation","scope":"assistant-entry"}'
taku_call "$V" settings '{"action":"selectLiveProvider","profileId":"gemini-live"}'
taku_call "$V" settings '{"action":"saveLiveKey","key":"GEMINI_API_KEY"}'
taku_call "$V" settings '{"action":"removeLiveKey"}'
taku_call "$V" settings '{"action":"removeFalKey"}'
```

Use `$V`, method `settings`, JSON `action`. Supply only the fields listed for each action.

| Action | Fields |
|---|---|
| `read`, `removeFalKey`, `removeLiveKey` | none |
| `saveFalKey`, `saveLiveKey` | `key` (trimmed 1–4096 characters, no internal whitespace, control characters or commas) |
| `selectLiveProvider` | `profileId` (`fal-grok` or `gemini-live`), effective on next open |
| `selectFalModel` | `model` from `fal.models`; needs a readable key |
| `setAudioEnabled` | boolean `enabled`; disabling revokes managed sessions |
| `configureReading`, `resetReading` | `language` (`zh-CN` or `en-US`); configure also needs `voiceId` (shipped choices, `LANG-local-N`) and `speed` (0.5–2.0) |
| `configureDictation`, `resetDictation` | `scope:"assistant-entry"`; configure also requires `primaryProfileId`, `fallbackProfileId`, `language` |

Dictation defaults to `zipformer`, null fallback, and `system` language. The other profile is `fal-whisper`. A fallback must differ from the primary, and any cloud route needs a readable key. `language` accepts `system/zh-CN/en-US`; unsupported system language returns `UNSUPPORTED_LANGUAGE`. Existing sessions retain their settings. Full chat dictation uses local Zipformer and the saved language.

Read returns `enabled,audioEnabled,systemRevision,audioRevision,reading,dictation,fal,live`. `enabled` (global AI) is read-only here. Dictation backend rows expose availability reasons, processing location, languages and capture/drain limits. `fal` returns `configured,masked,model,models,realtimeAvailable:false,error`; configured means readable, not remotely validated. `KEY_UNREADABLE/MODEL_UNREADABLE` indicate saved-state problems. Replacing/removing keys retains model preference. This API starts no inference/capture and cannot return plaintext keys. Keep real keys out of conversations, task files, logs, Intents and shell history. Use the password field in `am start -n org.agentos.audio/.AudioHomeActivity` for real credentials. Errors include `INVALID_ARGUMENT`, `UNSUPPORTED_OPERATION`, `PROVIDER_NOT_CONFIGURED`, `SETTINGS_UNREADABLE`, `SETTINGS_NOT_SAVED`, `CONFIG_UNAVAILABLE`.


## Message reactions

Besides its normal reply, Taku may put one emoji reaction on a user message (Figma 297:433:
a small badge on the bubble's bottom-right corner). For each new user message (one whose turn is
still being answered) Jev decides once, in a single choice between "No reaction" and 240 curated
chat-reaction emoji (Unicode's emoji frequency ranking filtered for chat reactions, plus newer
staples such as 🥹 🫶 🫡), minus any this phone's font cannot draw. Jev judges each message on
its own, without a preference either way, and reacts when more than half of the probability is
away from "none". Nothing is decided unless Jev is enabled
with a model (the same switch and model as gateway `decide`), and history is never back-filled.
Agents set reactions from the same curated list; `reactions/options` returns it. Reactions are stored per thread/turn/item and appear as `reaction` on `userMessage`
items in session reads and snapshots, and as a live `session/reaction` stream event.

```sh
taku_call "$D" reactions/options '{}'
# {reactions:[{emoji:"😂",name:"face with tears of joy",hint:null}, ...]}: curated order, only emoji this phone draws
# React to the message you are answering: no IDs needed. In an Agent shell the conversation is $CODEX_THREAD_ID.
taku_call "$D" reactions/set "{\"threadId\":\"$CODEX_THREAD_ID\",\"reaction\":\"🎉\"}"
# {threadId, reactions:[...], target:{turnId,itemId}}
taku_call "$D" reactions/list '{"threadId":"THREAD_ID"}'
# {threadId, reactions:[{turnId,itemId,reaction:"👍"|null,source:"jev"|"agent",decidedAt}], newest first,
#  messages:[{turnId,itemId,text,reaction}]}: the 20 latest user messages, newest first, text cut to 200 chars
taku_call "$D" reactions/set '{"threadId":"THREAD_ID","turnId":"TURN_ID","itemId":"ITEM_ID","reaction":"🎉"}'
taku_call "$D" reactions/set '{"threadId":"THREAD_ID","turnId":"TURN_ID","itemId":"ITEM_ID","reaction":null}'
```

Without `turnId`/`itemId`, `reactions/set` targets the thread's latest user message (including
the one whose turn is still being answered) and reports it in `target`. To react to an earlier
message, take its `turnId`/`itemId` from `reactions/list` `messages`; give both or neither
(`INVALID_ARGUMENT`). A thread with no user message returns `MESSAGE_UNAVAILABLE`.

`reactions/set` accepts one `emoji` from `reactions/options` exactly as returned (for example
"🎉" or "❤️" with U+FE0F), or `null` to clear. Anything else, including skin-tone variants,
unqualified forms such as a bare "❤", text, or several emoji, is `INVALID_REACTION`. It updates an open chat at once and returns
the same list. Unknown parameters are `UNSUPPORTED_PARAMETER`.

## Primary conversation replies

```sh
taku_call "$D" reply/read '{"threadId":"THREAD_UUID","turnId":"TURN_ID"}'
taku_call "$D" reply/read '{"threadId":"THREAD_UUID","turnId":"TURN_ID","replyId":"REPLY_ID"}'
```

`reply/read` takes required string `threadId,turnId` and optional `replyId`. Lists at most 32 summaries with up to 4,000 readable characters each; use the returned ID for the full typed component view, provenance, readable text and lifecycle. Invalid fields return `INVALID_ARGUMENT`; missing objects return `THREAD_UNAVAILABLE/TURN_UNAVAILABLE/REPLY_UNAVAILABLE`. Lifecycle: streaming, sealed, completed, failed, interrupted. Sealed awaits native success; failed/interrupted replies may retain valid partial content. Presentation does not prove external side effects.

## Background workspace App access

Expo `/workspaces` and the retained native viewer use the same broker. Access follows the app permission policy and requires the unlocked owner. Other users get `USER_UNAVAILABLE`; locked owner gets `USER_LOCKED`. Discover leases and accepted policy ranges before making changes.

### Read and configure

```sh
taku_call "$D" workspaces/list '{}'
taku_call "$D" workspaces/apps '{}'
taku_call "$D" workspaces/configure '{"patch":{"allowBackground":false}}'
taku_call "$D" workspaces/configure '{"patch":{"maxActiveScreens":2}}'
taku_call "$D" workspaces/configure '{"patch":{"blockedPackages":["com.android.deskclock"]}}'
```

`workspaces/list` optionally takes `threadId`; `apps` returns `{apps,policy}` with `packageName,label,installed,allowed` (including saved blocks for uninstalled apps). `configure.patch` updates only included settings: boolean `allowBackground` (default true; false blocks new ordinary/additional requests), integer `maxActiveScreens` (currently 1–10, default 10; reducing retires excess idle screens at acquisition), and `blockedPackages` (replaces full array, default empty). Errors: `UNKNOWN_WORKSPACE_SETTING`, `INVALID_BACKGROUND_SETTING`, `INVALID_WORKSPACE_LIMIT`, `INVALID_APP_PACKAGE`, `INVALID_SCREEN_PACKAGE`, `DUPLICATE_APP_PACKAGE`.

Policy is saved before cleanup. Read `revocations` by generation: pending, released (blocked Apps cleaned up, other allowed Apps/turn/screen can remain), retained (user controls), failed (may be partial), superseded (target changed). Inspect `released,heldApps,screenRetained`. Revoked queued requests fail `APP_NOT_ALLOWED`. The next phone tool call in an affected turn returns a permission-change notice; reevaluate before further operations.

To match the Taku Home bookmark count, count `result.screens` rows with
`homePreviewEligible:true`. Pausing/taking over preserves eligibility during `stopping`;
closing/releasing clears it. Home previews return to the previous Home page on exit.

### Force-close a specified workspace

```sh
taku_call "$D" workspaces/release '{"threadId":"THREAD_ID","screenKey":"SCREEN_KEY","generation":"GENERATION"}'
```

Refresh `threadId,screenKey,generation`; a display number is insufficient. Force-close stops the main turn or stops the sub-agent and prevents further sub-agent turns; it can interrupt the caller, so use an independent readback. `returnToThread:true` opens the original conversation after release. `opened:false,returnError` reflect navigation failure after cleanup; avoid repeating cleanup. Errors: `SCREEN_NOT_BOUND`, `SCREEN_OWNER_MISMATCH`, `WORKSPACE_CONTROL_BUSY`, `WORKSPACE_EXECUTION_STOP_UNCONFIRMED`, `WORKSPACE_PROCESS_STOP_UNCONFIRMED`. A missing row alone cannot confirm physical close.

### Preview, open, and control an exact lease

```sh
taku_call "$D" workspaces/preview '{"threadId":"THREAD_ID","screenKey":"SCREEN_KEY","generation":"GENERATION"}'
taku_call "$D" workspaces/open '{"threadId":"THREAD_ID","screenKey":"SCREEN_KEY","generation":"GENERATION"}'
taku_call "$D" workspaces/pause '{"threadId":"THREAD_ID","screenKey":"SCREEN_KEY","generation":"GENERATION"}'
taku_call "$D" workspaces/takeControl '{"threadId":"THREAD_ID","screenKey":"SCREEN_KEY","generation":"GENERATION"}'
taku_call "$D" workspaces/input '{"threadId":"THREAD_ID","screenKey":"SCREEN_KEY","generation":"GENERATION","controlRevision":7,"input":{"type":"tap","x":100,"y":200}}'
taku_call "$D" workspaces/returnAndResume '{"threadId":"THREAD_ID","screenKey":"SCREEN_KEY","generation":"GENERATION","controlRevision":7,"requestId":"REQUEST_UUID"}'
taku_call "$D" workspaces/addRequirement '{"threadId":"THREAD_ID","screenKey":"SCREEN_KEY","generation":"GENERATION","controlRevision":7,"requestId":"REQUEST_UUID","text":"Review the current screen before continuing."}'
taku_call "$D" workspaces/executionStatus '{"threadId":"THREAD_ID","screenKey":"SCREEN_KEY","generation":"GENERATION","requestId":"REQUEST_UUID"}'
```

Use the exact lease tuple for every call and the current integer `controlRevision` for input/resume/requirement (7 in the examples). Preview opens the retained live viewer read-only; open opens its thread. Accepted activity launches return `{opened:true,threadId}`. Errors: `SCREEN_LEASE_CHANGED`, `SCREEN_REBIND_REQUIRED`, `THREAD_UNAVAILABLE`, `AGENT_FRONTEND_UNAVAILABLE`. Pause and takeControl use stop confirmation. Only confirmed `controlOwner:"user"` permits input. Input forms: `{type:"tap",x,y}`, `{type:"swipe",fromX,fromY,toX,toY,durationMs}`, `{type:"key",keyCode:4}`. Coordinates are integer screen pixels and duration is 100–2000 ms.

Use a fresh lowercase UUID `requestId` for new `returnAndResume/addRequirement`; reuse only for identical parameters. Requirements are nonblank, max 4,000 characters. `executionStatus` reads the receipt even after the screen disappears; inspect `status,requiresReconciliation,screenAvailable` before retrying. Errors: `WORKSPACE_REQUEST_ID_CONFLICT`, `WORKSPACE_REQUEST_NOT_FOUND`, `WORKSPACE_EXECUTION_BUSY`, `SCREEN_CONTROL_CHANGED`.

### Delete or archive a session and track cleanup

```sh
taku_call "$D" sessions/removals '{"action":"submit","threadId":"THREAD_ID","target":"archive"}'
taku_call "$D" sessions/removals '{"action":"submit","threadId":"THREAD_ID","target":"delete"}'
taku_call "$D" sessions/removals '{"action":"get","threadId":"THREAD_ID"}'
taku_call "$D" sessions/removals '{"action":"list"}'
```

The conversation cleanup GUI uses these durable receipts. Archive preserves history; delete removes it. Read `result.accepted` for persisted intent, `targetReached` for the session outcome and `completed` for finished processing/cleanup. Pending families are hidden and barred from acquiring resources or starting new turns. A completed archive with `targetReached:false,outcome:"no_record"` found no session to archive. Clean screens may stay idle for reuse.

Repeats reuse the pending request ID. Delete upgrades archive and increments revision; no downgrade. `get` can take `requestId`; `status:"unavailable"` means missing/expired receipt. Pending intents survive restart and the latest 100 completed receipts are retained. Status: pending, running, complete; phases: queued, verifying, cleanup, retry, persistence, complete. `persistence` with `accepted:false` means the write is unconfirmed. To verify absence the broker checks normal/archived history and unlimited `thread/loaded/list`; incomplete enumeration remains pending. Errors: `INVALID_SCREEN_THREAD`, `INVALID_SESSION_REMOVAL_TARGET`, `UNKNOWN_SESSION_REMOVAL_ACTION` or I/O failure.

### Builtin instructions after an APK upgrade

Discovery/turn preparation updates `taku-open-access/SKILL.md` when it matches the last verified official SHA-256. Interrupted updates reconcile recorded digests. `skills/read.builtinUpdate` shows retained status, reason, message and current/available hashes for local edits or untrusted baselines (`LOCAL_MODIFIED/UNVERIFIED_BASELINE`). Compare both versions before resolving. Rediscover/re-read after update; instructions already loaded in a conversation are unaffected.

### Turn-end cleanup

`workspaces/list` shows `cleanup_failed` with `lastError:"SCREEN_CLEANUP_FAILED"`, `heldApps` and `cleanupRetryDelayMs`. The screen remains reserved. The worker retries after 30 seconds until close is confirmed. Wait and query while `stopping:true,admitted:false,canResume:false`. Empty `packageName` means current page unknown.

## First-run onboarding

Home shows a first-run onboarding card (Figma 1155:527, 1379:1785, 1379:2012, 1379:2239–1379:2907, 1341:782) only when **all**
are confirmed: onboarding is not completed, AI is confirmed unavailable (`account/status`
`aiAvailable:false` with a settled `phase`), and the phone is ready (`device_provisioned=1`, this
user's `user_setup_complete=1`, user unlocked). Anything uncertain keeps it hidden: a broker that
is still loading, verifying or unreachable, or a readiness value Home cannot read. Once a
settled answer exists, a failed or unsettled account read keeps it for up to 15 s, so a single
busy call or a broker restart does not hide a card the user is working in; after that the status
is unknown again. While it is pending, Home re-checks every 3 s in the foreground (backing off to
1 min while the account cannot be read), so the card appears once everything is confirmed. Once the user reaches the AI step (`connect_ai`), the card stays there through
sign-in and verification, and after AI connects, until **Continue** (`onboarding/finish`), which
moves to **You’re all set** (`all_set`). Every AI connection page also offers **Set up later**
(`onboarding/dismiss`), including while connecting or offline. It writes `dismissed` and immediately
restores Home without requiring AI or showing a misleading connected/all-set screen. It does not
clear the name, accounts or keys; an already submitted connection may still complete. Configure AI
later from Taku AI settings, or explicitly reset onboarding to replay it. During `intro`, `name` and
`connect_ai` Home composes only the wallpaper, the empty-Home message and the card (no Feed, home
pages, status bar or AI entry; assistant intents are ignored). During `all_set` Home shows the
real Feed (its usual cards, or the Ask Taku invitation when empty) pinned to the Feed page, with
the card on top. The AI entry shows its usual hint on arrival (`assistant/get` `hintExpanded:true`,
folding back after 4 s as always). The entry and Feed cards work. **I’ll explore**, or tapping
any Feed card or the Ask Taku invitation (the tap still reaches the card), completes onboarding;
for Agents both are `onboarding/explore`. The card is
not a Feed item and is not the SystemUI island, so `feed/list` never contains it.

Progress is per Android user in `Settings.Secure` `taku_onboarding_step`
(`intro`, `name`, `connect_ai`, `all_set`, `completed`, `dismissed`; missing or unknown means `intro`). A factory reset
clears it, so onboarding runs again. Before the AI step, confirmed available AI
(`aiAvailable:true`: a ChatGPT sign-in, or Magpie running with a model) writes `completed`, even
if the card was never shown (existing users). At `connect_ai` it enables Continue instead, so Jev
and Fal can still be added; `all_set` waits for I’ll explore. Neither `completed` nor `dismissed` restarts automatically, including after reboot or OTA. Losing AI afterwards does not
restart it. An unknown account state never completes it. The name is Memory's `userName`
(`content://org.agentos.memory.open-access`, unset value `我`); when it is already set, the name
step is skipped and `connect_ai` is stored.

```sh
O=content://org.agentos.agent.home-onboarding
content call --uri $O --method onboarding/get
# Card actions, with the GUI's validation, in order:
content call --uri $O --method onboarding/set-up                 # intro → name ("Set up Taku")
content call --uri $O --method onboarding/submit-name --arg '{"name":"Deki"}'   # name → connect_ai ("Continue")
content call --uri $O --method onboarding/skip-name              # name → connect_ai ("Skip for now")
# AI step (connect_ai only). The card walks ChatGPT → Jev → Fal one page at a time ("Connect AI
# Agent", then each page's Connect / "Skip for now" / "Next"); the pages are card navigation only,
# not stored state, and these calls work whichever page the card shows:
content call --uri $O --method onboarding/connect --arg '{"service":"chatgpt"}'  # device-code sign-in; the card shows chatgptSignIn
content call --uri $O --method onboarding/cancel-sign-in
content call --uri $O --method onboarding/connect --arg '{"service":"jev"}'   # use a Jev Magpie already serves (the card tries this on opening the Jev page)
content call --uri $O --method onboarding/connect --arg '{"service":"jev","key":"apikey_..."}'   # TypeSafe or OpenRouter key
content call --uri $O --method onboarding/connect --arg '{"service":"fal","key":"..."}'
content call --uri $O --method onboarding/finish                 # connect_ai → all_set (last page's "Continue" or "Skip for now"; needs available AI)
content call --uri $O --method onboarding/explore                # all_set → completed ("I’ll explore")
content call --uri $O --method onboarding/dismiss                # connect_ai → dismissed ("Set up later"); no parameters or working AI needed
# Jev or Fal skipped during onboarding: the same setup page (Figma 2-3 / 2-5) at any step,
# with the same validation as the card. Answers {service, connection[, opened]}.
content call --uri $O --method ai-setup/get --arg '{"service":"jev"}'      # connection: not_connected | connecting | connected | unknown
content call --uri $O --method ai-setup/connect --arg '{"service":"jev","key":"apikey_..."}'   # no key: a Jev model Magpie already serves
content call --uri $O --method ai-setup/connect --arg '{"service":"fal","key":"..."}'
content call --uri $O --method ai-setup/open --arg '{"service":"fal"}'      # shows the page over the current screen
# Agent-only: back to intro (available AI completes it again immediately).
content call --uri $O --method onboarding/reset
settings get secure taku_onboarding_step
```

For a local full-flow replay without deleting accounts, keys, Memory or desktop layout:

```sh
settings put secure taku_onboarding_test_mode 1
content call --uri content://org.agentos.agent.home-onboarding --method onboarding/reset
# Disable replay and return to ordinary first-run rules:
settings put secure taku_onboarding_test_mode 0
```

This per-user switch defaults off; only `1` enables it (missing/other values mean off).
`onboarding/get.testMode` reports the same value used by Home, and `testModeSetting` names it.
In replay, existing AI does not auto-complete intro/name and an existing name does not skip
that page. The phone must still be provisioned and unlocked. Every page shows real connection
states; Continue still requires working AI. A service that is already connected shows its
connected state and the way forward; to test a fresh sign-in, sign out from the assistant's
account page first. Replay never signs out or clears credentials.
Completing the replay hides the card normally; call reset again for another run, or disable
replay. On the AI step, `onboarding/dismiss` exits without working AI; repeated dismiss calls are
harmless. On other unfinished steps it returns `WRONG_STEP`.

Every method returns `{ok:true,result}` with the state (`dismiss` reads local progress only so
it never waits for an account/network refresh; service fields retain the last observed values):
`{setting, testModeSetting, testMode, stored, visible, step, hiddenReason, phoneReady, ai, nameSet, canFinish, canDismiss, services, chatgptSignIn}`, where
`services` is `{chatgpt, jev, fal}`, each `not_connected`, `connecting`, `connected` or `unknown`
(`fal` is `null` when Taku Audio is not installed; the card then has no Fal page). `step` is the
card being shown (`null` when hidden). `canDismiss` is true on `connect_ai`. `hiddenReason` is `completed`, `dismissed`, `ai_available`,
`ai_unknown` or `phone_not_ready`. `ai` is `available`, `unavailable` or `unknown`. `nameSet` is `null` when
Memory could not be read. `chatgptSignIn` is `{url, code}` exactly as the card shows them while a
ChatGPT device-code sign-in is pending (`services.chatgpt` is `connecting` and the broker phase is
`pending`), and `null` otherwise, including when the broker's URL is not an `https` page on
`auth.openai.com` or `chatgpt.com` (the ChatGPT page then shows empty wells and waits).

Callers: Agents and ADB (root, system, shell) and platform-signed components have full access. A
third-party app needs App Permission Center `taku.control` (default deny); even then
`chatgptSignIn` is always `null` for it, and `onboarding/connect` and `ai-setup/connect` (starting
a sign-in, saving a key) are refused with `PERMISSION_DENIED`. No method ever returns a Jev or Fal
key. Errors are `{ok:false,code,message}`:

| Code | Meaning |
|---|---|
| `WRONG_STEP` | The action belongs to another step (for example `submit-name` at `intro`) |
| `ALREADY_COMPLETED` | Onboarding is completed or AI is available |
| `AI_STATUS_UNKNOWN` | The broker has not settled whether AI is available; the card is hidden |
| `PHONE_NOT_READY` | Setup wizard, provisioning or unlock not finished |
| `INVALID_NAME` | `name` missing, blank after trimming, or over 20 characters (Memory's rule) |
| `NAME_NOT_SAVED` | Memory rejected or could not save the name; the step does not change |
| `UNSUPPORTED_PARAMETER` | An argument the method does not take |
| `SETTINGS_WRITE_FAILED` | `taku_onboarding_step` could not be written |
| `AI_NOT_CONNECTED` | `finish` before AI is available |
| `INVALID_SERVICE` | `service` is not `chatgpt`, `jev` or `fal` |
| `INVALID_KEY` | A blank key, over 4096 characters, or with whitespace or commas; for Jev, not a TypeSafe (`apikey_…`) or OpenRouter (`sk-or-v1-…`) key |
| `KEY_NOT_SAVED`, `MAGPIE_UNAVAILABLE`, `NO_DECISION_MODEL`, `AUDIO_UNAVAILABLE` | The key store rejected or could not take the key. With a Jev `key`, `NO_DECISION_MODEL` and `MAGPIE_UNAVAILABLE` are not errors: the key is saved, `services.jev` reads `connecting`, and onboarding keeps retrying `jev/enable` every 5 s for up to 3 minutes (then `not_connected` and a card notice). Without a key, `NO_DECISION_MODEL` means a key is needed |
| `TASK_RUNNING`, `LOGIN_UNAVAILABLE`, `ACCOUNT_UNAVAILABLE` | ChatGPT sign-in could not start (see `account/login/start`) |
| `PERMISSION_DENIED` | A third-party app without a `taku.control` grant, or one calling a credential method (`connect`) |
| `UNKNOWN_METHOD` | Not one of the methods above |

The `connect_ai` card walks the services one page at a time (Figma 2-0 to 2-5): a greeting with
**Connect AI Agent**, then **ChatGPT**, **Jev** and **Fal** (no Fal page when Taku Audio is not
installed), with a progress bar (green once a service is connected) and a back chevron (Android
Back does the same; leaving ChatGPT mid-sign-in cancels it). A service that is already connected
shows "… Connected" with the next step and is not managed there. The pages are card navigation
only; the stored step stays `connect_ai`.

**ChatGPT** asks for a device code on arrival when no AI is available yet (`account/login/start`
with `type:"chatgptDeviceCode"`, `open:false`); when Magpie already serves a model it waits for
Connect, because a sign-in switches Magpie away. Nothing opens: the page shows three steps, the
official link (`chatgptSignIn.url`, printed without `https://`) and the one-time code
(`chatgptSignIn.code`), each with a copy button. Connect stays disabled while the sign-in waits
for approval on another device and asks again after a failure; the page turns connected by itself
when the account lands. If the user leaves Home while it is pending, the island shows "Signing…"
and a brief "Connected" when the account lands (only while Home is away), and the assistant
brings Home back. Backing out (Back, a failed or abandoned sign-in) switches a previous Magpie
connection back. **Jev** first switches on a Jev model Magpie already serves (`jev/enable`); the
page otherwise shows where to make a key, then a key field that takes a TypeSafe (`apikey_…`) or
OpenRouter (`sk-or-v1-…`) key (`jev/connect`), configured into Magpie in the background.
**Fal** shows the same steps for a fal key (Taku Audio `settings` `saveFalKey`). Key fields show the
key by default; the eye masks it (presentation only, no state). While a key saves (Jev up to about
30 s) the field locks and Connect reads "Connecting…". If a Jev key is saved before Magpie lists
its model, the page stays "Connecting…" while onboarding retries `jev/enable` (every 5 s, up to 3
minutes); a rejected key is said under the field at once. **Skip for now** moves on unconnected.
ChatGPT is required to finish (or Magpie with a model); Jev
and Fal are optional. A development device that must skip onboarding without AI can run
`settings put secure taku_onboarding_step completed` from the shell. That writes the setting
directly, without the provider's checks.

## Home feed

```sh
taku_call "$A" assistant/open '{"mode":"text"}'
taku_call "$A" assistant/get
```

```sh
taku_call "$F" feed/list '{}'          # cards, pins, removed, eventCursor
taku_call "$F" feed/get '{"id":"CARD_ID"}'
taku_call "$F" feed/load-more '{}'
taku_call "$F" feed/remove '{"id":"CARD_ID"}'
taku_call "$F" feed/restore '{"id":"CARD_ID"}'
taku_call "$F" feed/pin '{"id":"CARD_ID","index":0}'
taku_call "$F" feed/unpin '{"id":"CARD_ID"}'
taku_call "$F" feed/reorder-pins '{"ids":["ID1","ID2"]}'
taku_call "$F" feed/action '{"id":"CARD_ID","actionId":"ACTION","values":{}}'
taku_call "$F" feed/ask-taku '{"id":"CARD_ID"}'
taku_call "$F" feed/publish "$(printf '{"card":%s}' "$(cat /sdcard/Download/feed-card.json)")"
taku_call "$F" feed/generate '{"prompt":"Generate a progress card: 3 of 8 items completed, 37.5% progress. Show only these data."}'
taku_call "$F" feed/generate "$(printf '{"card":%s}' "$(cat /sdcard/Download/feed-card.json)")"
taku_call "$F" feed/retract '{"id":"CARD_ID"}'
taku_call "$F" feed/reset '{}'
```

```sh
content read --uri 'content://org.agentos.agent.home-feed/events?after=0&cardId=acme.review.1&origin=gui&op=action'
content read --uri 'content://org.agentos.agent.home-feed/events?after=0&follow=1&cardIdPrefix=acme.&origin=gui&op=action'
content read --uri "content://org.agentos.agent.home-feed/events?after=$PROCESSED_CURSOR&follow=1&cardId=acme.review.1&origin=gui&op=action"
```

```sh
node system/agent-app/modules/taku-home/scripts/read-feed-events.mjs --serial "$SERIAL" --after "$PROCESSED_CURSOR" --follow --card-id acme.review.1 --origin gui --op action
```

`feed/list` returns `cards,pins,removed,eventCursor`. If loaded with no visible cards or pins, `emptyState` describes the Ask Taku invitation (`type,app,title,message,action`); null during load/error or with content. The invitation has no card ID and opens text without sending or recording. Retry load with `feed/load-more`. `feed/ask-taku` pre-fills the composer. Remove persists until restore, replacement or reset; reset preserves event history and UUID.

`feed/generate` takes exactly one `prompt` or full `card`. Prompts use the Magpie `agent-ui.text` route; imported cards make no model call. Generation stores a new card first, returns `id,replaced:false` and the model route when relevant, and executes no card actions. Repeating generates another card. Limits: 96,000 UTF-8 bytes per request, 16,000 UTF-16 units per prompt, 96 body elements. Errors include `INVALID_SPEC`, `INVALID_MODEL_OUTPUT`, `RUNTIME_UNAVAILABLE`, `RUNTIME_TIMEOUT`, `RUNTIME_LOST`, `BUSY`, `STATE_WRITE_FAILED`. Check `feed/list` after lost replies. The host can run `node system/agent-app/modules/taku-home/scripts/generate-feed-card.mjs --serial SERIAL --prompt TEXT` or `--json-file FILE`; optional `--adb PATH`.

Read events after publication to receive choices. Finite replay ends with a checkpoint. `after` excludes a cursor (default `0`); `now` starts at the opening head; `follow=1` replays then waits. Use a quoted URI and URI-encode filter values. Up to 128 repeated `cardId` values or one literal `cardIdPrefix`, not both. Filters: `origin=gui|agent`, `op=action|publish|generate|remove|restore|pin|unpin|reorder-pins|retract|reset`. Invalid cursor, unknown/duplicate scalar fields, or URI over 16 KiB fails. Filters do not enforce ownership.

The host reader accepts repeated `--card-id`, `--card-prefix`, `--adb` and `--help`. Android callers use a worker thread and a reliable `ContentResolver.openFileDescriptor(uri,"r")` pipe; check its error after EOF. NDJSON frames: `ready`, flat `event`, `checkpoint`. Action event fields include `cursor,cardId,actionId,actionSource,intentType,phase:"accepted",values`; JSON value types are preserved. Acceptance is persisted before the action runs; use the operation result for success. Successful action replies include `eventCursor`. New values/events are bounded to 64/128 KiB.

Persist each opaque cursor with the business result or use it for idempotency. Advance to `checkpoint.through` only after all earlier matching events complete. `ready.through` and pipe output are not acknowledgments. Store progress per device, database UUID and filter. Reconnect from processed progress, discard incomplete lines and dedupe replay. To follow a snapshot, use its `eventCursor`. Idle checkpoints arrive every 10 s; leases expire at 300 s (`STREAM_LEASE_EXPIRED`); backpressure may cause `STREAM_SLOW_CONSUMER`. Follow EOF is a disconnect even with a zero Android exit code. Feed cannot wake a stopped Agent.

No history count/time cap. A one-time SQLite import retains available legacy actions as `legacy`, with `phase:"legacy-recorded"`; `ready.legacyImportedCount/legacyHistoryMayBeIncomplete` describe the import. Legacy events may exceed new limits. Clearing app data invalidates cursors. Storage errors do not fabricate an empty Feed.

Cards use `taku.feed-card/1`; repository schema and parameters are in `system/agent-app/modules/taku-home/README.md` and `CONTRACT.md`. Image bodies allow HTTPS or known `asset:feed/<path>`. Remote SVG needs a `.svg` path, HTTPS final URL and `image/svg+xml`; static drawing only (no CSS/entities/scripts/external references/embedded images), max 256 Ki characters, 2,048 tags and 10 s. Invalid generated references return `INVALID_SPEC`; raw publication has render-time validation. Icon and open-url rules are separate.

Feed errors: `NOT_FOUND`, `INVALID_ARGUMENT`, `INVALID_CARD`, `CARD_TOO_LARGE`, `CARD_REMOVED`, `INVALID_ORDER`, `ID_CONFLICT`, `NOT_AGENT_CARD`, `UNKNOWN_ACTION`, `APP_NOT_INSTALLED`, `NO_ACTIVITY`, `STATE_READ_FAILED`, `STATE_WRITE_FAILED`, `EVENT_TOO_LARGE`, `TIMEOUT`, `UNKNOWN_METHOD`. Streams also return `INVALID_CURSOR`, `CURSOR_EPOCH_MISMATCH`, `CURSOR_AHEAD`, `STREAM_BUSY`.

## Home apps, layout and widgets

```sh
taku_call "$L" apps/list '{}'          # key, package, label, system, glyphIcon, iconAsset, page, index, folderId
taku_call "$L" apps/launch '{"key":"PACKAGE/ACTIVITY"}'
taku_call "$L" apps/info '{"key":"PACKAGE/ACTIVITY"}'
taku_call "$L" apps/uninstall '{"key":"PACKAGE/ACTIVITY"}'    # shows the system confirmation
taku_call "$L" apps/share '{"key":"PACKAGE/ACTIVITY"}'        # Android chooser; does not send
taku_call "$L" apps/hide '{"key":"PACKAGE/ACTIVITY"}'         # remove from Home, keep installed
taku_call "$L" apps/show '{"key":"PACKAGE/ACTIVITY"}'         # restore to the last app page
taku_call "$L" layout/editing/get '{}'
taku_call "$L" layout/editing/set '{"editing":true}'          # same mode as Edit Home Screen
taku_call "$L" layout/editing/set '{"editing":false}'         # Done

taku_call "$L" layout/get '{}'         # mode, capacities, pages[].items[]: app | folder, in GUI order
taku_call "$L" gestures/get '{}'       # live launcher gesture policy + actual Pager state (read only)
taku_call "$L" layout/set-mode '{"mode":"scroll"}'   # pages (default) | scroll
taku_call "$L" layout/move '{"key":"PACKAGE/ACTIVITY","page":0,"index":2}'   # insert before item 2
taku_call "$L" layout/move '{"key":"PACKAGE/ACTIVITY","page":1}'             # end of page 1
taku_call "$L" layout/move '{"key":"PACKAGE/ACTIVITY","page":0,"index":4,"into":true}'  # onto item 4: folder
taku_call "$L" layout/move '{"folderId":"FOLDER_ID","page":2,"index":0}'
taku_call "$L" layout/ungroup '{"folderId":"FOLDER_ID"}'
taku_call "$L" layout/rename-folder '{"id":"FOLDER_ID","name":"Travel"}'
taku_call "$L" layout/reset '{}'       # restore all apps in default order across pages
taku_call "$L" widgets/get '{}'        # clock, date, alarm, weather, sleep, media
taku_call "$L" widgets/media '{"command":"play-pause"}'   # previous | next
taku_call "$L" widgets/alarms '{}'
```

Home uses shared page order even in `scroll` mode; `pages` is default. Editing is live process-local state and the setter requires boolean `editing`. `apps/list` and `layout/get` expose hidden apps. Hide preserves install/launch; show restores to Home; move a hidden app only after restoring it (`APP_HIDDEN`). Uninstall invokes Android confirmation. Share opens a chooser with a Play Store URL for Play installs or a package ID otherwise. Reset restores hidden apps and default ordering.

`layout/move` requires a destination page and exactly one of app `key` or `folderId`. Page equal to count creates a page. `index` is pre-move order, 0 through item count (omitted appends). With `into:true`, drop an app onto another app to create a folder or onto a folder to join it. Moving a member out takes its key and `into:false` (default). Results report `effect` (moved/page-created/folder-created/added-to-folder/unchanged), page/index, folder ID if applicable and page count. Full pages cascade; empty later pages vanish; a one-app folder dissolves. First and later page capacities are 28 and 24 cells. Apps/folders take one cell; widgets use spans. Use returned `cellX,cellY` for actual packing.

`gestures/get` provides live window gesture inset geometry, pointer sequence (`sequence,active,terminal,startX,startY,dx,dy`), direction owner (pending/system/horizontal/vertical), current/settled page, offset, scrolling and drag state. `available:false` means Home is not composed; `TIMEOUT` means the UI thread is busy. Ownership is policy, not confirmation of Android navigation. The diagnostic changes nothing. It also reports `systemStart` and `sideBackExcluded`. During ordinary desktop browsing, side-edge Back is excluded over the full height and those swipes switch pages (`sideBackExcluded:true`, `systemStart:false`); opening a menu, folder, edit mode, dialog, AI input or onboarding restores Back. Top/bottom edges remain with Android. Icon edge paging is a separate long-press drag operation.

Errors: `APP_NOT_FOUND`, `NOT_ON_HOME`, `NOT_FOUND`, `EMPTY_CELL`, `FOLDER_FULL` (12 apps), `UNAVAILABLE`, `SYSTEM_APP`, `LAUNCH_FAILED`, `NO_MEDIA_SESSION`, `NO_ACTIVITY`, `INVALID_ARGUMENT` (including invalid page/index, or folder name outside 1–24 characters), `STATE_WRITE_FAILED`, `TIMEOUT`, `UNKNOWN_METHOD`. `widgets/get` aliases `widgets/list`. The six default widgets seed once; deletion persists.

### Standard Android App Widgets

```sh
taku_call "$L" widgets/providers
taku_call "$L" widgets/list
taku_call "$L" widgets/add '{"provider":"org.taku.widgets/.DayWidget","page":0,"index":0,"spanX":2,"spanY":2}'
# Replace 123 with the appWidgetId returned by add/list on this device.
taku_call "$L" widgets/move '{"appWidgetId":123,"page":1,"index":0}'
taku_call "$L" widgets/resize '{"appWidgetId":123,"spanX":2,"spanY":1}'
taku_call "$L" widgets/content '{"appWidgetId":123}'
taku_call "$L" widgets/action '{"appWidgetId":123,"path":"0/0"}'
taku_call "$L" widgets/configure '{"appWidgetId":123}'
taku_call "$L" widgets/remove '{"appWidgetId":123}'
```

```sh
taku_call "content://org.taku.widgets.data" get
# Returns the same resolved action used by Clock's foreground Open alarms button.
taku_call "content://org.taku.widgets.data" command '{"command":"alarms"}'
# Run the returned agentCommand to open it (normally):
am start -a android.intent.action.SHOW_ALARMS
taku_call "content://org.taku.widgets.data" command '{"command":"play-pause"}'
taku_call "content://org.taku.weather.data" get
taku_call "content://org.taku.weather.data" refresh
taku_call "content://org.taku.weather.data" cities/search '{"query":"Guangzhou"}'
taku_call "content://org.taku.weather.data" location/set '{"city":"Guangzhou","latitude":23.1291,"longitude":113.2644}'
```

```sh
taku_call "content://org.taku.widgets.data" command '{"command":"calendar"}'
am start -n org.taku.widgets/.WidgetActionActivity -a org.taku.widgets.command.calendar
```

```sh
taku_call "content://org.taku.weather.data" open
# On success execute the returned agentCommand:
am start -n org.taku.weather/.MainActivity
taku_call "content://org.taku.widgets.data" command '{"command":"clock"}'
am start -n org.taku.widgets/.WidgetActionActivity -a org.taku.widgets.command.clock
```

`widgets/providers` lists component, label, spans/minimums, resize axes and configuration activity. Add may open Android binding/configuration and return `status:"pending-user-configuration"`. Read persisted `widgets/list.pending`; use `widgets/resume {appWidgetId}` or `widgets/cancel {appWidgetId}`. Cancel frees a pending ID. Resuming an already placed widget returns added; cancel returns already-added and preserves it. Reconfigure cancel preserves its existing binding. Remove releases the ID without uninstalling. Add can append a page; resize/move reflow overflow. Host spans 1–4 columns/1–6 rows and obey provider limits; invalid moves leave placement intact.

`widgets/content` reads the hosted view's text, descriptions, visibility, enabled state and click paths. Read immediately before `widgets/action`, which clicks the current path through the provider's own PendingIntent. A bitmap with no description reports `IMAGE_HAS_NO_SEMANTIC_DESCRIPTION`; collection rows exist only as instantiated and upstream private data needs its provider API. Errors: `PROVIDER_NOT_FOUND`, `NOT_FOUND`, `PROVIDER_UNAVAILABLE`, `NOT_BOUND`, `NOT_CONFIGURABLE`, `NOT_RESIZABLE`, `SIZE_TOO_SMALL`, `SIZE_TOO_LARGE`, `ACTION_UNAVAILABLE`, `INVALID_ARGUMENT`, `STATE_WRITE_FAILED`, `TIMEOUT`. Android diagnostic: `dumpsys appwidget`.

Clock `get` returns device timezone/epoch, time to next local midnight, next alarm (null if absent) and active media state. Clock commands: `clock,alarms,calendar,music,play-pause,previous,next`. If a reply has `effect:"activity-launch-required"`, execute returned `agentCommand`; this is the same foreground action as a widget tap. Previous/next without a session report `NO_MEDIA_SESSION`. Missing handlers report `NO_CLOCK_APPLICATION`, `NO_ALARM_APPLICATION`, `NO_CALENDAR_APPLICATION`, `NO_MUSIC_APPLICATION`; unknown commands report `INVALID_ARGUMENT`. Legacy `widgets/media` and `widgets/alarms` forward here.

Weather `open` resolves the Pixel Weather launcher and returns `package,component,agentCommand` or `PIXEL_WEATHER_UNAVAILABLE`. It cannot verify Google service/network readiness or read Pixel Weather's private city list, units or forecasts. The widget uses separate Open-Meteo data. Its settings activity is `am start -n org.taku.weather/.WeatherSettingsActivity`. `get` reports configured city/coordinates, availability, fetching, timestamps, stale/error, source and current weather/timezone. Poll after refresh; network failures retain last measurement with age. City setting saves immediately; refresh requests coalesce and start at least 30 s apart, with `refreshScheduled,refreshRetryAfterMs,searchRetryAfterMs` returned. Search tries at most once per 2 s (`RATE_LIMITED`); invalid queries consume no slot. Taku widget providers publish no launcher entries; uninstalling them removes their widgets.

### Built-in icons and the Taku app folder

`apps/list` exposes `glyphIcon,iconAsset` (null when Android provides it). Core roles and unbranded AOSP utilities use glyphs; other apps keep their icons. First launch seeds `builtin-taku` when at least two Taku apps are installed. It then behaves as an ordinary folder; inspect `apps` and launch by member key.

## Assistant gestures

```sh
settings get secure navigation_mode          # 2 = gesture (default), 0 = three-button
cmd overlay list | grep navbar               # [x] marks the enabled mode
cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural
cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton
```

```sh
am start -a android.intent.action.MAIN -n org.agentos.agent/.MainActivity
am start -a android.intent.action.MAIN -n org.agentos.agent/.MainActivity --es threadId THREAD_UUID
am start -a android.intent.action.ASSIST
taku_call "$A" system/open
am start -a org.agentos.action.AI_SETTINGS -p org.agentos.agent
```

```sh
settings get secure assist_touch_gesture_enabled     # unset/0 = off (TakuOS default), 1 = on
settings put secure assist_touch_gesture_enabled 1
```

Taku defaults to gesture mode, using the standard navigation-bar overlay. Quickstep's double tap is built into the ROM. Read `assistant/get` after system assist; `cmd voiceinteraction show` can time out despite opening. Older keyboard links targeting `org.agentos.assistant` redirect to the same Taku AI settings. Legacy `PanelActivity` notifications keep `task_id`; expired tasks open unavailable details. The older bottom-corner swipe is off by default. The platform patch provides hardware voice-key routing.

## Panels

```sh
taku_call "$D" panels/describe '{}'
taku_call "$D" panels/list '{"limit":50}'
taku_call "$D" panels/list '{"limit":50,"cursor":"CURSOR_FROM_PREVIOUS_LIST"}'
taku_call "$D" panels/get '{"panelId":"PANEL_UUID_FROM_PIN_OR_LIST"}'
taku_call "$D" panels/pin '{"source":{"kind":"matter","matterId":"THREAD_UUID/trip-plan"},"requestId":"PIN_REQUEST_UUID"}'
taku_call "$D" panels/pin '{"source":{"kind":"primary","threadId":"THREAD_UUID","turnId":"TURN_ID","replyId":"REPLY_ID","revision":3,"contractHash":"64_HEX"},"requestId":"PIN_REQUEST_UUID"}'
taku_call "$D" panels/rename '{"panelId":"PANEL_UUID","title":"Trip plan","expectedMetadataRevision":1,"requestId":"RENAME_REQUEST_UUID"}'
taku_call "$D" panels/move '{"panelId":"PANEL_UUID","beforePanelId":null,"expectedListRevision":4,"requestId":"MOVE_REQUEST_UUID"}'
taku_call "$D" panels/open '{"panelId":"PANEL_UUID"}'
taku_call "$D" panels/navigation '{}'
taku_call "$D" panels/unpin '{"panelId":"PANEL_UUID","expectedListRevision":5,"requestId":"UNPIN_REQUEST_UUID"}'
taku_call "$D" panels/continue '{"panelId":"PANEL_UUID","text":"Add the return flight","expectedSourceVersion":2,"requestId":"CONTINUE_REQUEST_UUID"}'
taku_call "$D" matter/read '{"matterId":"THREAD_UUID/trip-plan"}'
taku_call "$D" matter/dispatch '{"matterId":"THREAD_UUID/trip-plan","requestId":"ACTION_REQUEST_UUID","actionRef":"ACTION_REF","expectedRevision":7,"values":{}}'
taku_call "$D" card-view/read '{"matterId":"THREAD_UUID/trip-plan"}'
taku_call "$D" card-view/apply '{"matterId":"THREAD_UUID/trip-plan","expectedUiRevision":2,"requestId":"EDIT_REQUEST_UUID","changes":[{"kind":"field","id":"note","value":""},{"kind":"selection","id":"filters","itemIds":[]}]}'
```

Use `panels/describe` for the installed `taku.panels/1` fields, limits and error codes. Chat, Panels directory and desktop share one record per `panelId`. Native `pin.canPin` governs eligibility. The reply long-press menu offers Pin to Panels only for `mode:"panel"` replies; pinned replies offer Open panel and Unpin. Ordinary `mode:"card"` replies have no pin controls, and none appear below messages. Panel updates replace the source under the same ID. Unpin removes its directory entry and page while preserving conversation/work.

`list` returns `items,nextCursor,listRevision`; limit defaults to 50 (1–100). Cursors bind list revision and position. `get` returns `panel,sourceState,updateReceipt,capabilities,listRevision`; source state is current/offline/thread-unavailable/unavailable with optional code. Capabilities are canOpen/canContinue/canUnpin. Rename checks metadata revision; move/unpin check list revision; move appends for null `beforePanelId`, self is no-op. Successful mutations return updated revision and `replayed` where applicable. Panel/thread/request UUIDs are lowercase; versions are nonnegative integers; titles trim to 1–120 characters. Continue text is 1–16,000; other requests are bounded to 48,000 characters.

Pin source is a native `matterId` (`threadId/key`) or an exact primary reply (thread, turn, reply, revision, 64-hex contract hash). Only completed usable cards qualify (`REPLY_INCOMPLETE`); missing matter returns `MATTER_UNAVAILABLE`. Repeat pin of the same matter returns `created:false`. Reuse `requestId` for identical requests after unknown outcomes; changed input gives `IDEMPOTENCY_CONFLICT`. A replay returns the original result without a new write.

Open returns `{accepted:true,panelId}`; verify navigation readback `{available,visible,selectedPanelId}` (also `content://org.agentos.agent.panel-navigation`, method `navigation`). Equivalent launch: `am start -a org.agentos.agent.action.OPEN_PANEL --es panelId PANEL_UUID`. Unknown IDs fail `PANEL_NOT_FOUND`. Directory change notifications at `content://org.agentos.assistant.open-access/panels` carry no event data; re-read revision. Matter has its own subscription.

`panels/continue` sends a task bound to this panel and its expected source version; use the ordinary submission receipt for busy/queued/unknown outcomes. Opening the original thread through `am start -n org.agentos.agent/.MainActivity --es threadId THREAD_UUID` keeps subsequent messages ordinary. For external Continue navigation use a fresh UUID `panelLaunchId` per intentional navigation; app-owned launches are consumed on the first accepted send and retained on rejected/unknown delivery. A deleted origin leaves the last snapshot viewable, but returns `SOURCE_THREAD_NOT_FOUND` on continue.

`matter/read` returns `{snapshot,cursor}` with native action capabilities, derived `pin,cardView,presentation`; presentation is trusted primary reply or a matter request/source. `matter/dispatch` uses declared `actionRef`, matter revision and `values`, and returns the GUI execution receipt. `card-view/read` returns `matterId,uiRevision,ui,review?` (draft/source/review flags and selected IDs). Apply names declared fields or selections and rejects unknown IDs/types; false, zero and empties are values. UI changes notify Matter even without a business revision change. Replacement makes old-source writes `STALE_SOURCE`; re-read and review the new schema. An already committed old-source edit can still replay its receipt. Storage exhaustion returns an error and retains pinned entries.

Results over 256 KiB can return `{ok:true,resultUri}`. Read once, as the same UID, within 30 seconds (e.g. `content read --uri "$RESULT_URI"` on shell or `ContentResolver.openFileDescriptor` from an app). The complete envelope is returned. Buffer: eight results, 16 MiB total, 8 MiB each. Failures include `RESULT_TOO_LARGE` and `RESULT_EXPIRED`.

Common codes: `INVALID_ARGUMENT`, `UNKNOWN_METHOD`, `PANEL_NOT_FOUND`, `MATTER_UNAVAILABLE`, `REPLY_INCOMPLETE`, `SOURCE_CHANGED`, `SOURCE_UNAVAILABLE`, `SOURCE_THREAD_NOT_FOUND`, `SOURCE_ALREADY_PINNED`, `PANEL_UPDATE_AMBIGUOUS`, `PANEL_UPDATE_PENDING`, `STALE_SOURCE`, `VERSION_CONFLICT`, `LIST_CHANGED`, `IDEMPOTENCY_CONFLICT`, `DRAFT_REVIEW_REQUIRED`, `THREAD_BUSY`, `THREAD_QUEUED`, `CARD_STATE_MIGRATION_PENDING`, `MATTER_STORAGE_FULL`, `STORAGE_ERROR`. Taku Home's Chats tab reads the same conversation directory as `listThreads` and opens the selected thread.

## Home AI entry and Dynamic Island

```sh
taku_call "$A" assistant/get                         # state (below)
taku_call "$A" assistant/hint '{"expanded":true}' # bottom hint; auto-collapses after 4 s; false closes it
taku_call "$A" assistant/open '{"mode":"text"}'  # or "voice"; shows the island
taku_call "$A" assistant/draft '{"text":"Can you check"}'
taku_call "$A" assistant/ask '{"text":"Find a flight to Kyoto"}'  # steers an active turn
taku_call "$A" assistant/reply '{"suggestionId":"ID"}'  # a reply chip the agent offered
taku_call "$A" assistant/retry                       # checks a pending ID, or retries after confirmed failure
taku_call "$A" assistant/release                     # keep original content and release an unresolved submission
taku_call "$A" assistant/stop                        # interrupt the turn / cancel the queued task
taku_call "$A" assistant/expand '{"full":true}' # read the whole reply; false = ⅓ screen
taku_call "$A" assistant/collapse                    # camera pill while working
taku_call "$A" assistant/dismiss                     # pill if working, otherwise ends the session
taku_call "$A" assistant/open-conversation           # continue in the full chat
taku_call "$A" assistant/resume '{"threadId":"ID"}'  # full chat's collapse: island shows ID, Home comes forward
taku_call "$A" assistant/close-input
taku_call "$A" assistant/reset
# Voice (hold to talk, or dictation when the text field is open):
taku_call "$A" assistant/voice-start
taku_call "$A" assistant/voice-finish                # hold: release sends after successful finalization; dictation: appends once
taku_call "$A" assistant/voice-cancel                # revoke this operation; retain the existing draft
taku_call "$A" assistant/voice-to-text               # finish the same capture and append its complete result to Text
```

Home, system gesture and full chat share the native conversation. `assistant/get` projects `entry,island,threadId,working,needsUser,pending,task,error,draft,failedText,online,voice,turn`; `turn` has active/failed state, user text, reply, steps and offered suggestions. Draft attachments appear in `draftAttachmentNames`; `ask` preserves them when sending matching text (empty text allowed with attachments). Different text uses handoff to retain displaced content. `resume` expands the current thread (empty/omitted starts fresh) and brings Home forward unless `home:false`. `open` accepts text or voice. Text open leaves active capture/finalization unchanged; use voice-to-text or cancel first.

On failed send or turn (`THREAD_BUSY`, `THREAD_QUEUED`, `GATEWAY_UNAVAILABLE`, `TASK_FAILED`, `TURN_FAILED`), text is retained/restored without overwriting a newer draft. After 30 s offline the UI reports `NETWORK_UNAVAILABLE` and keeps the request even if the broker still works. Retry checks pending ID before resending; edit with open/draft. `failedText` clears on next send/reset. Other errors: `INVALID_TEXT` (max 16,000 characters), `BUSY`, `NOT_WORKING`, `NOTHING_TO_RETRY`, `SUGGESTION_NOT_FOUND`, `INVALID_ARGUMENT`, `TIMEOUT`, `UNKNOWN_METHOD`. There is no external transcript injection.

### System gesture handle and temporary voice

```sh
taku_call "$A" assistant/app-build '{"threadId":"CURRENT_THREAD","turnId":"CURRENT_ACTIVE_TURN","active":true}'
```

```sh
taku_call "$A" assistant/voice-continue   # Keep talking → existing chat
taku_call "$A" assistant/live-start '{"threadId":"ID"}'
taku_call "$A" assistant/live-toggle-mic
taku_call "$A" assistant/live-set-mic '{"sessionId":"SESSION_ID","muted":true}'
taku_call "$A" assistant/live-stop
taku_call "$A" assistant/voice-finish     # release: finalize and submit once
# Legacy operation without a button on 1-1:
taku_call "$A" assistant/voice-to-text    # finalize into the editable draft
```

Patched Quickstep double-tap opens focused text over the current app; hold starts the selected Taku voice service. Recognition runs only while held; pauses never send. The microphone starts before the speech model or fal key is ready, retaining early audio. During `voice.phase:"Preparing"`, the island shows no listening text or animation; `Capturing` shows “Taku is listening...” and triggers the hold's only vibration, indicating it is time to speak. Releasing stops and finalizes capture, sending a successful nonempty transcript once and leaving the card open for progress and the reply. Releasing before capture starts or with an empty result sends nothing and closes the voice page; errors remain visible. Tapping the expanded island card outside suggestion chips runs its primary action (open conversation or missing-fal-key setup); swiping up still collapses it. Cancel revokes undelivered capture. Keep talking enters the same conversation in Live Talk without dropping the active recording. Live utterances resume after the reply; microphone toggles pause/resume and close returns to chat. Audio stops when leaving foreground or turning off the screen; rotation and Keep talking handoff retain capture. The video control is disabled because upstream is unavailable. Outside text, Agent voice-start/open(voice) act as the press: recording continues through pauses until voice-finish releases and sends, or voice-cancel cancels. An unfinished hold is cancelled without sending at 120 s. voice-to-text appends into the draft. Failed/partial/cancelled speech never delivers. Overflow retains the full result for recovery.

`assistant/live-start` takes `threadId` (empty begins a new conversation on send); stop/toggle take optional threadId and leave other chats untouched. `voice-continue` requires active (held) recording; `THREAD_UNAVAILABLE` leaves listening open. `assistant/choose-prompt {"text":"PROMPT"}` cancels undelivered capture and opens an editable draft (1–16,000 characters). It does not send.

Recording uses Taku Audio's `assistant-entry` settings. Local Zipformer stays on device; Whisper cloud or an explicitly configured fallback uploads audio to fal for transcription. Fallback applies before capture, not as runtime replay. Cloud bounds: 60 s capture and 100 s finalization. Local drain: 5 s. Transport grace: 3 s beyond the engine deadline. `voice.phase` exposes Preparing/Capturing/Finalizing/Closed and `outcome` exposes success/failure/cancel. `voice.ready` means actual capture and `voice.levels` has up to 48 measured PCM RMS samples. Inspect `voice.error` for engine code, e.g. `MODEL_MISSING` or `PROVIDER_NOT_CONFIGURED`. `VOICE_DISABLED` means policy/consumer denial; `NETWORK_FAILED` maps to `VOICE_NETWORK_FAILED`, provider/processing timeouts to `VOICE_TIMEOUT`, invalid credentials to `VOICE_AUTH_FAILED`, and provider/rate failures to `VOICE_PROVIDER_UNAVAILABLE`. Voice commands return `DEVICE_LOCKED` until awake/unlocked.

Check recording permission with `cmd appops get org.agentos.audio RECORD_AUDIO` and `dumpsys package org.agentos.audio`. Use Taku Audio's Allow microphone access or `pm grant org.agentos.audio android.permission.RECORD_AUDIO` as shell; `pm revoke` revokes. Missing permission yields `PERMISSION_DENIED` / `VOICE_PERMISSION_REQUIRED`. On Mac, `adb -s SERIAL emu avd hostmicon` enables host mic forwarding and `hostmicoff` disables it; Android cannot inspect or change macOS consent. Nonzero PCM levels verify input reception.

`assistant/app-build` takes the current `threadId,turnId` and boolean `active`. Set false on completion. It toggles presentation only; only actual app builds may show a compact pill. Errors: `INVALID_ARGUMENT`, `STALE_TURN`, `NOT_WORKING`. Read `buildingApp,appBuildTurnId` and `cmd statusbar island state` (`compactVisible`).

The chat waveform button starts Live Talk with one tap. The Live Talk panel
displays the provider disclosure during the session.

`live-start` accepts the visible chat's `threadId`. Pass `""` for a new chat. A repeated
start on the same active or connecting session returns its current state. To reconnect,
repeat `live-start` while the same chat is visible. Reconnect uses the current
provider selection and preserves the mute state and confirmed text context.
The connection generation advances.
A muted session keeps no provider connection open for either provider. When
Gemini Live retires its connection after its fixed lifetime, the Audio Engine
replaces it in place with the same confirmed text context: `lifecycle` stays
`active`, the generation does not change and no error is reported.

`live-set-mic` requires a nonempty `sessionId` and a JSON boolean `muted`. A repeated
setter with the same target state makes no change. `live-stop` accepts an optional
`sessionId` and `threadId`. `live-toggle-mic` accepts an optional `threadId`.
Wrong IDs return `SESSION_CHANGED`. Missing or mistyped required fields return
`INVALID_ARGUMENT`. Starting or resuming capture requires an unlocked device and the
visible chat. The errors are `DEVICE_LOCKED`, `HOST_REQUIRED`, and `VOICE_UNAVAILABLE`
when Taku Audio is absent. Audio Engine errors appear in `result.liveTalk.error`.
`PROVIDER_NOT_CONFIGURED` means the selected provider's key is missing or
unreadable. `PROVIDER_AUTH_FAILED` means the provider rejected the key. For
either error, update that provider's key in Taku Audio before reconnecting.
`PROVIDER_REMOVED` means the session's key was removed.
Other errors include `NETWORK_FAILED`, `RESOURCE_BUSY`, and `AEC_UNAVAILABLE`.

`assistant/get` and Live Talk actions return the same `result.liveTalk` shown by the GUI.
It is null before the first session. Read `sessionId`, `threadId`, `sequence`, `generation`,
`lifecycle`, `provider`, `muted`, `capturing`, `speech`, `playback`, `input`, `output`,
`levels`, `task`, and `error`. `provider` is the effective `fal-grok` or
`gemini-live` returned by the Audio Engine. It is null until the engine
accepts the open request for that connection. `liveTalkDisclosure` names the
provider while that connection is in use and explains the selection otherwise.
Lifecycle values are `connecting`, `active`, `disconnected`, `closed`, and
`failed`. `playback` exposes a local response ID and state. `input` has
`itemId`, `text`, and `final`. `output` has `responseId`, `text`, `final`, and
`source:"generated"`. Generated subtitles have no sample-aligned timestamps.
`task` carries `requestId`, `taskId`, `turnId`, `state`, `version` and `error`. Read the
original chat for full task replies, references, approval and user-input requests. An
uncertain receipt is reconciled with its original ID. Only a verified `readOnly` task
can be automatically replaced. If replacement is blocked, use the original chat.
`voice-continue` requires an active hold. A handoff that cannot open the
thread reports `THREAD_UNAVAILABLE`.

Grok Voice uses the shared fal key. Gemini Live uses the separate key managed by `saveLiveKey` and `removeLiveKey`. Removing a key ends sessions that depend on it. Provider selection takes effect on the next open, including reconnect, with no automatic fallback. The default is `fal-grok`. Settings `live.profileId` reports the selection and `live.configured` reports whether its key is readable. `live.gemini` exposes `configured`, `masked`, and `error` even when Grok is selected. Its error is null or `KEY_UNREADABLE`. Either provider can be selected before saving its key. Translation model selection is independent.

### Full chat dictation and reply playback

```sh
taku_call "$A" assistant/chat-voice-get
taku_call "$A" assistant/chat-voice-start '{"threadId":""}'
# Use the integer operationId returned by start or get. The value 1 is an example.
taku_call "$A" assistant/chat-voice-finish '{"operationId":1}'
taku_call "$A" assistant/chat-voice-cancel '{"operationId":1}'
taku_call "$A" assistant/reply-speak '{"threadId":"THREAD_UUID","replyId":"REPLY_ID","text":"Reply text to read aloud."}'
taku_call "$A" assistant/reply-stop '{"threadId":"THREAD_UUID","replyId":"REPLY_ID"}'
```

Open the target chat first; use `threadId:""` for the new-chat draft. Responses are `{dictation,speech}`. Dictation records locally and appends only a successful result to the original selection if draft revision still matches. Finish/cancel require the current integer `operationId`; stale IDs leave current capture alone. `speech` is null or contains thread ID, reply ID, phase (preparing/speaking/failed) and error. Speak at most 12,000 readable characters. Stop is scoped to the exact reply/thread. Starting capture stops playback; leaving chat releases both. Errors: `CHAT_NOT_VISIBLE`, `DEVICE_LOCKED`, `VOICE_BUSY`, `VOICE_UNAVAILABLE`.

Gesture producers use `gesture/event` with `action:"prepare"`, `id` (8–128 ASCII letters/digits/underscore/hyphen), optional `source` (system default, desktop, agent), and device uptime `time` (use `SystemClock.uptimeMillis()`). Then `gesture/activate {"id":"ID"}` acknowledges UI readiness. `move,heartbeat,end,cancel` take matching id, strictly increasing integer `sequence` starting at 1, and uptime time. Move adds finite `x,y` and positive `width,height` in full-window pixels. System heartbeat is 500 ms; 3 s lease and 120 s cap apply only while held. End chooses Cancel/Keep talking by release zone, otherwise finalizes and sends. End/cancel before readiness prevents late mic opening. Wrong/old/repeated events have no effect. Competing gestures return `VOICE_BUSY`; future or >5 s old timestamps return `STALE_GESTURE`. Invalid values return `INVALID_ARGUMENT`; stop retrying that gesture after `TIMEOUT`. Read `assistant/get.gesture` (id, source, phase, sequence and times), `holdZone,voiceContinuous,voicePaused`.

Replace the example time with current device uptime in milliseconds.

```sh
taku_call "$A" gesture/event '{"action":"prepare","id":"my-gesture-001","source":"agent","time":1234567}'
taku_call "$A" gesture/activate '{"id":"my-gesture-001"}'
```

### Open-access boundary and request behavior

Audio, Feed events, widget actions, alarms/media and voice can expose personal data or act on the device. Check the per-provider permission policy and Android runtime restrictions. Gesture IDs only sequence operations. SystemUI supplies the native island; `listeningHeaderHost:"unavailable"` means its ROM provider is absent. Use `cmd statusbar island state` for renderer/native readiness, visible text and suggestion count, and `cmd statusbar island action 'KEY' '{"id":"ID"}'` for the same action as a card tap.

## Location arrival events

The Agent shell can register a time-limited arrival watch. Choose the destination with
the user. Each watch emits one event after Android GPS confirms an outside-to-inside
transition. The Agent can use the place ID to look up the user's relevant content.

```sh
R=content://org.agentos.environment.arrival
expires=$((($(date +%s) + 5400) * 1000))
content call --uri "$R" --method register --arg "{\"id\":\"visit-1\",\"placeId\":\"saved-place\",\"latitude\":37.0,\"longitude\":-122.0,\"radiusMeters\":200,\"expiresAtMs\":$expires}"
content call --uri "$R" --method list
content call --uri "$R" --method pending
content call --uri "$R" --method ack --arg '{"eventId":"EVENT_ID_FROM_PENDING"}'
content call --uri "$R" --method cancel --arg '{"id":"visit-1"}'
```

Use actual destination coordinates in place of the example. Use the same shell identity
for each call. A watch persists until cancellation or expiry. Acknowledge only after handling
the event, and use its stable event ID to avoid duplicate reminders. An app can register its
own receiver to be woken for arrivals. It chooses what to show and handles notification access.

Results are JSON in the Bundle's `result` field. `PERMISSION_DENIED` means the caller
lacks access. `LOCATION_PERMISSION_REQUIRED` means Taku Location Arrival needs precise
location and Allow all the time in Android Settings. `LOCATION_UNAVAILABLE` means the
image has no GPS provider. `INVALID_ARGUMENT` covers malformed input, a radius outside
100–5000 m, a past expiry or one more than 24 hours away. Monitoring resumes after the
first unlock following reboot.

### Native assistant card dismissal

Use `cmd statusbar island collapse` for the same presentation dismissal as the accessible Dismiss action or upward swipe. Read back `presentation.card` (null) and `presentation.reason`. Collapse leaves tasks and voice running; use `assistant/voice-cancel` to stop input separately. New explicit interaction increments `assistant/get.expansionRequest`; passive updates honor dismissal. See the installed Taku Island skill for its provider and Quickstep recents.

## Onboarding accounts and Jev

All `jev/*` and `account/login/*` operations require `taku.root` for ordinary
applications, including status and future methods; `taku.control` alone returns
`PERMISSION_DENIED` before parsing. Local agent/root/system/shell callers retain direct
access and Home uses the signature-protected gateway. `account/login/start` and
`jev/connect` additionally remain trusted-only even with an ordinary app's root grant.
`account/status` is a redacted control read.

```sh
# ChatGPT sign-in and AI availability (the same broker account view as the account page and Home
# onboarding; no sign-in URL or one-time code). chatgptConnected:true only after the broker's
# account/read returned a ChatGPT account; aiAvailable:true for that or for Magpie mode
# (kind:"magpie") running with at least one model. phase:"loading" or "verifying" means not yet
# settled. A cold broker's loading snapshot starts a background account/read (one at a time,
# with retry backoff); it never starts sign-in or changes the model connection.
# ACCOUNT_UNAVAILABLE when the broker cannot be reached.
taku_call "$D" account/status '{}'
# Start ChatGPT sign-in (the account page's broker control). type: "chatgpt" (browser, default) or
# "chatgptDeviceCode". Returns the account/status fields plus url and code (what the account page
# shows) and opened. open:true also opens the official page in the browser for "chatgpt", or the
# account page with the one-time code for "chatgptDeviceCode". Home's ChatGPT page uses
# "chatgptDeviceCode" without opening anything: its card shows the url and code itself.
# Trusted callers only (root, system, shell, platform-signed): it hands out the device code, so a
# third-party app gets PERMISSION_DENIED even with a Permission Center grant. jev/connect, which
# replaces the Jev key, is trusted-only for the same reason.
# Switches the assistant from Magpie to ChatGPT mode first; if that sign-in then does not land
# (account/login/cancel, failure, a refused start, or 15 min without finishing), Magpie and its
# selected model are switched back. Agent sign-ins never bring Home forward; only Home's own page
# does. Codes: INVALID_LOGIN_TYPE, TASK_RUNNING (tasks still running), LOGIN_UNAVAILABLE,
# ACCOUNT_UNAVAILABLE (account view unreachable or invalid).
taku_call "$D" account/login/start '{"type":"chatgpt","open":false}'
taku_call "$D" account/login/cancel '{}'
# Jev decision model: {enabled, model, ready}. Jev always runs through the phone's Magpie.
# jev/enable switches Jev on with a Jev model Magpie already serves (NO_DECISION_MODEL: no Jev model is listed).
# TypeSafe providers include decide=https://api.typesafe.ai/v1. Broker startup/config sync repairs
# older preset-only entries in the shared Settings store without replacing keys or explicit endpoints.
# After updating the Broker, retry jev/enable; an already saved key does not need to be entered again.
# jev/connect takes a TypeSafe Jev key (apikey_..., Magpie preset "typesafe") or an OpenRouter key
# (sk-or-v1-...), adds or reuses the Magpie provider in the background (no Magpie key or admin page),
# waits for its Jev model (preferring jev-latest; the whole call is bounded to about 30 s), binds it
# and switches Jev on. Codes:
# INVALID_KEY, KEY_NOT_SAVED, NO_DECISION_MODEL (key kept; Magpie lists no Jev model for it yet;
# retry jev/enable), MAGPIE_UNAVAILABLE (key kept; Magpie's catalog never answered; retry
# jev/enable once Magpie runs), SETTINGS_WRITE_FAILED.
taku_call "$D" jev/status '{}'
taku_call "$D" jev/enable '{}'
taku_call "$D" jev/connect '{"key":"apikey_..."}'
```
