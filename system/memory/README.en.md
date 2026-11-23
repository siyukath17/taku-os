# Taku Memory 0.16.0

[中文](README.md) · [Content-aware titles and suggestions](../../docs/reviews/taku-memory-content-2026-10-22/README.en.md) · [Daily-app acceptance](../../docs/reviews/taku-memory-daily-apps-2026-10-22/README.md)

One standalone Android APK, org.agentos.memory, combines automatic local memory, a native library, and proactive suggestions. It has its own UID and private SQLite database. Existing on-device Magpie owns models and credentials; the existing Assistant and TaskBroker own Agent execution.

## 0.16.0: faster recall, same results

Search, shared by Agents (`memory_search`, open-access `search`), the Suggestions page and the user card, now returns the same ranked results in less time. On a cloud test machine a whole-sentence question took about 12 ms instead of 29 ms at 10k memories, and about 55 ms instead of 159 ms at 50k.

- **Vectors run alongside the keyword scan.** The query vector and the evidence top 30 run on a `memory-query` thread while the caller scans keywords and node text. The last 64 query vectors are cached. The model's first load takes its own lock, so a cold start no longer blocks other searches.
- **Only the top 30 are kept.** Evidence vectors live in plain arrays, and scoring keeps the best 30 as it goes. Equal scores keep the earlier row, as the previous stable sort did.
- **Nodes are prepared once.** Lowercased node text and `related` sets are computed when nodes load; the one-hop expansion builds its reverse map once per search.
- **Cheaper keyword SQL.** A covering index `memory_kw(first_seen,id,title,head,fresh)` lets the newest-first scan read the index alone. Existing databases get it from the 15-minute maintenance job. Terms without cased letters use `instr()` instead of `LIKE`; terms with letters stay case-insensitive.
- **Pruning is throttled.** Search prunes at most once a minute instead of every time; changing settings makes the next search prune first.

Rankings match the previous code entry by entry on 16 queries at 1k, 10k and 50k memories. Debug builds add a read-only, root-only `benchSearch` hook for timing each stage on a device. Not yet measured on a device. See the [0.16.0 acceptance record](../../docs/reviews/taku-memory-0.16.0-2026-11-20/README.md).

## Current Feed event protocol

Feed no longer caps publications at 100 or evicts old cards. Memory consumes finite `/events` replay and stores the processed checkpoint in kv `feedFeedbackCursor`. Disconnects keep the previous cursor; replay deduplicates by event cursor. Reminders retain their latest 20 feedback entries. Accepted actions show `selected`, without claiming an external effect completed. The GUI suggestions page, public Agent `nudges`, and existing root/Broker `nudge` read the same feedback. Legacy launchers without `eventCursor` still use `recentActions`. The 0.15.0 notes below describe that older contract.

A reminder's notification, system hint and Home card stay in step. When read-back finds that the user removed the card, chose one of its buttons or handed it to Taku on Home, memory clears that reminder's notification and hint; pinning alone keeps them. When the user hands a reminder to Taku from the notification or the Suggestions page, memory clears the hint and the old notification (the notification then shows the hand-off status) and retracts a message reminder's Home card unless the user pinned it. The evening brief card stays up until the next brief replaces it.

## 0.15.1: five bugs fixed

After #38 and #33 landed on main, memory was checked again against the home feed's real code:

- **Agents could not list reminders**: `nudges` was not on the `org.agentos.memory.open-access` allowlist. It is now, and the agent docs show the command.
- **An emoji could stop the whole card**: the home feed counts the pin label (≤24) and detail (≤32) in UTF-16 units, where an emoji takes two. Memory cut by characters, so a name or reminder with emoji could run over and the card was refused. Cuts now count UTF-16 units and never split an emoji.
- **Cards the home feed's AI made could stay up**: they have no expiry of their own, and memory only retracted them after a successful nightly organize or with the next reminder. The 15-minute maintenance job now checks too: it retracts cards that are due, reads reactions back when recent reminders have cards, and takes everything down when the feed cards are off, deciding locally before it calls the home feed. Reading back more often also keeps reactions from being pushed out of the home feed's shared 50-entry log.
- **The new Taku home could become a recording source**: memory never recorded itself or the Taku assistant (`org.agentos.assistant`), but the new home and Taku chat moved to `org.agentos.agent`, which was not excluded. Selected under "Apps to record", it would feed memory its own reminder cards and what Taku said. Memory itself, both Taku apps, the system display and System UI are now never recorded, even when selected, and are left out of the app list; `status` lists them as `neverObserved`.
- **The newest reminder of a kind sat at the bottom**: the home feed keeps equal ranks in publishing order. Ranks now add a tiny share of the time (about 0.018 now, 0.0003 a year), so the newest of a kind comes first and the three kinds keep their order.

