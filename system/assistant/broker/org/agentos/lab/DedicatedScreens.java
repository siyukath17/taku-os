package org.agentos.lab;

import org.json.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Dedicated screens: every session that uses the phone gets a background screen of its own, and
 * holds the Apps it uses, one holder per App. The only door: phone tools, Codex events and UI
 * requests all come through here. The ledger (one entry per screen) and the waiting room live in
 * memory only.
 */
final class DedicatedScreens implements PhoneExecutionScreens.Ledger {
    private final java.util.concurrent.locks.ReentrantLock stateLock = new java.util.concurrent.locks.ReentrantLock();
    private final java.util.concurrent.locks.Condition changed = stateLock.newCondition();
    CleanupWork.Held lock() { return CleanupWork.lock(stateLock); }
    boolean lockHeld() { return stateLock.isHeldByCurrentThread(); }

    static final int MAX_APPS_PER_REQUEST = 5;

    /** Requests that may wait for one App at a time. */
    static final int MAX_WAITING_PER_APP = 3;

    /**
     * From this many QUEUE_FULL refusals in a row, the session is told to give up and tell the
     * user, rather than keep retrying.
     */
    static final int GIVE_UP_AFTER = 5;

    /** The user's own screen (Display.DEFAULT_DISPLAY). */
    static final int MAIN_DISPLAY = 0;

    /**
     * A card keeps the session's Apps this long before they can be handed over, so a quick answer
     * costs no page; an empty screen can be taken only this long after its last App went, so a
     * session that releases one App before requesting the next keeps its screen.
     */
    static final long HOLD_MS = 30_000;

    /**
     * An idle screen is closed after this long: each one keeps a display and a process alive, and a
     * new one starts in about a second.
     */
    static final long IDLE_MS = 2 * 60 * 60 * 1000L;

    /** A failed turn-end close retains its resources and retries on a worker after this delay. */
    static final long CLEANUP_RETRY_MS = 30_000;

    /** emptySince until the session first lets its last App go. */
    private static final long NEVER = Long.MAX_VALUE;

    private final PhoneExecutionScreens.Host host;
    private final PhoneTasks tasks;
    private final Clock clock;
    private final PhoneSubAgents.Rpc codex;

    /** The system language ("zh", "en", ...), for the waitReason sentences. */
    private final Supplier<String> systemLanguage;

    /**
     * Where failures nobody waits for are written: Android's log on the device (the broker's own
     * output goes nowhere), standard error in host tests.
     */
    private final Consumer<String> warn;

    /** Cards the user has not answered yet, by session. */
    private final Map<String, Question> questions = new HashMap<>();

    private final List<DedicatedScreen> ledger = new ArrayList<>();

    /**
     * The waiting room: requests not granted yet, in arrival order, at most one per session. Only
     * grantReady hands Apps out.
     */
    private final List<Ticket> waiting = new ArrayList<>();

    /** Main sessions looked up so far, by session: who spawned a session never changes. */
    private final Map<String, String> mainSessions = new HashMap<>();

    /** Immutable spawn ancestry, recorded before allocation so deletion cannot erase it. */
    private final Map<String, List<String>> sessionFamilies = new HashMap<>();
    private final Map<String, Long> cacheTimes = new LinkedHashMap<>();
    private final Map<String, Integer> cacheRequests = new HashMap<>();
    private static final long CACHE_CHECK_MS = 60 * 60 * 1000L, CACHE_DAY_MS = 24 * CACHE_CHECK_MS;
    private volatile java.util.function.Predicate<String> removalFence = session -> false;
    private final Set<String> endingSessions = new HashSet<>();

    /** For a closed sub-agent: its parent, and what the parent is told when it wakes it. */
    record ParentNote(String parent, String text) {}

    record RevokedScreen(
            String thread, String key, String generation, int userId,
            String taskTitle, List<String> packages, boolean userControlled) {}

    /**
     * Sub-agents whose screen the user closed; every turn they start is refused. Cleared when their
     * main session is archived or deleted.
     */
    private final Map<String, ParentNote> closedSubAgents = new HashMap<>();

    /** QUEUE_FULL refusals in a row, per session; cleared once it gets in line or its turn ends. */
    private final Map<String, Integer> refusals = new HashMap<>();

    /** New screens being started outside the table lock; they count toward the limit. */
    private int opening;

    /**
     * How many times Codex has gone away. A new screen started before the latest time closes once
     * it is up: the session that asked for it is gone.
     */
    private int codexRestarts;

    /** How long a screen stays idle before it is closed; scripts shorten it. */
    private volatile long idleMs = IDLE_MS;

    /** What a screen tool does: look (screenshot), act (tap, swipe, type), or open a held App. */
    enum Use {
        LOOK,
        ACT,
        OPEN
    }

    /**
     * One dedicated screen in the ledger: which session uses it, what it holds, who controls it.
     */
    private static final class DedicatedScreen implements PhoneExecutionScreens.Lease {
        final String key, generation;
        final int display, userId;

        /**
         * Held while a tool works on the screen; a release waits for it, so a task is never removed
         * in the middle of a step.
         */
        final ReentrantLock input = new ReentrantLock();

        /** The session using the screen; null while idle. */
        String session;

        /** One allocation to a session, even when its next turn reuses this physical screen. */
        Object occupancy;

        /** A close invalidates the allocation before interrupting its turn; restore on failure. */
        Object closingOccupancy;

        /**
         * The session's turn ended; its tasks are being removed. Neither the screen nor its Apps
         * are handed out yet.
         */
        boolean releasing;

        /** Persists the ownership evidence independently of the invalidated ticket marker. */
        String allocationId = "";

        /** Task cleanup and physical close both failed; keep the entry until close confirms. */
        String cleanupError = "";

        final Set<String> apps = new LinkedHashSet<>();

        /**
         * Apps handed over while the session's card waits: held by nobody, and handed out only once
         * the screen's tasks are gone. Android moves an App's task to the screen that opens it, so
         * handing an App out earlier would move this session's page to the next holder.
         */
        final Set<String> handing = new LinkedHashSet<>();

        /** When the session let its last App go (clock time); NEVER until it does. */
        long emptySince = NEVER;

        /** The name the user sees for each App granted on this screen, by package. */
        final Map<String, String> labels = new HashMap<>();

        /** What the session said the screen is doing, from its latest request (shown in the UI). */
        String taskTitle = "";

        /** Held for a whole user control action (take over, pause, give back). */
        final ReentrantLock control = new ReentrantLock();

        /** agent, user (taken over) or paused. */
        String controlOwner = "agent";

        /** Counts every control change; a user's tap or a give-back names the one it saw. */
        long controlRevision;

        /** A pause has started; its control change is not made yet. */
        boolean stopping;

        /** The pause under way is the user closing the screen: refusals say that instead. */
        boolean closing;

        /**
         * Taken over or paused since the broker last let a turn in. Until then only a turn the
         * broker starts (or lets in, for a sub-agent) runs, and a turn that ends releases nothing:
         * it is the one the takeover stopped, or one refused since.
         */
        boolean resumeRequired;

        /** Why the last control change failed, for the UI. */
        String lastError = "";

        /**
         * How many times the screen has gone idle. The timer set when it goes idle closes it only
         * if this has not changed, so a screen taken and freed again meanwhile gets its full time.
         */
        int timesIdle;

        DedicatedScreen(String key, String generation, int display, int userId) {
            this.key = key;
            this.generation = generation;
            this.display = display;
            this.userId = userId;
        }

        /** What screen tools act on: the screen's key, generation and display. */
        public PhoneExecutionScreens.Screen screenHandle() {
            return new PhoneExecutionScreens.Screen(key, generation, display, false, "", userId);
        }

        public ReentrantLock controlLock() {
            return control;
        }

        public ReentrantLock inputLock() {
            return input;
        }
    }

    /** A request_apps call not granted yet. */
    private static final class Ticket {
        final String session, taskTitle;

        /** Package to label, in request order. */
        final Map<String, String> apps;

        /** The session's screen; null while a new one is being started. */
        DedicatedScreen screen;

        /** The screen occupancy in which this ticket was granted, never the host generation. */
        Object occupancy;

        boolean granted, withdrawn;
        Exception failure;

        /** codexRestarts when its new screen started. */
        int codexRestarts;

        /** Set when the request is sent back: the held Apps a newcomer wants. */
        List<String> wanted;

        Ticket(String session, Map<String, String> apps, String taskTitle) {
            this.session = session;
            this.apps = apps;
            this.taskTitle = taskTitle;
        }
    }

    /** A card (ask_user, confirm_action) waiting for the user. */
    private static final class Question {
        /** text: the card's question or title, for the UI list; returnTo: where the session was. */
        final String text, returnTo;

        /** Its 30 seconds are up: the Apps go once somebody waits for one. */
        boolean due;

        /** The Apps handed over meanwhile; null while none were. */
        List<String> handedOver;

        Question(String text, String returnTo) {
            this.text = text;
            this.returnTo = returnTo;
        }
    }

    /** Time and delayed callbacks. */
    interface Clock {
        long now();

        void after(long ms, Runnable run);
    }

    /** The broker builds its one instance in DedicatedScreensOnDevice; host tests pass fakes. */
    DedicatedScreens(
            PhoneExecutionScreens.Host host,
            PhoneTasks tasks,
            Clock clock,
            PhoneSubAgents.Rpc codex,
            Supplier<String> systemLanguage,
            Consumer<String> warn) {
        this.host = host;
        this.tasks = tasks;
        this.clock = clock;
        this.codex = codex;
        this.systemLanguage = systemLanguage;
        this.warn = warn;
        clock.after(CACHE_CHECK_MS, this::maintainCaches);
    }

    private void maintainCaches() {
        try { sweepCaches(); }
        finally { clock.after(CACHE_CHECK_MS, this::maintainCaches); }
    }

