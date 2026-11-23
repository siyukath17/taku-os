# Taku OS independent background displays

Internal session-removal commands are `removal-read screenKey`, `removal-status screenKey generation`, and `removal-stop screenKey generation`. The first returns ordinary status; verification returns current/replaced/gone. Gone requires both an explicitly absent socket and no Android registration for the named display. Communication failure is not gone. These commands exec the actual CLI process; stopping a named screen still holds the lifecycle lock. A timeout can terminate the actual command process. Deploy the updated wrapper and JAR together; see [session removal](../../docs/session-removals.md).

`src/org/agentos/lab/` and `phone-display` are the only host/wrapper sources. The original experimental entry point symlinks here rather than retaining a second copy. The viewer is `system/assistant/.../MirrorActivity.java`.

As of October 26, 2026, a temporary `ec891cd` deployment on Pixel 9 / Taku OS 1.0.1 completed real parallel queries in three `phone-native` branches: Xiaohongshu found notes about the same store, Amap found a route to L4 in the same mall, and Meituan returned the existing reservation's success receipt. WeChat remained on the main screen. No new reservations or messages were made. The computer controller dispatched the three branches; automatic decomposition and aggregation are not implemented. This run did not repeat emulator verification, and full ROM integration is pending. See the [multi-app acceptance record](../../docs/multi-screen-parallel-case-2026-10-26.md).

The three native threads used display IDs 6, 7, and 8 in that run. These IDs are evidence, never constants. Subsequent local failure fixes have not been retested on hardware and are recorded separately from the `ec891cd` video version.

```sh
# Repository root: compile without installing.
bash system/display/build.sh
# Host regression: publish the final frame after brief updates stop; no device operations.
bash system/display/test-frame-coalescer-host.sh
# Multiple-screen slots, identity validation, and wrapper regressions; no device operations.
bash system/display/test-multi-screen-host.sh
# Device root shell; integrate the program/JAR into the development image first.
phone-display start
agent_display_id=$(phone-display id)
am start --display "$agent_display_id" -n com.android.deskclock/.DeskClock
input -d "$agent_display_id" tap 411 77
phone-display capture
phone-display stop
```

One phone can create independent slots for different tasks, each with its own Android display, process, and socket:

```sh
phone-display start xiaohongshu
phone-display start maps
phone-display start meituan
phone-display list
phone-display id maps
phone-display capture maps
phone-display stop maps
```

`start`, `id`, `status`, `capture`, and `stop` accept an optional `screenKey`. Omitting it or using reserved alias `default` retains the original directory, display name, and `taku-mirror` socket. Other names must match `[a-z][a-z0-9-]{0,31}`. Explicit empty strings, paths, spaces, and uppercase letters fail rather than falling back to the default screen.

A named screen uses state directory `/data/local/agentos/virtual-display/screens/<screenKey>`, socket `taku-mirror-<screenKey>`, and display name `Taku-Independent-Screen-<screenKey>`. Each launches through its own `app_process`. Its file lock, screenshots, and stop requests belong to its directory. Starting or stopping named screens does not operate the default screen's `taku_display` init service.

Kernel file locks serialize `start` / `stop` for the same slot. The lock covers stopping the default screen's init service so an old instance's exit cannot accidentally stop a concurrently started replacement. Different slots do not wait for each other. The long-running host does not inherit this lifecycle lock. Android Toybox `/system/bin/flock` with file-descriptor support is required.

Android mksh marks persistent descriptors created by `exec 9>...` close-on-exec, so flock calls must explicitly use `9>&9`. `bash system/display/test-lifecycle-lock-device.sh <adb-serial>` checks lock contention and release on a real device. It touches only its own `/data/local/tmp/taku-flock-probe-*` files and neither starts nor stops displays. Pixel 9 passed this probe and the three-app queries; cancellation, restart, and full lifecycle still require dedicated acceptance.

`status` returns one JSON object. Read-only `list` returns `{"screens":[...]}` containing only verified live screens. Clients connect to the matching local socket and verify the actual peer PID/UID, display name, Android's currently registered displayId, and slot directory. Leftover `display.json` / `display.id` files alone do not prove a live screen. Android `LocalSocket` clients must `connect` before `setSoTimeout`; this order was verified in real Pixel 9 status reads and screenshot exports.

Each host start generates a new UUID `generation`. When binding a task, save `screenKey`, `generation`, and `displayId`. Later capture or release uses:

```sh
phone-display capture maps <generation-from-status>
phone-display stop maps <generation-from-status>
```

The real host checks generation again for these operations, preventing actions against a replacement after a restart or Android displayId reuse. `capture` still outputs two lines: screenshot metadata JSON and an absolute PNG path. Metadata includes slot, generation, PID, displayId, and `captureRequestId`. `id` still outputs only a number. Missing or identity-mismatched slots fail without guessing or touching the main screen. The sole compatibility exception is default-screen `stop` without generation: an explicitly absent socket counts as already stopped; permission errors or a live host with mismatched identity still fail.

