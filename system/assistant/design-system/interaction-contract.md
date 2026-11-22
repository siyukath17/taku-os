# Chat interaction contract

This contract preserves the product direction behind the 2026-10-27 Chat update. It describes the intended interaction and the implementation boundary; it is not a declaration that every PRD surface is complete.

## Publication scope — 2026-10-28

The user explicitly requested publication to `main` after removing the floating
window's lower-left character. This includes the Chat design-system integration,
same-session previews, takeover/continuation and requirement UI described here.
The final floating view has one header row, a small white Expand action, a quiet
Close control, no pencil shortcut and no decorative character.

Publication does not promote earlier emulator evidence into Pixel or full-ROM
acceptance. The last character-removal build was installed on the emulator, but
its native floating screenshot could not be recaptured after a cold boot lost
the temporary runtime mounts. The earlier screenshots remain historical.

Before publication, a shared atomic receipt claim was added to prevent two
surfaces from dispatching duplicate requirement/continuation requests. Late
catalog callbacks retain the winning request, including after acknowledgement;
changed or cleared drafts cannot be sent from an older view. The dedicated
Android fixture passes 80 assertions with fake transport and private test data.
This is UI-state regression coverage, not new native model execution evidence.
Fresh Android rendering fixtures also pass 58 conversation assertions across
nine streaming frames and 86 Markdown assertions. The rebuilt APK was installed
on `emulator-5590` and its hash was verified:
`471fe1c995eded8c6babf35bfbcbb78ab1ae3b43ca02e2894f438e00172a45ae`.

Reproducible host checks: `test-chat-projection-host.sh` passes 32 preview
identity checks and 46 conversation-projection assertions;
`test-workspace-execution-host.sh` passes 64 receipt/execution checks;
`test-execution-screens-host.sh` passes 148 admission checks; and
`test-workspace-processes-host.sh` passes 11 process-tracking checks without
sending signals. All script paths are relative to `system/assistant/`.

Known recovery boundary: a pending execution restored after a Broker restart
becomes `unknown` and keeps its Session reserved. Status polling alone cannot
resolve it after restart; there is no automatic native-history reconciliation
exit yet. A locally persisted request that never reached the Broker can likewise
remain unresolved when status returns not-found. Neither case permits clearing
the record or replaying the action with a new ID. This preserves duplicate-action
safety but can prevent further execution in that Session.

## Sources and precedence