    /** Both maps belong to one session entry. Bytes are a conservative string/object budget. */
    private long cacheBytes(String session) {
        long bytes = 192L + 2L * session.length();
        String main = mainSessions.get(session);
        if (main != null) bytes += 64L + 2L * main.length();
        for (String ancestor : sessionFamilies.getOrDefault(session, List.of()))
            bytes += 48L + 2L * ancestor.length();
        return bytes;
    }

    private boolean cacheProtected(String session) {
        if (cacheRequests.containsKey(session) || endingSessions.contains(session) || questions.containsKey(session)) return true;
        for (DedicatedScreen screen : ledger) if (session.equals(screen.session)) return true;
        for (Ticket ticket : waiting) if (!ticket.withdrawn && session.equals(ticket.session)) return true;
        if (removalFence.test(session)) return true;
        for (String ancestor : sessionFamilies.getOrDefault(session, List.of()))
            if (removalFence.test(ancestor)) return true;
        return false;
    }

    /** Called with the ledger lock, including when the cache is populated outside admission. */
    private void cached(String session) {
        cacheTimes.putIfAbsent(session, clock.now());
        sweepCaches();
    }

    private void sweepCaches() {
        try (CleanupWork.Held held = lock()) {
            List<String> eligible = new ArrayList<>();
            long bytes = 0, now = clock.now();
            for (String session : cacheTimes.keySet()) if (!cacheProtected(session)) {
                eligible.add(session); bytes += cacheBytes(session);
            }
            eligible.sort(Comparator.comparingLong(cacheTimes::get));
            if (eligible.isEmpty()) return;
            if (eligible.size() < 200 && bytes < 2_000_000 && now - cacheTimes.get(eligible.get(0)) < 7 * CACHE_DAY_MS) return;
            int remaining = eligible.size();
            for (String session : eligible) {
                if (remaining <= 120 && bytes <= 1_200_000 && now - cacheTimes.get(session) < 5 * CACHE_DAY_MS) break;
                bytes -= cacheBytes(session); remaining--;
                cacheTimes.remove(session); sessionFamilies.remove(session); mainSessions.remove(session);
            }
        }
    }

    /** The session's screen with this key and generation, for a user control action. */
    public PhoneExecutionScreens.Lease exact(
            String session, String key, String generation) throws Exception { try (CleanupWork.Held held1 = lock()) {
        return exactScreen(session, key, generation);
    } }

    private DedicatedScreen exactScreen(String session, String key, String generation)
            throws IOException {
        PhoneExecutionScreens.thread(session);
        DedicatedScreen screen = own(session);
        if (screen == null) throw new IOException("SCREEN_NOT_BOUND");
        if (!screen.key.equals(key) || !screen.generation.equals(generation))
            throw new IOException("SCREEN_OWNER_MISMATCH");
        return screen;
    }

    /**
     * The session's screen with its control fields, from the ledger alone: checked right before a
     * turn is sent, on the app-server pump, so it neither asks Android nor Codex.
     */
    public JSONObject describe(String session) throws Exception { try (CleanupWork.Held held2 = lock()) {
        DedicatedScreen screen = own(session);
        if (screen == null)
            for (DedicatedScreen candidate : ledger)
                if (session.equals(candidate.session) && !candidate.cleanupError.isEmpty()) {
                    screen = candidate;
                    break;
                }
        return screen == null ? new JSONObject().put("bound", false) : item(screen);
    } }

    /** Policy writes and App grants share the ledger lock. Work that waits runs outside it. */
    List<RevokedScreen> configurePolicy(JSONObject patch) throws Exception { try (CleanupWork.Held held3 = lock()) {
        JSONObject policy = WorkspacePolicy.configure(patch);
        if (!patch.has("blockedPackages")) return List.of();
        grantReady();
        Set<String> blocked = WorkspacePolicy.blockedPackages(policy);
        List<RevokedScreen> affected = new ArrayList<>();
        for (DedicatedScreen screen : ledger) {
            if (screen.session == null || screen.releasing) continue;
            RevokedScreen revoked = revokedScreen(screen, blocked);
            if (revoked != null) affected.add(revoked);
        }
        return affected;
    } }

    private static RevokedScreen revokedScreen(DedicatedScreen screen, Set<String> blocked) {
        List<String> packages = new ArrayList<>(screen.apps);
        packages.retainAll(blocked);
        if (packages.isEmpty()) return null;
        return new RevokedScreen(screen.session, screen.key, screen.generation, screen.userId,
                screen.taskTitle, List.copyOf(packages), screen.controlOwner.equals("user"));
    }

    /** Recheck after acquiring the control lock. An intervening takeover keeps the user's screen. */
    RevokedScreen beginRevocation(RevokedScreen target) throws Exception { try (CleanupWork.Held held4 = lock()) {
        DedicatedScreen screen = own(target.thread());
        if (screen == null || !screen.key.equals(target.key())
                || !screen.generation.equals(target.generation())) return null;
        RevokedScreen revoked = revokedScreen(
                screen, WorkspacePolicy.blockedPackages(WorkspacePolicy.snapshot()));
        return revoked;
    } }

    /** Revoke Apps, not the turn or screen. The caller holds the screen's control lock. */
    JSONObject releaseRevoked(RevokedScreen target, PhoneExecutionScreens.Action quiesce)
            throws Exception {
        DedicatedScreen screen;
        try (CleanupWork.Held held20 = lock()) {
            screen = own(target.thread());
            if (screen == null || !screen.key.equals(target.key())
                    || !screen.generation.equals(target.generation()))
                return revocationState(target, List.of()).put("status", "superseded");
        }
        if (!screen.input.tryLock(30, TimeUnit.SECONDS))
            throw new IOException("SCREEN_INPUT_NOT_QUIESCENT");
        try {
            RevokedScreen current = beginRevocation(target);
            if (current == null)
                return revocationState(target, List.of()).put("status", "superseded");
            if (current.userControlled())
                return revocationState(target, List.of()).put("status", "retained");
            validate(screen);
            quiesce.run(screen.screenHandle());
            validate(screen);
            // Policy and turn ownership may have changed while input was being drained.
            current = beginRevocation(target);
            if (current == null)
                return revocationState(target, List.of()).put("status", "superseded");
            if (current.userControlled())
                return revocationState(target, List.of()).put("status", "retained");
            JSONObject result = release(screen, current.packages());
            List<String> released = new ArrayList<>();
            JSONArray packages = result.getJSONArray("released");
            for (int i = 0; i < packages.length(); i++) released.add(packages.getString(i));
            return revocationState(target, released)
                    .put("packageNames", new JSONArray(current.packages()))
                    .put("status", released.isEmpty() ? "superseded" : "released");
        } finally {
            screen.input.unlock();
        }
    }

    /** Ownership at receipt publication, including when cleanup failed or the screen moved on. */
    JSONObject revocationState(RevokedScreen target, List<String> released)
            throws Exception { try (CleanupWork.Held held5 = lock()) {
        DedicatedScreen screen = null;
        for (DedicatedScreen candidate : ledger)
            if (target.thread().equals(candidate.session) && candidate.key.equals(target.key())
                    && candidate.generation.equals(target.generation())) screen = candidate;
        // A turn finishing concurrently still holds its Apps until finishTurn completes cleanup.
        return released(released, screen == null ? List.of() : screen.apps)
                .put("screenRetained", screen != null);
    } }

    /** The screen process is still the one in the ledger. */
    public void validate(PhoneExecutionScreens.Lease lease) throws Exception {
        DedicatedScreen screen = (DedicatedScreen) lease;
        PhoneExecutionScreens.Screen live =
                PhoneExecutionScreens.descriptor(
                        screen.key, host.status(screen.key), false);
        if (live.displayId != screen.display
                || !live.generation.equals(screen.generation)
                || host.currentUser() != screen.userId)
            throw new IOException("SCREEN_GENERATION_CHANGED");
    }

    /**
     * A takeover or pause starts: the agent loses control at once. The session keeps its screen and
     * Apps (the user only borrows them), and gives up what it was still waiting for: its request in
     * line and its card.
     */
    public void beginPause(String session, String key, String generation)
            throws Exception { try (CleanupWork.Held held6 = lock()) {
        DedicatedScreen screen = exactScreen(session, key, generation);
        screen.controlOwner = "paused";
        screen.controlRevision++;
        screen.stopping = true;
        screen.closing = false;
        screen.resumeRequired = true;
        screen.lastError = "";
        questions.remove(session);
        Ticket ticket = pending(session);
        if (ticket != null) withdraw(ticket);
    } }

    void beginReturn(String session, String key, String generation) throws Exception { try (CleanupWork.Held held7 = lock()) {
        requireAppsAllowed(exactScreen(session, key, generation));
        beginPause(session, key, generation);
    } }

    /**
     * The user starts closing the screen: as for a pause, except the session is told its screen is
     * closing. close follows once the session has stopped.
     */
    public void beginClose(String session, String key, String generation)
            throws Exception { try (CleanupWork.Held held8 = lock()) {
        beginPause(session, key, generation);
        DedicatedScreen screen = exactScreen(session, key, generation);
        screen.closing = true;
        screen.closingOccupancy = screen.occupancy;
        screen.occupancy = null;
    } }

    /**
     * The control change, once the session has stopped: owner agent (given back), user or paused.
     */
    public JSONObject finishControl(String session, String key, String generation, String owner)
            throws Exception {
        if (!Set.of("agent", "user", "paused").contains(owner))
            throw new IOException("INVALID_SCREEN_CONTROL_OWNER");
        DedicatedScreen screen;
        try (CleanupWork.Held held21 = lock()) {
            screen = exactScreen(session, key, generation);
        }
        if (!screen.input.tryLock(2, TimeUnit.SECONDS))
            throw new IOException("SCREEN_INPUT_NOT_QUIESCENT");
        try {
            validate(screen);
            try (CleanupWork.Held held22 = lock()) {
                if (exactScreen(session, key, generation) != screen)
                    throw new IOException("SCREEN_BINDING_CHANGED");
                if (owner.equals("agent")) requireAppsAllowed(screen);
                screen.controlOwner = owner;
                screen.controlRevision++;
                screen.stopping = false;
                screen.resumeRequired = true;
                screen.lastError = "";
                return item(screen);
            }
        } finally {
            screen.input.unlock();
        }
    }