Each screen is created with `VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP` (when the platform has it), so it has its own display/power group and the power key turning the main screen off does not also turn the task's screen `STATE_OFF` (BUG-0034). As a fallback, `capture` never exports a black image: a display state of OFF, DOZE, DOZE_SUSPEND or ON_SUSPEND fails with `SCREEN_OFF: ...`, and a latest frame whose every pixel is exactly RGB 0 fails with `SCREEN_BLACK_FRAME: ...` (one non-zero pixel, e.g. any dark-mode App, is never black). Successful capture metadata includes `displayState`. The broker reports both to the Agent as retryable `observe_background` errors. Policy lives in Android-free `ScreenGuard.java`, host-tested by `system/assistant/test-execution-screens-host.sh` (`ScreenOffCaptureTest`).

DisplayManager.createVirtualDisplay and an ImageReader Surface create a logical display in the same system. Read IDs dynamically; never hardcode 2 or 3. The logical IDs used by am/input differ from SurfaceFlinger IDs used by screencap. A separate app_process owns the display; it is not another Android VM. The main-screen viewer connects to the UID-restricted `taku-mirror` abstract Unix socket. There is no root-shell TCP service.

Program paths remain `/system_ext/framework/agentos-virtual-display.jar` and `/system_ext/bin/phone-display`. stop synchronously waits for cleanup/process exit; start restores a display. Reboot requires a new start. The default slot remains compatible with the existing init service; named slots start on demand. The existing main-screen viewer still connects to the default screen. Multi-screen tasks must use their own slot routing rather than treating that viewer as an entry point for every background screen.

Each screen coalesces updates at most once per 200 ms. Notifications schedule a read; execution retrieves the latest frame, preserving the final frame after brief activity. PNG capture and mirrored JPEG use the same frame. If a frame update is pending, capture waits for it. In `capture.json`, `capturedAt` is export time; `frameAt` / `frameElapsedMs` record frame processing time. `frameAgeMs` and `frameSequence` identify new visual content. Live `status` also exposes frame times and sequence. A static page's frame time legitimately stops advancing; a new export time is not a new frame.

`ec891cd` passed isolated host tests and JAR compilation. Initial Pixel 9 window and screenshot evidence is in ignored `out/multi-screen-case-20261026/first-three-screens/`. The real three-way execution after Wi-Fi recovery, final results, and recordings are in `out/multi-screen-case-20261026/run-01/`. `rollout-*.jsonl` and final `agent-view-*.json` support the business outcomes and overlapping execution times; status polling or a four-panel video alone is insufficient. Real account screenshots and raw records are not distributed with source.

In this run, Xiaohongshu found the target store's location page and notes. Amap found the route within the same mall and an L4 destination; its “1 meter” result describes map points, not actual indoor distance or the device's GPS distance from the store. After one normal swipe passed verification, Meituan read the earlier reservation's success receipt. No reservation was created, modified, or canceled, and no chat message was sent.

The run had exceptions: Meituan initially stopped at security verification and completed after the controller continued with a second turn under prior authorization. One Xiaohongshu `type_text` readback failed before a later real search succeeded. Amap's detail area displayed a network error. This was not an error-free run. Automatic three-way dispatch, result aggregation, and confirmation cards are not integrated. Failure cases such as canceling one screen while the others continue require separate testing.

Two subsequent boundary fixes passed local regression and JAR compilation: screen leases of archived or deleted threads can be cleaned up under their original ownership; ongoing gestures stop further input after host generation changes or disconnection. These fixes have not been deployed or retested on hardware. The `ec891cd` video in `run-01` is not acceptance evidence for them.

Historical Android 16 ARM64 userdebug / SELinux Enforcing emulator records demonstrated background DeskClock rendering and targeted input, continued main-screen use, native mirror taps, persistence after collapsing the viewer, and stop/restart. This run adds no emulator acceptance. Historical results do not replace verification of multiple slots. Evidence remains at `../../../2026-10-09/virtual-display/` and `../../../2026-10-09/mirror-screen/`.

App data and single-instance tasks remain shared. The display host does not arbitrate conflicts between users and Agents. Each app's window, input, media, and login compatibility needs separate acceptance. Historical Douyin green/color-pattern decoding artifacts also occurred on the main screen alone; they do not establish support for every third-party video app. This multiple-slot version passed the three real Pixel 9 queries above, not all apps or complete failure recovery. Full ROM integration and acceptance remain pending.

Codec/texture diagnostics are separated into `tooling/diagnostics/` and excluded from the background-display runtime JAR. Historical related records are in `evidence/api-inventory/historical/`. Neighboring experiment-directory locations document provenance only and are not clean-checkout dependencies.