- Internal design review, unavailable in this source distribution: Chat, screen controls and confirmations, lightweight input, and surface placement.
- Internal design review, unavailable in this source distribution: generation boundaries, iterative design, task-sized GenUI, and reusable framework behavior.
- Internal design review, unavailable in this source distribution: PRD §4 defines Agent interaction and §5 defines confirmation gates.
- [Mobile DS v0.8 Skill](https://github.com/Taku-OS/taku-os-mobile-design-system/blob/design/mobile-ds-v0.8/skill/taku-design-system/SKILL.md) and the [local import record](README.md) define visual tokens and return-context rules. The imported package is pinned to `355e8c2d4edc436b6664e30c3edb396f732ad76c` and supplies a light theme only.

The meetings define behavior, not new color, radius or spacing values. Use the DS for those values. Where spoken behavior and the PRD conflict, preserve the existing runtime contract until product and runtime owners agree on the change.

## Default UI and generated content

The second meeting distinguishes a usable, designed system shell from generated task content. Keep default Chat/navigation available without generation. Generated Widgets fill framework-managed slots; Feed ordering still follows size and priority. Do not interpret a red rectangle in a mockup as permission to regenerate the whole layout.

Prefer small, useful forms and actions for the current task step. Reveal content progressively, avoid redundant descriptions, and use clearly unfinished placeholders while loading. Do not wait to generate an entire application before showing the first useful step. Bespoke built-in components are an exception for important cases blocked by generation, not the default implementation strategy.

Keep case support general and extensible: a case the shell cannot support should lead to a framework improvement. An internal design review, unavailable in this source distribution, reaffirmed this and the plugin boundaries. Arbitrary user-generated interfaces remain exploratory rather than a default quality guarantee.

The current DS is a development baseline, not final design sign-off: an internal design review noted missing updates and planned further polish. Continue building working modules against it and integrate design iteratively. This research-preview scope does not weaken approval or acceptance requirements.

## One Session, one conversation

The PRD defines a Session as its conversation, without a separate task interface. A Session contains the user’s request, Taku’s replies, collapsible **Activity**, live workspace previews and results. Changing the presentation must preserve the same thread, task and screen lease; it must not create a second task store.

Activity explains how work is progressing. Keep detailed tool output inside it, then leave the conclusion, supporting evidence and useful next actions visible when work finishes. Do not present a tool receipt as proof of business success. Generated cards extend the conversation; they do not replace its history.

In chat, place each reply's text above its own Activity disclosure, including while text is streaming. Expanding or collapsing Activity must not move it above the text or reassign tools to another reply. Before any reply text arrives, Activity can still show progress. Keep expansion state scoped to the session and reply.

Chat opens in a resizable bottom sheet. Returning from settings or a workspace preserves the draft, cursor and reading position. Stream updates follow the bottom only when appropriate; they must not pull a reader away from earlier content. Closing the sheet hides the UI without stopping its task.

## One screen, three presentations

The target is **inline → floating → fullscreen**, all showing the same workspace. Inline identifies the App and current step. The floating form is movable, can close or expand, and should not cover input or an approval. Fullscreen adds the step history and separate Take over / Stop controls; minimizing returns to the same context.

| Action | Contract |
|---|---|
| View / expand | Read-only. Displaying a screen never grants input control. |
| Take over | Stop Agent execution and confirm quiescence before granting user input. |
| Return control | Give control back, observe the current screen again, then explicitly continue the same task. Returning ownership alone is not continuation. |
| Stop | Stop current execution, return to Chat and retain history/results. The final resumable state needs the clarification below. |
| Close / minimize | Change presentation, without implicitly approving, stopping or replaying work. |

**Stop and Cancel are not interchangeable.** The internal design review describes stopping the Session; the PRD also describes a paused state with Resume. For a rejected confirmation, the meeting permits continued reasoning while the PRD says to remain waiting. UI code must not guess the resolution. Keep the existing distinctions between native turn interrupt, legacy task cancellation, workspace pause and rejection of one pending request. Never delete the Session or replay a rejected action to reconcile the wording.

## Lightweight input and blocking confirmation

A short correction should be possible without opening full Chat, with an explicit route back to the same Session for longer text or attachments. This is separate from an approval gate. Approval must identify the pending operation and return a response to that exact request. Dismissal, silence, expiry and Cancel are not approval; countdown defaults for optional choices must not be extended to sensitive actions.

## Surface slots

The intended Agent interface offers constrained slots for bottom sheets, floating content, annotations and the island. A user can change where persistent content appears. Keep one active bottom sheet, restore the prior context when appropriate, avoid duplicate questions across surfaces and never let annotations impersonate system controls. This is a product/API direction, not a claim that a general GenUI placement API already exists.

## Scope of this Chat update

“Changed” below means implemented in this change; acceptance evidence belongs in the next section.

| Area | Changed or reused now | Still to connect or validate |
|---|---|---|
| Chat / Activity / results | Native conversation rendering, DS typography/icons, compact composer, collapsible Activity and return-context handling updated; real history/output projections reused. | General generated result-card schemas and all PRD result interactions. |
| Workspace preview | Real frames from the current thread's existing lease; inline, movable floating and fullscreen presentations share that lease. Fullscreen has separate Take control, Return & continue and Stop actions. | Task-level semantic step descriptions; prolonged disconnect/resource and multi-screen interaction coverage. Viewing three existing hosts is not proof of three Apps executing concurrently. |
| Takeover / continuation | Existing broker quiescence and input gates reused. New `returnAndResume` observes the original screen image and explicitly starts a turn in the same native thread. Plain `returnControl` still only changes ownership and reports resume required. | Broader interrupted-tool and uncertain external-action recovery; Pixel and ROM acceptance. |
| Lightweight requirements | Floating and fullscreen views reuse one requirement component and draft. `addRequirement` steers the exact active turn or starts a turn when idle; paused/user-owned states reject without discarding the draft. | Live active-turn steering coverage; generalized generated forms and attachment workflows. |
| Questions / placement | Existing request-specific approvals and Surfaces reused. Floating content yields to pending questions; this does not change approval authority. | General GenUI surface-slot/restore coordination and full pending-question overlap coverage. |
| Sensitive content | Exact lease/peer checks and stale-frame clearing added for previews. These are identity/freshness checks, not redaction. | Login/payment masking and sensitive-screen capture protection across the actual display path. |

## Workspace execution protocol

Operations remain in the same Session. Stop Agent safely before the user takes over. After the user selects “Hand back and continue,” the system reads the current original screen image and resumes the original thread. The user can add instructions in the floating or full-screen view, with the draft preserved across views. “Received” means only that the runtime received the request; completion depends on the actual result of that thread.

All actions use `op: "workspaces"` and the exact `threadId`, `screenKey` and
`generation`. Execution requests additionally require `controlRevision` and a
UUID `requestId`.

| Action | Input / execution | Receipt and boundary |
|---|---|---|
| `addRequirement` | Nonblank `text`, at most 4,000 characters. Active turn: `turn/steer` with its exact `expectedTurnId`; idle: `turn/start`. | No new Session or screen. User-owned, paused, waiting-for-user and conflicting runtime states fail closed. A changed active turn is not silently converted into a new start. |
| `returnAndResume` | Preflight the selected image model, stop and verify quiescence, capture the original PNG, then start a turn in the existing thread. | Capture UUID/time, host identity, exact lease, dimensions, frame sequence and SHA-256 bind the attached `localImage` to the request. No OCR or screenshot-to-text conversion. |
| `executionStatus` | Same lease and `requestId`; no revision or text required. | Read the saved receipt only. Never executes or retries a request. |
| Existing `pause` / `takeControl` | Stop execution and verify quiescence before allowing user input. | Retain the Session, history and lease. UI Stop uses the pause path; it does not delete the conversation. |
| Existing `returnControl` | Return ownership only. | Does not send a model request or claim continuation. |

The receipt moves from `pending` to `accepted`, `failed` or `unknown`.
`accepted` identifies a native `turnId`; `taskCompleted: false` deliberately
prevents an acknowledgement from being presented as task success. Follow the
canonical native turn and inspect the actual result.

The same request ID with the same content only reads its saved receipt; changing
the lease, revision, action or text rejects. A definite failure permits an
explicit user Retry with a refreshed lease/revision and new ID. Pending or
unknown delivery must be queried under the original ID. Unknown delivery keeps
the thread fenced across restarts; a live late acknowledgement can reconcile
it, but automatic reconciliation after a Broker restart is not implemented.
Stop remains distinct from approval rejection and from legacy task cancellation.
Never treat stopping, a transport timeout or receipt acceptance as permission to
repeat an uncertain external action.

The model receives the original image and task context with instructions to
reconcile the screen and existing results before acting. A static page can retain
its rendered frame sequence; a fresh export timestamp is not a claim that a new
frame was rendered. Protocol and host checks are documented in
[`tests/workspace-execution/README.md`](../tests/workspace-execution/README.md).

## Emulator acceptance record — initial Chat candidate

The initial 2026-10-27 capture used `codex/chat-ui-design-20261027`, based on
`1d8a1bd`, with uncommitted UI changes. It was not merged at the time of capture.
The initial development APK was installed on `emulator-5590`
(`Taku_Workspaces_20261026`, Android 36). That initial APK's SHA-256 is
`532cf1af04de27721cc53733753b3ef4a0c302ae8a1ebdeb70c46fa6360ae5a1`.
This hash and the layout matrix below belong to the initial candidate, not the
later interaction build.

| Check | Result |
|---|---|
| Build and imported DS resources | Pass. Official resources retain their source hashes and licenses. |
| Native conversation rendering | 58 assertions across 9 Unicode streaming frames; stable message views, selection, Activity expansion and session isolation. |
| Markdown | 86 assertions in each of light and dark. This is compatibility coverage, not design approval of dark mode. |
| Layout | English and Chinese at 390 dp; long English at 360 dp; 150% Chinese at 360 dp and English at 430 dp. No measured horizontal overflow or undersized visible icon controls. |
| Real composer and return | Complete text entered with display-0 KeyEvents, retained after opening the real Clock preview and returning to the same conversation. Test draft removed afterward. |
| IME and sheet | Real keyboard observed with composer above it. Handle drag changed sheet height without dismissing the conversation. |
| Real preview | Clock JPEG visible from the exact existing lease. All screen owners, revisions and generations unchanged by viewing. |
| Protocol boundaries | 32 host-side checks for exact-thread filtering, lease validity and host identity. These do not replace device disconnect/memory tests. |

Evidence is retained under `out/chat-ui-implementation/`, especially
`verification.json`, `matrix-summary.json`, `input-probe/result.json` and
`real-preview-report.json`. The layout screenshots use production views inside
a separate test APK with illustrative conversation records. The live Clock
preview is a real service integration check. Neither is a model-task or purchase
acceptance test. The fixture APK was removed after capture.

Fast shell `input text` did not reliably deliver the entire string in this test
environment. The final input check uses one UiAutomation connection, explicit
display 0 and current timestamps for each key event; it verifies the complete
text before and after preview navigation. The earlier injection diagnostics are
retained alongside the passing report rather than counted as a successful test.

## Emulator acceptance record — real workspace interaction

The later 2026-10-27 pass used the same `emulator-5590` and the existing Clock
thread `01a17949-2f98-7f90-aaa2-e7ee1e5a0542`, screen
`task-b3b65b8785674d85b87f`, generation
`506da35e-6ed8-4020-ad8b-da8a7412bee2`, display 10. The existing native thread
resumed successfully even though its earlier local record had no turns. This
pass created neither a replacement Session nor a new screen. Calendar and Browser
hosts were retained; their older empty conversation records were not restored or
counted as native-task acceptance.

| Check | New evidence and result |
|---|---|
| Three presentations | Production UI opened inline → floating → fullscreen → floating on the existing Clock lease. `float-flow-report.json` records the steps; screenshots include `workspace-floating-en.png` and `workspace-fullscreen-en.png`. |
| Requirement draft and gate | The same draft survived floating/fullscreen switching. Sending while control was unavailable showed a rejection and retained the text; the completed `draft-flow-5.json` records the assertions. Earlier incomplete attempts are retained for diagnosis. This is separate from the successful model request below. |
| First real model turn | `gpt-6.1-sol` used `taku_phone.observe_background` and `tap` on the existing Clock workspace, then completed with the result that no cities were listed. No alarm, timer or stopwatch was created or started. |
| Floating requirement → native completion | Production Send requirement submitted request `7b278852-4cff-4cbf-8288-bae8f7e466a1` using `addRequirement` → `turn/start`. Native turn `01a17e10-972a-7522-b99a-49ac9515e3fb` completed after inspecting the Timer tab, reporting that no timer was running. This proves the idle-start path; it does not claim live `turn/steer` acceptance. |
| Take control and actual input | Production Take control changed the Clock owner to user at revision 23. Fullscreen mirror input visibly changed Timer → Clock on that lease. The takeover and changed-screen captures are `live-fullscreen-takeover-en.png` and `live-fullscreen-user-changed-en.png`. |
| Return, original image and continuation | Production Return & continue submitted request `75094fc1-b222-479a-87df-c6c96f17ef24`, attached request-owned `observation.png` as `localImage`, and received native acceptance. The same thread's turn `01a17e11-bb66-77c3-bb3f-e816a7bfa7e7` subsequently **completed**, describing the current Clock view and the earlier Timer result. The receipt alone is not the completion evidence. |
| Stop and preserved conversation | After a production floating requirement was submitted, fullscreen Stop returned to the same Chat. `native-after-stop.json` records turn `01a17e12-7f75-79b3-be14-259a9e2a26b6` as `interrupted` and retains its real user message. `live-after-stop-catalog.json` confirms owner `paused`, revision 27 and no active turn; `live-after-stop-runtime.json` confirms completed cancellation cleanup with zero remaining tracked processes. The pre-click snapshot was still idle during request queuing and no tool worker had started, so this does not prove interruption of a long-running tool worker. |
| Host protocol checks | All Broker Java sources compiled against the Android SDK; 64 workspace execution/capture checks, 148 screen/admission checks and 11 process-stop checks passed. These cover duplicate/conflicting requests, unknown/restart fencing, late acknowledgements, exact lease/revision and original-image evidence. They are host checks, not extra device cases. |
| UI state fixtures | `workspace-execution-tests.json` reports 34 assertions with `fixtureOnly: true` and `realRuntimeInvoked: false`. These supplement the production UI/native records above; they do not establish model execution. |

New evidence is retained under `out/chat-ui-remaining/`, especially
`native-after-return.json`, `native-after-stop.json`, the two
`workspace-executions-<requestId>.json` receipts, the `live-*-steps-report.json`
reports and their `live-*.png` captures. The return receipt records a 720 × 1280
original PNG, `originalImage: true`, `ocrUsed: false`, and SHA-256
`d11a0f24dddecf9f639f90112d03887e0ce9c64775544a03c3a5bb9fffd19eb0`.

The final floating-redesign APK and its targeted layout checks are recorded
below; the older matrices above remain historical evidence. This pass proves a bounded real-model Clock
interaction on the emulator. It does not prove a concurrent cross-App business
case, a booking/payment, long-running worker cancellation, recovery of an unknown
external action, Pixel behaviour, full-ROM behaviour, or prolonged frame memory
and disconnect stability. The remaining scope-table items are still open.


## Visual correction after screenshot review — 2026-10-28

The first functional candidate was not visually aligned with PRD section 4. In
particular its floating window was too large, supplementary input was always
visible, fullscreen controls stacked, and Chat retained excess chrome. Functional
acceptance did not constitute design acceptance. The original candidate and its
screenshots remain diagnostic evidence rather than the approved presentation.

The redesigned floating view follows the reference's compact App-card hierarchy:
App icon/name and state, a broad real-frame preview, then a lightweight action
row. Ordinary width follows approximately 42% of portrait width, with a
176–184 dp range to preserve native tap targets; large text can widen the card.
The preview fills its fixed viewport using a centre-horizontal, top-aligned
crop without stretching. It is read-only; Expand opens the complete frame.

Following the user's next refinement, the compact footer contains only Expand;
both the external Add note block and the pencil shortcut are removed. The
fullscreen viewer retains its supplementary-input entry. An unresolved request
can still restore the lightweight input state, which hides the miniature
screen and preserves the same
lease and requirement draft, and returns to the prior preview position. No new
request is submitted merely by opening or closing that input. The reference's
3D mascot is not present in the imported design-system package. The floating
entry in the previous revision used `ic_tako_mark`. A subsequent product-environment check
found the repository's existing `taku_pet.png` and `taku_pet_atlas.png`, used by
DesktopPetView; these are available for reuse and must not be described as
missing project assets.

Fullscreen
shows the real frame, up to four recent native steps, and equal Take over / Stop
actions; Edit is in its header. Chat uses one header, an attachment/voice tools
menu, compact composer and the official type, spacing, icon and Sage roles.

The PRD determines these presentation relationships; the Skill determines named
visual values. The round PRD Send button therefore does not override the
Skill's r6 control mapping. The dark workspace shell is an explicit local PRD
mapping in `workspace_viewer_colors.xml`: the imported Skill supports light
appearance only. Neither this mapping nor the floating proportion is claimed
to be an exported Figma mobile component.

The actual Clock content is not replaced with the PRD's illustrative Amazon or
Feed content. Fullscreen preserves the full frame without cropping actionable
content; its aspect ratio can leave side bands. Recent steps still use real tool
action labels, not invented shopping progress. Native continuation messages and
image attachments remain in the immutable history; compact source-aware
presentation is a separate design follow-up. General generated result cards and
GenUI placement remain outside this candidate's completed scope.

At this capture, visual review and main integration were still pending. The
emulator APK did not establish Pixel or full-ROM acceptance.

The floating host shares its verified catalog with its frame reader, retaining
the timestamp from the original native list response. This removes a redundant
full catalog read. Exact lease, peer, generation and frame-dimension checks,
the five-second freshness limit, pending-user suppression and epoch fences are
unchanged. Missing or expired data shows an unavailable/loading state rather
than retaining a stale image.

### Previous floating redesign verification

The installed development APK has SHA-256
`3c87e40aaa371a100da5013f40c118a96d2959978156f0256df6c558f7ce3e97`.
The 39-step production UI check (`float-v4-review-steps-report.json`) covers
minimizing from the real viewer, decoded live frames, opening supplementary
input, preserving an unsent draft, returning to preview, dragging and opening
fullscreen. The immediate fullscreen screenshot was captured while connecting
and is not used as decoded-frame acceptance.

Two targeted floating layout checks passed: English at 360 dp / font scale 1.0
and Chinese at 360 dp / font scale 1.5. Both check a real decoded frame before
and after editing, visible controls with at least 48 dp touch area, the real
keyboard and retained draft. Both restore the original emulator settings and
leave lease/control identity unchanged. Their reports are
`float-v4-en-narrow-verified/summary.json` and
`float-v4-zh-large-verified/summary.json`. English at the default 411 dp and the
Chinese large-text captures also received direct pixel review.

The earlier Chinese geometry failure is retained: Android reported the window
bottom one pixel above its root/control bottom. The companion's actual visible
intersection was 99 px, exceeding the 96 px minimum. The helper now permits at
most two pixels of coordinate rounding while still requiring a strict 48 dp
visible touch area; it does not relax missing-frame, stale-lease or clipping
failures. These are targeted emulator checks, not a complete layout matrix or
user design approval. No requirement was submitted during this visual pass.

### Compact floating chrome — user refinement, 2026-10-28

Historical candidate; the PRD reconciliation below supersedes its visual values.

The user requested smaller actions, tighter borders and a single-line heading.
The default header now places the App icon, App name, status dot and Close on
one row. Ordinary Ready has no separate visible label; attention states retain
short text on that same line, with full status in the accessibility description.
Long App names may ellipsize. The footer combines a 14/19.88 compact Expand
label and 16 dp arrow with a separate 20 dp edit glyph. Both actions retain
independent 48 dp touch targets, with transparent surfaces and pressed feedback.
This compact action type role is a user-requested local adaptation, not a newly
exported Figma component.

The former external companion block and footer gap are removed. Card inset is
4 dp, outer radius 16 dp, and normal preview height remains 200 dp. At ordinary
text scale the overall window decreases from about 372 to 300 dp in height;
the reduction comes from chrome, not shrinking or covering the real frame.
The input view uses the short localized Add note label and retains the same
draft, native request and execution boundaries. Development screenshots now
use the actual Taku Home, selected as the emulator's default HOME; this remains
an AOSP + Taku component integration environment, not full-ROM acceptance.

Compact candidate APK SHA-256:
`f6296c20fc28df26f09140a9fa21c5a3f01d31647e271ab37c8d8084fbb213fa`.
`compact-final-steps-report.json` passes 15 production UI steps on the same
Clock lease, including entry, real frame, opening the editor, unchanged draft
and returning to preview. `compact-zh-large-verified/summary.json` passes the
360 dp Chinese / font-scale-1.5 floating geometry, frame, keyboard and draft
checks; the original emulator settings were restored and the installed APK
hash was checked. Both default English and Chinese large-text screenshots were
visually inspected. This pass does not claim all attention-state labels, all
screen sizes, Pixel behaviour, or user design approval. Main was unchanged at
that capture.

### Remove pencil shortcut — subsequent user refinement

Historical candidate; removal of the edit shortcut remains in effect, while the
PRD reconciliation below supersedes the bare-text Expand styling.

The default footer now centres a lightweight Expand action with no edit icon
or replacement shortcut. This removes only the explicit floating edit entry;
deferred unresolved-request recovery, the requirement draft and fullscreen
editing remain available. The native frame, compact header and execution
control boundaries are unchanged. New evidence is tracked separately in
`floating-no-pencil-steps-report.json` and `visual-candidate-manifest.json`.

Non-touch Chat scroll (TalkBack, keyboard or mouse wheel) is not accepted in this
pass. Static review identified an existing follow-bottom flag that is cleared by
touch movement but may survive these other scrolling routes; a later content
layout could pull that reader toward the end. This does not invalidate the
verified touch/draft return path, but remains an accessibility follow-up.

### PRD floating-card reconciliation — 2026-10-28

After the user rejected the bare-text candidate, the floating shell was compared
directly with `out/design-reference/sess-float.png`. The current default keeps
the requested single header line and no pencil, while restoring the reference's
white Expand face and quiet boxed Close. The user subsequently requested removal
of the lower-left character. This is a local mapping of the PRD, not full-screen
Figma equivalence.

- The 176–184 dp normal card retains a 200 dp real-frame viewport, an 8 dp side
  inset and 16 dp outer radius. The frame remains a read-only crop; fullscreen
  preserves the complete original frame. No product screenshot is fabricated.
- Expand uses the compact 14/19.88 role and a 32 dp visible white face inside
  its 48 dp tap area. The extra arrow and floating edit shortcut are absent.
- Close uses a 16 dp glyph in a 28 dp muted plate, retaining its 48 dp tap area.
  The 16 dp glyph is an explicit compact PRD exception to the usual 20 dp icon
  mapping. FIT_CENTER and padding applied after background assignment enforce
  the visible size independently of ImageButton defaults.
- The floating character and its 8 dp bottom inset are removed. Expand now uses
  symmetric padding so its label is centred. The shared character asset and
  Taku Home's separate character remain unchanged.
- Draft recovery, first-frame acknowledgement, native identity and execution
  control remain unchanged. Ordinary Ready hides the redundant dot.

The one-line header, omitted character and compact button intentionally follow
the user's later refinements. The reference's textured shell is not reproduced;
the existing documented solid dark workspace mapping remains. Taku Home is the
actual installed launcher, but its separate layout is not changed in this pass.
Fresh evidence and the installed APK hash are recorded in
`out/chat-ui-remaining/visual-candidate-manifest.json`; older screenshots above
are historical. The later request to publish to main is recorded above; it does
not assert exact visual equivalence or add new device acceptance.

The subsequent character-removal APK built and was installed on the owned
emulator with a matching SHA-256. Fresh floating pixels were not captured:
the emulator had stopped, and its cold boot no longer had the temporary native
runtime mounts. Earlier frame/interaction screenshots are historical evidence;
this small visual change does not claim a new native-frame acceptance pass.