    /**
     * The session could not be stopped. Paused rather than left stopping: a give-back stops it
     * again before handing control back.
     */
    public void controlFailed(
            String session, String key, String generation, String error) throws Exception { try (CleanupWork.Held held9 = lock()) {
        DedicatedScreen screen = exactScreen(session, key, generation);
        if (screen.closing) {
            screen.occupancy = screen.closingOccupancy;
            screen.closingOccupancy = null;
        }
        screen.controlOwner = "paused";
        screen.controlRevision++;
        screen.stopping = false;
        screen.closing = false;
        screen.resumeRequired = true;
        screen.lastError = error;
    } }

    /** The user's own tap, swipe or back key while the screen is taken over. */
    public JSONObject userInput(
            String session,
            String key,
            String generation,
            long controlRevision,
            PhoneExecutionScreens.Action action)
            throws Exception {
        DedicatedScreen screen;
        try (CleanupWork.Held held23 = lock()) {
            screen = exactScreen(session, key, generation);
        }
        screen.input.lockInterruptibly();
        try {
            try (CleanupWork.Held held24 = lock()) {
                if (exactScreen(session, key, generation) != screen
                        || screen.stopping
                        || !screen.controlOwner.equals("user"))
                    throw new IOException("SCREEN_USER_CONTROL_REQUIRED");
                if (screen.controlRevision != controlRevision)
                    throw new IOException("SCREEN_CONTROL_CHANGED");
            }
            validate(screen);
            return action.run(screen.screenHandle());
        } finally {
            screen.input.unlock();
        }
    }

    /**
     * A sub-agent's screen was given back. Its parent wakes it inside Codex, so the broker does not
     * start that turn: let it in.
     */
    public void resumeGranted(String session, String key, String generation)
            throws Exception { try (CleanupWork.Held held10 = lock()) {
        exactScreen(session, key, generation).resumeRequired = false;
    } }

    /** The session may run a turn: its screen, if it has one, is under the agent's control. */
    public boolean agentAdmitted(String session) { try (CleanupWork.Held held11 = lock()) {
        if (sessionUnavailable(session)) return false;
        DedicatedScreen screen = own(session);
        if (screen == null) return true;
        if (!agentControls(screen)) return false;
        try {
            requireAppsAllowed(screen);
            return true;
        } catch (Exception blocked) {
            return false;
        }
    } }

    /** Policy covers every held App, including Apps that are not currently on top. */
    private void requireAppsAllowed(DedicatedScreen screen) throws Exception {
        for (String pkg : screen.apps) WorkspacePolicy.requireAppAllowed(pkg);
    }

    void requireAppsAllowed(String session, String key, String generation)
            throws Exception { try (CleanupWork.Held held12 = lock()) {
        requireAppsAllowed(exactScreen(session, key, generation));
    } }

    /** For a turn the broker queues: what its screen looked like, checked again when it starts. */
    public PhoneExecutionScreens.Admission admission(String session) throws Exception { try (CleanupWork.Held held13 = lock()) {
        PhoneExecutionScreens.thread(session);
        if (!agentAdmitted(session)) throw new IOException("SCREEN_AGENT_PAUSED");
        DedicatedScreen screen = own(session);
        return screen == null
                ? new PhoneExecutionScreens.Admission(session, "", "", -1)
                : new PhoneExecutionScreens.Admission(
                        session, screen.key, screen.generation, screen.controlRevision);
    } }

    /**
     * A turn queued under this grant may still run: its screen has not changed control since. A
     * screen released or taken meanwhile does not count: screens come and go with turns.
     */
    public boolean admissionCurrent(PhoneExecutionScreens.Admission grant) { try (CleanupWork.Held held14 = lock()) {
        if (grant == null || !agentAdmitted(grant.thread)) return false;
        DedicatedScreen screen = own(grant.thread);
        return screen == null
                || !screen.key.equals(grant.key)
                || screen.controlRevision == grant.revision;
    } }

    /**
     * A turn of the session started; false refuses it. grant: from admission when the broker queued
     * it, null for one it did not (a sub-agent's, one Codex queued). After a takeover only a turn
     * with a grant, or one let in, runs; it ends the takeover.
     */
    public boolean turnStarted(String session, PhoneExecutionScreens.Admission grant) { try (CleanupWork.Held held15 = lock()) {
        if (closedSubAgents.containsKey(session)) return false;
        if (grant != null ? !admissionCurrent(grant) : !agentAdmitted(session)) return false;
        DedicatedScreen screen = own(session);
        if (screen != null && screen.resumeRequired) {
            if (grant == null) return false;
            screen.resumeRequired = false;
        }
        return true;
    } }

    /** Nobody has taken the screen over or paused it. */
    private static boolean agentControls(DedicatedScreen screen) {
        return screen.controlOwner.equals("agent") && !screen.stopping;
    }

    /** The refusal for a screen tool while the user has the screen, or is closing it. */
    private static IOException agentPaused(DedicatedScreen screen) {
        if (screen.closing)
            return new IOException(
                    "SCREEN_CLOSED: The user closed your screen; this turn is being stopped.");
        return new IOException(
                "SCREEN_AGENT_PAUSED: The user has taken over your screen; you resume when they"
                        + " give it back.");
    }

    /**
     * The session shows a card (ask_user, confirm_action) and waits for the user. Its Apps stay its
     * own for 30 seconds, then are handed over once somebody waits for one: the user may take long
     * to answer, and the others should not wait that long.
     */
    void asked(String session, String text, String returnTo) {
        Question q = new Question(text, returnTo);
        try (CleanupWork.Held held25 = lock()) {
            questions.put(session, q);
        }
        clock.after(
                HOLD_MS,
                () -> {
                    try (CleanupWork.Held held26 = lock()) {
                        // answered meanwhile, or this timer is an earlier card's
                        if (questions.get(session) != q) return;
                        q.due = true;
                        handOverIfWanted(session);
                    }
                });
    }

    /**
     * The session's card is answered or gone. Returns what the answer adds when its Apps were
     * handed over meanwhile, else null. Under the table lock, where the hand-over is decided too,
     * so either it was handed over and the answer says so, or it was answered first and kept.
     */
    JSONObject questionEnded(String session) throws Exception { try (CleanupWork.Held held16 = lock()) {
        Question q = questions.remove(session);
        if (q == null || q.handedOver == null) return null;
        return new JSONObject()
                .put("packageNames", new JSONArray(q.handedOver))
                .put("returnTo", q.returnTo)
                .put(
                        "advice",
                        "Your Apps went to a session waiting for them while the user decided. The"
                                + " answer stands: request_apps them, get back to returnTo, check"
                                + " its details match, then act on the answer.");
    } }

    /**
     * Hands all of a waiting session's Apps over once its 30 seconds are up and somebody waits for
     * one: out of its hands at once, out to others when its screen is cleared. The screen stays its
     * own.
     */
    private void handOverIfWanted(String session) {
        Question q = questions.get(session);
        DedicatedScreen screen = own(session);
        if (q == null || !q.due || screen == null || screen.apps.isEmpty()) return;
        boolean wanted = false;
        for (Ticket t : waiting)
            wanted |= !t.session.equals(session) && !wants(t, screen.apps).isEmpty();
        if (!wanted) return;
        q.handedOver = new ArrayList<>(screen.apps);
        screen.handing.addAll(screen.apps);
        screen.apps.clear();
        Thread clear =
                new Thread(
                        () -> handOver(screen, session),
                        "dedicated-screens-hand-over " + screen.key + " " + session);
        clear.setDaemon(true);
        clear.start();
    }

    /** Removes every task on the screen after the step in flight, then hands the Apps out. */
    private void handOver(DedicatedScreen screen, String session) {
        screen.input.lock();
        try {
            boolean mine;
            try (CleanupWork.Held held27 = lock()) {
                // its turn may have ended meanwhile: that release clears the screen instead
                mine = session.equals(screen.session) && !screen.releasing;
            }
            if (mine) closeTasks(screen, null);
        } catch (Exception error) {
            warn.accept("dedicated screen tasks not removed: " + error.getMessage());
        } finally {
            try (CleanupWork.Held held28 = lock()) {
                screen.handing.clear();
                if (session.equals(screen.session)) screen.emptySince = clock.now();
                grantReady();
            }
            screen.input.unlock();
        }
    }

    /**
     * request_apps: a screen for the session and all of the Apps, or none of them. apps: package to
     * label. Waits in line while another session holds one of them or an earlier request wants one:
     * first come, first served, so a request for several Apps is never overtaken. A session holding
     * Apps waits only while nobody waits for one of them, and is sent back once somebody does, so
     * holders never wait for each other and cannot deadlock.
     */
    JSONObject request(String session, Map<String, String> apps, String taskTitle)
            throws Exception {
        PhoneExecutionScreens.thread(session);
        try (CleanupWork.Held held = lock()) { cacheRequests.merge(session, 1, Integer::sum); }
        try { return requestPinned(session, apps, taskTitle); }
        finally {
            try (CleanupWork.Held held = lock()) {
                cacheRequests.computeIfPresent(session, (key, count) -> count == 1 ? null : count - 1);
                sweepCaches();
            }
        }
    }

