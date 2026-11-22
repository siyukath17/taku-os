package org.agentos.lab;

import org.json.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class DedicatedScreensTest {
    static int checks;

    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    interface Checked {
        void run() throws Exception;
    }

    static void rejects(String code, Checked action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected " + code);
        } catch (IOException error) {
            check(error.getMessage().contains(code), "Unexpected rejection: " + error);
        }
    }

    /**
     * phone-display in memory. startFailure: the next start throws it. startGate: start waits for
     * it, ignoring interrupts the way a running command does (TaskBroker.command reads its output
     * to the end). loseDescriptor: an interrupt during that wait starts the screen and then throws
     * InterruptedException (TaskBroker.command when waitFor sees the interrupt before the exit).
     * statusInterrupts: status throws InterruptedException that many times first. statusFailures:
     * status fails that many times first, the screen still running (phone-display cannot be read
     * for a moment). unplug: the screen process dies on its own.
     */
    static final class Host implements PhoneExecutionScreens.Host {
        final Map<String, JSONObject> slots = new ConcurrentHashMap<>();
        final List<String> stopped = new CopyOnWriteArrayList<>();
        int next = 2;
        volatile String startFailure = "";
        String stopFailure = "";
        volatile CountDownLatch startGate;
        volatile boolean loseDescriptor;
        final java.util.concurrent.atomic.AtomicInteger statusInterrupts =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger statusFailures =
                new java.util.concurrent.atomic.AtomicInteger();

        Host() {
            slots.put("default", info("default", 1));
        }

        JSONObject info(String key, int display) {
            return new JSONObject()
                    .put("screenKey", key)
                    .put("generation", UUID.randomUUID().toString())
                    .put("displayId", display);
        }

        public JSONObject start(String key) throws Exception {
            CountDownLatch gate = startGate;
            if (gate != null) {
                boolean interrupted = false;
                while (true)
                    try {
                        gate.await();
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                if (interrupted && loseDescriptor) {
                    synchronized (this) {
                        slots.put(key, info(key, next++));
                    }
                    throw new InterruptedException();
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
            synchronized (this) {
                String failure = startFailure;
                if (!failure.isEmpty()) {
                    startFailure = "";
                    throw new IOException(failure);
                }
                JSONObject r = info(key, next++);
                slots.put(key, r);
                return new JSONObject(r.toString());
            }
        }

        public JSONObject status(String key) throws Exception {
            if (statusInterrupts.getAndUpdate(n -> Math.max(0, n - 1)) > 0)
                throw new InterruptedException();
            if (statusFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0)
                throw new IOException("HOST_STATUS_UNAVAILABLE");
            JSONObject v = slots.get(key);
            if (v == null) throw new IOException("HOST_NOT_RUNNING");
            return new JSONObject(v.toString());
        }

        /** Runs first thing in stop, with the screen's key. */
        volatile java.util.function.Consumer<String> beforeStop = key -> {};

        public void stop(String key, String generation) throws Exception {
            beforeStop.accept(key);
            if (!stopFailure.isEmpty()) throw new IOException(stopFailure);
            JSONObject v = status(key);
            if (!generation.equals(v.getString("generation")))
                throw new IOException("HOST_GENERATION_CHANGED");
            stopped.add(key);
            slots.remove(key);
        }

        void unplug(String key) {
            slots.remove(key);
        }
    }

    /**
     * Android tasks the test lays out itself; open() puts a task on top of its display. removeGate:
     * remove counts down removing, then waits for the gate (5 seconds at most).
     */
    static final class Tasks implements PhoneTasks {
        final List<Task> tasks = new CopyOnWriteArrayList<>();
        int next = 100;
        volatile CountDownLatch removeGate, removing;
        volatile String removeFailure = "";

        synchronized int open(String pkg, int display) {
            tasks.add(0, new Task(next, pkg, display));
            return next++;
        }

        public List<Task> list() {
            return List.copyOf(tasks);
        }

        public void remove(int id) throws Exception {
            if (!removeFailure.isEmpty()) throw new IOException(removeFailure);
            CountDownLatch gate = removeGate;
            if (gate != null) {
                removing.countDown();
                gate.await(5, TimeUnit.SECONDS);
            }
            tasks.removeIf(t -> t.id() == id);
        }
    }

    /**
     * Hand-moved clock. advance runs due callbacks in time order on the test thread, including ones
     * they schedule.
     */
    static final class Clock implements DedicatedScreens.Clock {
        record Due(long at, long seq, Runnable run) {}

        final PriorityQueue<Due> due =
                new PriorityQueue<>(Comparator.comparingLong(Due::at).thenComparingLong(Due::seq));
        long now, seq;

        public synchronized long now() {
            return now;
        }

        public synchronized void after(long ms, Runnable run) {
            due.add(new Due(now + ms, seq++, run));
        }

        void advance(long ms) {
            long end;
            synchronized (this) {
                end = now + ms;
            }
            while (true) {
                Due d;
                synchronized (this) {
                    d = due.peek();
                    if (d == null || d.at > end) break;
                    due.poll();
                    now = d.at;
                }
                d.run.run();
            }
            synchronized (this) {
                now = end;
            }
        }
    }

    /**
     * Codex app-server as PhoneSubAgents sees it (its Rpc shape: call(method, params)). Records
     * every call; answers thread/list (all descendants, archived or live, page by page; the last
     * nextCursor is JSON null as Codex sends it) and thread/read (the spawning parent and the path
     * it addresses the sub-agent by); everything else returns {}.
     */
    static final class Codex {
        final Map<String, String> parents = new ConcurrentHashMap<>();
        final Set<String> archived = ConcurrentHashMap.newKeySet();
        final List<String> calls = new CopyOnWriteArrayList<>();
        int page = 50;

        /** Codex does not answer: every call fails. */
        volatile boolean down;

        boolean under(String child, String ancestor) {
            for (String p = parents.get(child); p != null; p = parents.get(p))
                if (p.equals(ancestor)) return true;
            return false;
        }

        JSONObject call(String method, JSONObject params) throws IOException {
            if (down) throw new IOException("app-server gone");
            calls.add(
                    method
                            + " "
                            + params.optString("threadId", params.optString("ancestorThreadId")));
            if (method.equals("thread/list")) {
                List<String> all = new ArrayList<>();
                for (String t : new TreeSet<>(parents.keySet()))
                    if (under(t, params.getString("ancestorThreadId"))
                            && archived.contains(t) == params.optBoolean("archived")) all.add(t);
                int from = params.has("cursor") ? Integer.parseInt(params.getString("cursor")) : 0,
                        to = Math.min(all.size(), from + page);
                JSONArray data = new JSONArray();
                for (String t : all.subList(from, to)) data.put(new JSONObject().put("id", t));
                return new JSONObject()
                        .put("data", data)
                        .put(
                                "nextCursor",
                                to < all.size() ? Integer.toString(to) : JSONObject.NULL);
            }
            if (method.equals("thread/read")) {
                String id = params.getString("threadId"), parent = parents.get(id);
                Object source =
                        parent == null
                                ? "appServer"
                                : new JSONObject()
                                        .put(
                                                "subAgent",
                                                new JSONObject()
                                                        .put(
                                                                "thread_spawn",
                                                                new JSONObject()
                                                                        .put(
                                                                                "parent_thread_id",
                                                                                parent)
                                                                        .put(
                                                                                "agent_path",
                                                                                "/root/sub-" + id)
                                                                        .put(
                                                                                "agent_nickname",
                                                                                "Euclid")));
                return new JSONObject()
                        .put("thread", new JSONObject().put("id", id).put("source", source));
            }
            return new JSONObject();
        }
    }

    static final Path SAMPLES = Path.of("system/assistant/tests/dedicated-screens/am-stack-list");

    static PhoneTasks.Task task(int id, String pkg, int display) {
        return new PhoneTasks.Task(id, pkg, display);
    }

    /**
     * Real `am stack list` output, captured 2026-11-06. Expected rows read off the files by hand.
     */
    static void phoneTasks() throws Exception {
        // emulator-5582: main screen only, several App tasks, plus a split-screen container whose
        // tasks have no App
        List<PhoneTasks.Task> emulator =
                PhoneTasks.parse(Files.readString(SAMPLES.resolve("emulator.txt")));
        check(
                emulator.equals(
                        List.of(
                                task(6, "org.agentos.assistant", 0),
                                task(175, "com.android.deskclock", 0),
                                task(173, "com.android.settings", 0),
                                task(171, "com.android.contacts", 0))),
                "main screen tasks top to bottom, no-App tasks skipped: " + emulator);
        // Pixel 9: background screens 2 and 3 exist; 3 holds a Settings task whose activity is gone
        // (no topActivity),
        // 2 is empty and so never appears in the output
        List<PhoneTasks.Task> pixel =
                PhoneTasks.parse(Files.readString(SAMPLES.resolve("pixel9.txt")));
        check(
                pixel.equals(
                        List.of(
                                task(34, "org.agentos.assistant", 0),
                                task(29, "com.android.vending", 0),
                                task(8, "com.android.launcher3", 0),
                                task(32, "com.android.settings", 3))),
                "background screen task kept even without a live activity: " + pixel);
        check(pixel.stream().noneMatch(t -> t.displayId() == 2), "an empty screen has no tasks");
        // Android names a task by its package alone when it has no root component
        // (RootWindowContainer, android16-release)
        check(
                PhoneTasks.parse(
                                "RootTask id=40 bounds=[0,0][720,1280] displayId=5 userId=0\n"
                                    + "  taskId=41: com.example.x bounds=[0,0][720,1280] userId=0"
                                    + " visible=true\n")
                        .equals(List.of(task(41, "com.example.x", 5))),
                "package-only task name");
    }

    static String session(int n) {
        return String.format("00000000-0000-4000-8000-%012d", n);
    }

    /** DedicatedScreens wired to the fakes. */
    static final class Rig {
        final Host host = new Host();
        final Tasks tasks = new Tasks();
        final Clock clock = new Clock();
        final Codex codex = new Codex();
        final DedicatedScreens screens =
                new DedicatedScreens(
                        host,
                        tasks,
                        clock,
                        codex::call,
                        () -> Locale.getDefault().getLanguage(),
                        System.err::println);

        JSONObject request(int session, String title, String... packages) throws Exception {
            return screens.request(session(session), apps(packages), title);
        }
    }

    /** Package to label; the label is the name's last part, upper-cased (com.x: X). */
    static Map<String, String> apps(String... packages) {
        Map<String, String> apps = new LinkedHashMap<>();
        for (String p : packages) apps.put(p, p.substring(p.lastIndexOf('.') + 1).toUpperCase());
        return apps;
    }

    /**
     * A request_apps call on a thread of its own, as a tool thread makes it; starts once go opens
     * (null: at once). Daemon, and every wait on it is bounded, so a failed check never leaves the
     * JVM hanging.
     */
    static final class Call {
        final CompletableFuture<JSONObject> result = new CompletableFuture<>();
        final Thread worker;

        Call(Rig rig, int session, String title, String... packages) {
            this(null, rig, session, title, packages);
        }

        Call(CountDownLatch go, Rig rig, int session, String title, String... packages) {
            worker =
                    new Thread(() -> {
                        try {
                            if (go != null) go.await();
                            result.complete(rig.request(session, title, packages));
                        } catch (Throwable error) {
                            result.completeExceptionally(error);
                        }
                    });
            worker.setDaemon(true);
            worker.start();
        }

        List<Object> granted() throws Exception {
            return held(result.get(5, TimeUnit.SECONDS));
        }

        /** The error code it failed with, or the exception's class when it has no message. */
        String refused() throws Exception {
            try {
                result.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                return cause.getMessage() != null
                        ? cause.getMessage()
                        : cause.getClass().getSimpleName();
            }
            throw new AssertionError("granted, expected a refusal");
        }

        boolean done() {
            return result.isDone();
        }
    }

    static Thread daemon(Checked body) {
        Thread t =
                new Thread(() -> {
                    try {
                        body.run();
                    } catch (Exception ignored) {
                    }
                });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** pkg's entry in screen_status, or null when nobody holds or awaits it. */
    static JSONObject app(Rig rig, String pkg) throws Exception {
        for (Object o : rig.screens.status(session(0)).getJSONArray("apps"))
            if (((JSONObject) o).getString("packageName").equals(pkg)) return (JSONObject) o;
        return null;
    }

    static int waitingFor(Rig rig, String pkg) throws Exception {
        JSONObject app = app(rig, pkg);
        return app == null ? 0 : app.getInt("waiting");
    }

    /** Waits (5 seconds at most) until n requests are in line for pkg. */
    static void awaitWaiting(Rig rig, String pkg, int n) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        while (waitingFor(rig, pkg) != n) {
            if (System.nanoTime() > end)
                throw new AssertionError(n + " in line for " + pkg + ": " + app(rig, pkg));
            Thread.sleep(5);
        }
    }

    static List<Object> held(JSONObject granted) {
        return granted.getJSONArray("heldApps").toList();
    }

    /** A: request_apps by a session that holds nothing (no other session competing). */
    static void requestApps() throws Exception {
        Rig rig = new Rig();
        JSONObject a = rig.request(1, "Book a table", "com.example.maps");
        String key = a.getString("screenKey");
        check(!key.equals("default") && rig.host.slots.containsKey(key), "A1: a new screen: " + a);
        check(held(a).equals(List.of("com.example.maps")), "A1: the App is held: " + a);
        JSONObject again = rig.request(1, "Share the booking", "com.example.chat");
        check(again.getString("screenKey").equals(key), "A2: same turn, same screen: " + again);
        check(rig.host.slots.size() == 2, "A2: no second screen started (default + one)");
        check(held(again).equals(List.of("com.example.maps", "com.example.chat")), "A2: " + again);
    }

    /** Runs a screen tool for the session; returns the display it ran on. */
    static int use(Rig rig, int session, DedicatedScreens.Use use, String pkg) throws Exception {
        return rig.screens
                .withScreen(
                        session(session), use, pkg, s -> new JSONObject().put("ran", s.displayId))
                .getInt("ran");
    }

    /** B: the check before a screen tool runs. */
    static void beforeUse() throws Exception {
        Rig rig = new Rig();
        for (DedicatedScreens.Use u : DedicatedScreens.Use.values())
            rejects("APPS_NOT_REQUESTED", () -> use(rig, 1, u, "com.example.maps"));
        int display = rig.request(1, "t", "com.example.maps").getInt("displayId");
        check(use(rig, 1, DedicatedScreens.Use.ACT, null) == display, "B1: acts on its own screen");
        check(
                use(rig, 1, DedicatedScreens.Use.OPEN, "com.example.maps") == display,
                "B4: held App");
        rejects("APP_NOT_HELD", () -> use(rig, 1, DedicatedScreens.Use.OPEN, "com.example.chat"));
        check(use(rig, 1, DedicatedScreens.Use.LOOK, null) == display, "B6: a screenshot");
        rejects("APPS_NOT_REQUESTED", () -> use(rig, 2, DedicatedScreens.Use.LOOK, null));
    }

    /** The fields of a "CODE: {json}" refusal. */
    static JSONObject fields(String code, String message) {
        check(message.startsWith(code + ": "), "expected " + code + ": " + message);
        return new JSONObject(message.substring(code.length() + 2));
    }

    /** The message the action is refused with. */
    static String refusal(Checked action) throws Exception {
        try {
            action.run();
        } catch (IOException error) {
            return error.getMessage();
        }
        throw new AssertionError("expected a refusal");
    }

    /** B5: acting needs the App on top of the screen to be one the session holds. */
    static void topApp() throws Exception {
        Rig rig = new Rig();
        int d = rig.request(1, "t", "com.x").getInt("displayId");
        check(use(rig, 1, DedicatedScreens.Use.ACT, null) == d, "B5: an empty screen");
        int page = rig.tasks.open("com.x", d);
        rig.tasks.open("com.other", 0);
        check(
                use(rig, 1, DedicatedScreens.Use.ACT, null) == d,
                "B5: X on top; other screens do not count");
        // an in-App jump into Y
        rig.tasks.open("com.y", d);
        boolean[] ran = {false};
        String refused =
                refusal(() ->
                        rig.screens.withScreen(
                                session(1),
                                DedicatedScreens.Use.ACT,
                                null,
                                s -> {
                                    ran[0] = true;
                                    return new JSONObject();
                                }));
        check(
                fields("UNHELD_APP_ON_TOP", refused).getString("packageName").equals("com.y")
                        && !ran[0],
                "B5: blocked before acting, Y named: " + refused);
        rejects("UNHELD_APP_ON_TOP", () -> use(rig, 1, DedicatedScreens.Use.OPEN, "com.x"));
        check(use(rig, 1, DedicatedScreens.Use.LOOK, null) == d, "B5: a screenshot is not checked");
        rig.request(1, "t", "com.y");
        check(use(rig, 1, DedicatedScreens.Use.ACT, null) == d, "B5: Y held, go on");
        check(tasksOn(rig, d).contains(page), "B5: X's page is still there");
        release(rig, 1, "com.y");
        check(
                tasksOn(rig, d).equals(List.of(page))
                        && use(rig, 1, DedicatedScreens.Use.ACT, null) == d,
                "B5: releasing Y closes it, X is on top again");
    }

    /**
     * OPEN with a launch that does what Android does: a new task on the session's screen, or (a
     * single-instance App) none while the App already has a task anywhere. Returns the launches.
     */
    static int[] openApp(Rig rig, int session, String pkg, boolean singleInstance) throws Exception {
        int[] launches = {0};
        rig.screens.withScreen(
                session(session),
                DedicatedScreens.Use.OPEN,
                pkg,
                s -> {
                    launches[0]++;
                    boolean exists = false;
                    for (PhoneTasks.Task t : rig.tasks.list())
                        if (t.packageName().equals(pkg)) exists = true;
                    if (!singleInstance || !exists) rig.tasks.open(pkg, s.displayId);
                    return new JSONObject().put("ran", s.displayId);
                });
        return launches;
    }

    static List<Integer> tasksOf(Rig rig, String pkg, int display) {
        List<Integer> out = new ArrayList<>();
        for (PhoneTasks.Task t : rig.tasks.list())
            if (t.displayId() == display && t.packageName().equals(pkg)) out.add(t.id());
        return out;
    }

    /**
     * B3: the user's decision, "the agent may take over at any time": an App the session holds that
     * is also open on the main screen or in Recents never blocks it (no USER_HOLDS_APP). OPEN lands
     * the App on the session's own screen.
     */
    static void takesOverAnyTime() throws Exception {
        Rig rig = new Rig();
        rig.screens.openSettleMs = 50;
        int home = rig.tasks.open("org.agentos.assistant", 0);
        // in the user's Recents, then in front on the main screen
        int recents = rig.tasks.open("com.x", 0);
        int front = rig.tasks.open("com.y", 0);
        JSONObject got = rig.request(1, "t", "com.x", "com.y");
        int d = got.getInt("displayId");
        check(held(got).equals(List.of("com.x", "com.y")), "B3: requesting is not checked: " + got);
        check(use(rig, 1, DedicatedScreens.Use.ACT, null) == d, "B3: ACT runs");
        check(use(rig, 1, DedicatedScreens.Use.LOOK, null) == d, "B3: LOOK runs");

        // a normal App: a task of its own on the session's screen, the user's copy left alone
        int[] n = openApp(rig, 1, "com.x", false);
        check(
                n[0] == 1 && tasksOf(rig, "com.x", d).size() == 1
                        && tasksOf(rig, "com.x", 0).equals(List.of(recents)),
                "B3: multiple-task launch, user's Recents copy kept: " + rig.tasks.list());
        release(rig, 1, "com.x");

        // a single-instance App reuses the user's task on the main screen: closed, then reopened here
        n = openApp(rig, 1, "com.y", true);
        check(
                n[0] == 2 && tasksOf(rig, "com.y", d).size() == 1
                        && tasksOf(rig, "com.y", 0).isEmpty()
                        && !rig.tasks.list().stream().anyMatch(t -> t.id() == front),
                "B3: single-instance App moved to the session's screen: " + rig.tasks.list());
        check(tasksOn(rig, 0).equals(List.of(recents, home)), "B3: home and other Apps untouched");

        // the user opens Y on the main screen meanwhile (Android moved the session's task over)
        rig.tasks.remove(tasksOf(rig, "com.y", d).get(0));
        rig.tasks.open("com.y", 0);
        check(use(rig, 1, DedicatedScreens.Use.ACT, null) == d, "B3: ACT still runs, no USER_HOLDS_APP");
        n = openApp(rig, 1, "com.y", true);
        check(
                n[0] == 2 && tasksOf(rig, "com.y", d).size() == 1 && tasksOf(rig, "com.y", 0).isEmpty(),
                "B3: and open_app takes it back: " + rig.tasks.list());

        // already on its screen: a single launch, nothing on the main screen is touched
        int mainX = rig.tasks.open("com.x", 0);
        rig.request(1, "t", "com.x");
        n = openApp(rig, 1, "com.x", false);
        n = openApp(rig, 1, "com.x", true);
        check(n[0] == 1 && tasksOf(rig, "com.x", 0).equals(List.of(mainX, recents)), "B3: App on screen, one launch: " + rig.tasks.list());

        // Taku's own home/chat are never closed to make room
        Rig own = new Rig();
        own.screens.openSettleMs = 50;
        int chat = own.tasks.open("org.agentos.agent", 0);
        own.request(1, "t", "org.agentos.agent");
        rejects("OPEN_LANDED_ON_MAIN", () -> openApp(own, 1, "org.agentos.agent", true));
        check(tasksOf(own, "org.agentos.agent", 0).equals(List.of(chat)), "B3: Taku's chat left alone");

        // an App Android keeps on the main screen even after the reopen: reported, not looped
        Rig stuck = new Rig();
        stuck.screens.openSettleMs = 50;
        stuck.tasks.open("com.s", 0);
        int sd = stuck.request(1, "t", "com.s").getInt("displayId");
        int[] tries = {0};
        rejects(
                "OPEN_LANDED_ON_MAIN",
                () -> stuck.screens.withScreen(session(1), DedicatedScreens.Use.OPEN, "com.s", s -> {
                    tries[0]++;
                    stuck.tasks.open("com.s", 0);
                    return new JSONObject();
                }));
        check(tries[0] == 2 && tasksOf(stuck, "com.s", sd).isEmpty(), "B3: one reopen only");
        openApp(stuck, 1, "com.s", false);
        check(tasksOf(stuck, "com.s", sd).size() == 1, "B3: a normal launch then lands on the screen");
    }

    static JSONObject release(Rig rig, int session, String... packages) throws Exception {
        return rig.screens.release(session(session), List.of(packages));
    }

    /** C1, C6: release_apps. */
    static void releaseApps() throws Exception {
        Rig rig = new Rig();
        int d = rig.request(1, "t", "com.x", "com.y").getInt("displayId");
        int x = rig.tasks.open("com.x", d), y = rig.tasks.open("com.y", d);
        int usersX = rig.tasks.open("com.x", 0), other = rig.tasks.open("com.z", 9);
        JSONObject r = release(rig, 1, "com.x");
        check(
                r.getJSONArray("released").toList().equals(List.of("com.x"))
                        && held(r).equals(List.of("com.y")),
                "C1: " + r);
        Set<Integer> left = new HashSet<>();
        for (PhoneTasks.Task t : rig.tasks.list()) left.add(t.id());
        check(
                left.equals(Set.of(y, usersX, other)),
                "C1: only X's task on its own screen is gone: " + left);
        check(release(rig, 1, "com.x").getJSONArray("released").isEmpty(), "C6: released twice");
        check(release(rig, 1, "com.q").getJSONArray("released").isEmpty(), "C6: never held");
        check(release(rig, 3, "com.y").getJSONArray("released").isEmpty(), "C6: no screen at all");
        check(
                held(release(rig, 3, "com.y")).isEmpty() && rig.tasks.list().size() == 3,
                "C6: no effect");
        release(rig, 1, "com.y");
        rejects("APPS_NOT_REQUESTED", () -> use(rig, 1, DedicatedScreens.Use.ACT, null));
        check(
                use(rig, 1, DedicatedScreens.Use.LOOK, null) == d,
                "C1: the screen stays for the turn");
    }

    static List<Integer> tasksOn(Rig rig, int display) {
        List<Integer> out = new ArrayList<>();
        for (PhoneTasks.Task t : rig.tasks.list()) if (t.displayId() == display) out.add(t.id());
        return out;
    }

    /**
     * C2: the turn ends; A2: the idle screen goes to the next session; the step in flight first.
     */
    static void turnEnds() throws Exception {
        Rig rig = new Rig();
        check(rig.screens.turnEnded(session(1)) == null, "C2: nothing to release without a screen");
        JSONObject a = rig.request(1, "t", "com.x");
        int d = a.getInt("displayId");
        rig.tasks.open("com.x", d);
        rig.tasks.open("com.jumped.into", d);
        int usersX = rig.tasks.open("com.x", 0);
        rig.screens.turnEnded(session(1)).join(5_000);
        check(
                tasksOn(rig, d).isEmpty() && tasksOn(rig, 0).equals(List.of(usersX)),
                "C2: whole screen cleared");
        // the user swipes X away, so session 2 can act in it (B3)
        rig.tasks.remove(usersX);
        rejects("APPS_NOT_REQUESTED", () -> use(rig, 1, DedicatedScreens.Use.LOOK, null));
        JSONObject b = rig.request(2, "t", "com.x");
        check(
                b.getString("screenKey").equals(a.getString("screenKey")),
                "A2: idle screen reused: " + b);
        check(
                rig.host.slots.size() == 2 && tasksOn(rig, d).isEmpty(),
                "A2: no new screen, a clean one");

        // a step is still running when the turn ends: screen and Apps stay out of reach until it is
        // done
        CountDownLatch inside = new CountDownLatch(1), done = new CountDownLatch(1);
        Thread step =
                new Thread(() -> {
                    try {
                        rig.screens.withScreen(
                                session(2),
                                DedicatedScreens.Use.ACT,
                                null,
                                s -> {
                                    inside.countDown();
                                    done.await();
                                    return new JSONObject();
                                });
                    } catch (Exception ignored) {
                    }
                });
        // a failed check must not leave the JVM waiting on it
        step.setDaemon(true);
        step.start();
        check(inside.await(5, TimeUnit.SECONDS), "the step is running");
        rig.tasks.open("com.x", d);
        Thread release = rig.screens.turnEnded(session(2));
        JSONObject next = rig.request(2, "next turn", "com.w");
        check(
                !next.getString("screenKey").equals(a.getString("screenKey")),
                "its own next turn gets another screen while this one is releasing");
        Call waits = new Call(rig, 3, "t", "com.x");
        awaitWaiting(rig, "com.x", 1);
        JSONObject c = rig.request(4, "t", "com.y");
        check(
                !c.getString("screenKey").equals(a.getString("screenKey")),
                "releasing screen not handed out");
        check(
                tasksOn(rig, d).size() == 1 && !waits.done(),
                "tasks and the App wait for the step in flight");
        done.countDown();
        release.join(5_000);
        step.join(5_000);
        check(tasksOn(rig, d).isEmpty(), "then removed");
        check(waits.granted().contains("com.x"), "and the App goes to the one waiting");
    }

    /** R2: the UI list, one item per screen in use (docs/dedicated-screens-ui-fields.md). */
    static void listRows() throws Exception {
        Rig rig = new Rig();
        check(rig.screens.list().isEmpty(), "R2: nothing in use, nothing listed");
        JSONObject a = rig.request(1, "Book a table", "com.x");
        JSONObject row = rig.screens.list().getJSONObject(0);
        check(
                row.getString("threadId").equals(session(1))
                        && row.getString("mainThreadId").equals(session(1)),
                "R2: " + row);
        check(
                row.getString("taskTitle").equals("Book a table")
                        && row.getString("packageName").isEmpty(),
                "R2: " + row);
        check(row.getString("status").equals("running") && row.isNull("waitReason"), "R2: " + row);
        check(
                row.getString("controlOwner").equals("agent")
                        && row.getLong("controlRevision") == 0,
                "R2: " + row);
        for (String key : List.of("screenKey", "generation", "displayId"))
            check(row.get(key).toString().equals(a.get(key).toString()), "R2: " + key);
        check(
                row.getString("mirrorSocket").equals("taku-mirror-" + a.getString("screenKey")),
                "R2: preview socket");
        rig.tasks.open("com.x", a.getInt("displayId"));
        rig.tasks.open("com.jumped.into", a.getInt("displayId"));
        rig.request(1, "Share the booking", "com.y");
        row = rig.screens.list().getJSONObject(0);
        check(row.getString("packageName").equals("com.jumped.into"), "R2: the App on top: " + row);
        check(
                row.getString("taskTitle").equals("Share the booking"),
                "R2: title follows the request");
        rig.screens.turnEnded(session(1)).join(5_000);
        check(rig.screens.list().isEmpty(), "R2: idle screens are not listed");
    }

    /** A5: bad arguments and policy refusals use the existing codes and start no screen. */
    static void requestRefusals() throws Exception {
        Path policy = Files.createTempDirectory("dedicated-policy").resolve("policy.json");
        WorkspacePolicy.store = new WorkspacePolicy.Store(policy.toFile());
        Rig rig = new Rig();
        rejects("INVALID_SCREEN_THREAD", () -> rig.screens.request("x", apps("a.b"), "t"));
        rejects("INVALID_SCREEN_PACKAGE", () -> rig.request(1, "t"));
        rejects("INVALID_SCREEN_PACKAGE", () -> rig.request(1, "t", "not a package"));
        rejects(
                "INVALID_SCREEN_PACKAGE",
                () -> rig.request(1, "t", "a.a", "a.b", "a.c", "a.d", "a.e", "a.f"));
        rejects("INVALID_TASK_TITLE", () -> rig.request(1, " ", "a.b"));
        rejects("INVALID_TASK_TITLE", () -> rig.request(1, "x".repeat(81), "a.b"));
        WorkspacePolicy.configure(
                new JSONObject().put("blockedPackages", new JSONArray().put("a.b")));
        rejects("APP_NOT_ALLOWED", () -> rig.request(1, "t", "a.c", "a.b"));
        WorkspacePolicy.configure(new JSONObject().put("allowBackground", false));
        rejects("BACKGROUND_WORK_DISABLED", () -> rig.request(1, "t", "a.c"));
        check(rig.host.slots.size() == 1, "A5: no screen started by a refused request");
        WorkspacePolicy.store = new WorkspacePolicy.Store(null);
    }

    /**
     * The later tests lean on these fakes, so the parts with logic in them are checked once here.
     */
    static void fakes() throws Exception {
        Clock clock = new Clock();
        List<String> ran = new ArrayList<>();
        clock.after(30_000, () -> ran.add("30s"));
        clock.after(
                10_000,
                () -> {
                    ran.add("10s");
                    clock.after(5_000, () -> ran.add("15s"));
                });
        clock.advance(20_000);
        check(
                ran.equals(List.of("10s", "15s")) && clock.now() == 20_000,
                "due callbacks run in time order, ones they schedule too: " + ran);
        clock.advance(10_000);
        check(ran.size() == 3 && ran.get(2).equals("30s"), "the rest runs when its time comes");
        Host host = new Host();
        host.startFailure = "HOST_START_FAILED";
        rejects("HOST_START_FAILED", () -> host.start("s1"));
        host.start("s1");
        host.unplug("s1");
        rejects("HOST_NOT_RUNNING", () -> host.status("s1"));
        check(
                host.stopped.isEmpty(),
                "one failed start only; an unplugged screen was never stopped");
        Codex codex = new Codex();
        codex.page = 2;
        codex.parents.putAll(
                Map.of("c1", "main", "c2", "main", "c3", "main", "g1", "c1", "other", "main2"));
        codex.archived.add("c3");
        List<String> live = new ArrayList<>();
        String cursor = null;
        do {
            JSONObject p = new JSONObject().put("ancestorThreadId", "main");
            if (cursor != null) p.put("cursor", cursor);
            JSONObject r = codex.call("thread/list", p);
            for (Object o : r.getJSONArray("data")) live.add(((JSONObject) o).getString("id"));
            cursor = r.isNull("nextCursor") ? null : r.getString("nextCursor");
        } while (cursor != null);
        check(
                live.equals(List.of("c1", "c2", "g1")),
                "live descendants at any depth, over two pages: " + live);
        check(
                codex.call(
                                        "thread/list",
                                        new JSONObject()
                                                .put("ancestorThreadId", "main")
                                                .put("archived", true))
                                .getJSONArray("data")
                                .length()
                        == 1,
                "archived listed apart");
        check(
                codex.call("thread/read", new JSONObject().put("threadId", "g1"))
                        .getJSONObject("thread")
                        .getJSONObject("source")
                        .getJSONObject("subAgent")
                        .getJSONObject("thread_spawn")
                        .getString("parent_thread_id")
                        .equals("c1"),
                "thread/read names the parent");
    }

    /** A3, C3: a held App is queued for; each release hands it to the earliest request. */
    static void queue() throws Exception {
        Rig rig = new Rig();
        rig.request(1, "t", "com.x");
        Call b = new Call(rig, 2, "b", "com.x");
        awaitWaiting(rig, "com.x", 1);
        Call c = new Call(rig, 3, "c", "com.x");
        awaitWaiting(rig, "com.x", 2);
        check(!b.done() && !c.done(), "A3: both wait");
        rejects("REQUEST_PENDING", () -> rig.request(2, "again", "com.y"));
        check(waitingFor(rig, "com.y") == 0, "a second request while one waits changes nothing");
        release(rig, 1, "com.x");
        check(b.granted().equals(List.of("com.x")), "A3: the first to come gets it");
        check(waitingFor(rig, "com.x") == 1 && !c.done(), "C3: the next keeps waiting");
        rig.screens.turnEnded(session(2)).join(5_000);
        check(c.granted().equals(List.of("com.x")), "C3: then the next gets it");
    }

    /**
     * A4: all or none, the free App is not held for the waiting request; C3: a request for several
     * Apps gets them together and is never overtaken.
     */
    static void allOrNone() throws Exception {
        Rig rig = new Rig();
        rig.request(1, "t", "com.x", "com.y");
        Call b = new Call(rig, 2, "b", "com.x", "com.y");
        awaitWaiting(rig, "com.y", 1);
        Call c = new Call(rig, 3, "c", "com.y");
        awaitWaiting(rig, "com.y", 2);
        release(rig, 1, "com.y");
        check(app(rig, "com.y").isNull("heldBy"), "A4: Y free, not held for the waiting X+Y");
        rejects("APPS_NOT_REQUESTED", () -> use(rig, 2, DedicatedScreens.Use.OPEN, "com.y"));
        check(!b.done() && !c.done(), "C3: Y is free, but X+Y came first, so Y alone waits too");
        release(rig, 1, "com.x");
        check(b.granted().equals(List.of("com.x", "com.y")), "A4: X and Y together");
        rig.screens.turnEnded(session(2)).join(5_000);
        check(c.granted().equals(List.of("com.y")), "C3: then Y alone");
    }

    /** The held Apps a HELD_APP_WANTED refusal names. */
    static List<Object> wanted(String message) {
        return fields("HELD_APP_WANTED", message).getJSONArray("packageNames").toList();
    }

    /** A9, A10: a session holding X asks for more while nobody waits for X. */
    static void append() throws Exception {
        Rig rig = new Rig();
        int d = rig.request(1, "t", "com.x").getInt("displayId");
        int page = rig.tasks.open("com.x", d);
        JSONObject y = rig.request(1, "Share it", "com.y");
        check(held(y).equals(List.of("com.x", "com.y")), "A9: Y at once, X kept: " + y);
        check(tasksOn(rig, d).equals(List.of(page)), "A9: X's page is still there");

        rig.request(2, "t", "com.z");
        Call z = new Call(rig, 1, "t", "com.x", "com.z");
        awaitWaiting(rig, "com.z", 1);
        check(!z.done(), "A10: waits for Z");
        check(waitingFor(rig, "com.x") == 0, "A10: not in line for X, which it holds");
        check(
                app(rig, "com.y").getString("heldBy").equals(session(1)),
                "A10: holds X and Y meanwhile");
        release(rig, 2, "com.z");
        check(z.granted().equals(List.of("com.x", "com.y", "com.z")), "A10: Z once it is free");
        check(tasksOn(rig, d).equals(List.of(page)), "A10: X's page is still there");
    }

    /** A6: an append counts toward the 3 in line; A8: an append stopped in line. */
    static void appendInLine() throws Exception {
        Rig rig = new Rig();
        rig.request(1, "t", "com.x");
        rig.request(5, "t", "com.p");
        new Call(rig, 2, "t", "com.x");
        awaitWaiting(rig, "com.x", 1);
        new Call(rig, 3, "t", "com.x");
        awaitWaiting(rig, "com.x", 2);
        Call five = new Call(rig, 5, "t", "com.x");
        awaitWaiting(rig, "com.x", 3);
        check(
                queueFull(rig, 6, "com.x").getString("packageName").equals("com.x"),
                "A6: the append took the third place");
        rig.request(7, "t", "com.q");
        check(queueFull(rig, 7, "com.x").getInt("attempt") == 1, "A6: an append is refused too");
        check(app(rig, "com.q").getString("heldBy").equals(session(7)), "A6: keeping its App");

        five.worker.interrupt();
        check(five.refused().equals("InterruptedException"), "A8: the append's call cancelled");
        check(
                app(rig, "com.p").getString("heldBy").equals(session(5))
                        && waitingFor(rig, "com.x") == 2,
                "A8: out of line, P kept for the turn");
        Call again = new Call(rig, 5, "t", "com.x");
        awaitWaiting(rig, "com.x", 3);
        rig.screens.turnEnded(session(5)).join(5_000);
        check(again.refused().equals("TURN_ENDED"), "A8: the append's turn ended");
        check(
                app(rig, "com.p") == null && waitingFor(rig, "com.x") == 2,
                "A8: P released with the turn");

        // stopped as it is granted, after asking again for the App it holds: only Y goes back
        Rig r = new Rig();
        r.request(1, "t", "com.x");
        r.request(2, "t", "com.y");
        Call g = new Call(r, 1, "t", "com.x", "com.y");
        awaitWaiting(r, "com.y", 1);
        try (CleanupWork.Held ledgerHeld = r.screens.lock()) {
            release(r, 2, "com.y");
            g.worker.interrupt();
        }
        check(g.refused().equals("InterruptedException"), "A8: stopped as it was granted");
        check(
                app(r, "com.y") == null && app(r, "com.x").getString("heldBy").equals(session(1)),
                "A8: Y went back, X stays");
    }

    /** A11: someone queues for X while X's holder waits for more: the holder is sent back. */
    static void sentBack() throws Exception {
        Rig rig = new Rig();
        rig.request(1, "t", "com.x");
        rig.request(2, "t", "com.y");
        Call more = new Call(rig, 1, "t", "com.y", "com.w");
        awaitWaiting(rig, "com.w", 1);
        Call w = new Call(rig, 3, "t", "com.w");
        awaitWaiting(rig, "com.w", 2);
        check(!w.done(), "W is free, but session 1 asked for it first");
        Call x = new Call(rig, 4, "t", "com.x");
        check(wanted(more.refused()).equals(List.of("com.x")), "A11: sent back, X named");
        check(app(rig, "com.x").getString("heldBy").equals(session(1)), "A11: X still held");
        check(waitingFor(rig, "com.y") == 0, "A11: out of Y's line");
        check(w.granted().equals(List.of("com.w")), "A11: sending back withdraws: W moves on");
        awaitWaiting(rig, "com.x", 1);
        check(!x.done(), "A11: the newcomer waits for X as usual");
        release(rig, 1, "com.x");
        check(x.granted().equals(List.of("com.x")), "A11: and gets it once released");

        // sending one back grants a holder behind it the App the newcomer wants: that grant stays
        Rig r = new Rig();
        r.request(1, "t", "com.x");
        r.request(4, "t", "com.y");
        r.request(2, "t", "com.p");
        Call one = new Call(r, 1, "t", "com.y", "com.w");
        awaitWaiting(r, "com.w", 1);
        Call two = new Call(r, 2, "t", "com.w");
        awaitWaiting(r, "com.w", 2);
        Call three = new Call(r, 3, "t", "com.x", "com.w");
        check(wanted(one.refused()).equals(List.of("com.x")), "A11: session 1 sent back");
        check(two.granted().equals(List.of("com.p", "com.w")), "A11: session 2 keeps W");
        awaitWaiting(r, "com.w", 1);
        check(!three.done(), "A11: the newcomer waits for X and W");
        release(r, 2, "com.w");
        release(r, 1, "com.x");
        check(three.granted().equals(List.of("com.x", "com.w")), "A11: then gets both");
    }

    /** A12: someone already waits for X: X's holder asking for more fails at once, no effects. */
    static void refusedAtOnce() throws Exception {
        Rig rig = new Rig();
        rig.request(1, "t", "com.x");
        Call two = new Call(rig, 2, "t", "com.x");
        awaitWaiting(rig, "com.x", 1);
        check(
                wanted(refusal(() -> rig.request(1, "t", "com.y"))).equals(List.of("com.x")),
                "A12: Y is free, refused anyway");
        check(app(rig, "com.y") == null, "A12: Y neither held nor queued for");
        check(waitingFor(rig, "com.x") == 1 && !two.done(), "A12: the one waiting is untouched");

        // Y is free, but an earlier request for X and Y waits for this X
        Rig r = new Rig();
        r.request(1, "t", "com.x");
        Call xy = new Call(r, 2, "t", "com.x", "com.y");
        awaitWaiting(r, "com.y", 1);
        check(
                wanted(refusal(() -> r.request(1, "t", "com.y"))).equals(List.of("com.x")),
                "A12: refused while X+Y waits");
        check(
                app(r, "com.y").isNull("heldBy") && waitingFor(r, "com.y") == 1 && !xy.done(),
                "A12: no side effects");
        release(r, 1, "com.x");
        check(xy.granted().equals(List.of("com.x", "com.y")), "A12: the earlier request goes on");
    }

    /** Waits (5 seconds at most) until one of the calls is done; returns it. */
    static Call firstDone(Call a, Call b) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        while (!a.done() && !b.done()) {
            if (System.nanoTime() > end) throw new AssertionError("neither call is done");
            Thread.sleep(1);
        }
        return a.done() ? a : b;
    }

    /**
     * A13: session 1 holds X and waits for Y; session 2 holds Y and asks for X: session 2 fails,
     * session 1 waits on.
     */
    static void crossed() throws Exception {
        Rig rig = new Rig();
        rig.request(1, "t", "com.x");
        rig.request(2, "t", "com.y");
        Call one = new Call(rig, 1, "t", "com.y");
        awaitWaiting(rig, "com.y", 1);
        check(
                wanted(refusal(() -> rig.request(2, "t", "com.x"))).equals(List.of("com.y")),
                "A13: S2 fails at once");
        check(
                !one.done() && waitingFor(rig, "com.x") == 0,
                "A13: S1 not sent back, S2 not in line");
        release(rig, 2, "com.y");
        check(one.granted().equals(List.of("com.x", "com.y")), "A13: S1 gets Y once S2 lets go");

        // both at the same moment: one fails and the other waits, never both waiting
        for (int i = 0; i < 20; i++) {
            Rig r = new Rig();
            r.request(1, "t", "com.x");
            r.request(2, "t", "com.y");
            CountDownLatch go = new CountDownLatch(1);
            Call a = new Call(go, r, 1, "t", "com.y"), b = new Call(go, r, 2, "t", "com.x");
            go.countDown();
            Call failed = firstDone(a, b), waits = failed == a ? b : a;
            String kept = failed == a ? "com.x" : "com.y";
            check(wanted(failed.refused()).equals(List.of(kept)), "A13: one fails at once");
            awaitWaiting(r, kept, 1);
            check(!waits.done(), "A13: the other waits");
            release(r, failed == a ? 1 : 2, kept);
            check(waits.granted().size() == 2, "A13: and gets both");
        }
    }

    /** The QUEUE_FULL refusal's fields. */
    static JSONObject queueFull(Rig rig, int session, String... packages) throws Exception {
        try {
            rig.request(session, "t", packages);
        } catch (IOException e) {
            check(e.getMessage().startsWith("QUEUE_FULL: "), "expected QUEUE_FULL: " + e);
            return new JSONObject(e.getMessage().substring("QUEUE_FULL: ".length()));
        }
        throw new AssertionError("expected QUEUE_FULL");
    }

    /** A6: 3 in line per App at most; the 5th refusal in a row changes the advice. */
    static void queueFull() throws Exception {
        Rig rig = new Rig();
        rig.request(1, "t", "com.x");
        List<Call> line = new ArrayList<>();
        for (int t = 2; t <= 4; t++) {
            line.add(new Call(rig, t, "t", "com.x"));
            awaitWaiting(rig, "com.x", t - 1);
        }
        int screens = rig.host.slots.size();
        List<String> advice = new ArrayList<>();
        for (int attempt = 1; attempt <= 5; attempt++) {
            JSONObject r =
                    attempt == 3
                            ? queueFull(rig, 5, "com.free", "com.x")
                            : queueFull(rig, 5, "com.x");
            check(
                    r.getString("packageName").equals("com.x") && r.getInt("attempt") == attempt,
                    "A6: " + r);
            advice.add(r.getString("advice"));
        }
        check(
                new HashSet<>(advice.subList(0, 4)).size() == 1
                        && !advice.get(4).equals(advice.get(0)),
                "A6: the 5th refusal in a row changes the advice: " + advice);
        check(
                rig.host.slots.size() == screens && waitingFor(rig, "com.x") == 3,
                "A6: a refusal starts no screen and leaves the line alone");
        check(app(rig, "com.free") == null, "A6: nor holds the free App");

        // getting in line once starts the count over
        rig.screens.turnEnded(session(2)).join(5_000);
        check(line.get(0).refused().equals("TURN_ENDED"), "one leaves the line");
        Call five = new Call(rig, 5, "t", "com.x");
        awaitWaiting(rig, "com.x", 3);
        five.worker.interrupt();
        five.refused();
        new Call(rig, 6, "t", "com.x");
        awaitWaiting(rig, "com.x", 3);
        check(queueFull(rig, 5, "com.x").getInt("attempt") == 1, "A6: count starts over");
        check(queueFull(rig, 5, "com.x").getInt("attempt") == 2, "A6: and goes on");
        rig.screens.turnEnded(session(5)).join(5_000);
        check(queueFull(rig, 5, "com.x").getInt("attempt") == 1, "A6: a turn's end resets it too");
    }

    /** A7: every dedicated screen counts toward the limit: idle, waiting and still starting. */
    static void screenLimit() throws Exception {
        Path policy = Files.createTempDirectory("dedicated-limit").resolve("policy.json");
        WorkspacePolicy.store = new WorkspacePolicy.Store(policy.toFile());
        try {
            check(WorkspacePolicy.snapshot().getInt("maxActiveScreens") == 10, "A7: 10 by default");
            WorkspacePolicy.configure(new JSONObject().put("maxActiveScreens", 10));
            rejects(
                    "INVALID_WORKSPACE_LIMIT",
                    () -> WorkspacePolicy.configure(new JSONObject().put("maxActiveScreens", 11)));
            WorkspacePolicy.configure(new JSONObject().put("maxActiveScreens", 2));
            Rig rig = new Rig();
            rig.request(1, "t", "com.x");
            Call waits = new Call(rig, 2, "t", "com.x");
            awaitWaiting(rig, "com.x", 1);
            rejects("SCREEN_LIMIT_REACHED", () -> rig.request(3, "t", "com.y"));
            check(
                    rig.host.slots.size() == 3,
                    "A7: a waiting session's screen counts (default + 2)");
            rig.screens.turnEnded(session(1)).join(5_000);
            check(waits.granted().equals(List.of("com.x")), "the waiting one gets X");
            rig.request(3, "t", "com.y");
            check(rig.host.slots.size() == 3, "A7: an idle screen is reused at the limit");

            WorkspacePolicy.configure(new JSONObject().put("maxActiveScreens", 3));
            rig.host.startGate = new CountDownLatch(1);
            Call starting = new Call(rig, 4, "t", "com.z");
            awaitWaiting(rig, "com.z", 1);
            rejects("SCREEN_LIMIT_REACHED", () -> rig.request(5, "t", "com.w"));
            rig.host.startGate.countDown();
            check(
                    starting.granted().equals(List.of("com.z")),
                    "A7: a screen still starting counts");
        } finally {
            WorkspacePolicy.store = new WorkspacePolicy.Store(null);
        }
    }

    /** A8: a request stopped in line or while its screen starts. */
    static void stopped() throws Exception {
        // in line: out at once, and the next in line whose Apps are free gets them
        Rig rig = new Rig();
        rig.request(1, "t", "com.x");
        Call b = new Call(rig, 2, "b", "com.x", "com.y");
        awaitWaiting(rig, "com.y", 1);
        Call c = new Call(rig, 3, "c", "com.y");
        awaitWaiting(rig, "com.y", 2);
        rig.screens.turnEnded(session(2)).join(5_000);
        check(b.refused().equals("TURN_ENDED"), "A8: its turn ended");
        check(c.granted().equals(List.of("com.y")), "A8: the one behind it gets Y at once");
        check(waitingFor(rig, "com.x") == 0, "A8: gone from every line");
        Call d = new Call(rig, 4, "d", "com.x", "com.w");
        awaitWaiting(rig, "com.w", 1);
        Call e = new Call(rig, 5, "e", "com.w");
        awaitWaiting(rig, "com.w", 2);
        d.worker.interrupt();
        check(d.refused().equals("InterruptedException"), "A8: its tool call was cancelled");
        check(e.granted().equals(List.of("com.w")), "A8: the one behind it moves up");
        check(use(rig, 4, DedicatedScreens.Use.LOOK, null) > 0, "its screen stays for the turn");

        // while its new screen starts: the screen still starts and is left idle for the next one
        for (boolean interrupt : new boolean[] {false, true}) {
            Rig r = new Rig();
            r.host.startGate = new CountDownLatch(1);
            Call f = new Call(r, 1, "f", "com.v");
            awaitWaiting(r, "com.v", 1);
            if (interrupt) f.worker.interrupt();
            else {
                r.screens.turnEnded(session(1));
                awaitWaiting(r, "com.v", 0);
            }
            r.host.startGate.countDown();
            check(
                    f.refused().equals(interrupt ? "InterruptedException" : "TURN_ENDED"),
                    "A8: stopped while starting");
            check(
                    r.host.slots.size() == 2
                            && r.screens.status(session(0)).getInt("idleScreens") == 1,
                    "A8: the screen started and is idle");
            r.host.startGate = null;
            r.request(2, "g", "com.v");
            check(r.host.slots.size() == 2, "A8: the next session takes it, no second screen");
        }

        // the stop lands while the tool waits for phone-display start to exit (case B): the screen
        // is
        // up but its descriptor is lost; it is read back with status, also when the same stop's
        // second interrupt cuts that read
        for (int again : new int[] {0, 1}) {
            Rig r = new Rig();
            r.host.startGate = new CountDownLatch(1);
            r.host.loseDescriptor = true;
            r.host.statusInterrupts.set(again);
            Call f = new Call(r, 1, "f", "com.v");
            awaitWaiting(r, "com.v", 1);
            f.worker.interrupt();
            r.host.startGate.countDown();
            check(f.refused().equals("InterruptedException"), "A8: stopped while starting (B)");
            check(
                    r.host.slots.size() == 2
                            && r.screens.status(session(0)).getInt("idleScreens") == 1,
                    "A8: the screen is read back and left idle (B, " + again + " more interrupts)");
            r.host.startGate = null;
            r.host.loseDescriptor = false;
            r.request(2, "g", "com.v");
            check(r.host.slots.size() == 2, "A8: the next session takes it, no second screen (B)");
        }

        // stopped at the very moment it is granted: the Apps go back at once. Holding the table
        // lock (DedicatedScreens' own monitor) pins that moment: g is granted but cannot return
        // yet.
        Rig r = new Rig();
        r.request(1, "t", "com.x");
        Call g = new Call(r, 2, "g", "com.x");
        awaitWaiting(r, "com.x", 1);
        try (CleanupWork.Held ledgerHeld = r.screens.lock()) {
            release(r, 1, "com.x");
            g.worker.interrupt();
        }
        check(g.refused().equals("InterruptedException"), "A8: stopped as it was granted");
        check(app(r, "com.x") == null, "A8: X went back at once");
    }

    static void awaitParked(Thread t) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        while (t.getState() != Thread.State.WAITING) {
            if (System.nanoTime() > end) throw new AssertionError("parked: " + t.getState());
            Thread.sleep(5);
        }
    }

    /**
     * C5: a release waits for the step in flight; a release and two requests at once: one holder.
     */
    static void releaseTiming() throws Exception {
        Rig rig = new Rig();
        int d = rig.request(1, "t", "com.x").getInt("displayId");
        rig.tasks.open("com.x", d);
        CountDownLatch inside = new CountDownLatch(1), done = new CountDownLatch(1);
        daemon(() ->
                rig.screens.withScreen(
                        session(1),
                        DedicatedScreens.Use.ACT,
                        null,
                        s -> {
                            inside.countDown();
                            done.await();
                            return new JSONObject();
                        }));
        check(inside.await(5, TimeUnit.SECONDS), "the step is running");
        Call b = new Call(rig, 2, "b", "com.x");
        awaitWaiting(rig, "com.x", 1);
        Thread releasing = daemon(() -> release(rig, 1, "com.x"));
        awaitParked(releasing);
        check(!b.done() && tasksOn(rig, d).size() == 1, "C5: nobody gets X while the step runs");
        rig.tasks.removing = new CountDownLatch(1);
        rig.tasks.removeGate = new CountDownLatch(1);
        done.countDown();
        check(rig.tasks.removing.await(5, TimeUnit.SECONDS), "the release is removing X's task");
        check(
                app(rig, "com.x").getString("heldBy").equals(session(1)),
                "C5: X stays put while its task is being removed");
        rig.tasks.removeGate.countDown();
        check(b.granted().equals(List.of("com.x")), "C5: then the one waiting gets it");
        check(tasksOn(rig, d).isEmpty(), "C5: X's task on the old screen is gone");

        for (int i = 0; i < 20; i++) {
            Rig r = new Rig();
            r.request(1, "t", "com.x");
            CountDownLatch go = new CountDownLatch(1);
            Call two = new Call(go, r, 2, "t", "com.x"), three = new Call(go, r, 3, "t", "com.x");
            Thread rel =
                    daemon(() -> {
                        go.await();
                        release(r, 1, "com.x");
                    });
            go.countDown();
            rel.join(5_000);
            // settled: one of them holds X, the other waits (both granted would never get here)
            long end = System.nanoTime() + 5_000_000_000L;
            JSONObject x;
            while ((x = app(r, "com.x")) == null
                    || x.getInt("waiting") != 1
                    || !List.of(session(2), session(3)).contains(x.opt("heldBy"))) {
                if (System.nanoTime() > end)
                    throw new AssertionError("C5: one holder, the other waits: " + x);
                Thread.sleep(5);
            }
            Call won = x.getString("heldBy").equals(session(2)) ? two : three;
            Call lost = won == two ? three : two;
            check(won.granted().equals(List.of("com.x")) && !lost.done(), "C5: " + x);
        }
    }

    static Map<String, JSONObject> rows(Rig rig) throws Exception {
        Map<String, JSONObject> rows = new HashMap<>();
        for (Object o : rig.screens.list())
            rows.put(((JSONObject) o).getString("threadId"), (JSONObject) o);
        return rows;
    }

    /** R2: waiting_for_app and waitReason in the UI list. */
    static void listWaiting() throws Exception {
        // host tests have no getprop, so the system language is the process default
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
        try {
            listWaitingIn();
        } finally {
            Locale.setDefault(before);
        }
    }

    static void listWaitingIn() throws Exception {
        Rig rig = new Rig();
        rig.request(1, "Book a table", "com.x");
        Call b = new Call(rig, 2, "Tell Wang", "com.x");
        awaitWaiting(rig, "com.x", 1);
        Call c = new Call(rig, 3, "Share it", "com.y", "com.x");
        awaitWaiting(rig, "com.x", 2);
        Map<String, JSONObject> rows = rows(rig);
        JSONObject one = rows.get(session(1)),
                two = rows.get(session(2)),
                three = rows.get(session(3));
        check(one.getString("status").equals("running") && one.isNull("waitReason"), "R2: " + one);
        check(
                two.getString("status").equals("waiting_for_app")
                        && two.getString("taskTitle").equals("Tell Wang"),
                "R2: " + two);
        JSONObject reason = two.getJSONObject("waitReason");
        check(
                reason.getString("code").equals("WAITING_FOR_APP")
                        && reason.getString("text").equals("在等X"),
                "R2: nobody ahead: " + reason);
        check(
                three.getJSONObject("waitReason").getString("text").equals("在等X，前面还有 1 个"),
                "R2: the App it waits for, one ahead: " + three);
        Locale.setDefault(Locale.ENGLISH);
        check(
                rows(rig)
                        .get(session(3))
                        .getJSONObject("waitReason")
                        .getString("text")
                        .equals("Waiting for X, 1 ahead"),
                "R2: in the system language");
        Locale.setDefault(Locale.JAPANESE);
        check(
                rows(rig)
                        .get(session(3))
                        .getJSONObject("waitReason")
                        .getString("text")
                        .equals("Waiting for X, 1 ahead"),
                "R2: a language without its own sentence gets English");
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
        rig.screens.turnEnded(session(1)).join(5_000);
        b.granted();
        rows = rows(rig);
        check(
                rows.get(session(2)).getString("status").equals("running")
                        && rows.get(session(2)).isNull("waitReason"),
                "R2: granted: " + rows.get(session(2)));
        check(
                rows.get(session(3)).getJSONObject("waitReason").getString("text").equals("在等X"),
                "R2: moved up: " + rows.get(session(3)));
        check(!c.done(), "still waiting");
    }

    static List<String> appsLine(JSONObject status) {
        List<String> out = new ArrayList<>();
        for (Object o : status.getJSONArray("apps")) {
            JSONObject a = (JSONObject) o;
            out.add(
                    a.getString("packageName")
                            + " "
                            + (a.isNull("heldBy") ? "-" : a.getString("heldBy").substring(33))
                            + " "
                            + a.getInt("waiting"));
        }
        return out;
    }

    /** screen_status: screens' sessions, Apps' holders and lines, the same as the ledger says. */
    static void screenStatus() throws Exception {
        Rig rig = new Rig();
        rig.request(4, "t", "com.q");
        rig.screens.turnEnded(session(4)).join(5_000);
        int d = rig.request(1, "Book a table", "com.x", "com.y").getInt("displayId");
        rig.tasks.open("com.x", d);
        new Call(rig, 2, "Tell Wang", "com.x");
        awaitWaiting(rig, "com.x", 1);
        new Call(rig, 3, "c", "com.x", "com.z");
        awaitWaiting(rig, "com.x", 2);
        JSONObject s = rig.screens.status(session(2));
        check(
                s.getString("you").equals(session(2))
                        && s.getInt("maxScreens") == 10
                        && s.getInt("idleScreens") == 0,
                "status: " + s);
        Map<String, JSONObject> screens = new HashMap<>();
        for (Object o : s.getJSONArray("screens"))
            screens.put(((JSONObject) o).getString("threadId"), (JSONObject) o);
        check(screens.size() == 3, "one entry per screen in use: " + s);
        JSONObject one = screens.get(session(1)), two = screens.get(session(2));
        check(
                one.getString("status").equals("running")
                        && one.getString("topApp").equals("com.x")
                        && one.getString("taskTitle").equals("Book a table"),
                "status: " + one);
        check(
                two.getString("status").equals("waiting_for_app")
                        && two.getString("topApp").isEmpty()
                        && two.getString("taskTitle").equals("Tell Wang"),
                "status: " + two);
        check(
                appsLine(s).equals(List.of("com.x 001 2", "com.y 001 0", "com.z - 1")),
                "holders and lines: " + appsLine(s));
        rig.screens.turnEnded(session(1)).join(5_000);
        s = rig.screens.status(session(2));
        check(
                s.getInt("idleScreens") == 1
                        && appsLine(s).equals(List.of("com.x 002 1", "com.z - 1")),
                "after session 1's turn: " + appsLine(s));
    }

    static final String RETURN_TO = "Haidilao Wangfujing booking page, tomorrow, 2 people";

    /** The session shows a card, as NativeBridge reports an ask_user call. */
    static void ask(Rig rig, int session) {
        rig.screens.asked(session(session), "Which time?", RETURN_TO);
    }

    /** Who holds pkg by screen_status: the session's last three digits, or "-". */
    static String holder(Rig rig, String pkg) throws Exception {
        JSONObject app = app(rig, pkg);
        return app == null || app.isNull("heldBy") ? "-" : app.getString("heldBy").substring(33);
    }

    /**
     * D1, D2, D3: a card keeps the session's Apps for 30 seconds, then hands all of them over once
     * someone waits for one.
     */
    static void cardHandsOver() throws Exception {
        // D1: someone waits within the 30 seconds
        Rig rig = new Rig();
        int d = rig.request(1, "t", "com.x", "com.y").getInt("displayId");
        rig.tasks.open("com.x", d);
        rig.tasks.open("com.jumped.into", d);
        int usersZ = rig.tasks.open("com.z", 0);
        ask(rig, 1);
        Call waits = new Call(rig, 2, "t", "com.x");
        awaitWaiting(rig, "com.x", 1);
        rig.clock.advance(29_999);
        check(holder(rig, "com.x").equals("001") && !waits.done(), "D1: kept for 30 seconds");
        rig.clock.advance(1);
        check(waits.granted().equals(List.of("com.x")), "D1: handed over at 30 seconds");
        check(holder(rig, "com.y").equals("-"), "D1: all of its Apps, not only the one wanted");
        check(
                tasksOn(rig, d).isEmpty() && tasksOn(rig, 0).equals(List.of(usersZ)),
                "D1: its whole screen cleared, the main screen untouched");
        rejects("APPS_NOT_REQUESTED", () -> use(rig, 1, DedicatedScreens.Use.ACT, null));
        check(use(rig, 1, DedicatedScreens.Use.LOOK, null) == d, "D1: the screen stays its own");

        // D2: somebody comes only after the 30 seconds
        Rig late = new Rig();
        late.request(1, "t", "com.x");
        ask(late, 1);
        late.clock.advance(60_000);
        check(holder(late, "com.x").equals("001"), "D2: nobody waits, kept");
        check(
                new Call(late, 2, "t", "com.x").granted().equals(List.of("com.x")),
                "D2: handed over the moment somebody waits");

        // D3: answered in time; that card's timer counts for nothing, not for the next card either
        Rig quick = new Rig();
        quick.request(1, "t", "com.x");
        ask(quick, 1);
        Call w = new Call(quick, 2, "t", "com.x");
        awaitWaiting(quick, "com.x", 1);
        quick.clock.advance(10_000);
        check(
                quick.screens.questionEnded(session(1)) == null,
                "D3: answered in time, nothing handed over");
        quick.clock.advance(10_000);
        // a second card at 20 s
        ask(quick, 1);
        // 35 s: past the first card's 30 seconds
        quick.clock.advance(15_000);
        check(
                holder(quick, "com.x").equals("001") && !w.done(),
                "D3: the first card's timer does not count for the second");
        // 50 s: the second card's 30 seconds
        quick.clock.advance(15_000);
        check(w.granted().equals(List.of("com.x")), "D3: the second card's own 30 seconds");

        // D3: nobody waits for its Apps; a request for another App does not count
        Rig calm = new Rig();
        calm.request(1, "t", "com.x");
        ask(calm, 1);
        calm.clock.advance(3_600_000);
        calm.request(3, "t", "com.y");
        check(holder(calm, "com.x").equals("001"), "D3: nobody waits for X, kept an hour");
    }

    /**
     * D4: after a hand-over the answer says so, with returnTo; the session requests its Apps again.
     */
    static void answerAfterHandOver() throws Exception {
        Rig rig = new Rig();
        rig.request(1, "t", "com.x", "com.y");
        ask(rig, 1);
        Call waits = new Call(rig, 2, "t", "com.x");
        awaitWaiting(rig, "com.x", 1);
        rig.clock.advance(30_000);
        waits.granted();
        JSONObject note = rig.screens.questionEnded(session(1));
        check(
                note != null
                        && note.getJSONArray("packageNames")
                                .toList()
                                .equals(List.of("com.x", "com.y"))
                        && note.getString("returnTo").equals(RETURN_TO)
                        && note.has("advice"),
                "D4: the Apps handed over and returnTo: " + note);
        check(rig.screens.questionEnded(session(1)) == null, "D4: said once");
        Call back = new Call(rig, 1, "t", "com.x", "com.y");
        awaitWaiting(rig, "com.x", 1);
        release(rig, 2, "com.x");
        check(back.granted().equals(List.of("com.x", "com.y")), "D4: gets them again once free");
    }

    /**
     * D6: the hand-over and the answer at the same moment: either handed over and the answer says
     * so, or answered first and nothing handed over.
     */
    static void answerRacesHandOver() throws Exception {
        for (int i = 0; i < 40; i++) {
            Rig rig = new Rig();
            rig.request(1, "t", "com.x");
            ask(rig, 1);
            Call waits = new Call(rig, 2, "t", "com.x");
            awaitWaiting(rig, "com.x", 1);
            rig.clock.advance(29_999);
            CountDownLatch go = new CountDownLatch(1);
            Thread timer =
                    daemon(() -> {
                        go.await();
                        rig.clock.advance(1);
                    });
            go.countDown();
            JSONObject note = rig.screens.questionEnded(session(1));
            timer.join(5_000);
            if (note != null)
                check(waits.granted().equals(List.of("com.x")), "D6: said handed over, and it was");
            else
                check(
                        holder(rig, "com.x").equals("001") && !waits.done(),
                        "D6: answered first, kept");
        }

        // the answer comes while the handed-over Apps' tasks are still being removed
        Rig rig = new Rig();
        int d = rig.request(1, "t", "com.x").getInt("displayId");
        rig.tasks.open("com.x", d);
        ask(rig, 1);
        Call waits = new Call(rig, 2, "t", "com.x");
        awaitWaiting(rig, "com.x", 1);
        rig.tasks.removing = new CountDownLatch(1);
        rig.tasks.removeGate = new CountDownLatch(1);
        rig.clock.advance(30_000);
        check(rig.tasks.removing.await(5, TimeUnit.SECONDS), "the hand-over is removing tasks");
        check(
                rig.screens.questionEnded(session(1)) != null,
                "D6: decided before the answer, said so");
        Call back = new Call(rig, 1, "t", "com.x");
        awaitWaiting(rig, "com.x", 2);
        check(!waits.done() && !back.done(), "X goes to nobody until its tasks are gone");
        rig.tasks.removeGate.countDown();
        check(waits.granted().equals(List.of("com.x")), "then to the first in line");
        rig.tasks.removeGate = null;
        check(!back.done(), "the session waits behind it");
    }

    /** Whether screen_status marks the session's screen reclaimable. */
    static boolean reclaimable(Rig rig, int session) throws Exception {
        for (Object o : rig.screens.status(session(0)).getJSONArray("screens")) {
            JSONObject s = (JSONObject) o;
            if (s.getString("threadId").equals(session(session)))
                return s.getBoolean("reclaimable");
        }
        throw new AssertionError("no screen for session " + session);
    }

    /**
     * A screen whose session holds no App and waits for none can be taken, 30 seconds on, by a
     * request that finds the limit reached.
     */
    static void emptyScreen() throws Exception {
        Path policy = Files.createTempDirectory("dedicated-empty").resolve("policy.json");
        WorkspacePolicy.store = new WorkspacePolicy.Store(policy.toFile());
        try {
            WorkspacePolicy.configure(new JSONObject().put("maxActiveScreens", 2));
            Rig rig = new Rig();
            JSONObject a = rig.request(1, "t", "com.x");
            int d = a.getInt("displayId");
            rig.tasks.open("com.x", d);
            rig.tasks.open("com.jumped.into", d);
            release(rig, 1, "com.x");
            check(tasksOn(rig, d).isEmpty(), "releasing the last App clears the whole screen");
            check(
                    rig.request(1, "t", "com.y")
                            .getString("screenKey")
                            .equals(a.getString("screenKey")),
                    "release, then request: the same screen");
            release(rig, 1, "com.y");
            rig.request(2, "t", "com.z");
            check(
                    rig.host.slots.size() == 3,
                    "below the limit a new screen opens, the empty one is left alone (default +"
                            + " 2)");
            rejects("SCREEN_LIMIT_REACHED", () -> rig.request(3, "t", "com.w"));
            rig.clock.advance(29_999);
            check(!reclaimable(rig, 1), "not within 30 seconds");
            rejects("SCREEN_LIMIT_REACHED", () -> rig.request(3, "t", "com.w"));
            rig.clock.advance(1);
            check(
                    reclaimable(rig, 1) && !reclaimable(rig, 2),
                    "screen_status marks it after 30 seconds");
            WorkspacePolicy.configure(new JSONObject().put("maxActiveScreens", 3));
            check(
                    !rig.request(5, "t", "com.u")
                                    .getString("screenKey")
                                    .equals(a.getString("screenKey"))
                            && rows(rig).containsKey(session(1)),
                    "below the limit a new screen opens, even with one to take");
            // All three screens are still occupied; exercise reclaim at equality, not above it.
            WorkspacePolicy.configure(new JSONObject().put("maxActiveScreens", 3));
            check(
                    rig.request(3, "t", "com.w")
                            .getString("screenKey")
                            .equals(a.getString("screenKey")),
                    "at the limit it is taken");
            check(!rows(rig).containsKey(session(1)), "its session leaves the UI list");
            rejects("APPS_NOT_REQUESTED", () -> use(rig, 1, DedicatedScreens.Use.LOOK, null));
            check(
                    rig.screens.turnEnded(session(1)) == null,
                    "its turn's end has nothing to release");

            // waiting in line, or holding Apps: never taken
            release(rig, 2, "com.z");
            Call line = new Call(rig, 2, "t", "com.w");
            awaitWaiting(rig, "com.w", 1);
            rig.clock.advance(60_000);
            rejects("SCREEN_LIMIT_REACHED", () -> rig.request(4, "t", "com.v"));
            check(!line.done(), "still in line, on its own screen");
        } finally {
            WorkspacePolicy.store = new WorkspacePolicy.Store(null);
        }
    }

    /**
     * D5: the session's empty screen is taken while its card waits; the answer still carries
     * returnTo, and the session gets a screen again by requesting.
     */
    static void screenTaken() throws Exception {
        Path policy = Files.createTempDirectory("dedicated-taken").resolve("policy.json");
        WorkspacePolicy.store = new WorkspacePolicy.Store(policy.toFile());
        try {
            WorkspacePolicy.configure(new JSONObject().put("maxActiveScreens", 2));
            Rig rig = new Rig();
            String key = rig.request(1, "t", "com.x").getString("screenKey");
            ask(rig, 1);
            Call waits = new Call(rig, 2, "t", "com.x");
            awaitWaiting(rig, "com.x", 1);
            rig.clock.advance(30_000);
            waits.granted();
            check(
                    rows(rig).get(session(1)).getString("status").equals("waiting_for_user"),
                    "D5: listed while it waits for the user");
            rig.clock.advance(30_000);
            check(
                    rig.request(3, "t", "com.y").getString("screenKey").equals(key),
                    "D5: at the limit its empty screen is taken");
            check(!rows(rig).containsKey(session(1)), "D5: no longer listed");
            JSONObject note = rig.screens.questionEnded(session(1));
            check(
                    note != null && note.getString("returnTo").equals(RETURN_TO),
                    "D5: the answer still carries returnTo");
            rejects("SCREEN_LIMIT_REACHED", () -> rig.request(1, "t", "com.y"));
            rig.screens.turnEnded(session(3)).join(5_000);
            check(
                    held(rig.request(1, "t", "com.y")).equals(List.of("com.y")),
                    "D5: requests a screen again like anyone");
        } finally {
            WorkspacePolicy.store = new WorkspacePolicy.Store(null);
        }
    }

    /** R2: waiting_for_user in the UI list and in screen_status. */
    static void listWaitingForUser() throws Exception {
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
        try {
            Rig rig = new Rig();
            rig.request(1, "Book a table", "com.x");
            rig.screens.asked(session(1), "订几点？", RETURN_TO);
            JSONObject row = rows(rig).get(session(1));
            JSONObject reason = row.getJSONObject("waitReason");
            check(
                    row.getString("status").equals("waiting_for_user")
                            && reason.getString("code").equals("WAITING_FOR_USER")
                            && reason.getString("text").equals("在等你回答：订几点？"),
                    "R2: " + row);
            Locale.setDefault(Locale.ENGLISH);
            check(
                    rows(rig)
                            .get(session(1))
                            .getJSONObject("waitReason")
                            .getString("text")
                            .equals("Waiting for your answer: 订几点？"),
                    "R2: the sentence in the system language, the card's words as written");
            JSONObject s = rig.screens.status(session(0)).getJSONArray("screens").getJSONObject(0);
            check(
                    s.getString("status").equals("waiting_for_user")
                            && !s.getBoolean("reclaimable"),
                    "screen_status: " + s);
            rig.screens.questionEnded(session(1));
            row = rows(rig).get(session(1));
            check(
                    row.getString("status").equals("running") && row.isNull("waitReason"),
                    "R2: answered: " + row);
            rig.screens.asked(session(1), "Again?", "");
            rig.screens.turnEnded(session(1)).join(5_000);
            rig.request(1, "next turn", "com.y");
            check(
                    rows(rig).get(session(1)).getString("status").equals("running"),
                    "a turn's end drops its card");
        } finally {
            Locale.setDefault(before);
        }
    }

    /** The session's screen as the UI names it: screenKey, then generation. */
    static String[] lease(Rig rig, int session) throws Exception {
        JSONObject row = rows(rig).get(session(session));
        return new String[] {row.getString("screenKey"), row.getString("generation")};
    }

    /**
     * A pause or takeover all the way through, as WorkspaceControl does it: owner user or paused.
     */
    static void control(Rig rig, int session, String owner) throws Exception {
        String[] l = lease(rig, session);
        rig.screens.beginPause(session(session), l[0], l[1]);
        rig.screens.finishControl(session(session), l[0], l[1], owner);
    }

    /** B2, E1, R2: taking over or pausing only borrows the screen. */
    static void takeOver() throws Exception {
        Rig rig = new Rig();
        int d = rig.request(1, "Book a table", "com.x").getInt("displayId");
        rig.request(3, "t", "com.z");
        Call more = new Call(rig, 1, "t", "com.z");
        awaitWaiting(rig, "com.z", 1);
        String[] l = lease(rig, 1);
        rejects("SCREEN_OWNER_MISMATCH", () -> rig.screens.exact(session(1), l[0], "x"));
        rejects("SCREEN_NOT_BOUND", () -> rig.screens.exact(session(2), l[0], l[1]));
        JSONObject row = rows(rig).get(session(1));
        check(
                row.getString("controlOwner").equals("agent")
                        && row.getLong("controlRevision") == 0
                        && !row.getBoolean("canResume"),
                "R2: before: " + row);
        rig.screens.beginPause(session(1), l[0], l[1]);
        check(
                more.refused().startsWith("SCREEN_AGENT_PAUSED"),
                "E1: its request in line is withdrawn");
        check(waitingFor(rig, "com.z") == 0, "E1: out of Z's line");
        row = rows(rig).get(session(1));
        check(
                row.getString("status").equals("stopping") && !row.getBoolean("canResume"),
                "R2: on the way: " + row);
        rig.screens.finishControl(session(1), l[0], l[1], "user");
        row = rows(rig).get(session(1));
        check(
                row.getString("status").equals("user_control")
                        && row.getString("controlOwner").equals("user")
                        && row.getLong("controlRevision") == 2
                        && row.getBoolean("canResume")
                        && row.getString("taskTitle").equals("Book a table"),
                "R2: user_control: " + row);
        check(holder(rig, "com.x").equals("001"), "E1: it still holds X");
        Call b = new Call(rig, 2, "t", "com.x");
        awaitWaiting(rig, "com.x", 1);
        release(rig, 3, "com.z");
        check(!b.done() && waitingFor(rig, "com.x") == 1, "E1: whoever waits for X keeps waiting");

        for (DedicatedScreens.Use u : List.of(DedicatedScreens.Use.ACT, DedicatedScreens.Use.OPEN))
            rejects("SCREEN_AGENT_PAUSED", () -> use(rig, 1, u, "com.x"));
        check(use(rig, 1, DedicatedScreens.Use.LOOK, null) == d, "B2: a screenshot still runs");
        rejects("SCREEN_AGENT_PAUSED", () -> rig.request(1, "t", "com.y"));
        rejects("SCREEN_AGENT_PAUSED", () -> release(rig, 1, "com.x"));
        check(holder(rig, "com.x").equals("001"), "B2: a refused release keeps X");
        check(!rig.screens.agentAdmitted(session(1)), "E1: no turn for the session meanwhile");
        rejects("SCREEN_AGENT_PAUSED", () -> rig.screens.admission(session(1)));

        long revision = row.getLong("controlRevision");
        JSONObject tap =
                rig.screens.userInput(
                        session(1),
                        l[0],
                        l[1],
                        revision,
                        s -> new JSONObject().put("ran", s.displayId));
        check(tap.getInt("ran") == d, "the user's tap runs on the screen");
        rejects(
                "SCREEN_CONTROL_CHANGED",
                () -> rig.screens.userInput(session(1), l[0], l[1], revision - 1, s -> null));

        control(rig, 1, "paused");
        row = rows(rig).get(session(1));
        check(
                row.getString("status").equals("paused")
                        && row.getString("controlOwner").equals("paused")
                        && row.getLong("controlRevision") == 4,
                "R2: paused: " + row);
        rejects(
                "SCREEN_USER_CONTROL_REQUIRED",
                () -> rig.screens.userInput(session(1), l[0], l[1], 4, s -> null));
        rejects("SCREEN_AGENT_PAUSED", () -> use(rig, 1, DedicatedScreens.Use.ACT, null));
        check(!b.done(), "E1: paused, X still not handed on");

        rig.screens.beginPause(session(1), l[0], l[1]);
        rig.screens.controlFailed(session(1), l[0], l[1], "WORKSPACE_EXECUTION_STOP_UNCONFIRMED");
        row = rows(rig).get(session(1));
        check(
                row.getString("status").equals("paused")
                        && row.getString("lastError").equals("WORKSPACE_EXECUTION_STOP_UNCONFIRMED")
                        && row.getBoolean("canResume"),
                "a failed stop leaves it paused, with the error, still able to give back: " + row);
        rig.host.unplug(l[0]);
        rejects(
                "HOST_NOT_RUNNING",
                () -> rig.screens.finishControl(session(1), l[0], l[1], "agent"));
    }

    /** E1: a takeover drops the session's card, so its Apps are not handed over meanwhile. */
    static void takeOverCard() throws Exception {
        Rig rig = new Rig();
        rig.request(1, "t", "com.x");
        ask(rig, 1);
        control(rig, 1, "user");
        Call b = new Call(rig, 2, "t", "com.x");
        awaitWaiting(rig, "com.x", 1);
        rig.clock.advance(60_000);
        check(!b.done() && holder(rig, "com.x").equals("001"), "E1: X is not handed over");
        check(
                rows(rig).get(session(1)).getString("status").equals("user_control"),
                "R2: user_control, not waiting_for_user");
    }

    /** E1: a screen under takeover is never taken, even empty and at the limit. */
    static void takeOverNotTaken() throws Exception {
        Path policy = Files.createTempDirectory("dedicated-takeover").resolve("policy.json");
        WorkspacePolicy.store = new WorkspacePolicy.Store(policy.toFile());
        try {
            WorkspacePolicy.configure(new JSONObject().put("maxActiveScreens", 2));
            Rig rig = new Rig();
            rig.request(1, "t", "com.x");
            release(rig, 1, "com.x");
            control(rig, 1, "user");
            rig.request(2, "t", "com.y");
            rig.clock.advance(60_000);
            check(!reclaimable(rig, 1), "E1: screen_status does not mark it");
            rejects("SCREEN_LIMIT_REACHED", () -> rig.request(3, "t", "com.z"));
            check(rows(rig).containsKey(session(1)), "E1: still the session's");
        } finally {
            WorkspacePolicy.store = new WorkspacePolicy.Store(null);
        }
    }

    /** E2: turns that end because of a takeover keep the screen and the Apps. */
    static void takeOverTurnEnds() throws Exception {
        Rig rig = new Rig();
        int d = rig.request(1, "t", "com.x").getInt("displayId");
        rig.tasks.open("com.x", d);
        String[] l = lease(rig, 1);
        rig.screens.beginPause(session(1), l[0], l[1]);
        check(
                rig.screens.turnEnded(session(1)) == null,
                "E2: the turn the takeover stopped ends: nothing released");
        check(
                tasksOn(rig, d).size() == 1 && holder(rig, "com.x").equals("001"),
                "E2: X and its page stay");
        rig.screens.finishControl(session(1), l[0], l[1], "user");
        check(!rig.screens.turnStarted(session(1), null), "E2: a turn meanwhile is refused");
        check(
                rig.screens.turnEnded(session(1)) == null,
                "E2: the refused turn ends: nothing released");
        check(
                tasksOn(rig, d).size() == 1
                        && holder(rig, "com.x").equals("001")
                        && rows(rig).get(session(1)).getString("status").equals("user_control"),
                "E2: still the session's, under user control");
    }

    /**
     * E2: a turn end races the start of a pause. Either the turn ends first (released, and the
     * pause finds no screen), or the pause starts first (kept).
     */
    static void takeOverRace() throws Exception {
        Rig first = new Rig();
        first.tasks.open("com.x", first.request(1, "t", "com.x").getInt("displayId"));
        String[] old = lease(first, 1);
        first.tasks.removing = new CountDownLatch(1);
        first.tasks.removeGate = new CountDownLatch(1);
        Thread releasing = first.screens.turnEnded(session(1));
        check(first.tasks.removing.await(5, TimeUnit.SECONDS), "the release is removing tasks");
        rejects("SCREEN_NOT_BOUND", () -> first.screens.beginPause(session(1), old[0], old[1]));
        first.tasks.removeGate.countDown();
        releasing.join(5_000);
        check(
                holder(first, "com.x").equals("-") && rows(first).isEmpty(),
                "E2: the turn ended first: released, nothing to pause");
        int kept = 0, released = 0;
        for (int i = 0; i < 200; i++) {
            Rig rig = new Rig();
            rig.request(1, "t", "com.x");
            String[] l = lease(rig, 1);
            CyclicBarrier go = new CyclicBarrier(2);
            CompletableFuture<Thread> ended = new CompletableFuture<>();
            Thread turn =
                    daemon(() -> {
                        go.await(5, TimeUnit.SECONDS);
                        ended.complete(rig.screens.turnEnded(session(1)));
                    });
            go.await(5, TimeUnit.SECONDS);
            String paused = "";
            try {
                rig.screens.beginPause(session(1), l[0], l[1]);
            } catch (IOException refused) {
                paused = refused.getMessage();
            }
            turn.join(5_000);
            Thread release = ended.get(5, TimeUnit.SECONDS);
            if (release != null) release.join(5_000);
            if (paused.isEmpty()) {
                kept++;
                check(
                        release == null && holder(rig, "com.x").equals("001"),
                        "E2: paused first, so kept");
            } else {
                released++;
                check(
                        paused.equals("SCREEN_NOT_BOUND")
                                && release != null
                                && holder(rig, "com.x").equals("-")
                                && rows(rig).isEmpty(),
                        "E2: ended first, so released and nothing to pause: " + paused);
            }
        }
        check(kept + released == 200, "E2: one of the two each time");
    }

    /**
     * E3: give back and continue; the next turn the broker starts runs, the screen still its own.
     */
    static void giveBack() throws Exception {
        Rig rig = new Rig();
        int d = rig.request(1, "t", "com.x").getInt("displayId");
        rig.tasks.open("com.x", d);
        PhoneExecutionScreens.Admission stale = rig.screens.admission(session(1));
        control(rig, 1, "user");
        Call b = new Call(rig, 2, "t", "com.x");
        awaitWaiting(rig, "com.x", 1);
        control(rig, 1, "agent");
        JSONObject row = rows(rig).get(session(1));
        check(
                row.getString("controlOwner").equals("agent")
                        && row.getString("status").equals("running")
                        && !row.getBoolean("canResume"),
                "E3: control back to the agent: " + row);
        check(
                !rig.screens.admissionCurrent(stale),
                "E3: a turn queued before the takeover is not let in");
        check(
                !rig.screens.turnStarted(session(1), null),
                "E3: nor a turn the broker did not start");
        check(rig.screens.turnEnded(session(1)) == null, "E3: whose end releases nothing");
        PhoneExecutionScreens.Admission grant = rig.screens.admission(session(1));
        check(rig.screens.turnStarted(session(1), grant), "E3: the give-back's own turn runs");
        check(
                use(rig, 1, DedicatedScreens.Use.ACT, null) == d
                        && tasksOn(rig, d).size() == 1
                        && holder(rig, "com.x").equals("001")
                        && !b.done(),
                "E3: on its own screen, X and its page still its own");
        check(
                rig.screens.turnStarted(session(1), null),
                "a later turn needs no grant: the takeover is over");
        rig.screens.turnEnded(session(1)).join(5_000);
        check(b.granted().contains("com.x"), "E3: that turn's end releases as usual");
    }

    /**
     * E4: a sub-agent's screen given back: the turn its parent wakes it in gets in once the broker
     * has let it (resumeGranted), not before.
     */
    static void subAgentGiveBack() throws Exception {
        Rig rig = new Rig();
        rig.codex.parents.put(session(4), session(9));
        String key = rig.request(4, "t", "com.x").getString("screenKey");
        control(rig, 4, "user");
        control(rig, 4, "agent");
        check(
                !rig.screens.turnStarted(session(4), null),
                "E4: without the pass the sub-agent's turn is refused");
        String[] l = lease(rig, 4);
        rig.screens.resumeGranted(session(4), l[0], l[1]);
        check(
                rig.screens.turnStarted(session(4), null),
                "E4: with it, the turn its parent wakes runs");
        check(
                lease(rig, 4)[0].equals(key) && holder(rig, "com.x").equals("004"),
                "E4: same screen, X still its own");
    }

    /** R2: mainThreadId is the session at the top of its spawn chain, asked of Codex once. */
    static void listMainSession() throws Exception {
        Rig rig = new Rig();
        rig.codex.parents.put(session(4), session(9));
        rig.codex.parents.put(session(5), session(4));
        rig.request(1, "t", "com.x");
        rig.request(4, "t", "com.y");
        rig.request(5, "t", "com.z");
        Map<String, JSONObject> rows = rows(rig);
        check(
                rows.get(session(1)).getString("mainThreadId").equals(session(1))
                        && rows.get(session(4)).getString("mainThreadId").equals(session(9))
                        && rows.get(session(5)).getString("mainThreadId").equals(session(9)),
                "R2: itself, its parent, its parent's parent: " + rows);
        int asked = rig.codex.calls.size();
        rows(rig);
        check(rig.codex.calls.size() == asked, "R2: asked once per session");
        check(
                rig.screens.describe(session(4)).getString("controlOwner").equals("agent"),
                "describe: the row's control fields, without asking Codex");
    }

    /**
     * F5: archiving or deleting a session frees its screen and the screens of every session it
     * spawned (archived with it or still live), taken over or not; their Apps go to whoever waits.
     */
    static void archived() throws Exception {
        Rig rig = new Rig();
        String main = session(9);
        rig.codex.parents.putAll(
                Map.of(
                        session(2),
                        main,
                        session(3),
                        main,
                        session(4),
                        session(2),
                        session(6),
                        main));
        rig.codex.archived.addAll(List.of(session(2), session(4)));
        Map<Integer, Integer> displays = new HashMap<>();
        for (int s : new int[] {9, 2, 3, 4}) {
            int d = rig.request(s, "t", "com.app" + s).getInt("displayId");
            rig.tasks.open("com.app" + s, d);
            displays.put(s, d);
        }
        int unrelated = rig.request(7, "t", "com.app7").getInt("displayId");
        rig.tasks.open("com.app7", unrelated);
        control(rig, 3, "user");
        Call other = new Call(rig, 5, "t", "com.app2");
        awaitWaiting(rig, "com.app2", 1);
        Call sub = new Call(rig, 6, "t", "com.app9");
        awaitWaiting(rig, "com.app9", 1);
        rig.screens.sessionGone(main).join(5_000);
        check(
                rows(rig).keySet().equals(Set.of(session(5), session(7))),
                "F5: only the unrelated session and the one that waited keep a screen: "
                        + rows(rig).keySet());
        boolean cleared = true;
        for (int d : displays.values()) cleared &= tasksOn(rig, d).isEmpty();
        check(cleared, "F5: their screens are cleared, the taken-over one too");
        check(tasksOn(rig, unrelated).size() == 1, "F5: the unrelated screen is untouched");
        check(other.granted().contains("com.app2"), "F5: their Apps go to the one waiting");
        check(sub.refused().equals("TURN_ENDED"), "F5: a sub-agent's request in line ends");
        // the sub-agent in line had a screen too: a screen comes before the Apps
        check(
                rig.screens.status(session(0)).getInt("idleScreens") == 5,
                "F5: the freed screens are idle");
        int screens = rig.host.slots.size();
        boolean agent = true;
        for (int s = 10; s < 15; s++) {
            rig.request(s, "t", "com.a" + s);
            JSONObject row = rows(rig).get(session(s));
            agent &=
                    row.getString("controlOwner").equals("agent")
                            && row.getString("status").equals("running");
        }
        check(
                agent && rig.host.slots.size() == screens,
                "F5: the next sessions take those screens and control them, the taken-over one"
                        + " too");

        // Codex cannot list its sub-agents: the session's own screen is freed all the same
        Rig r = new Rig();
        r.request(9, "t", "com.x");
        r.codex.down = true;
        r.screens.sessionGone(main).join(5_000);
        check(
                r.screens.status(session(0)).getInt("idleScreens") == 1 && app(r, "com.x") == null,
                "F5: Codex down, its own screen still goes");
    }

    /**
     * F6: a screen idle for 2 hours is closed and leaves the ledger; one taken meanwhile is not,
     * and counts its 2 hours again from when it next goes idle.
     */
    static void idleClose() throws Exception {
        Rig rig = new Rig();
        long idle = DedicatedScreens.IDLE_MS;
        String first = rig.request(1, "t", "com.x").getString("screenKey");
        rig.screens.turnEnded(session(1)).join(5_000);
        rig.clock.advance(idle - 1);
        check(rig.host.stopped.isEmpty(), "F6: open until the 2 hours are up");
        rig.clock.advance(1);
        check(
                rig.host.stopped.equals(List.of(first))
                        && rig.screens.status(session(0)).getInt("idleScreens") == 0,
                "F6: then closed and out of the ledger");
        String second = rig.request(2, "t", "com.x").getString("screenKey");
        check(!second.equals(first), "F6: the next request starts a new screen");

        rig.screens.turnEnded(session(2)).join(5_000);
        rig.clock.advance(idle / 2);
        check(
                rig.request(3, "t", "com.x").getString("screenKey").equals(second),
                "F6: taken while idle");
        rig.clock.advance(idle / 2);
        check(!rig.host.stopped.contains(second), "F6: taken meanwhile, not closed");
        rig.screens.turnEnded(session(3)).join(5_000);
        rig.clock.advance(idle - 1);
        check(!rig.host.stopped.contains(second), "F6: its 2 hours start again when idle again");
        rig.clock.advance(1);
        check(rig.host.stopped.contains(second), "F6: and end then");

        // taken and freed again before the first timer: that timer leaves it alone
        String third = rig.request(4, "t", "com.x").getString("screenKey");
        rig.screens.turnEnded(session(4)).join(5_000);
        rig.clock.advance(idle / 2);
        rig.request(5, "t", "com.x");
        rig.screens.turnEnded(session(5)).join(5_000);
        rig.clock.advance(idle / 2);
        check(
                !rig.host.stopped.contains(third),
                "F6: the earlier idle spell's timer does not close it");
        rig.clock.advance(idle / 2);
        check(rig.host.stopped.contains(third), "F6: its own 2 hours do");

        // a screen left idle by a start the stop cut short closes too
        Rig r = new Rig();
        r.host.startGate = new CountDownLatch(1);
        Call f = new Call(r, 1, "f", "com.v");
        awaitWaiting(r, "com.v", 1);
        r.screens.turnEnded(session(1));
        awaitWaiting(r, "com.v", 0);
        r.host.startGate.countDown();
        f.refused();
        r.clock.advance(idle);
        check(r.host.stopped.size() == 1, "F6: a screen idle from its start is closed too");

        // scripts shorten the idle time; screens that go idle afterwards use it
        r.screens.idleTimeout(5_000);
        r.host.startGate = null;
        String key = r.request(2, "t", "com.v").getString("screenKey");
        r.screens.turnEnded(session(2)).join(5_000);
        r.clock.advance(5_000);
        check(r.host.stopped.contains(key), "F6: closed after the shortened idle time");
    }

    /**
     * PhoneSubAgents, moved from the screen-pool prototype with its tests (the nickname ones
     * dropped).
     */
    /**
     * F3: the user closes a sub-agent's screen once it is stopped. Its tasks go before the screen,
     * its Apps go on, and Codex keeps the sub-agent, so every turn it starts is refused until its
     * main session is archived.
     */
    static void closeSubAgent() throws Exception {
        Rig rig = new Rig();
        String main = session(9), sub = session(4);
        rig.codex.parents.put(sub, main);
        int d = rig.request(4, "Compare prices", "com.x").getInt("displayId");
        rig.tasks.open("com.x", d);
        int onMain = rig.tasks.open("com.home", DedicatedScreens.MAIN_DISPLAY);
        Call waits = new Call(rig, 2, "t", "com.x");
        awaitWaiting(rig, "com.x", 1);
        String[] l = lease(rig, 4);
        List<List<Integer>> leftAtStop = new CopyOnWriteArrayList<>();
        rig.host.beforeStop =
                key -> {
                    if (key.equals(l[0])) leftAtStop.add(tasksOn(rig, d));
                };
        control(rig, 4, "user");
        rig.screens.beginClose(sub, l[0], l[1]);
        DedicatedScreens.ParentNote note = new DedicatedScreens.ParentNote(main, "it stopped");
        rig.screens.close(sub, l[0], l[1], note);
        check(!rows(rig).containsKey(sub), "F3: its item leaves the UI list");
        check(
                rig.host.stopped.contains(l[0]) && leftAtStop.equals(List.of(List.of())),
                "F3: its screen is closed, every task on it removed first: " + leftAtStop);
        check(
                tasksOn(rig, DedicatedScreens.MAIN_DISPLAY).equals(List.of(onMain)),
                "F3: tasks elsewhere are left alone");
        check(waits.granted().contains("com.x"), "F3: its Apps go to the session waiting");
        check(
                rig.screens.status(session(0)).getInt("idleScreens") == 0,
                "F3: the closed screen is not handed out as idle");
        rejects("SCREEN_NOT_BOUND", () -> rig.screens.exact(sub, l[0], l[1]));
        check(!rig.screens.turnStarted(sub, null), "F3: a turn it starts later is refused");
        check(!rig.screens.turnStarted(sub, null), "F3: and the next one too");
        check(
                note.equals(rig.screens.closedNote(sub)),
                "F3: a refused turn of it finds its parent and what to tell it");
        check(rig.screens.turnStarted(main, null), "F3: its main session runs on");
        check(rig.screens.closedNote(main) == null, "F3: its main session has nothing to tell");
        rig.screens.sessionGone(main).join(5_000);
        check(
                rig.screens.turnStarted(sub, null) && rig.screens.closedNote(sub) == null,
                "F3: once its main session is archived, it is no longer refused");
    }

    /**
     * F4: the user closes the main session's screen once it is stopped: the screen goes, the
     * session's next turn runs, and its sub-agent keeps its own screen.
     */
    static void closeMain() throws Exception {
        Rig rig = new Rig();
        String main = session(1);
        rig.codex.parents.put(session(5), main);
        int d = rig.request(1, "t", "com.x").getInt("displayId");
        rig.tasks.open("com.x", d);
        rig.request(5, "t", "com.y");
        rig.request(3, "t", "com.z");
        Call more = new Call(rig, 1, "t", "com.z");
        awaitWaiting(rig, "com.z", 1);
        String[] l = lease(rig, 1);
        rig.screens.beginClose(main, l[0], l[1]);
        check(
                more.refused().startsWith("SCREEN_CLOSED"),
                "F4: its request in line ends, saying the screen is being closed");
        rejects("SCREEN_CLOSED", () -> use(rig, 1, DedicatedScreens.Use.ACT, null));
        rig.screens.close(main, l[0], l[1], null);
        check(
                !rows(rig).containsKey(main)
                        && rig.host.stopped.contains(l[0])
                        && tasksOn(rig, d).isEmpty(),
                "F4: its screen is closed, its tasks removed");
        check(
                rig.screens.turnStarted(main, null) && rig.screens.closedNote(main) == null,
                "F4: the main session's next turn runs");
        check(
                rows(rig).containsKey(session(5)) && holder(rig, "com.y").equals("005"),
                "F4: its sub-agent keeps its screen and Apps");
        check(
                rig.request(1, "t", "com.x").getInt("displayId") != d,
                "F4: asking again, it gets another screen");

        String[] again = lease(rig, 1);
        rig.screens.beginClose(main, again[0], again[1]);
        rig.screens.controlFailed(main, again[0], again[1], "WORKSPACE_EXECUTION_STOP_UNCONFIRMED");
        check(
                rows(rig).get(main).getString("status").equals("paused"),
                "F4: a close that could not stop the session leaves the screen paused");
        rejects("SCREEN_AGENT_PAUSED", () -> use(rig, 1, DedicatedScreens.Use.ACT, null));
        rig.host.unplug(again[0]);
        rig.screens.beginClose(main, again[0], again[1]);
        rig.screens.close(main, again[0], again[1], null);
        check(
                !rows(rig).containsKey(main) && app(rig, "com.x") == null,
                "F4: a screen whose process is already gone leaves the ledger all the same");
    }

    /** A blocked held App, even below the top App, fences execution and giving control back. */
    static void policyHandoff() throws Exception {
        Path directory = Files.createTempDirectory("dedicated-policy-handoff");
        WorkspacePolicy.Store previous = WorkspacePolicy.store;
        WorkspacePolicy.store = new WorkspacePolicy.Store(directory.resolve("policy.json").toFile());
        try {
            Rig rig = new Rig();
            JSONObject got = rig.request(1, "t", "com.x", "com.y");
            rig.tasks.open("com.x", got.getInt("displayId"));
            WorkspacePolicy.configure(new JSONObject().put("blockedPackages", new JSONArray().put("com.y")));
            check(!rig.screens.agentAdmitted(session(1)), "a blocked held App fences native execution");
            rejects("APP_NOT_ALLOWED", () -> use(rig, 1, DedicatedScreens.Use.ACT, null));
            String key = got.getString("screenKey"), generation = got.getString("generation");
            rig.screens.beginPause(session(1), key, generation);
            rig.screens.finishControl(session(1), key, generation, "paused");
            rejects("APP_NOT_ALLOWED", () -> rig.screens.finishControl(session(1), key, generation, "agent"));
            check(rig.screens.describe(session(1)).getString("controlOwner").equals("paused"),
                    "a rejected give-back keeps the screen paused");
            WorkspacePolicy.configure(new JSONObject().put("blockedPackages", new JSONArray()));
            rig.screens.finishControl(session(1), key, generation, "agent");
            check(rig.screens.agentAdmitted(session(1)), "granting App access allows a fresh continuation");
        } finally {
            WorkspacePolicy.store = previous;
            Files.deleteIfExists(directory.resolve("policy.json"));
            Files.delete(directory);
        }
    }

    static void blockedRequestInLine() throws Exception {
        Path directory = Files.createTempDirectory("dedicated-policy-queue");
        WorkspacePolicy.Store previous = WorkspacePolicy.store;
        WorkspacePolicy.store = new WorkspacePolicy.Store(directory.resolve("policy.json").toFile());
        try {
            Rig rig = new Rig();
            int clockDisplay = rig.request(1, "Clock", "com.clock").getInt("displayId");
            rig.tasks.open("com.clock", clockDisplay);
            rig.request(2, "Settings", "com.settings");
            Call blocked = new Call(rig, 3, "Clock and Settings", "com.clock", "com.settings");
            awaitWaiting(rig, "com.clock", 1);
            Call next = new Call(rig, 4, "Settings", "com.settings");
            awaitWaiting(rig, "com.settings", 2);

            rig.tasks.removing = new CountDownLatch(1);
            rig.tasks.removeGate = new CountDownLatch(1);
            Thread releasing = daemon(() -> release(rig, 1, "com.clock"));
            try {
                check(rig.tasks.removing.await(5, TimeUnit.SECONDS), "Clock is being released");
                List<DedicatedScreens.RevokedScreen> affected = rig.screens.configurePolicy(
                        new JSONObject().put("blockedPackages", new JSONArray().put("com.clock")));
                check(affected.size() == 1 && affected.get(0).thread().equals(session(1)),
                        "only the actual holder needs cleanup");
                check(blocked.refused().equals("APP_NOT_ALLOWED"),
                        "a queued request is refused before the release can hand out Clock");
            } finally {
                rig.tasks.removeGate.countDown();
            }
            releasing.join(5_000);
            release(rig, 2, "com.settings");

            check(next.granted().equals(List.of("com.settings")), "the next eligible request goes ahead");
            check(holder(rig, "com.clock").equals("-"), "the revoked App was never handed out");
        } finally {
            WorkspacePolicy.store = previous;
            Files.deleteIfExists(directory.resolve("policy.json"));
            Files.delete(directory);
        }
    }

    static void revocationKeepsAllowedApp() throws Exception {
        Path directory = Files.createTempDirectory("dedicated-policy-partial");
        WorkspacePolicy.Store previous = WorkspacePolicy.store;
        WorkspacePolicy.store = new WorkspacePolicy.Store(directory.resolve("policy.json").toFile());
        try {
            Rig rig = new Rig();
            JSONObject got = rig.request(1, "Clock and Settings", "com.clock", "com.settings");
            int display = got.getInt("displayId");
            rig.tasks.open("com.clock", display);
            int settingsTask = rig.tasks.open("com.settings", display);
            DedicatedScreens.RevokedScreen target = rig.screens.configurePolicy(
                    new JSONObject().put("blockedPackages", new JSONArray().put("com.clock"))).get(0);
            rig.screens.beginRevocation(target);
            JSONObject result = rig.screens.releaseRevoked(target, screen -> new JSONObject());
            check(result.getJSONArray("released").toList().equals(List.of("com.clock")),
                    "Spec 2: revoke only the blocked App");
            check(held(result).equals(List.of("com.settings"))
                            && rows(rig).get(session(1)).getString("screenKey").equals(got.getString("screenKey")),
                    "Spec 2: keep the allowed App and dedicated screen");
            check(tasksOn(rig, display).equals(List.of(settingsTask)),
                    "Spec 2: leave the allowed App's page intact");
            use(rig, 1, DedicatedScreens.Use.ACT, null);
            rejects("APP_NOT_ALLOWED", () -> rig.request(1, "Clock", "com.clock"));
            check(rig.screens.turnStarted(session(1), null),
                    "Spec 2: automatic revocation does not close the session");
        } finally {
            WorkspacePolicy.store = previous;
            Files.deleteIfExists(directory.resolve("policy.json"));
            Files.delete(directory);
        }
    }

    static void revocationIdentityAndTurnEnd() throws Exception {
        Path directory = Files.createTempDirectory("dedicated-policy-identity");
        WorkspacePolicy.Store previous = WorkspacePolicy.store;
        WorkspacePolicy.store = new WorkspacePolicy.Store(directory.resolve("policy.json").toFile());
        try {
            Rig rig = new Rig();
            JSONObject got = rig.request(1, "Both", "com.clock", "com.settings");
            rig.tasks.open("com.clock", got.getInt("displayId"));
            DedicatedScreens.RevokedScreen target = rig.screens.configurePolicy(
                    new JSONObject().put("blockedPackages", new JSONArray().put("com.clock"))).get(0);
            rejects("SCREEN_GENERATION_CHANGED", () -> rig.screens.releaseRevoked(target, screen -> {
                rig.host.slots.put(screen.screenKey, rig.host.info(screen.screenKey, screen.displayId));
                return new JSONObject();
            }));
            check(holder(rig, "com.clock").equals("001") && rig.tasks.list().size() == 1,
                    "Spec 2: a physical replacement during input drain is not cleaned as the old screen");

            WorkspacePolicy.configure(new JSONObject().put("blockedPackages", new JSONArray()));
            Rig ending = new Rig();
            JSONObject own = ending.request(1, "Both", "com.clock", "com.settings");
            ending.tasks.open("com.clock", own.getInt("displayId"));
            DedicatedScreens.RevokedScreen revoked = ending.screens.configurePolicy(
                    new JSONObject().put("blockedPackages", new JSONArray().put("com.clock"))).get(0);
            ending.tasks.removing = new CountDownLatch(1);
            ending.tasks.removeGate = new CountDownLatch(1);
            CompletableFuture<JSONObject> release = new CompletableFuture<>();
            daemon(() -> {
                try { release.complete(ending.screens.releaseRevoked(revoked, screen -> new JSONObject())); }
                catch (Throwable error) { release.completeExceptionally(error); }
            });
            Thread turnEnd;
            try {
                check(ending.tasks.removing.await(5, TimeUnit.SECONDS), "Spec 2: revocation is deleting its App task");
                turnEnd = ending.screens.turnEnded(session(1));
                JSONObject during = ending.screens.revocationState(revoked, List.of());
                check(during.getJSONArray("heldApps").length() == 2 && during.getBoolean("screenRetained"),
                        "Spec 2: a concurrent turn end still reports grants awaiting actual cleanup");
            } finally {
                ending.tasks.removeGate.countDown();
            }
            check(release.get(5, TimeUnit.SECONDS).getJSONArray("released").toList().equals(List.of("com.clock")),
                    "Spec 2: the policy worker reports only its own actual release");
            turnEnd.join(5_000);
            check(!ending.screens.revocationState(revoked, List.of()).getBoolean("screenRetained")
                            && app(ending, "com.settings") == null,
                    "Spec 2: the turn end finishes the remaining cleanup and makes the screen idle");
        } finally {
            WorkspacePolicy.store = previous;
            Files.deleteIfExists(directory.resolve("policy.json"));
            Files.delete(directory);
        }
    }

    static void revokedEmptyScreen() throws Exception {
        Path directory = Files.createTempDirectory("dedicated-policy-empty");
        WorkspacePolicy.Store previous = WorkspacePolicy.store;
        WorkspacePolicy.store = new WorkspacePolicy.Store(directory.resolve("policy.json").toFile());
        try {
            for (boolean manualFirst : new boolean[]{false, true}) {
                WorkspacePolicy.configure(new JSONObject().put("maxActiveScreens", 1)
                        .put("blockedPackages", new JSONArray()));
                Rig rig = new Rig();
                JSONObject got = rig.request(1, "Clock", "com.clock");
                int display = got.getInt("displayId");
                rig.tasks.open("com.clock", display);
                rig.tasks.open("com.jumped", display);
                DedicatedScreens.RevokedScreen target = rig.screens.configurePolicy(
                        new JSONObject().put("blockedPackages", new JSONArray().put("com.clock"))).get(0);
                if (manualFirst) release(rig, 1, "com.clock");
                else rig.screens.releaseRevoked(target, screen -> new JSONObject());
                rig.clock.advance(10_000);
                if (manualFirst) rig.screens.releaseRevoked(target, screen -> new JSONObject());
                else release(rig, 1, "com.clock");
                check(tasksOn(rig, display).isEmpty(), "Spec 2: the last App clears in-App jumps too");
                rig.clock.advance(19_999);
                rejects("SCREEN_LIMIT_REACHED", () -> rig.request(2, "Settings", "com.settings"));
                rig.clock.advance(1);
                JSONObject next = rig.request(2, "Settings", "com.settings");
                check(next.getString("screenKey").equals(got.getString("screenKey")),
                        "Spec 2: duplicate/interleaved release never restarts the 30-second clock");
                JSONObject stale = rig.screens.releaseRevoked(target, screen -> {
                    throw new AssertionError("A stale revocation must not drain another session's input");
                });
                check(stale.getString("status").equals("superseded") && !stale.getBoolean("screenRetained")
                                && holder(rig, "com.settings").equals("002"),
                        "Spec 2: a transferred screen keeps its new holder and Apps");
            }
            WorkspacePolicy.configure(new JSONObject().put("blockedPackages", new JSONArray()));
            Rig rig = new Rig();
            JSONObject got = rig.request(1, "Clock", "com.clock");
            DedicatedScreens.RevokedScreen target = rig.screens.configurePolicy(
                    new JSONObject().put("blockedPackages", new JSONArray().put("com.clock"))).get(0);
            rig.screens.releaseRevoked(target, screen -> new JSONObject());
            check(rig.request(1, "Settings", "com.settings").getString("screenKey").equals(got.getString("screenKey")),
                    "Spec 2: the original turn can request an allowed App during protection");
            release(rig, 1, "com.settings");
            rig.screens.turnEnded(session(1)).join(5_000);
            check(rig.request(2, "Settings", "com.settings").getString("screenKey").equals(got.getString("screenKey")),
                    "Spec 2: a turn end makes the empty screen reusable before 30 seconds");
        } finally {
            WorkspacePolicy.store = previous;
            Files.deleteIfExists(directory.resolve("policy.json"));
            Files.delete(directory);
        }
    }

    static void unconfirmedExplicitScreenStop() throws Exception {
        Path directory = Files.createTempDirectory("dedicated-policy-stop");
        WorkspacePolicy.Store previous = WorkspacePolicy.store;
        WorkspacePolicy.store = new WorkspacePolicy.Store(directory.resolve("policy.json").toFile());
        try {
            Rig rig = new Rig();
            String thread = session(1);
            JSONObject got = rig.request(1, "Clock", "com.clock", "com.settings");
            String key = got.getString("screenKey"), generation = got.getString("generation");
            JSONObject patch = new JSONObject().put("blockedPackages", new JSONArray().put("com.clock"));
            DedicatedScreens.RevokedScreen target = rig.screens.configurePolicy(patch).get(0);
            rig.screens.beginClose(thread, key, generation);
            rig.host.stopFailure = "HOST_STOP_UNCONFIRMED";
            rejects("HOST_STOP_UNCONFIRMED", () -> rig.screens.close(thread, key, generation, null, true));
            rig.screens.controlFailed(thread, key, generation, "HOST_STOP_UNCONFIRMED");
            check(rows(rig).get(thread).getString("status").equals("paused")
                            && holder(rig, "com.clock").equals("001")
                            && holder(rig, "com.settings").equals("001"),
                    "an unconfirmed host stop keeps the paused screen and all of its Apps");

            rig.host.stopFailure = "";
            target = rig.screens.configurePolicy(patch).get(0);
            rig.screens.beginClose(thread, key, generation);
            rig.screens.close(thread, key, generation, null, true);
            check(!rows(rig).containsKey(thread) && !rig.host.slots.containsKey(key)
                            && app(rig, "com.clock") == null && app(rig, "com.settings") == null,
                    "saving the policy again can finish cleanup after a failed stop");
        } finally {
            WorkspacePolicy.store = previous;
            Files.deleteIfExists(directory.resolve("policy.json"));
            Files.delete(directory);
        }
    }

    static void admissionFences()throws Exception {
        Rig rig=new Rig();DedicatedScreens registry=rig.screens;
        JSONObject bound=rig.request(1,"A","test.app1");String key=bound.getString("screenKey"),gen=bound.getString("generation");
        java.util.concurrent.atomic.AtomicInteger lookups=new java.util.concurrent.atomic.AtomicInteger();
        WorkspaceAdmissions admissions=new WorkspaceAdmissions(()->{lookups.incrementAndGet();return registry;});
        check(lookups.get()==0,"shared ledger is still obtained lazily, not when admissions is constructed");
        admissions.beginSkillMaintenance();
        boolean maintenanceRejected=false;
        try { admissions.queue("turn/start",session(1)); } catch(Exception expected) { maintenanceRejected=true; }
        check(maintenanceRejected,"skill maintenance fences new turns");
        admissions.endSkillMaintenance();
        WorkspaceAdmissions.Ticket beforeMaintenance=admissions.queue("turn/start",session(1));
        maintenanceRejected=false;
        try { admissions.beginSkillMaintenance(); } catch(Exception expected) { maintenanceRejected=true; }
        check(maintenanceRejected,"pending turn fences skill maintenance");
        admissions.failedBeforeSend(beforeMaintenance);

        {
            for(String method:List.of("turn/start","turn/steer","thread/realtime/start","thread/realtime/appendText")){
                WorkspaceAdmissions.Ticket old=admissions.queue(method,session(1));
                registry.beginPause(session(1),key,gen);registry.finishControl(session(1),key,gen,"agent");
                rejects("SCREEN_CONTROL_CHANGED",()->admissions.beforeSend(1,old));admissions.failedBeforeSend(old);
                check(admissions.pending(session(1))==0,"old queued "+method+" retires without being sent after control return");
            }
            check(registry.describe(session(1)).getBoolean("resumeRequired"),"return control retains awaiting-resume state");
            check(!admissions.turnStarted(session(1)),"unrequested native queued turn cannot clear awaiting-resume");
            WorkspaceAdmissions.Ticket inFlight=admissions.queue("turn/start",session(1));admissions.beforeSend(2,inFlight);
            admissions.response(2,true);
            check(admissions.pending(session(1))==1,"successful start response alone does not prove started event has drained");
            registry.beginPause(session(1),key,gen);registry.finishControl(session(1),key,gen,"agent");
            check(!admissions.turnStarted(session(1)),"late started event retains old admission even after agent control returns");
            check(admissions.pending(session(1))==0&&registry.describe(session(1)).getBoolean("resumeRequired"),"rejected stale start does not mark the task resumed");
            WorkspaceAdmissions.Ticket fresh=admissions.queue("turn/start",session(1));admissions.beforeSend(3,fresh);
            check(admissions.turnStarted(session(1)),"explicit fresh turn after return is admitted");
            check(!registry.describe(session(1)).getBoolean("resumeRequired")&&admissions.pending(session(1))==1,"only actual fresh started clears resume state, while pending response is still tracked");
            admissions.response(3,true);check(admissions.pending(session(1))==0,"both start acknowledgments retire admission exactly once");
            WorkspaceAdmissions.Ticket uncertain=admissions.queue("turn/steer",session(1));admissions.beforeSend(4,uncertain);admissions.failedBeforeSend(uncertain);
            check(admissions.pending(session(1))==1,"uncertain socket write remains outstanding for handoff proof");
            admissions.response(4,false);check(admissions.pending(session(1))==0,"definite RPC rejection releases pending proof");
            registry.beginPause(session(1),key,gen);registry.finishControl(session(1),key,gen,"agent");
            WorkspaceAdmissions.Ticket realtime=admissions.queue("thread/realtime/start",session(1));admissions.beforeSend(5,realtime);
            check(admissions.turnStarted(session(1)),"fresh realtime turn can precede its session-start response");admissions.response(5,true);
            check(admissions.realtimeActive(session(1)),"realtime session keeps a grant beyond its start response");
            registry.beginPause(session(1),key,gen);registry.finishControl(session(1),key,gen,"agent");
            check(!admissions.turnStarted(session(1)),"old realtime session cannot revive after a handoff");
            admissions.realtimeStopped(session(1));check(!admissions.realtimeActive(session(1)),"explicit realtime stop clears active-session proof");
        }
    }
    static void workspaceReservations()throws Exception {
        Rig rig=new Rig();DedicatedScreens registry=rig.screens;
        JSONObject bound=rig.request(1,"A","test.app1");String key=bound.getString("screenKey"),gen=bound.getString("generation");
        WorkspaceAdmissions admissions=new WorkspaceAdmissions(()->registry);
        WorkspaceAdmissions.Ticket queued=admissions.queue("turn/start",session(1));
        String request=UUID.randomUUID().toString();admissions.reserve(session(1),request);
        rejects("WORKSPACE_EXECUTION_RESERVED",()->admissions.beforeSend(10,queued));admissions.failedBeforeSend(queued);
        rejects("WORKSPACE_EXECUTION_RESERVED",()->admissions.queue("turn/start",session(1)));
        rejects("WORKSPACE_EXECUTION_RESERVED",()->admissions.queue("turn/steer",session(1),UUID.randomUUID().toString()));
        rejects("WORKSPACE_EXECUTION_RESERVED",()->admissions.reserve(session(1),UUID.randomUUID().toString()));
        check(admissions.queue("turn/interrupt",session(1))==null,"Stop remains available during reserved delivery");
        check(admissions.queue("thread/read",session(1))==null,"Receipt reconciliation reads remain available");
        WorkspaceAdmissions.Ticket own=admissions.queue("turn/start",session(1),request);
        registry.beginPause(session(1),key,gen);registry.finishControl(session(1),key,gen,"agent");
        rejects("SCREEN_CONTROL_CHANGED",()->admissions.beforeSend(11,own));admissions.failedBeforeSend(own);
        WorkspaceAdmissions.Ticket fresh=admissions.queue("turn/start",session(1),request);admissions.beforeSend(12,fresh);
        check(admissions.turnStarted(session(1)),"exact reserved request may start after valid handoff");admissions.response(12,true);
        admissions.clear();rejects("WORKSPACE_EXECUTION_RESERVED",()->admissions.queue("turn/start",session(1)));
        admissions.releaseReservation(session(1),UUID.randomUUID().toString());rejects("WORKSPACE_EXECUTION_RESERVED",()->admissions.queue("turn/start",session(1)));
        admissions.releaseReservation(session(1),request);
        WorkspaceAdmissions.Ticket normal=admissions.queue("turn/start",session(1));check(normal!=null,"generic request admitted after definite release");admissions.failedBeforeSend(normal);
        rejects("WORKSPACE_EXECUTION_RESERVED",()->admissions.queue("turn/start",session(1),request));
    }

    /** A screen tool's step that fails without touching the screen. */
    static JSONObject failingStep(PhoneExecutionScreens.Screen screen) throws Exception {
        throw new IOException("NO_SUCH_BUTTON");
    }

    /**
     * G3: the screen process dies on its own, found when a step on the screen fails (a screenshot
     * too): the session is told, its entry goes and its Apps go to whoever waits. A step failing on
     * a live screen changes nothing.
     */
    static void screenLost() throws Exception {
        Rig rig = new Rig();
        JSONObject a = rig.request(1, "t", "com.x");
        int d = a.getInt("displayId");
        rig.tasks.open("com.x", d);
        Call waits = new Call(rig, 2, "t", "com.x");
        awaitWaiting(rig, "com.x", 1);
        rejects(
                "NO_SUCH_BUTTON",
                () ->
                        rig.screens.withScreen(
                                session(1),
                                DedicatedScreens.Use.ACT,
                                null,
                                DedicatedScreensTest::failingStep));
        check(
                rows(rig).containsKey(session(1)) && !waits.done(),
                "G3: a step failing on a live screen keeps the screen and its Apps");
        rig.host.unplug(a.getString("screenKey"));
        rejects(
                "SCREEN_LOST",
                () ->
                        rig.screens.withScreen(
                                session(1),
                                DedicatedScreens.Use.ACT,
                                null,
                                s -> rig.host.status(s.screenKey)));
        check(!rows(rig).containsKey(session(1)), "G3: its entry leaves the ledger");
        check(waits.granted().contains("com.x"), "G3: its App goes to the session waiting");
        rejects("APPS_NOT_REQUESTED", () -> use(rig, 1, DedicatedScreens.Use.LOOK, null));
        JSONObject again = rig.request(1, "t", "com.y");
        check(again.getInt("displayId") != d, "G3: asking again, it gets another screen");

        rig.host.unplug(again.getString("screenKey"));
        rejects(
                "SCREEN_LOST",
                () ->
                        rig.screens.withScreen(
                                session(1),
                                DedicatedScreens.Use.LOOK,
                                null,
                                s -> rig.host.status(s.screenKey)));
        check(
                !rows(rig).containsKey(session(1)) && app(rig, "com.y") == null,
                "G3: a failed screenshot finds it too, and the App is free");

        Rig r = new Rig();
        JSONObject b = r.request(1, "t", "com.x");
        int bd = b.getInt("displayId");
        r.tasks.open("com.x", bd);
        r.host.statusFailures.set(1);
        rejects(
                "SCREEN_LOST",
                () ->
                        r.screens.withScreen(
                                session(1),
                                DedicatedScreens.Use.ACT,
                                null,
                                DedicatedScreensTest::failingStep));
        check(
                r.host.stopped.contains(b.getString("screenKey")) && tasksOn(r, bd).isEmpty(),
                "G3: a screen that only could not be read is closed, its tasks first, so none is"
                        + " left without an owner");

        Rig q = new Rig();
        JSONObject c = q.request(1, "t", "com.x");
        q.request(3, "t", "com.y");
        Call more = new Call(q, 1, "t", "com.y");
        awaitWaiting(q, "com.y", 1);
        q.host.unplug(c.getString("screenKey"));
        rejects(
                "SCREEN_LOST",
                () ->
                        q.screens.withScreen(
                                session(1),
                                DedicatedScreens.Use.ACT,
                                null,
                                s -> q.host.status(s.screenKey)));
        more.refused();
        q.screens.release(session(3), List.of("com.y"));
        check(
                app(q, "com.y") == null,
                "G3: a request for more it made meanwhile fails, never granted on the lost screen");
    }

    /**
     * G2: Codex goes away, so no turn running now ever ends: every screen closes, its tasks first,
     * and only then are its Apps free; every request in line fails; a card holds nothing; a screen
     * being started is closed once up. Closed sub-agents stay refused.
     */
    static void codexGone() throws Exception {
        Rig rig = new Rig();
        String main = session(9), sub = session(8);
        rig.codex.parents.put(sub, main);
        rig.request(8, "t", "com.s");
        String[] closed = lease(rig, 8);
        rig.screens.beginClose(sub, closed[0], closed[1]);
        rig.screens.close(
                sub, closed[0], closed[1], new DedicatedScreens.ParentNote(main, "it stopped"));

        int d = rig.request(1, "holds a page", "com.x").getInt("displayId");
        rig.tasks.open("com.x", d);
        Call queued = new Call(rig, 2, "t", "com.x");
        rig.request(3, "t", "com.y");
        Call more = new Call(rig, 3, "t", "com.x");
        awaitWaiting(rig, "com.x", 2);
        rig.request(4, "t", "com.z");
        ask(rig, 4);
        rig.request(5, "t", "com.w");
        control(rig, 5, "user");
        CountDownLatch up = new CountDownLatch(1);
        rig.host.startGate = up;
        Call starting = new Call(rig, 7, "t", "com.u");
        awaitWaiting(rig, "com.u", 1);
        rig.host.startGate = null;
        rig.request(6, "t", "com.v");
        rig.screens.turnEnded(session(6)).join(5_000);
        check(
                rig.screens.status(session(0)).getInt("idleScreens") == 1,
                "G2 setup: one screen idle");

        rig.tasks.removeGate = new CountDownLatch(1);
        rig.tasks.removing = new CountDownLatch(1);
        Thread closing = rig.screens.codexGone();
        for (Call c : List.of(queued, more)) c.refused();
        check(
                rig.tasks.removing.await(5, TimeUnit.SECONDS) && rows(rig).isEmpty(),
                "G2: no session has a screen any more");
        Call next = new Call(rig, 10, "t", "com.x");
        awaitWaiting(rig, "com.x", 1);
        check(!next.done(), "G2: an App waits while its tasks are being removed");
        rig.tasks.removeGate.countDown();
        check(next.granted().contains("com.x"), "G2: then it is free");
        closing.join(5_000);
        up.countDown();
        starting.refused();
        check(tasksOn(rig, d).isEmpty(), "G2: the tasks on the screens are removed");
        String nextKey = rows(rig).get(session(10)).getString("screenKey");
        check(
                rig.host.slots.keySet().equals(Set.of("default", nextKey)),
                "G2: every screen process is stopped, the one being started too: "
                        + rig.host.slots.keySet());
        JSONObject status = rig.screens.status(session(0));
        check(
                status.getInt("idleScreens") == 0 && status.getJSONArray("apps").length() == 1,
                "G2: none is left idle, no App held or awaited but the new one: " + status);
        check(!rig.screens.turnStarted(sub, null), "G2: a closed sub-agent stays refused");
        rig.request(4, "t", "com.z");
        rig.clock.advance(DedicatedScreens.HOLD_MS);
        Call wantsZ = new Call(rig, 11, "t", "com.z");
        awaitWaiting(rig, "com.z", 1);
        check(
                holder(rig, "com.z").equals("004") && !wantsZ.done(),
                "G2: the card it showed before is gone: once it holds Apps again, they are not"
                        + " handed over");
    }

    static void subAgents() throws Exception {
        Codex codex = new Codex();
        codex.page = 2;
        String main = session(9), c2 = session(2), c3 = session(3), c4 = session(4);
        String c5 = session(5);
        codex.parents.putAll(Map.of(c2, main, c3, main, c4, main, c5, main));
        codex.archived.add(c5);
        JSONObject child = codex.call("thread/read", new JSONObject().put("threadId", c2));
        check(
                PhoneSubAgents.parent(child).equals(main)
                        && PhoneSubAgents.path(child).equals("/root/sub-" + c2),
                "thread/read names the parent and the path it addresses the sub-agent by");
        check(
                PhoneSubAgents.parent(
                                codex.call("thread/read", new JSONObject().put("threadId", main)))
                        .isEmpty(),
                "a session the broker started has no parent");
        String text = PhoneSubAgents.relayText("/root/inspect_settings", c2, "Book a table");
        check(
                text.contains("/root/inspect_settings")
                        && text.contains(c2)
                        && text.contains("Book a table")
                        && text.contains("followup_task")
                        && text.contains("look at its screen")
                        && text.contains("called off"),
                "the parent is told which sub-agent, which task, to wake it with followup_task"
                        + " either way: look first if the task is still wanted, end if the user"
                        + " called it off");
        String closed = PhoneSubAgents.closedText("/root/inspect_settings", "Book a table");
        check(
                closed.contains("/root/inspect_settings")
                        && closed.contains("Book a table")
                        && closed.contains("refused")
                        && closed.contains("Stop waiting"),
                "a closed screen: the parent is told which sub-agent, which task, that its turns"
                        + " are refused, to stop waiting for it");
        check(
                PhoneSubAgents.gone(codex::call, main)
                        .equals(new LinkedHashSet<>(List.of(c5, c2, c3, c4))),
                "archive: archived and live descendants are both gone, every page read");
    }

    public static void main(String[] args) throws Exception {
        // a call left waiting by a broken check must fail the run, not hang it
        daemon(() -> {
            Thread.sleep(60_000);
            for (Map.Entry<Thread, StackTraceElement[]> t : Thread.getAllStackTraces().entrySet())
                System.err.println(t.getKey() + " " + Arrays.toString(t.getValue()));
            System.err.println("DedicatedScreensTest: timed out");
            Runtime.getRuntime().halt(1);
        });
        fakes();
        phoneTasks();
        requestApps();
        requestRefusals();
        beforeUse();
        topApp();
        takesOverAnyTime();
        releaseApps();
        turnEnds();
        listRows();
        queue();
        allOrNone();
        append();
        appendInLine();
        sentBack();
        refusedAtOnce();
        crossed();
        queueFull();
        screenLimit();
        stopped();
        releaseTiming();
        listWaiting();
        screenStatus();
        cardHandsOver();
        answerAfterHandOver();
        answerRacesHandOver();
        emptyScreen();
        screenTaken();
        listWaitingForUser();
        takeOver();
        takeOverCard();
        takeOverNotTaken();
        takeOverTurnEnds();
        takeOverRace();
        giveBack();
        subAgentGiveBack();
        listMainSession();
        archived();
        idleClose();
        closeSubAgent();
        closeMain();
        policyHandoff();
        blockedRequestInLine();
        revocationKeepsAllowedApp();
        revocationIdentityAndTurnEnd();
        revokedEmptyScreen();
        unconfirmedExplicitScreenStop();
        admissionFences();
        workspaceReservations();
        screenLost();
        codexGone();
        subAgents();
        System.out.println("DedicatedScreensTest: " + checks + " checks passed");
    }
}

final class TaskBroker {
    static final File ROOT = new File("unused-test-runtime");

    static String read(File file) throws Exception {
        return Files.readString(file.toPath());
    }

    static void write(File file, String value) throws Exception {
        Path temp = Path.of(file + ".tmp");
        Files.writeString(temp, value);
        Files.move(
                temp,
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    static String command(String... arguments) throws Exception {
        if (List.of(arguments).contains("org.agentos.assistant.WORKSPACE_REVOKED")) return "";
        throw new IOException("Unexpected host command in unit test");
    }
}