Five new component checks; `cut` and `rank` pass 9 checks on the JVM; the emulator run is still outstanding. See the [0.15.1 acceptance record](../../docs/reviews/taku-memory-0.15.1-2026-11-10/README.md).

## 0.15.0: home feed card lifecycle and feedback, aligned with home PR #33

Vela's home PR #33 adds a recommendation API guide (`docs/feed-recommendation-api.md`) that spells out rules memory had not followed:

- Expired cards are hidden but stay stored and keep one of the 100 places shared by every caller; so do cards the user removed.
- `feed/publish` beyond 100 silently pushes out the oldest card, possibly another app's; `feed/generate` returns `FEED_FULL` instead.
- There is no callback. What the user does can only be polled from `feed/list` `recentActions`, which keeps the last 50 entries.
- When `feed/generate` gets no answer the outcome is unknown; the card may have been published.
- Design samples rank 0.70–0.99; only a rank above 0.99 sorts above all of them.

What changed in memory:

- **Every card is recorded and retracted**: fixed-layout cards are now recorded in kv `feedExpiring` too and retracted when they expire. Before, only cards the home feed's AI made were retracted, so fixed message cards kept their place. Cards the user removed are retracted at the next pass.
- **Turning it off takes them down**: turning off "Show on the home feed" or "Speak up first" retracts every card memory published; cards the home feed cannot be reached for go at the nightly pass.
- **Reactions are read back**:
  - before each card, after the nightly pass and when the Memory app opens, memory reads `feed/list` and records what happened to the last three days' reminder cards in the new nudges column `feedback`;
  - each event is `{at, op, origin, effect}`, de-duplicated, at most 20;
  - a removal that already fell out of the log but is still in `removed[]` is recorded with an empty origin;
  - they sum up as `reactions`: `asked` (a button opened Taku with the card's draft), `opened` (opened the app the message came from), `pinned`, `removed`.
- **Visible**: reminders from the last 7 days on the Suggestions page show a line "On the home feed: …". `nudge` returns `feedback`, and a new read method `nudges` (`days` 1–7, `limit` 1–30) returns the same list. Signed consumers and `org.agentos.memory.open-access` content reads both require `queryEnabled`. The open API shares the UI's store and management operations; `search/get/board/profile/agenda/nudges/graph/browse` reject reads while the switch is off, without changing it. Explicit `configure` remains available to agents and local apps just like the GUI, so this is a runtime setting, not a hostile-app isolation boundary. While queries are off, explicit `update` returns only `id/revision`, without old content or history. The Memory app's local UI can still browse. Broker-only `nudge` remains outside the open allowlist.
- **Rank and glyph**: memory's cards used to sit between the samples; now they rank above 0.99: a clash 1.3, a message 1.2, the bedtime brief 1.1. Message cards pin with `chat` instead of `list`.
- **No answer: look first**: when `feed/generate` gets no answer or an unreadable one, memory looks on the feed for a new `agent-generated:` card created since the call with exactly the description's buttons. It keeps the card if it passes the check and retracts it otherwise; only when there is none does it publish the fixed card.
- **Full feed**: on `FEED_FULL` memory only sends the notification instead of publishing the fixed card, which would push out another app's card.
- **Debugging**: two new root-only hooks in debug builds: `deliverNudge` sends a reminder without asking the model; `feedTidy` runs the clean-up now, or with `withdraw` takes everything down.
- **Test stand-in**:
  - `feed/list` now has the home feed's shape: card summaries, `pins`, `removed` and `recentActions` entries `{at, origin, op, cardId, detail}`;
  - new `feed/remove`, `restore`, `pin`, `unpin`, `action` and `ask-taku`, which take `origin:"gui"` to act as the user on screen;
  - `feed/stub` adds `full`, `lost` (publishes but never answers) and `silent` (neither).

Reactions are recorded, shown and readable by agents; they do not yet change when memory speaks up.

**Results**: the 26 fixed-layout cards pass `validateFeedSpec` against the latest catalog on Vela's branch, and the 6 read-back checks pass on the JVM. The test emulator could not boot this time (the Mac's CPU was taken by another job); component tests and the stand-in end-to-end run have not been run yet. See the [0.15.0 acceptance record](../../docs/reviews/taku-memory-0.15.0-2026-11-09/README.md).

## 0.14.1: rewritten descriptions for the home feed's AI