    private JSONObject requestPinned(String session, Map<String, String> apps, String taskTitle)
            throws Exception {
        if (apps.isEmpty() || apps.size() > MAX_APPS_PER_REQUEST)
            throw new IOException("INVALID_SCREEN_PACKAGE");
        for (String pkg : apps.keySet()) PhoneExecutionScreens.packageName(pkg);
        if (taskTitle == null || taskTitle.isBlank() || taskTitle.length() > 80)
            throw new IOException("INVALID_TASK_TITLE");
        family(session);
        Ticket ticket = new Ticket(session, new LinkedHashMap<>(apps), taskTitle);
        List<DedicatedScreen> retired = new ArrayList<>();
        try {
            try (CleanupWork.Held held29 = lock()) {
                if (sessionUnavailable(session)) throw new IOException("SCREEN_SESSION_REMOVING");
                JSONObject policy = WorkspacePolicy.snapshot();
                if (!policy.getBoolean("allowBackground"))
                    throw new IOException("BACKGROUND_WORK_DISABLED");
                Set<String> blocked = WorkspacePolicy.blockedPackages(policy);
                if (!Collections.disjoint(blocked, apps.keySet())) throw new IOException("APP_NOT_ALLOWED");
                if (pending(session) != null)
                    throw new IOException(
                            "REQUEST_PENDING: another request_apps call of yours is still pending.");
                ticket.screen = own(session);
                if (ticket.screen != null && !agentControls(ticket.screen))
                    throw agentPaused(ticket.screen);
                if (ticket.screen != null) {
                    if (!Collections.disjoint(blocked, ticket.screen.apps))
                        throw new IOException("APP_NOT_ALLOWED");
                    ticket.apps.keySet().removeAll(ticket.screen.apps);
                    Set<String> wanted = new LinkedHashSet<>();
                    for (Ticket t : waiting) wanted.addAll(wants(t, ticket.screen.apps));
                    if (!wanted.isEmpty()) throw heldAppWanted(wanted);
                }
                refuseIfQueueFull(session, ticket.apps.keySet());
                if (ticket.screen == null) {
                    int max = policy.getInt("maxActiveScreens");
                    Iterator<DedicatedScreen> idle = ledger.iterator();
                    while (ledger.size() + opening > max && idle.hasNext()) {
                        DedicatedScreen screen = idle.next();
                        if (screen.session != null || screen.releasing) continue;
                        idle.remove();
                        screen.occupancy = null;
                        retired.add(screen);
                    }
                    int count = ledger.size() + opening;
                    if (count <= max) ticket.screen = idle(session);
                    if (ticket.screen == null && count == max) ticket.screen = reclaim(session);
                    if (ticket.screen == null && count >= max)
                        throw new IOException(
                                "SCREEN_LIMIT_REACHED: retry in 30 seconds, or tell the user the phone"
                                        + " is busy.");
                }
                if (ticket.screen == null) {
                    opening++;
                    ticket.codexRestarts = codexRestarts;
                }
                refusals.remove(session);
                // a holder waiting for more is sent back once the newcomer wants what it holds; one
                // granted meanwhile by sending another back keeps its Apps
                for (Ticket t : new ArrayList<>(waiting)) {
                    List<String> wanted =
                            t.screen == null || t.granted ? List.of() : wants(ticket, t.screen.apps);
                    if (wanted.isEmpty()) continue;
                    t.wanted = wanted;
                    withdraw(t);
                }
                waiting.add(ticket);
                for (String asking : new ArrayList<>(questions.keySet())) handOverIfWanted(asking);
                grantReady();
            }
        } finally {
            // The selection is final even if this acquisition fails. Close every selected host
            // outside the ledger lock, and defer cancellation until the whole batch is attempted.
            boolean interrupted = Thread.interrupted();
            try {
                for (DedicatedScreen screen : retired) {
                    try {
                        host.stop(screen.key, screen.generation);
                    } catch (Exception error) {
                        interrupted |= error instanceof InterruptedException;
                        warn.accept("excess idle dedicated screen " + screen.key + " "
                                + screen.generation + " not closed: " + error.getMessage());
                    } finally {
                        interrupted |= Thread.interrupted();
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        if (ticket.screen == null) start(ticket);
        return await(ticket);
    }

    /** The held Apps the ticket wants. */
    private static List<String> wants(Ticket ticket, Set<String> held) {
        List<String> out = new ArrayList<>();
        for (String pkg : held) if (ticket.apps.containsKey(pkg)) out.add(pkg);
        return out;
    }

    private static IOException heldAppWanted(Collection<String> apps) throws Exception {
        return new IOException(
                "HELD_APP_WANTED: "
                        + new JSONObject()
                                .put("packageNames", new JSONArray(apps))
                                .put(
                                        "advice",
                                        "You can queue for more Apps only while nobody waits for"
                                                + " one you hold. You still hold these."
                                                + " release_apps them when done, then request_apps"
                                                + " what the step needs."));
    }

    private void refuseIfQueueFull(String session, Set<String> apps) throws Exception {
        for (String pkg : apps) {
            int queued = 0;
            for (Ticket t : waiting) if (t.apps.containsKey(pkg)) queued++;
            if (queued < MAX_WAITING_PER_APP) continue;
            int attempt = refusals.merge(session, 1, Integer::sum);
            String advice =
                    attempt < GIVE_UP_AFTER
                            ? "Retry in 30 seconds."
                            : "Give up; tell the user this App is busy.";
            throw new IOException(
                    "QUEUE_FULL: "
                            + new JSONObject()
                                    .put("packageName", pkg)
                                    .put("attempt", attempt)
                                    .put("advice", advice));
        }
    }

    /**
     * Starts the ticket's new screen outside the table lock, so other sessions go on meanwhile. A
     * session stopped meanwhile leaves the screen idle for the next one.
     */
    private void start(Ticket ticket) throws Exception {
        String key = "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        DedicatedScreen screen = null;
        Exception failed = null;
        boolean cut = false;
        try {
            screen = fromHost(key, host.start(key));
        } catch (InterruptedException interrupted) {
            // The stop cut short the wait for the command to exit, after the screen was up
            // (TaskBroker.command reads the output to the end first): read the screen back.
            cut = true;
            screen = readBack(key);
        } catch (Exception error) {
            failed = error;
        }
        boolean stopped = cut || Thread.interrupted();
        boolean codexWentAway;
        try (CleanupWork.Held held30 = lock()) {
            opening--;
            codexWentAway = ticket.codexRestarts != codexRestarts;
            if (screen != null && !codexWentAway) ledger.add(screen);
            if (screen != null && !codexWentAway && !stopped && !ticket.withdrawn) {
                screen.session = ticket.session;
                screen.occupancy = new Object();
                screen.allocationId = UUID.randomUUID().toString();
                ticket.screen = screen;
            } else {
                waiting.remove(ticket);
                if (screen != null && !codexWentAway) wentIdle(screen);
            }
            grantReady();
        }
        if (screen != null && codexWentAway)
            try {
                host.stop(screen.key, screen.generation);
            } catch (Exception error) {
                warn.accept("dedicated screen not stopped: " + error.getMessage());
            }
        if (failed != null) throw failed;
        if (stopped) throw new InterruptedException();
    }

    private DedicatedScreen fromHost(String key, JSONObject info) throws Exception {
        PhoneExecutionScreens.Screen card =
                PhoneExecutionScreens.descriptor(key, info, false);
        return new DedicatedScreen(key, card.generation, card.displayId, host.currentUser());
    }

    /**
     * The screen behind key after a stop cut its start short, or null if it is not up. A stop
     * interrupts the tool thread twice (turn/interrupt, then turn/completed), so an interrupted
     * read is tried again.
     */
    private DedicatedScreen readBack(String key) {
        for (int attempt = 0; attempt < 3; attempt++)
            try {
                return fromHost(key, host.status(key));
            } catch (InterruptedException again) {
                // the same stop's second interrupt: ask once more
            } catch (Exception notUp) {
                return null;
            }
        return null;
    }

    /**
     * Waits until the ticket is granted or sent back. Stopped meanwhile (interrupted, or its turn
     * ended): out of line at once; Apps granted at that very moment are given back at once.
     */
    private JSONObject await(Ticket ticket) throws Exception { try (CleanupWork.Held held17 = lock()) {
        try {
            while (!ticket.granted && !ticket.withdrawn) changed.await();
            if (Thread.interrupted()) throw new InterruptedException();
        } catch (InterruptedException stopped) {
            withdraw(ticket);
            throw stopped;
        }
        if (ticket.failure != null) throw ticket.failure;
        if (ticket.granted && ticket.occupancy != ticket.screen.occupancy) throw new IOException("TURN_ENDED");
        if (ticket.wanted != null) throw heldAppWanted(ticket.wanted);
        if (!ticket.granted && ticket.screen != null && !agentControls(ticket.screen))
            throw agentPaused(ticket.screen);
        if (!ticket.granted) throw new IOException("TURN_ENDED");
        Set<String> blocked = WorkspacePolicy.blockedPackages(WorkspacePolicy.snapshot());
        if (!Collections.disjoint(blocked, ticket.apps.keySet())
                || !Collections.disjoint(blocked, ticket.screen.apps))
            throw new IOException("APP_NOT_ALLOWED");
        return granted(ticket.screen);
    } }

    private void withdraw(Ticket ticket) {
        if (ticket.granted && ticket.occupancy != null
                && ticket.occupancy == ticket.screen.occupancy)
            ticket.screen.apps.removeAll(ticket.apps.keySet());
        ticket.withdrawn = true;
        waiting.remove(ticket);
        grantReady();
    }

    /**
     * The only place Apps are handed out: walks the waiting room in arrival order and grants every
     * request whose Apps nobody holds and no earlier request wants, so a request for several Apps
     * is never overtaken. Called after every change.
     */
    private void grantReady() {
        if (waiting.isEmpty()) {
            changed.signalAll();
            return;
        }
        Set<String> blocked;
        try {
            blocked = WorkspacePolicy.blockedPackages(WorkspacePolicy.snapshot());
        } catch (Exception failure) {
            for (Ticket ticket : waiting) {
                ticket.failure = failure;
                ticket.withdrawn = true;
            }
            waiting.clear();
            changed.signalAll();
            return;
        }
        for (Iterator<Ticket> it = waiting.iterator(); it.hasNext(); ) {
            Ticket t = it.next();
            if (!Collections.disjoint(blocked, t.apps.keySet())
                    || (t.screen != null && !Collections.disjoint(blocked, t.screen.apps))) {
                t.failure = new IOException("APP_NOT_ALLOWED");
                t.withdrawn = true;
                it.remove();
                continue;
            }
            if (sessionUnavailable(t.session) || t.screen == null || blocker(t) != null) continue;
            t.screen.apps.addAll(t.apps.keySet());
            t.screen.labels.putAll(t.apps);
            t.screen.taskTitle = t.taskTitle;
            t.occupancy = t.screen.occupancy;
            t.granted = true;
            it.remove();
        }
        changed.signalAll();
    }

    /** The first of the ticket's Apps it cannot have yet, or null. */
    private String blocker(Ticket ticket) {
        for (String pkg : ticket.apps.keySet()) {
            if (ahead(ticket, pkg) > 0) return pkg;
            for (DedicatedScreen screen : ledger)
                if ((screen != ticket.screen && screen.apps.contains(pkg))
                        || screen.handing.contains(pkg)) return pkg;
        }
        return null;
    }

    /** Requests in line before the ticket that want pkg. */
    private int ahead(Ticket ticket, String pkg) {
        int n = 0;
        for (Ticket t : waiting) {
            if (t == ticket) break;
            if (t.apps.containsKey(pkg)) n++;
        }
        return n;
    }

    private static JSONObject granted(DedicatedScreen screen) throws Exception {
        return new JSONObject()
                .put("screenKey", screen.key)
                .put("generation", screen.generation)
                .put("displayId", screen.display)
                .put("heldApps", new JSONArray(screen.apps));
    }

    /** A first grant reads ancestry outside the ledger lock; subsequent calls reuse it. */
    private List<String> family(String session) throws Exception {
        try (CleanupWork.Held held31 = lock()) {
            List<String> known = sessionFamilies.get(session);
            if (known != null) return known;
        }
        List<String> chain = PhoneSubAgents.ancestors(codex, session);
        try (CleanupWork.Held held32 = lock()) {
            sessionFamilies.put(session, chain);
            cached(session);
        }
        return chain;
    }

    private boolean sessionUnavailable(String session) {
        if (endingSessions.contains(session)) return true;
        List<String> chain = sessionFamilies.getOrDefault(session, List.of(session));
        for (String member : chain) if (removalFence.test(member)) return true;
        if (removalFence.test(session)) return true;
        return false;
    }

    private Set<String> knownFamily(String root) {
        Set<String> sessions = new LinkedHashSet<>(List.of(root));
        for (Map.Entry<String, List<String>> entry : sessionFamilies.entrySet())
            if (entry.getValue().contains(root)) sessions.add(entry.getKey());
        return sessions;
    }

    void removalFence(java.util.function.Predicate<String> predicate) { removalFence = predicate; }

    /** Native thread metadata fences newly spawned descendants without RPC on the event pump. */
    void observeRemovalThread(JSONObject thread) throws Exception {
        String parent = PhoneSubAgents.parent(new JSONObject().put("thread", thread));
        if (parent.isEmpty()) return;
        String session = thread.getString("id");
        try (CleanupWork.Held held = lock()) {
            List<String> chain = new ArrayList<>(List.of(session));
            chain.addAll(sessionFamilies.getOrDefault(parent, List.of(parent)));
            for (String member : chain) if (removalFence.test(member)) {
                sessionFamilies.put(session, List.copyOf(chain)); cached(session); return;
            }
        }
    }

    Set<String> removalFamily(String root) {
        try (CleanupWork.Held held = lock()) { return knownFamily(root); }
    }

    /** Resource identity survives the destruction of upstream session metadata. */
    JSONArray removalResources(Set<String> sessions) throws Exception {
        try (CleanupWork.Held held = lock()) {
            JSONArray result = new JSONArray();
            for (DedicatedScreen screen : ledger) if (sessions.contains(screen.session))
                result.put(new JSONObject().put("session", screen.session).put("screenKey", screen.key)
                        .put("generation", screen.generation).put("allocationId", screen.allocationId)
                        .put("displayId", screen.display).put("userId", screen.userId)
                        .put("resourceId", screen.key + "/" + screen.generation + "/" + screen.allocationId));
            return result;
        }
    }

    void removalCancelled(String session) {
        try (CleanupWork.Held held = lock()) {
            questions.remove(session); refusals.remove(session);
            Ticket ticket = pending(session); if (ticket != null) withdraw(ticket);
        }
    }

    /** Runs inline in the bounded worker. A stale receipt can never touch a new occupancy. */
    void cleanupRemoval(JSONObject resource) throws Exception {
        DedicatedScreen found = null;
        try (CleanupWork.Held held = lock()) {
            for (DedicatedScreen screen : ledger) if (screen.key.equals(resource.getString("screenKey"))) {
                if (!screen.generation.equals(resource.getString("generation"))
                        || !Objects.equals(screen.session, resource.getString("session"))
                        || !screen.allocationId.equals(resource.getString("allocationId"))) return;
                // Keep the exact allocation even when an earlier cleanup left it releasing.
                removalCancelled(screen.session);
                screen.releasing = true;
                screen.occupancy = null;
                found = screen;
                break;
            }
        }
        if (found != null) {
            if (!finishTurn(found, resource.getString("allocationId"))) throw new IOException("SCREEN_CLEANUP_FAILED");
        } else {
            // Broker restart: there is no ledger entry, but the durable key/generation remains.
            String key = resource.getString("screenKey"), generation = resource.getString("generation");
            String state = host.removalState(key, generation);
            if (state.equals("current")) host.stopRemoval(key, generation);
            else if (!state.equals("gone") && !state.equals("replaced")) throw new IOException("SCREEN_HOST_UNVERIFIED");
        }
    }

    void forgetRemoved(Set<String> sessions) {
        try (CleanupWork.Held held = lock()) {
            sessionFamilies.entrySet().removeIf(entry -> !Collections.disjoint(entry.getValue(), sessions));
            mainSessions.keySet().removeAll(sessions);
            cacheTimes.keySet().removeIf(session -> !sessionFamilies.containsKey(session) && !mainSessions.containsKey(session));
            closedSubAgents.keySet().removeAll(sessions); questions.keySet().removeAll(sessions);
            refusals.keySet().removeAll(sessions);
        }
    }

    /**
     * A screen tool for the session: only on its own screen, never the default one. LOOK needs the
     * screen; ACT needs an App held; OPEN needs that App held. ACT and OPEN also need the App on
     * top of the screen to be held (an in-App jump may have opened another one).
     *
     * <p>The same App open on the main screen or in Recents never blocks the session (the user's
     * decision: the agent may take an App over at any time). OPEN lands the App on the session's
     * screen: see {@link #settleOpen}.
     */
    JSONObject withScreen(String session, Use use, String pkg, PhoneExecutionScreens.Action action)
            throws Exception {
        DedicatedScreen screen;
        try (CleanupWork.Held held33 = lock()) {
            screen = allowed(session, use, pkg);
        }
        screen.input.lockInterruptibly();
        try {
            List<PhoneTasks.Task> now = use == Use.LOOK ? List.of() : tasks.list();
            try (CleanupWork.Held held34 = lock()) {
                if (allowed(session, use, pkg) != screen)
                    throw new IOException(
                            "SCREEN_CHANGED: Your screen changed while this step waited, so it did"
                                    + " not run. observe_background, then retry it.");
                String top = topApps(now).get(screen.display);
                if (top != null && !screen.apps.contains(top))
                    throw new IOException(
                            "UNHELD_APP_ON_TOP: "
                                    + new JSONObject()
                                            .put("packageName", top)
                                            .put(
                                                    "advice",
                                                    "The App on top of your screen is not one you"
                                                            + " hold. request_apps it, then use it"
                                                            + " or release_apps it to close it."));
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            try {
                PhoneExecutionScreens.Screen handle = screen.screenHandle();
                JSONObject result = action.run(handle);
                if (use == Use.OPEN && pkg != null && !pkg.isEmpty())
                    result = settleOpen(screen, pkg, now, () -> action.run(handle), result);
                return result;
            } catch (InterruptedException stopped) {
                throw stopped;
            } catch (Exception failed) {
                // Asked only now: asking costs a phone-display run (about half a second)
                if (alive(screen)) throw failed;
                lost(screen);
                throw new IOException(
                        "SCREEN_LOST: Your screen crashed; its pages are gone and your Apps are"
                                + " released. request_apps again, open_app to start over, and redo"
                                + " only the steps that did not take effect.");
            }
        } finally {
            screen.input.unlock();
        }
    }

    /** After a failed step: the screen process is still the one in the ledger. */
    private boolean alive(DedicatedScreen screen) throws InterruptedException {
        try {
            validate(screen);
            return true;
        } catch (InterruptedException stopped) {
            throw stopped;
        } catch (Exception gone) {
            return false;
        }
    }

    /**
     * The screen process died, or could not be read: the screen closes as if its session had let it
     * go for good. A request the session made meanwhile is dropped, so nothing is granted on it.
     */
    private void lost(DedicatedScreen screen) {
        try (CleanupWork.Held held35 = lock()) {
            screen.releasing = true;
            screen.occupancy = null;
            Ticket ticket = pending(screen.session);
            if (ticket != null) withdraw(ticket);
        }
        closeScreen(screen);
    }

    /**
     * Closes a screen nobody may take any more (marked releasing): its tasks go after the step in
     * flight (closing a screen with tasks on it leaves empty entries in Recents), then its entry,
     * which frees its Apps, then its process.
     */
    private void closeScreen(DedicatedScreen screen) {
        boolean inLedger;
        screen.input.lock();
        try {
            closeTasks(screen, null);
        } catch (Exception error) {
            warn.accept("dedicated screen tasks not removed: " + error.getMessage());
        } finally {
            try (CleanupWork.Held held36 = lock()) {
                screen.occupancy = null;
                inLedger = ledger.remove(screen);
                grantReady();
            }
            screen.input.unlock();
        }
        // out of the ledger already: whoever took it out stops it
        if (!inLedger) return;
        try {
            host.stop(screen.key, screen.generation);
        } catch (Exception error) {
            warn.accept("dedicated screen not stopped: " + error.getMessage());
        }
    }

    /**
     * Never closed to make room: the home screen and Taku's own chat. Their tasks on the main screen
     * are the user's way back, so a takeover leaves them alone even if a session holds them.
     */
    static final Set<String> PROTECTED_PACKAGES =
            Set.of(
                    "org.agentos.assistant",
                    "org.agentos.agent",
                    "com.android.launcher3",
                    "com.google.android.apps.nexuslauncher");

    /** How long OPEN waits for Android to place the App when it was already on the main screen. */
    long openSettleMs = 2_000;

    interface Launch {
        JSONObject run() throws Exception;
    }

    /**
     * OPEN, after the launch (started with --activity-multiple-task, so a normal App gets a new
     * task on the session's screen and the user's copy stays put): the App must end up on the
     * session's screen. A singleTask/singleInstance App reuses its one task instead; if that task
     * stays on the main screen, it is closed and the App launched again, now on the session's
     * screen. The input lock is held. Nothing to settle when the App had no task on the main
     * screen and still has none.
     */
    private JSONObject settleOpen(
            DedicatedScreen screen,
            String pkg,
            List<PhoneTasks.Task> before,
            Launch launch,
            JSONObject result)
            throws Exception {
        boolean onMainBefore = false;
        for (PhoneTasks.Task t : before)
            if (t.displayId() == MAIN_DISPLAY && t.packageName().equals(pkg)) onMainBefore = true;
        for (int attempt = 0; ; attempt++) {
            List<PhoneTasks.Task> now = placed(screen, pkg, onMainBefore);
            List<Integer> onMain = new ArrayList<>();
            boolean onScreen = false;
            for (PhoneTasks.Task t : now) {
                if (!t.packageName().equals(pkg)) continue;
                if (t.displayId() == screen.display) onScreen = true;
                else if (t.displayId() == MAIN_DISPLAY) onMain.add(t.id());
            }
            if (onScreen || onMain.isEmpty()) return result;
            if (attempt > 0 || PROTECTED_PACKAGES.contains(pkg))
                throw new IOException(
                        "OPEN_LANDED_ON_MAIN: "
                                + new JSONObject()
                                        .put("packageName", pkg)
                                        .put(
                                                "advice",
                                                "Android kept this App on the main screen. Do not"
                                                        + " act in it; tell the user it cannot run"
                                                        + " in the background."));
            // a single-instance App reused the main screen's task: close it, launch again
            for (int id : onMain) tasks.remove(id);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            result = launch.run();
            if (result != null) result.put("takenOverFromMain", true);
        }
    }

    /**
     * The tasks once Android has placed pkg: on the session's screen at once if it is there, else
     * (only when it was on the main screen before, where a reused task shows up late) up to
     * openSettleMs.
     */
    private List<PhoneTasks.Task> placed(DedicatedScreen screen, String pkg, boolean wait)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(wait ? openSettleMs : 0);
        for (; ; ) {
            List<PhoneTasks.Task> now = tasks.list();
            for (PhoneTasks.Task t : now)
                if (t.packageName().equals(pkg) && t.displayId() == screen.display) return now;
            if (System.nanoTime() >= deadline) return now;
            Thread.sleep(150);
        }
    }

    /**
     * release_apps: waits for the step in flight, closes these Apps' tasks on the session's screen
     * (every task when the last App goes, so the screen is clean for whoever may take it), then
     * gives the Apps back. The screen stays with the session until its turn ends, unless taken
     * meanwhile. Apps it does not hold are ignored.
     */
    JSONObject release(String session, List<String> packages) throws Exception {
        PhoneExecutionScreens.thread(session);
        DedicatedScreen screen;
        try (CleanupWork.Held held37 = lock()) {
            screen = own(session);
        }
        List<String> released = new ArrayList<>();
        if (screen == null) return released(released, List.of());
        screen.input.lockInterruptibly();
        try {
            try (CleanupWork.Held held38 = lock()) {
                if (own(session) != screen) return released(released, List.of());
                if (!agentControls(screen)) throw agentPaused(screen);
            }
            return release(screen, packages);
        } finally {
            screen.input.unlock();
        }
    }

    /** Shared cleanup for voluntary release and policy revocation; the input lock is held. */
    private JSONObject release(DedicatedScreen screen, List<String> packages) throws Exception {
        List<String> released = new ArrayList<>();
        boolean last;
        try (CleanupWork.Held held39 = lock()) {
            for (String pkg : packages) if (screen.apps.contains(pkg)) released.add(pkg);
            if (released.isEmpty()) return released(released, screen.apps);
            last = released.containsAll(screen.apps);
        }
        closeTasks(screen, last ? null : released);
        try (CleanupWork.Held held40 = lock()) {
            screen.apps.removeAll(released);
            if (screen.apps.isEmpty()) screen.emptySince = clock.now();
            grantReady();
            return released(released, screen.apps);
        }
    }

    /**
     * The session's turn ended: its request still in line is withdrawn, everything it holds is
     * released and its screen becomes idle. Marks the screen at once, then removes every task on it
     * (including Apps opened by an in-App jump) on a thread of its own, after the step in flight;
     * the Apps and the screen are handed out only then. Returns that thread, or null when nothing
     * is released: the session had no screen, or the user has taken it over (the session keeps it).
     */
    Thread turnEnded(String session) {
        DedicatedScreen screen = leave(session, false);
        if (screen == null) return null;
        // Named after the screen (one release per screen at a time) and its session.
        Thread release =
                new Thread(
                        () -> finishTurn(screen),
                        "dedicated-screens-release " + screen.key + " " + session);
        release.setDaemon(true);
        release.start();
        return release;
    }

    /**
     * The session was archived or deleted. Neither it nor any session it spawned runs another turn,
     * so their screens and Apps are freed now, those the user has taken over too: there is nobody
     * left to give them back to. Asks Codex for the spawned sessions, so it runs on a thread of its
     * own, never on the app-server pump; returns that thread.
     */
    Thread sessionGone(String session) {
        Thread gone =
                new Thread(
                        () -> {
                            Set<String> sessions;
                            try (CleanupWork.Held held41 = lock()) { sessions = knownFamily(session); }
                            try {
                                sessions.addAll(PhoneSubAgents.gone(codex, session));
                            } catch (Exception unknown) {
                                // Saved ancestry still identifies every session that acquired a screen.
                                warn.accept(
                                        "sessions spawned by an archived session not listed: "
                                                + unknown.getMessage());
                            }
                            Thread cleanup;
                            try (CleanupWork.Held held42 = lock()) { cleanup = freeFamily(sessions); }
                            try { cleanup.join(); }
                            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                        },
                        "dedicated-screens-gone " + session);
        gone.setDaemon(true);
        gone.start();
        return gone;
    }

    /** The caller holds the ledger lock; retire every family member before granting freed Apps. */
    private Thread freeFamily(Collection<String> sessions) {
        endingSessions.addAll(sessions);
        closedSubAgents.keySet().removeAll(sessions);
        List<DedicatedScreen> freed = new ArrayList<>();
        try {
            for (String session : sessions) {
                DedicatedScreen screen = leave(session, true);
                if (screen != null) freed.add(screen);
            }
        } finally {
            endingSessions.removeAll(sessions);
            grantReady();
        }
        Thread cleanup = new Thread(() -> {
            for (DedicatedScreen screen : freed) finishTurn(screen);
        }, "dedicated-screens-family-cleanup");
        cleanup.setDaemon(true);
        cleanup.start();
        return cleanup;
    }

    /**
     * Codex went away (stopped or restarting): no turn running now ever ends, so nothing else would
     * free their screens. Every screen closes, those the user has taken over too; every request in
     * line fails and no card holds Apps any more. Out of everyone's reach at once, closed on a
     * thread of its own (the caller holds NativeBridge's lock); returns that thread. A screen
     * already releasing ends idle and closes in idleMs.
     */
    Thread codexGone() {
        List<DedicatedScreen> closing = new ArrayList<>();
        try (CleanupWork.Held held43 = lock()) {
            codexRestarts++;
            // none is granted what another one frees on the way out
            for (Ticket t : waiting) t.withdrawn = true;
            waiting.clear();
            questions.clear();
            refusals.clear();
            for (DedicatedScreen screen : ledger)
                if (!screen.releasing) {
                    screen.releasing = true;
                    screen.occupancy = null;
                    closing.add(screen);
                }
            changed.signalAll();
        }
        Thread close =
                new Thread(
                        () -> {
                            for (DedicatedScreen screen : closing) closeScreen(screen);
                        },
                        "dedicated-screens-codex-gone");
        close.setDaemon(true);
        close.start();
        return close;
    }

    /**
     * Takes the session out of line and off its card, and marks its screen releasing for
     * finishTurn. Returns that screen, or null when it has none, or when the user has the screen
     * and evenTakenOver is false: the session keeps a screen it lent to the user.
     */
    private DedicatedScreen leave(String session, boolean evenTakenOver) { try (CleanupWork.Held held18 = lock()) {
        refusals.remove(session);
        questions.remove(session);
        Ticket ticket = pending(session);
        if (ticket != null) withdraw(ticket);
        DedicatedScreen screen = own(session);
        if (screen == null || (screen.resumeRequired && !evenTakenOver)) return null;
        screen.releasing = true;
        screen.occupancy = null;
        return screen;
    } }

    private boolean finishTurn(DedicatedScreen screen) {
        return finishTurn(screen, null);
    }

    private boolean finishTurn(DedicatedScreen screen, String expectedAllocation) {
        CleanupWork.acquire(screen.input);
        try {
            if (CleanupWork.active()) {
                try (CleanupWork.Held held = lock()) {
                    if (!ledger.contains(screen) || !screen.releasing) return true;
                    if (expectedAllocation != null && !screen.allocationId.equals(expectedAllocation)) return true;
                }
                String state = host.removalState(screen.key, screen.generation);
                if (state.equals("gone") || state.equals("replaced")) {
                    try (CleanupWork.Held held = lock()) {
                        ledger.remove(screen); screen.apps.clear(); screen.handing.clear();
                        screen.session = null; grantReady();
                    }
                    return true;
                }
                if (!state.equals("current")) throw new IOException("SCREEN_HOST_UNVERIFIED");
            }
            // Once a close was attempted, retry that close: its previous error may have arrived
            // after the host exited. Never publish that uncertain physical screen as idle.
            String failure = screen.cleanupError;
            if (failure.isEmpty()) {
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        closeTasks(screen, null);
                        failure = "";
                        break;
                    } catch (Exception error) {
                        CleanupWork.rethrowTimeout(error);
                        failure = "task cleanup: " + error.getMessage();
                        warn.accept("dedicated screen " + screen.key + " " + failure);
                    }
                }
            }
            if (!failure.isEmpty()) {
                try {
                    // Retain the entry, Apps and capacity throughout the confirmed physical stop.
                    if (CleanupWork.active()) host.stopRemoval(screen.key, screen.generation);
                    else host.stop(screen.key, screen.generation);
                } catch (Exception error) {
                    CleanupWork.rethrowTimeout(error);
                    try (CleanupWork.Held held44 = lock()) {
                        screen.cleanupError = "SCREEN_CLEANUP_FAILED: task cleanup failed twice; "
                                + "host stop is unconfirmed: " + error.getMessage();
                    }
                    warn.accept("dedicated screen " + screen.key + " " + screen.generation
                            + " " + screen.cleanupError + "; retry in " + CLEANUP_RETRY_MS + "ms");
                    if (!CleanupWork.active()) clock.after(CLEANUP_RETRY_MS, () -> {
                        try (CleanupWork.Held held45 = lock()) {
                            if (!ledger.contains(screen) || !screen.releasing) return;
                        }
                        Thread retry = new Thread(() -> finishTurn(screen),
                                "dedicated-screens-retry-close " + screen.key);
                        retry.setDaemon(true);
                        retry.start();
                    });
                    return false;
                }
                try (CleanupWork.Held held46 = lock()) {
                    ledger.remove(screen);
                    screen.apps.clear();
                    screen.handing.clear();
                    screen.session = null;
                    screen.cleanupError = "";
                    grantReady();
                }
                return true;
            }
            try (CleanupWork.Held held47 = lock()) {
                screen.apps.clear();
                screen.handing.clear();
                screen.session = null;
                screen.releasing = false;
                // An archived session's screen is freed even while the user has it; the next
                // session gets it under the agent's control.
                screen.controlOwner = "agent";
                screen.stopping = false;
                screen.resumeRequired = false;
                screen.lastError = "";
                wentIdle(screen);
                grantReady();
            }
            return true;
        } catch (Exception failure) {
            if (CleanupWork.active()) throw new CleanupWork.WaitFailed(failure);
            warn.accept("dedicated screen cleanup incomplete: " + failure);
            return false;
        } finally {
            screen.input.unlock();
        }
    }

    /**
     * The user closes the session's screen, after beginClose and a confirmed stop. Its tasks go
     * before the screen: closing a screen with tasks on it leaves empty entries in Recents. Codex
     * keeps a sub-agent (the broker cannot end one), so every turn a sub-agent starts from now on
     * is refused. subAgent: its parent and what to tell it, or null for a session that is not one.
     */
    void close(String session, String key, String generation, ParentNote subAgent)
            throws Exception {
        close(session, key, generation, subAgent, false);
    }

    void close(String session, String key, String generation, ParentNote subAgent,
            boolean confirmHostStopped) throws Exception {
        DedicatedScreen screen;
        Object occupancy = null;
        boolean retiring = false;
        try (CleanupWork.Held held48 = lock()) {
            screen = exactScreen(session, key, generation);
        }
        screen.input.lockInterruptibly();
        try {
            try (CleanupWork.Held held49 = lock()) {
                if (exactScreen(session, key, generation) != screen)
                    throw new IOException("SCREEN_BINDING_CHANGED");
                occupancy = screen.occupancy;
                screen.occupancy = null;
                retiring = true;
            }
            try {
                if (confirmHostStopped) validate(screen);
                closeTasks(screen, null);
            } catch (Exception error) {
                if (confirmHostStopped) throw error;
                warn.accept("dedicated screen tasks not removed: " + error.getMessage());
            }
            // Automatic revocation reports a successful release only after the host exits. Keep
            // the paused lease available for retry if Android could not complete the close.
            if (confirmHostStopped) host.stop(screen.key, screen.generation);
            try (CleanupWork.Held held50 = lock()) {
                ledger.remove(screen);
                screen.occupancy = null;
                screen.closingOccupancy = null;
                screen.session = null;
                if (subAgent != null) closedSubAgents.put(session, subAgent);
                grantReady();
            }
        } finally {
            try (CleanupWork.Held held51 = lock()) {
                // A confirmed-close failure retains the same paused allocation for retry.
                if (retiring && ledger.contains(screen)) screen.occupancy = occupancy;
            }
            screen.input.unlock();
        }
        if (confirmHostStopped) return;
        try {
            host.stop(screen.key, screen.generation);
        } catch (Exception error) {
            warn.accept("closed dedicated screen not stopped: " + error.getMessage());
        }
    }

    /**
     * For a refused turn of a closed sub-agent: what to tell its parent, else null. Whoever woke it
     * (its parent, nearly always) runs a turn right now, and nothing else tells it why the
     * sub-agent never answers.
     */
    ParentNote closedNote(String session) { try (CleanupWork.Held held19 = lock()) {
        return closedSubAgents.get(session);
    } }

    /** The screen has just gone idle: unless a session takes it meanwhile, it closes in idleMs. */
    private void wentIdle(DedicatedScreen screen) {
        int spell = ++screen.timesIdle;
        clock.after(idleMs, () -> closeIfIdle(screen, spell));
    }

    /** Out of the ledger first, so nobody takes it while it closes. */
    private void closeIfIdle(DedicatedScreen screen, int spell) {
        try (CleanupWork.Held held52 = lock()) {
            if (screen.session != null || screen.timesIdle != spell || !ledger.remove(screen))
                return;
            screen.occupancy = null;
        }
        try {
            host.stop(screen.key, screen.generation);
        } catch (Exception error) {
            warn.accept("idle dedicated screen not closed: " + error.getMessage());
        }
    }

    /** For scripted tests: screens that go idle from now on close after ms. */
    void idleTimeout(long ms) throws IOException {
        if (ms <= 0) throw new IOException("INVALID_IDLE_TIMEOUT");
        idleMs = ms;
    }

    private static JSONObject released(List<String> released, Collection<String> held)
            throws Exception {
        return new JSONObject()
                .put("released", new JSONArray(released))
                .put("heldApps", new JSONArray(held));
    }

    /** Removes the tasks on the screen: those of these Apps, or every one if apps is null. */
    private void closeTasks(DedicatedScreen screen, Collection<String> apps) throws Exception {
        if (apps != null && apps.isEmpty()) return;
        for (PhoneTasks.Task t : tasks.list())
            if (t.displayId() == screen.display && (apps == null || apps.contains(t.packageName())))
                tasks.remove(t.id());
    }

    /**
     * The UI list: occupied screens and failed turn-end cleanup. Normal releases and idle screens
     * are left out. packageName is read from Android when available, never guessed after failure.
     */
    JSONArray list() throws Exception {
        return list(null);
    }

    /** A single session's UI item; unrelated sessions need no Android or Codex inspection. */
    JSONArray list(String session) throws Exception {
        List<JSONObject> items = new ArrayList<>();
        List<Integer> displays = new ArrayList<>();
        List<Waiting> waiting = new ArrayList<>();
        List<Asking> asking = new ArrayList<>();
        try (CleanupWork.Held held53 = lock()) {
            for (DedicatedScreen screen : ledger) {
                if (screen.session == null || (screen.releasing && screen.cleanupError.isEmpty())) continue;
                if (session != null && !session.equals(screen.session)) continue;
                Question asks = screen.releasing ? null : questions.get(screen.session);
                Ticket waits = screen.releasing ? null : pending(screen.session);
                String blocked = waits == null ? null : blocker(waits);
                items.add(item(screen));
                displays.add(screen.display);
                if (asks != null) asking.add(new Asking(items.get(items.size() - 1), asks.text));
                else if (blocked != null)
                    waiting.add(
                            new Waiting(
                                    items.get(items.size() - 1),
                                    waits.apps.get(blocked),
                                    ahead(waits, blocked)));
            }
        }
        if (!waiting.isEmpty() || !asking.isEmpty()) {
            String language = systemLanguage.get();
            for (Waiting w : waiting)
                w.item.put("waitReason", waitReason(w.app, w.ahead, language));
            for (Asking a : asking) a.item.put("waitReason", userReason(a.text, language));
        }
        if (items.isEmpty()) return new JSONArray();
        Map<Integer, String> top = listedTopApps(items);
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.get(i);
            item.put("mainThreadId", mainSession(item.getString("threadId")));
            item.put("packageName", top.getOrDefault(displays.get(i), ""));
        }
        // A session can acquire another screen while its previous turn finishes cleanup.
        items.sort(Comparator.comparing(item -> item.optString("status").equals("cleanup_failed")));
        return new JSONArray(items);
    }

    /** A screen's UI list item from the ledger alone; list adds what Android and Codex know. */
    private JSONObject item(DedicatedScreen screen) throws Exception {
        Ticket waits = screen.releasing ? null : pending(screen.session);
        JSONObject item = screen.screenHandle()
                .json()
                .put("threadId", screen.session)
                .put("taskTitle", waits == null ? screen.taskTitle : waits.taskTitle)
                .put("label", "")
                .put("status", status(screen))
                .put("waitReason", JSONObject.NULL)
                .put("controlOwner", screen.controlOwner)
                .put("controlRevision", screen.controlRevision)
                .put("admitted", !screen.releasing)
                .put("homePreviewEligible", screen.session != null && !screen.closing && !screen.releasing)
                .put("stopping", screen.stopping || screen.releasing)
                .put("resumeRequired", screen.resumeRequired)
                .put("canResume", !screen.releasing && !screen.controlOwner.equals("agent") && !screen.stopping)
                .put("lastError", screen.cleanupError.isEmpty() ? screen.lastError : screen.cleanupError);
        if (!screen.cleanupError.isEmpty())
            item.put("heldApps", new JSONArray(screen.apps))
                    .put("cleanupRetryDelayMs", CLEANUP_RETRY_MS);
        return item;
    }

    /**
     * The session at the top of this one's spawn chain (itself unless it is a sub-agent), asked of
     * Codex once per session, outside the table lock: Codex answers through the app-server pump.
     * When Codex cannot tell, the session itself, and it is asked again next time.
     */
    private String mainSession(String session) {
        try (CleanupWork.Held held54 = lock()) {
            String known = mainSessions.get(session);
            if (known != null) return known;
        }
        String main = session;
        try {
            for (int depth = 0; depth < 8; depth++) {
                String parent =
                        PhoneSubAgents.parent(
                                codex.call("thread/read", new JSONObject().put("threadId", main)));
                if (parent.isEmpty()) break;
                main = parent;
            }
        } catch (Exception unknown) {
            return session;
        }
        try (CleanupWork.Held held55 = lock()) {
            mainSessions.put(session, main);
            cached(session);
        }
        return main;
    }

    /** A UI list item waiting for an App: the App it waits for and how many are ahead of it. */
    private record Waiting(JSONObject item, String app, int ahead) {}

    /** A UI list item waiting for the user: its card's question or title. */
    private record Asking(JSONObject item, String text) {}

    private String status(DedicatedScreen screen) {
        if (!screen.cleanupError.isEmpty()) return "cleanup_failed";
        if (screen.stopping) return "stopping";
        if (screen.controlOwner.equals("user")) return "user_control";
        if (screen.controlOwner.equals("paused")) return "paused";
        if (questions.containsKey(screen.session)) return "waiting_for_user";
        return pending(screen.session) == null ? "running" : "waiting_for_app";
    }

    /**
     * The waitReason sentences per system language: waiting for an App without and with people
     * ahead, then waiting for the user (the card's own words go after it, in whatever language the
     * session wrote them). A language not listed gets English. The App's own UI languages: English
     * and Chinese.
     */
    private static final Map<String, String[]> WAIT_TEXT =
            Map.of(
                    "en",
                    new String[] {
                        "Waiting for %s", "Waiting for %s, %d ahead", "Waiting for your answer: %s"
                    },
                    "zh",
                    new String[] {"在等%s", "在等%s，前面还有 %d 个", "在等你回答：%s"});

    /** waitReason for a request in line (docs/dedicated-screens-ui-fields.md). */
    private static JSONObject waitReason(String app, int ahead, String language) throws Exception {
        String[] text = WAIT_TEXT.getOrDefault(language, WAIT_TEXT.get("en"));
        return new JSONObject()
                .put("code", "WAITING_FOR_APP")
                .put(
                        "text",
                        ahead == 0
                                ? String.format(Locale.ROOT, text[0], app)
                                : String.format(Locale.ROOT, text[1], app, ahead));
    }

    /** waitReason for a card waiting for the user (docs/dedicated-screens-ui-fields.md). */
    private static JSONObject userReason(String text, String language) throws Exception {
        String[] sentences = WAIT_TEXT.getOrDefault(language, WAIT_TEXT.get("en"));
        return new JSONObject()
                .put("code", "WAITING_FOR_USER")
                .put("text", String.format(Locale.ROOT, sentences[2], text));
    }

    /**
     * screen_status, straight from the ledger and the waiting room: who uses each screen, who holds
     * each App and how many wait for it. Releasing screens are unavailable, never idle;
     * failed cleanup stays visible without inspecting Android tasks.
     */
    JSONObject status(String session) throws Exception {
        int max = WorkspacePolicy.snapshot().getInt("maxActiveScreens");
        List<JSONObject> screens = new ArrayList<>();
        List<Integer> displays = new ArrayList<>();
        Map<String, JSONObject> apps = new LinkedHashMap<>();
        int idle = 0;
        try (CleanupWork.Held held56 = lock()) {
            for (DedicatedScreen screen : ledger) {
                for (String pkg : screen.apps) app(apps, pkg).put("heldBy", screen.session);
                if (screen.session == null && !screen.releasing) idle++;
                if (screen.session == null || (screen.releasing && screen.cleanupError.isEmpty())) continue;
                Ticket waits = screen.releasing ? null : pending(screen.session);
                screens.add(
                        new JSONObject()
                                .put("threadId", screen.session)
                                .put(
                                        "taskTitle",
                                        waits == null ? screen.taskTitle : waits.taskTitle)
                                .put("status", status(screen))
                                .put("lastError", screen.cleanupError)
                                .put("cleanupRetryDelayMs", screen.cleanupError.isEmpty() ? 0 : CLEANUP_RETRY_MS)
                                .put("reclaimable", reclaimable(screen)));
                displays.add(screen.display);
            }
            for (Ticket t : waiting)
                for (String pkg : t.apps.keySet()) {
                    JSONObject app = app(apps, pkg);
                    app.put("waiting", app.getInt("waiting") + 1);
                }
        }
        Map<Integer, String> top = listedTopApps(screens);
        for (int i = 0; i < screens.size(); i++)
            screens.get(i).put("topApp", top.getOrDefault(displays.get(i), ""));
        return new JSONObject()
                .put("you", session)
                .put("maxScreens", max)
                .put("idleScreens", idle)
                .put("screens", new JSONArray(screens))
                .put("apps", new JSONArray(apps.values()));
    }

    private static JSONObject app(Map<String, JSONObject> apps, String pkg) throws Exception {
        JSONObject app = apps.get(pkg);
        if (app == null) {
            app = new JSONObject().put("packageName", pkg).put("heldBy", JSONObject.NULL);
            apps.put(pkg, app.put("waiting", 0));
        }
        return app;
    }

    /** The App of each display's top task. */
    private static Map<Integer, String> topApps(List<PhoneTasks.Task> now) {
        Map<Integer, String> top = new HashMap<>();
        for (PhoneTasks.Task t : now) top.putIfAbsent(t.displayId(), t.packageName());
        return top;
    }

    /** Cleanup failures stay queryable even when Android cannot report the current pages. */
    private Map<Integer, String> listedTopApps(List<JSONObject> items) throws Exception {
        if (items.stream().allMatch(item -> item.optString("status").equals("cleanup_failed")))
            return Map.of();
        try {
            return topApps(tasks.list());
        } catch (Exception unavailable) {
            if (items.stream().noneMatch(item -> item.optString("status").equals("cleanup_failed")))
                throw unavailable;
            return Map.of();
        }
    }

    private DedicatedScreen allowed(String session, Use use, String pkg) throws Exception {
        DedicatedScreen screen = own(session);
        if (screen == null) throw new IOException("APPS_NOT_REQUESTED: call request_apps first");
        if (use != Use.LOOK && !agentControls(screen)) throw agentPaused(screen);
        if (use != Use.LOOK && screen.apps.isEmpty())
            throw new IOException("APPS_NOT_REQUESTED: call request_apps first");
        if (use == Use.OPEN && !screen.apps.contains(pkg)) throw new IOException("APP_NOT_HELD");
        if (use != Use.LOOK) requireAppsAllowed(screen);
        return screen;
    }

    private DedicatedScreen own(String session) {
        for (DedicatedScreen screen : ledger)
            if (session.equals(screen.session) && !screen.releasing) return screen;
        return null;
    }

    private DedicatedScreen idle(String session) {
        for (DedicatedScreen screen : ledger)
            if (screen.session == null && !screen.releasing) {
                screen.session = session;
                screen.occupancy = new Object();
                screen.allocationId = UUID.randomUUID().toString();
                screen.emptySince = NEVER;
                return screen;
            }
        return null;
    }

    /**
     * At the limit: a screen whose session holds no App and waits for none, 30 seconds after it let
     * its last App go, goes to this session; null if there is none. Its tasks are gone already. A
     * screen taken over is never taken: the user is using it.
     */
    private DedicatedScreen reclaim(String session) {
        for (DedicatedScreen screen : ledger)
            if (reclaimable(screen)) {
                screen.session = session;
                screen.occupancy = new Object();
                screen.allocationId = UUID.randomUUID().toString();
                screen.emptySince = NEVER;
                return screen;
            }
        return null;
    }

    private boolean reclaimable(DedicatedScreen screen) {
        return screen.session != null
                && !screen.releasing
                && screen.apps.isEmpty()
                && screen.handing.isEmpty()
                && pending(screen.session) == null
                && !screen.resumeRequired
                && clock.now() - screen.emptySince >= HOLD_MS;
    }

    /** The session's request still in line (or starting its screen), if any. */
    private Ticket pending(String session) {
        for (Ticket t : waiting) if (t.session.equals(session)) return t;
        return null;
    }
}