Descriptions now spell out the element tree and how to page, list items without numbers, quote messages in an Inset, give the summary to use (the fixed card's, whose first 24 characters become the pin) and pass the actions indented with unescaped slashes. On the same 26 reminders through the home app's own generation code (`gpt-6-luna`), usable cards rose from 88% (91/104) to 94% (49/52), structure errors fell from 7 to 0 (208 descriptions over 8 runs) and copied numbering from over half the cards to none. See the [0.14.1 acceptance record](../../docs/reviews/taku-memory-0.14.1-2026-11-09/README.md).

## New in 0.14.0: describe reminders and let the home feed's AI design the card (experimental, off by default)

The home feed added `feed/generate`: given a description, it makes a card with its configured model route (`agent-ui.text` on Android Magpie) under an `agent-generated:<uuid>` id.

- **Switch**: Let the home feed's AI design cards, under Show on the home feed; `feedDescribe` in `configure`.
- **Description** (`FeedCards.describeEvening` / `describeMessage`):
  - the items, the message and what Taku says, to be copied as written;
  - the actions as a JSON object to use unchanged; the hand-off still says never send;
  - a few writing rules: the buttons' `on` field, no components that need an avatar, Pager and Dots attached, and the output order.
- **Check** (`FeedCheck`): memory reads the card back with `feed/get` and requires:
  - unchanged actions, ignoring whitespace;
  - a button for every action;
  - every supplied text present as written, at least its first 24 characters.
- **Fallback**: if the home feed cannot make a card, or its card fails the check, memory retracts it and tries once more; after that it publishes the fixed-layout card. A call the home feed never answered is not retried.
- **Retraction**: generated cards never expire, so memory keeps their ids and retracts them itself:
  - message cards after 24 hours, bedtime briefs at 23:59 the next day;
  - before each card and after each nightly organize.
- **Background thread**: home feed work runs on its own background thread, one call at a time, so notifications do not wait for it.
- **Debug**: `feedPreview` with `describe: true` returns the description.
- **Test stand-in**:
  - adds `feed/generate` and `feed/get` without a model;
  - `feed/stub` switches it to rewriting buttons (`tamper`) or refusing (`refuse`).

**Results**:

- The 26 reminder descriptions went through the home app's own generation code with `gpt-6-luna`. Both runs produced 24 cards that passed the home feed's validator, and all of them kept what memory asked for. 25 of the 26 worked in at least one run.
- Wording mattered a lot: the first wording got 14 cards past the home feed's validator, but none was usable because their buttons were not wired to any action.
- On the emulator, with the stand-in installed, memory kept a good card, caught rewritten buttons, fell back to the fixed layout after refusals and retracted cards on time.
- Not yet run in an installed home app.

See the [0.14.0 acceptance record](../../docs/reviews/taku-memory-0.14.0-2026-11-07/README.md).

## New in 0.13.0: reminders on the Taku home feed (experimental, off by default)

The Taku home (`system/agent-app/modules/taku-home`, Vela's `vela/TAKU-new-UI` branch) has a card feed that other modules can push JSON cards to: a `taku.feed-card/1` envelope whose body is json-render from the `taku-feed-1` catalog, published with `feed/publish` on `content://org.agentos.agent.home-feed` (see that module's CONTRACT.md).

- **Switch**: Settings → Organizing and assistant → Show on the home feed, shown only when Speak up first is on and a home with the feed is installed; `feedCards` in `configure`.
- **Bedtime brief card** `memory.brief.<id>`: the title and up to 9 items, 3 to a page; Hide and Let Taku plan (hands the items to Taku to plan tomorrow; replies are drafted, never sent). A new night retracts the previous card; it expires at 23:59 the next day.
- **Message card** `memory.nudge.<id>`: quotes the message, then what Taku remembers, tagged when it may clash; Open app (when the source app is known, otherwise Hide) and Draft a reply or Ask Taku. Expires after 24 hours.
- **Built from the reminder only** (`FeedCards`, `ProactiveNotice.feed`): no extra model call; the notification or system display is unchanged and a failed publish is only logged. The nudges table gains a `card` column, returned by `nudge`.
- **Privacy**: the home feed is open to apps on the device (no permission), so once a message and what Taku remembers are on the feed, other local apps can read them; the switch says so.
- **Debug and tests**: debug builds add root-only `feedPreview` (builds one card from given content, no model call, nothing published) and `card: true` on `proactive`; `build-tests.sh` also builds a home feed stand-in, `TakuHomeFeedStub.apk`, which takes the home feed's authority and must be uninstalled afterwards.

All 26 cards (21 bedtime briefs, 5 message reminders) and the 3 cards published on the emulator through the real path pass the home feed's own `validateFeedSpec` and render in the home app's web component gallery. With the stand-in installed, live model calls delivered a brief and a message reminder as cards, the next night retracted the old brief, and nothing was published with the switch off. Not yet seen in an installed home app. See the [0.13.0 acceptance record](../../docs/reviews/taku-memory-0.13.0-2026-11-05/README.md).

## 0.12.0: tuning the weak spots of 0.11.0

- **Organizer** (`MemoryGen`): `due` is written whenever something must be done ahead (rebook, order by delivery time, confirm, reply, pay, pick up, prepare) and worked out when possible; a new `attend` flag says whether the user must be there at that time.
- **User card** (`Recall.dueSoon`): times the user must attend are marked; when the list is full, a dated task or item counts as a deadline, "act before" gets no extra weight and the organizer's stake counts half as much; two lines at the same time with the same person, one of them attended, become one.
- **Clashes** (`Proactive`): the local check only compares times marked as attended (old graphs without the flag behave as before).
- **Check before sending** (`Proactive.check`, Broker kind `check`): after the model decides to speak on a notification, `gpt-6-luna` looks only for two mistakes (calling a deadline, delivery or check-in time a clash; tying unrelated things together) and the reminder is dropped if it finds one. About one call a day, ~4.5k tokens.
- **Changed today** (`Recall.changes`): a time that had already passed by last night is not a reschedule (a weekly meeting rolling on, an incident thread that keeps updating).

With the 22-day graph rebuilt by the 0.12.0 organizer: things with an act-by day 9 → 41; card coverage 74 → 75 of 80 (the new rules on the old graph stay at 74); bedtime brief 62, 62 → 65, 64 of 70; locally marked clashes 27 → 14; the check blocks 5/5 wrong reminders offline at the cost of 3/29 useful ones, and 3 of 4 wrong ones on the emulator with 0 of 6 useful ones blocked. See the [0.12.0 acceptance record](../../docs/reviews/taku-memory-0.12.0-2026-11-05/README.md).

## New in 0.11.0: the organizer writes what reminders need; reminders can go to the Agent

- **Organizer fields** (`MemoryGen`, `MemoryStore.applyGraph`): nodes gain `due` (the latest day to act, only when it differs from the thing's own date) and `stake` (what missing it costs, one line, empty when the evidence says nothing); `related` should also link what to think of together (what to wear or bring, the reason behind a gathering).
- **The user card uses them** (`Recall.dueSoon`): an item with `due` enters "the next two days" by that day, marked "before … (itself on …)"; the stake is shown and wins when the list is full; up to a few "↳ think of together" lines follow.
- **Clashes are marked locally** (`Proactive.slot`): when a notification asks about a time slot that overlaps a plan already on that day, the line goes to the model as "possible clash" for it to confirm.
- **Changes reach the bedtime brief** (`Recall.changes`): things that moved, were cancelled or done during the last day are listed as "changed today".
- **Hand to Agent** (`NudgeAction`, Broker `nudgeTask`): reminders in the notification and on the Suggestions page offer "Hand to Agent". The Broker reads the reminder and the background it was written from back from the memory service and queues one read-only task on the existing Agent (verify, suggest what to do, draft a reply without sending); a reminder is handed over once, after which the card shows the result. The nudges table gains `context` and `task` columns on first open.
- **Debug builds**: `maintain` accepts `graphOnly`; `configure` accepts `agendaFields` to compare cards with and without the three fields.

With the graph rebuilt by the current organizer on the 22-day synthetic timeline, the card has the item for 74 of 80 marked moments (71 without the fields); the bedtime brief mentions 62 and 62 of 70 evening moments (59 in 0.10.0, 54 without the fields); replaying 195 notifications it spoke 20 times, graded 16 useful, 2 of little use and 2 wrong (13 / 5 / 2 in 0.10.0). See the [0.11.0 acceptance record](../../docs/reviews/taku-memory-0.11.0-2026-11-04/README.md).

## New in 0.10.0: speaking up first (experimental, off by default)

With **Settings → Organizing and assistant → Speak up first (experimental)** on, memory is used without being asked (`Proactive`, `ProactiveNotice`):

- **Bedtime brief**: after the nightly organize (20:00 or later, once a night), the user card goes to the model, which writes up to 8 reminders, costliest to miss first.
- **A new notification touches something remembered**: a local check (no model) looks for a person on the card, a shared code or number, a day the notification asks about that already has plans, or a related habit; only then do the notification and those few memory lines go to the model, which decides whether to say something. Notifications only; page-captured chat lists do not trigger yet.
- **Delivery**: a system-display hint when connected, otherwise a notification; the Suggestions page lists what Taku brought up. At most 8 message notes a day, one model call per two minutes, the same thing not repeated within 12 hours; instruction-like or linked notifications never wake the model.
- **Model**: the Broker gains the `brief` and `nudge` kinds (recommended `gpt-6-luna`), under the same Taku AI Memory switch as organizing, in ephemeral threads without tools or agent background; about 4.5k tokens per call.

On the 22-day synthetic timeline the bedtime brief mentions 59 of 70 evening moments; of 195 notifications the local check woke the model 32 times and it spoke 20 times (about once a day), graded by a stronger model as 13 useful, 5 of little use and 2 wrong. Debug builds add root-only `proactive` and `proactiveReplay`. See the [0.10.0 acceptance record](../../docs/reviews/taku-memory-0.10.0-2026-11-03/README.md).

## New in 0.8.2: memory puts the right thing in front at the right moment

The 22-day synthetic timeline marks 80 moments when the assistant should speak up unprompted. Most depend on something seen days earlier (median 53 hours), so judging each new capture on its own catches at most 3 of them. This version only changes how the card's "next two days" list is chosen; no model calls, no organizer changes:

- **The user's own appointments stay**: a check-up or follow-up in the graph is still dropped when another person is named right before it ("妈妈体检") or the user is not the subject; the user's own (a dental follow-up, booking the annual check-up) is kept. Other people's conditions and diagnoses never pass.
- **When the list is full, the costliest to miss stay**: deadlines with a penalty, "before …" deadlines, promises, birthdays and weddings, and money come first; daily reports and "no need to be home" notices last. Two nodes about the same thing on the same day are shown once.
- **New commitments**: tasks that appeared in the last day but are not due within two days are listed that evening (up to 4).
- **Things that became possible on a past day** ("pick up from the 13th") stay for a week while still open.

The card holds the needed item at 74/80 moments (0.8.1: 68; evenings 67/70 vs. 61/70), with nothing lost; median card length grows from about 1,670 to 1,930 characters. A bedtime brief written by `gpt-6-luna` from the card alone, without tools (the light path proactive reminders will take, about 5.6k tokens each), mentions 62 of the 70 evening moments (0.8.1 card: 57). The full assistant searches memory itself, so its answers barely change, at 150k–230k tokens each. See the [0.8.2 acceptance record](../../docs/reviews/taku-memory-0.8.2-2026-11-03/README.md).

## New in 0.8.1

- **Organizer threads no longer carry the agent background**: the Broker added the agent background (device environment, skills catalog, copies of the board and card) to every `turn/start`, organizer turns included; once the assistant had run a task, each organizer call grew from about 12.5k to about 40k tokens. Organizer threads now get only the organizer instructions and their own input (`MemoryGen.owns`): the same 7 notifications take about 16k tokens per run.
- **Replies use the relevant plans and stakes**: when the assistant helps answer a message or handle a notification, it first works out who is involved, then puts those people's related plans (time, place) and stakes (why it matters) from the user card into the reply or advice (Broker `HarnessContext`). On the 22-day synthetic timeline, trigger messages went from 5/7 to 7/7 in both runs: asked about Ajie's birthday present, the drafted reply also confirms "the usual place, 6 pm on the 22nd"; when the early flight is cancelled, the advice explains that a competitor is also courting this client, so keeping the meeting with Mr. Wang comes first.
- **Next-two-days deduplication**: the graph often holds several close nodes for one thing; the card's "next two days" list keeps one item per day for near-identical titles.
- **Follow Taku AI**: main now routes this choice through the memory scene group (`memory.text`) on a Magpie connection and, by design, does not fall back: without a Magpie connection or with the group unavailable, organizing stops and Settings show the reason. 0.8.1 marks the choice as unavailable in the organizing model list ("needs a Magpie connection"); tapping it explains what to set up in Taku AI and does not save it. The Broker's `organizeModels` also returns `systemReady`.
- **Taku AI Memory switch**: since main, the switch is `taku_ai_app_memory` in `Settings.Global` (no longer `/data/local/agentos/ai/settings.json`). Organizing is refused while it is off and resumes when it is on; the smoke test now checks that location.

22-day regression (board, card and graph from the 0.5 organizer; same rubric): cross-topic 10 and 10 / 11 (0.8.0: 10, 9), association 11/11, trigger messages 7/7 twice (0.8.0: 5/7), bedtime review 24/25 (0.8.0 regraded on the same rubric: 22/25). See the [0.8.1 acceptance record](../../docs/reviews/taku-memory-0.8.1-2026-11-02/README.md), which also covers a clean install of the latest main (`a4bc391`).

## New in 0.8.0

- **English privacy rules**: every rule in `Privacy` has a Chinese and an English pattern. Other people's conditions, relationships, money and private plans to quit stay off the card; health events stay only with something the user does about them; a colleague leaving stays only for the handover. The user's own diagnoses, medication, income and accounts are dropped, as is instruction-like English text. Deleted entries are compared after removing English dates and filler words. Today's quotes follow the same line and match English messages by word.
- **Organizing model and cost**: **Settings → Organizing and assistant → Organizing model** offers Recommended (`gpt-6.1-sol` for graph and card, `gpt-6-luna` for the board), Follow the Taku AI text model, or a specific Codex model; an unavailable choice falls back to Recommended. Each run replaces the runtime's coding-agent instructions with the organizer's and turns off unused tools, cutting the fixed part of each request to about a third: the same 7 notifications now take about 36k tokens per run (79k in 0.7.0). Settings show the tokens and models of the last run. Organizer threads are ephemeral; organizer conversations left by earlier versions are deleted on the first run after the Broker starts.
- **Proactive reminders**: the per-message card adds "the next two days", taken from events, tasks and items in the memory graph due today through the day after tomorrow (one-off things like a parcel pickup or a final payment, which the card leaves out by design), in time order and under the same privacy rules. The assistant is asked to mention today's to day-after-tomorrow key dates, deadlines and conflicting plans in a sentence or two after answering, once per conversation.
- **Deletion reaches ongoing conversations**: after the user deletes or corrects a record, or edits the board or card, conversations already in progress get one note on their next turn: board, card, records and quotes from memory seen earlier may be void and should not be reused; look them up again. The note does not repeat what was removed. Content already sent to the model provider cannot be recalled.
- **Quantized vector model**: bge-small-zh-v1.5 dynamic quantized export (about 24 MB instead of 95 MB). On the 22-day synthetic data against fp32: mean document cosine 0.990; over 72 queries, top-1 agreement 91.7%, top-10 overlap 88.5%, 99.2% of fp32's top 5 still in the top 10; documents encode about 2.5× faster. After an upgrade all document and graph-node vectors are recomputed automatically (1,537 records in about 30 s on the emulator).

22-day synthetic regression (board, card and graph from the 0.5 organizer): cross-topic 10 and 9 of 11 over two runs (0.5: 6 and 8); association 11/11, trigger messages 5/7 and bedtime review 21/25 unchanged. See the [0.8.0 record](../../docs/reviews/taku-memory-0.8-2026-10-29/README.md).

## New in 0.7.0: append-only storage, on-device vectors, organized board and user card

0.7.0 merges the 0.4–0.5 memory branch on top of 0.6.0 (system AI, English/Chinese UI). Build and install the Broker from the same commit (`agentos-task-broker`: `MemoryGen` organizes memory; `HarnessContext` adds the board and user card). With only the Memory APK updated, nightly organizing fails because the old Broker does not know the request, and retries after 30 minutes; capture, retrieval and per-record analysis are unaffected. Run `python3 scripts/prepare-memory-vectors.py` before a ROM build (see below).

- **Storage**: a page stores only lines that are new relative to its recent versions (deduplicated line store). Nothing is forgotten by default; retention can be set to 1, 7 or 30 days. Notifications come through notification access (`NotificationTap`): only the title and text from selected apps, skipping verification codes, ongoing and protected notifications.
- **Retrieval**: keywords plus on-device bge-small-zh-v1.5 vectors (ONNX Runtime, offline) plus memory-graph nodes, fused by rank, then expanded one hop along graph relations.
- **Nightly organizing** (`Organizer`, through the assistant Broker with the user's own model account): new content becomes graph nodes, a "what am I busy with" board, and a short user card (up to 10 habits of the user; up to 12 important people, each with up to 3 notes and key dates). Runs at 11 PM; if that hour is missed (off, asleep, offline) it runs at the next chance; after a failure it waits 30 minutes.
- **How the assistant uses it**: the board is given whole. The user card is picked per message: the user's habits and core family, people with plans or dates in the next 3 days, people and habits the message touches, and today's related quotes not yet organized (quoted; links and instruction-like lines are left out). Notes whose dates have all passed are marked as past. Each can be turned off in settings.
- **Privacy**: rule-based filtering (`Privacy`) before saving and before showing. For other people the card never keeps condition or body details, relationship or family conflicts, money, or private plans to quit; health events stay only when tied to something the user does about them; a colleague leaving stays only for the handover. The user's own diagnoses, medication, income and accounts are not kept. Long numbers are masked; instruction-like entries are dropped.
- **Deletion and correction**: deleting or correcting a record drops every graph-node version citing it, and every board and user card version that may contain it; organizing rewinds and runs again soon with the remaining records (and the corrected text). Results from a run that overlapped a deletion are discarded. Node IDs are never reused.
- **User control**: **Settings → Organizing and assistant** shows the board and user card; delete an item, a whole person, or clear and rebuild. Deleted items are told to the organizing model and are also blocked by name and similar wording before saving and showing.
- **System AI**: nightly organizing follows **Taku AI** like per-record analysis: no organizing while system AI is paused or Memory's AI is off, and results are discarded if the setting changes mid-run; calls appear in the same recent-usage list.
- **Language**: the new settings, board and card pages use the same English and Chinese resources as 0.6.0. Board and card content is written by the organizing model in Chinese and is not translated.
- **Build flags**: simulated time (`testNow`), organizing at a given time (`maintain {now}`) and rebuilding only the card (`buildProfile`) exist only in `TAKU_DEBUG_HOOKS=1` debug builds and only for root; release builds reject them and must bundle the vector model.

Vector dependencies: `system/memory/vector-dependencies.json` pins the ONNX Runtime Android 1.29.0 AAR and Xenova/bge-small-zh-v1.5 `model_quantized.onnx` (about 24 MB) by URL and SHA-256. `scripts/prepare-memory-vectors.py` downloads and verifies them into the ignored `system/memory/downloads/` and prepares `downloads/assets/bge/` for `Android.bp` (model stored uncompressed). `build.sh` runs it on first build. `TakuMemory` in `Android.bp` must keep `use_embedded_native_libs: true` (otherwise Soong drops `libonnxruntime.so` / `libonnxruntime4j_jni.so` from the AAR) and the manifest must not set `extractNativeLibs="true"`; `system/memory/test-host.sh` checks that packaging contract on the host and that recall degrades to keyword-only, without crashing, when the native libraries are missing. The APK is about 37 MB.

Acceptance: 0.4–0.5 branch on the dedicated `TakuRecall` AVD ([record](../../docs/reviews/taku-memory-0.5-2026-10-28/README.md)); merged 0.7.0 re-test ([record](../../docs/reviews/taku-memory-0.7-2026-10-28/README.md)); 0.8.0 ([record](../../docs/reviews/taku-memory-0.8-2026-10-29/README.md)). Same-version physical phone, full ROM, cold boot and long-term battery are not verified.

## Get started

1. Open **Settings → Language** to choose System default, English, or 中文. English is the default resource language.
2. Enable **Auto capture**, select **Apps to remember**, and enable the **Observation service** in Android accessibility settings. Readable pages are saved after they settle; there is no per-item Remember action.
3. Browse **Memories** by date. Search, combine category and source filters, and load more records. Details preserve captured text and expose source, time, ID, and revision. Edit history retains five previous versions. Deletion removes text, history, and search results and suppresses the same original page for 30 days.
4. Select the existing Jev model under **Analysis model**. Selection alone sends nothing. Separately enable **Model analysis** to process queued text through on-device Magpie.
5. Enable **Agent memory access** and **Proactive suggestions** if wanted. Actionable decisions can offer a next step. Only accepting the actual UI creates a read-only Agent task. Open **Suggestions → View Agent result** to read its response.

Capture, analysis, Agent access, and proactive suggestions have separate controls. Pausing capture does not stop an enabled analysis queue. Pausing analysis retains the queue; resuming catches up in batches. Changing the UI language preserves records, original text, stored categories, filters, queue, and model settings. Display labels are localized; existing Agent replies and user content are not translated. New accepted tasks request a reply in the selected UI language. The system surface host localizes its generic controls using its own locale.

## Titles that tell you what was saved

Cards and detail headings locally extract the event name, meal plan, or route from the saved text. The app name remains a smaller source label. For example, **Calendar → Taku design review (Demo)**, **Alex Chen (Demo) → Saturday lunch · The Hummingbird Cafe**, and **Organic Maps → Marina Bay Sands → Singapore Flyer**. Excerpts emphasize the saved details instead of navigation controls. User-corrected titles take precedence.

Suggestions also name a concrete next step: **Draft lunch checklist**, **Draft review checklist**, **Prepare event checklist**, **Review route details**, or **Extract key points**. Meal copy can include the saved headcount, time and per-person budget; event copy includes its saved date and time. Accepting a suggestion passes a bounded task focus to TaskBroker, so the Agent fetches current evidence and drafts the corresponding checklist. Jev still determines whether an offer is appropriate; a `none` decision stays quiet.

Presentation is extractive and local: it adds no model requests and never rewrites the captured title, text, revision, or history. **About this memory → Saved title** preserves the original title; Agent APIs return that original evidence. Existing records gain the new presentation without a database migration or reanalysis. These bounded rules are not universal semantic extraction, fact reconciliation, or translation of user content.

## Capture, batches, and boundaries

Capture is event-driven, debounced for 900 ms, limited to selected apps on the unlocked primary display. Passwords, sensitive nodes, editable input, locked screens, and protected windows are excluded. The platform screenshot gate rejects secure windows; its buffer is immediately closed. Screenshots and videos are not stored.

A durable SQLite queue merges records for about **20 seconds**, at most **5 per request**, with at most one batch per **15 seconds** and **60 batches per hour**. Android scheduling may delay execution. Rate limits retain records; failures back off from 30 seconds to 30 minutes. Leases recover after 120 seconds. Late results cannot revive removed or corrected evidence.

The library screen searches by keyword; the Agent also gets on-device vector and graph retrieval (see 0.7.0 above). Records are kept forever unless a 1–30 day retention is chosen. Cross-app fact merging beyond the nightly graph, and multimodal memory, are not provided. Navigation text may also be captured; this is not universal semantic content extraction.

## Agent and build integration

The signature-protected provider exposes bounded memory_search / memory_get through the existing native phone tools. The Agent fetches current evidence by ID; the full database is never injected into the Harness. Records remain untrusted reference data. Query disablement, expiry, and deletion are enforced at read time. A suggestion task is read-only and carries no screenshot, unsent draft, or alternate credentials.

Use Assistant v59 with **this revision of the Broker** (including `memoryFocus`), and TakuSurfaces APK **v3 / 0.2.1** (wire protocol v2). The Memory APK alone cannot upgrade older Broker behavior.

```sh
bash system/memory/build.sh
bash system/memory/build-tests.sh
bash system/memory/test-host.sh   # host only: ROM native-library packaging contract + missing-library fallback
bash system/assistant/build.sh
bash system/surfaces/build.sh
```

Builds use the repository SDK/JDK configuration, pinned development signing material, and the existing checksum-pinned CommonMark dependencies. Native result rendering formats headings, emphasis and lists; raw HTML stays literal, remote images are not fetched, and model-generated links are inert. License notices are packaged in assets/licenses. The Memory APK has no INTERNET permission.

## What passed

In 0.5.0, actual Messaging and Calendar screens were captured with analysis off, then classified by the existing real Jev model. UI acceptance completed two English read-only Agent tasks with verified `memory_get`: **meal_checklist** (organize, confidence 1.00) and **event_prep** (organize, confidence 0.97). The calendar case used the expanded system hint. English → Chinese → English switching preserved the 32 retained records and made no model requests. The three earlier daily-app records, including their histories, remained exactly unchanged. **179 isolated assertions passed: 66 storage, 69 queue, 8 result rendering, 36 bilingual presentation.** See [current acceptance](../../docs/reviews/taku-memory-content-2026-10-22/README.en.md) for hashes, screenshots and exact boundaries.

The preceding 0.4.0 daily-app baseline remains useful:

The dedicated API 36 ARM64 emulator used actual **Android Messaging**, **Fossify Calendar**, and **Organic Maps**, with prepared local demo content. The apps are genuine Android applications, not fixture screens or browser pages. Messages were received through the emulator SMS interface; no messages were sent to people. The local event has no invitations or reminders. The map app computed a real walking route using official offline maps.

| Case | Real Jev outcome | Proactive / Agent outcome |
| --- | --- | --- |
| Chat arrangements | Work, organize, confidence 0.98 | Actual UI acceptance; TaskBroker completed; successful memory_get |
| Calendar commitments | Work, organize, confidence 0.95 | Actual UI acceptance; TaskBroker completed; successful memory_get |
| Walking route | Travel, none, confidence 0.69 | Quiet outcome; no hint or Agent task |

The baseline retained 30 records. Its quiet route decision is preserved in 0.5.0, with no new offer or Agent task. The device script separates capture, classify, finish, and no-offer stages and requires explicit live-model opt-in. It does not inject memories, model answers, or task acceptance. Set `TAKU_MEMORY_DAILY_OUT` to keep a new run separate, and use `--expected-focus meal_checklist` or `--expected-focus event_prep` in the finish stage to verify the requested task matches the button.

**Scope:** emulator component acceptance only. Same-version physical-device acceptance and full-ROM acceptance remain pending. The standalone Memory APK is not yet wired into the product ROM build. Recovery from this emulator's unexpected exit demonstrates retained local state, not general cold-boot, power, or production reliability.
