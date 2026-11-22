package org.agentos.assistant;

import java.io.File;
import java.nio.file.Files;
import org.json.JSONObject;

public final class AgentGatewaySubmissionTest {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("taku-gateway-test-").toFile();
        AgentGatewaySubmissions store = new AgentGatewaySubmissions(root);
        int[] calls = {0};
        JSONObject first = store.submit("first", "thread", "Hello", () -> {}, before -> { calls[0]++; before.persist("123"); return "123"; });
        if (!first.getString("state").equals("queued")) throw new AssertionError();
        JSONObject repeated = new AgentGatewaySubmissions(root).submit("first", "thread", "Hello", () -> {}, before -> { calls[0]++; return "456"; });
        if (calls[0] != 1 || !repeated.getBoolean("replayed") || !repeated.getString("taskId").equals("123")) throw new AssertionError("Duplicate published");
        try { store.submit("first", "thread", "Changed", () -> {}, before -> "456"); throw new AssertionError(); }
        catch (java.io.IOException expected) { if (!expected.getMessage().equals("REQUEST_ID_REUSED")) throw expected; }
        JSONObject failed = store.submit("failed", "thread", "No replay", () -> {}, before -> { calls[0]++; throw new java.io.IOException(); });
        store.submit("failed", "thread", "No replay", () -> {}, before -> { calls[0]++; return "789"; });
        if (calls[0] != 2 || !failed.getString("state").equals("deliveryUnknown")) throw new AssertionError("Uncertain publication replayed");
        int[] steers = {0};
        JSONObject accepted = store.steer("steer-1", "thread", "turn", "Extra", () -> {}, () -> { steers[0]++; return new JSONObject().put("turnId", "turn"); });
        JSONObject replay = new AgentGatewaySubmissions(root).steer("steer-1", "thread", "turn", "Extra", () -> {}, () -> { steers[0]++; return new JSONObject(); });
        if (!accepted.getString("state").equals("accepted") || !replay.getBoolean("replayed") || steers[0] != 1) throw new AssertionError("Steer replayed");
        JSONObject uncertain = store.steer("steer-2", "thread", "turn", "Extra", () -> {}, () -> { steers[0]++; throw new java.io.IOException("socket lost"); });
        store.steer("steer-2", "thread", "turn", "Extra", () -> {}, () -> { steers[0]++; return new JSONObject(); });
        if (!uncertain.getString("state").equals("deliveryUnknown") || steers[0] != 2) throw new AssertionError("Uncertain steer replayed");
        try { store.steer("steer-1", "thread", "different-turn", "Extra", () -> {}, JSONObject::new); throw new AssertionError(); }
        catch (java.io.IOException expected) { if (!expected.getMessage().equals("REQUEST_ID_REUSED")) throw expected; }
        int[] deniedSend = {0}, checks = {0};
        JSONObject notSent = store.submit("denied-send", "thread", "Keep draft", () -> {
            checks[0]++; throw new java.io.IOException("THREAD_BUSY");
        }, before -> { deniedSend[0]++; return "111"; });
        JSONObject notSentReplay = new AgentGatewaySubmissions(root).submit("denied-send", "thread", "Keep draft", () -> {
            checks[0]++;
        }, before -> { deniedSend[0]++; return "111"; });
        if (!notSent.getString("state").equals("notSent") || !notSent.getString("code").equals("THREAD_BUSY")
                || deniedSend[0] != 0 || checks[0] != 1 || !notSentReplay.getBoolean("replayed")
                || !notSentReplay.getString("state").equals("notSent")) throw new AssertionError("Denied publication replayed");
        JSONObject sendLost = store.submit("send-lost", "thread", "Unknown", () -> {}, before -> {
            // The same-looking error after entering publication is NOT proof of non-delivery.
            throw new java.io.IOException("THREAD_BUSY");
        });
        if (!sendLost.getString("state").equals("deliveryUnknown") || sendLost.has("code")) throw new AssertionError("Publication error guessed notSent");
        int[] deniedSteer = {0};
        JSONObject noSteer = store.steer("denied-steer", "thread", "old-turn", "Keep draft", () -> {
            throw new java.io.IOException("TURN_CHANGED");
        }, () -> { deniedSteer[0]++; return new JSONObject(); });
        JSONObject noSteerReplay = new AgentGatewaySubmissions(root).steer("denied-steer", "thread", "old-turn", "Keep draft", () -> {}, () -> {
            deniedSteer[0]++; return new JSONObject();
        });
        if (!noSteer.getString("state").equals("notSent") || !noSteer.getString("code").equals("TURN_CHANGED")
                || deniedSteer[0] != 0 || !noSteerReplay.getBoolean("replayed")
                || !noSteerReplay.getString("state").equals("notSent")) throw new AssertionError("Denied steer replayed");
        JSONObject steerLost = store.steer("steer-lost", "thread", "turn", "Unknown", () -> {}, () -> {
            throw new java.io.IOException("TURN_CHANGED");
        });
        if (!steerLost.getString("state").equals("deliveryUnknown") || steerLost.has("code")) throw new AssertionError("RPC error guessed notSent");
        int[] fallbacks = {0};
        AgentGatewaySubmissions.Fallback fallback = new AgentGatewaySubmissions.Fallback() {
            public void check() {}
            public String publish(TaskRequests.BeforePublish before) throws Exception { fallbacks[0]++; before.persist("900"); return "900"; }
            public boolean published(String task) { return task.equals("900"); }
        };
        JSONObject raced = store.steer("turn-ended", "thread", "turn", "Extra", () -> {}, () -> {
            throw new AgentGatewaySubmissions.SteerNotSubmitted();
        }, fallback);
        JSONObject racedReplay = new AgentGatewaySubmissions(root).steer("turn-ended", "thread", "turn", "Extra", () -> {}, () -> {
            throw new AssertionError("Rejected steer was repeated");
        }, fallback);
        if (!raced.getString("state").equals("accepted") || !raced.getString("taskId").equals("900")
                || !racedReplay.getBoolean("replayed") || fallbacks[0] != 1) throw new AssertionError("Turn-ended fallback must publish once");
        JSONObject preflightEnded = store.steer("preflight-ended", "thread", "turn", "Extra", () -> {
            throw new java.io.IOException("TURN_CHANGED");
        }, () -> { throw new AssertionError("Steer ran after preflight rejection"); }, fallback);
        if (!preflightEnded.getString("state").equals("accepted") || fallbacks[0] != 2) throw new AssertionError("Preflight turn change should queue once");
        JSONObject oldUnknown = store.steer("steer-lost", "thread", "turn", "Unknown", () -> {}, () -> {
            throw new AssertionError("Unknown steer repeated");
        }, fallback);
        if (!oldUnknown.getString("state").equals("deliveryUnknown") || fallbacks[0] != 2) throw new AssertionError("Unknown steer must not be retried as a new turn");
        AgentGatewaySubmissions.Fallback interrupted = new AgentGatewaySubmissions.Fallback() {
            public void check() {}
            public String publish(TaskRequests.BeforePublish before) throws Exception { fallbacks[0]++; before.persist("901"); throw new java.io.IOException("reply lost"); }
            public boolean published(String task) { return task.equals("901"); }
        };
        JSONObject publishedBeforeError = store.steer("published-then-lost", "thread", "turn", "Extra", () -> {}, () -> {
            throw new AgentGatewaySubmissions.SteerNotSubmitted();
        }, interrupted);
        JSONObject publishedReplay = new AgentGatewaySubmissions(root).steer("published-then-lost", "thread", "turn", "Extra", () -> {}, JSONObject::new, interrupted);
        if (!publishedBeforeError.getString("state").equals("accepted") || !publishedReplay.getString("taskId").equals("901") || fallbacks[0] != 3)
            throw new AssertionError("Published task must reconcile without duplicate");
        AgentGatewaySubmissions.Fallback killed = new AgentGatewaySubmissions.Fallback() {
            public void check() {}
            public String publish(TaskRequests.BeforePublish before) throws Exception { fallbacks[0]++; before.persist("902"); throw new AssertionError("simulated process death after publication"); }
            public boolean published(String task) { return task.equals("902"); }
        };
        try {store.steer("restart-after-publication", "thread", "turn", "Extra", () -> {}, () -> {
            throw new AgentGatewaySubmissions.SteerNotSubmitted();
        }, killed);throw new AssertionError("Expected simulated process death");}
        catch (AssertionError expected) {if (!expected.getMessage().startsWith("simulated process death")) throw expected;}
        JSONObject recovered = new AgentGatewaySubmissions(root).steer("restart-after-publication", "thread", "turn", "Extra", () -> {}, JSONObject::new, killed);
        if (!recovered.getString("state").equals("accepted") || !recovered.getString("taskId").equals("902") || fallbacks[0] != 4)
            throw new AssertionError("Restart must reconcile the already published task without repeating it");
        AgentGatewaySubmissions.Fallback noPublication = new AgentGatewaySubmissions.Fallback() {
            public void check() {}
            public String publish(TaskRequests.BeforePublish before) throws Exception { fallbacks[0]++; before.persist("903"); throw new AssertionError("simulated process death before publication"); }
            public boolean published(String task) { return false; }
        };
        try {store.steer("restart-before-publication", "thread", "turn", "Extra", () -> {}, () -> {
            throw new AgentGatewaySubmissions.SteerNotSubmitted();
        }, noPublication);throw new AssertionError("Expected simulated process death");}
        catch (AssertionError expected) {if (!expected.getMessage().startsWith("simulated process death")) throw expected;}
        JSONObject unknownAfterRestart = new AgentGatewaySubmissions(root).steer("restart-before-publication", "thread", "turn", "Extra", () -> {}, JSONObject::new, noPublication);
        if (!unknownAfterRestart.getString("state").equals("deliveryUnknown") || fallbacks[0] != 5)
            throw new AssertionError("Missing publication proof must remain unresolved");
        JSONObject privateFailure = store.submit("private-error", "thread", "Keep draft", () -> {
            throw new java.io.IOException("private socket details");
        }, before -> { throw new AssertionError("Publication must not run"); });
        if (!privateFailure.getString("state").equals("notSent") || !privateFailure.getString("code").equals("RUNTIME_UNAVAILABLE"))
            throw new AssertionError("Preflight error leaked detail");
        acpSteerRouting(root);
        sameThreadSequencing();
        fallbackSequencing();
        liveTaskReceipts();
        System.out.println("AgentGatewaySubmissionTest: durable send/steer receipt/restart/reuse/failure checks passed");
    }
    /** A route that records calls, or fails the test when used. */
    private static AgentGatewaySubmissions.SteerRoute route(String name, java.util.List<String> log, Checked check, java.util.concurrent.Callable<JSONObject> steer) {
        return new AgentGatewaySubmissions.SteerRoute() {
            public void check() throws Exception { log.add(name + ":check"); check.run(); }
            public JSONObject steer() throws Exception { log.add(name + ":steer"); return steer.call(); }
        };
    }

    /** An ACP turn ("acp-…") is steered through the broker route; its refusals are definitive notSent receipts. */
    private static void acpSteerRouting(File root) throws Exception {
        AgentGatewaySubmissions store = new AgentGatewaySubmissions(root);
        java.util.List<String> log = new java.util.ArrayList<>();
        int[] fallbacks = {0};
        AgentGatewaySubmissions.Fallback fallback = new AgentGatewaySubmissions.Fallback() {
            public void check() {}
            public String publish(TaskRequests.BeforePublish before) throws Exception { fallbacks[0]++; before.persist("950"); return "950"; }
            public boolean published(String task) { return task.equals("950"); }
        };
        AgentGatewaySubmissions.SteerRoute codex = route("codex", log, () -> {}, () -> { throw new AssertionError("Codex steered an ACP turn"); });
        // Accepted by the broker.
        JSONObject accepted = store.steer("acp-ok", "thread", "acp-1111", "Also red ones", codex,
                route("acp", log, () -> {}, () -> new JSONObject().put("state", "accepted").put("runtime", "acp")), fallback);
        if (!accepted.getString("state").equals("accepted") || !log.equals(java.util.List.of("acp:check", "acp:steer")) || fallbacks[0] != 0)
            throw new AssertionError("ACP steer must use the broker route: " + log + " " + accepted);
        JSONObject acceptedReplay = new AgentGatewaySubmissions(root).steer("acp-ok", "thread", "acp-1111", "Also red ones", codex,
                route("acp", log, () -> {}, () -> { throw new AssertionError("Accepted ACP steer replayed"); }), fallback);
        if (!acceptedReplay.getBoolean("replayed") || !acceptedReplay.getString("state").equals("accepted")) throw new AssertionError("ACP replay");
        // Codex turns are untouched.
        log.clear();
        JSONObject codexAccepted = store.steer("codex-ok", "thread", "turn-9", "Extra",
                route("codex", log, () -> {}, JSONObject::new), route("acp", log, () -> {}, () -> { throw new AssertionError("ACP steered a Codex turn"); }), fallback);
        if (!codexAccepted.getString("state").equals("accepted") || !log.equals(java.util.List.of("codex:check", "codex:steer")))
            throw new AssertionError("Codex steer route: " + log);
        // agy refused: notSent with the exact code, never queued as a new turn, never replayed.
        for (String code : new String[]{"STEER_WAITING_ANSWER", "STEER_CANCELLING"}) {
            JSONObject refused = store.steer("acp-refused-" + code, "thread", "acp-2222", "Keep my draft", codex,
                    route("acp", log, () -> {}, () -> { throw new AgentGatewaySubmissions.SteerRejected(code); }), fallback);
            if (!refused.getString("state").equals("notSent") || !refused.getString("code").equals(code) || fallbacks[0] != 0)
                throw new AssertionError("Refused ACP steer must be notSent " + code + ": " + refused);
            JSONObject refusedReplay = new AgentGatewaySubmissions(root).steer("acp-refused-" + code, "thread", "acp-2222", "Keep my draft", codex,
                    route("acp", log, () -> {}, () -> { throw new AssertionError("Refused ACP steer replayed"); }), fallback);
            if (!refusedReplay.getString("state").equals("notSent") || !refusedReplay.getString("code").equals(code) || fallbacks[0] != 0)
                throw new AssertionError("Refused ACP steer replay: " + refusedReplay);
        }
        // The broker's read-only check refuses a stopping turn: notSent before any mutation.
        JSONObject stopping = store.steer("acp-stopping", "thread", "acp-3333", "Too late", codex,
                route("acp", log, () -> { throw new AgentGatewaySubmissions.SteerRejected("STEER_CANCELLING"); },
                        () -> { throw new AssertionError("Steer ran after a refused check"); }), fallback);
        if (!stopping.getString("state").equals("notSent") || !stopping.getString("code").equals("STEER_CANCELLING") || fallbacks[0] != 0)
            throw new AssertionError("Stopping ACP turn: " + stopping);
        // The turn ended before the broker took it (withdrawn): queued once as the next turn.
        JSONObject ended = store.steer("acp-ended", "thread", "acp-4444", "Next please", codex,
                route("acp", log, () -> {}, () -> { throw new AgentGatewaySubmissions.SteerNotSubmitted(); }), fallback);
        if (!ended.getString("state").equals("accepted") || !ended.getString("taskId").equals("950") || fallbacks[0] != 1
                || !ended.getJSONObject("result").getBoolean("queuedAfterTurn")) throw new AssertionError("Ended ACP turn must queue once: " + ended);
        JSONObject endedAtCheck = store.steer("acp-ended-check", "thread", "acp-5555", "Next", codex,
                route("acp", log, () -> { throw new AgentGatewaySubmissions.SteerNotSubmitted(); },
                        () -> { throw new AssertionError("Steer ran after the turn ended"); }), fallback);
        if (!endedAtCheck.getString("state").equals("accepted") || fallbacks[0] != 2) throw new AssertionError("Ended at check: queued");
        // A transport failure during the ACP steer stays unknown (it may have been delivered).
        JSONObject lost = store.steer("acp-lost", "thread", "acp-6666", "Maybe", codex,
                route("acp", log, () -> {}, () -> { throw new java.io.IOException("STEER_UNCONFIRMED"); }), fallback);
        if (!lost.getString("state").equals("deliveryUnknown") || fallbacks[0] != 2) throw new AssertionError("Unconfirmed ACP steer: " + lost);
        if (AgentGatewaySubmissions.route("acp-x", codex, codex) != codex) throw new AssertionError("route identity");
        if (!AgentGatewaySubmissions.STEER_REFUSALS.contains("STEER_WAITING_ANSWER") || !AgentGatewaySubmissions.STEER_REFUSALS.contains("STEER_CANCELLING"))
            throw new AssertionError("refusal codes");
    }

    private interface Checked { void run() throws Exception; }
    private static void rejects(String code, Checked operation) throws Exception {
        try { operation.run(); }
        catch (java.io.IOException expected) {
            if (expected.getMessage().equals(code)) return;
            throw new AssertionError("Expected " + code + ", got " + expected.getMessage(), expected);
        }
        throw new AssertionError("Expected " + code);
    }
    private static void liveTaskReceipts() throws Exception {
        File root = Files.createTempDirectory("live-task-receipts-").toFile();
        AgentGatewaySubmissions store = new AgentGatewaySubmissions(root);
        rejects("LIVE_TASK_UNCONFIRMED", () -> store.requireLiveTask("missing", "thread", "Query", "210"));
        File directory = new File(TaskRuntime.TASKS, "210"); directory.mkdirs();
        JSONObject request = new JSONObject().put("id", "210").put("conversationId", "thread").put("prompt", "Query");
        store.submit("live", "thread", "Query", () -> {}, before -> {
            TaskRequests.write(new File(directory, "request.json"), request.toString());
            before.persist("210");
            TaskRequests.write(new File(directory, "ready"), "1");
            return "210";
        });
        String receipt = Files.readString(new File(root, "live.json").toPath());
        new AgentGatewaySubmissions(root).requireLiveTask("live", "thread", "Query", "210");
        rejects("LIVE_TASK_MISMATCH", () -> store.requireLiveTask("live", "thread", "Query", "211"));
        rejects("REQUEST_ID_REUSED", () -> store.requireLiveTask("live", "other", "Query", "210"));
        rejects("REQUEST_ID_REUSED", () -> store.requireLiveTask("live", "thread", "Changed", "210"));
        TaskRequests.write(new File(directory, "request.json"), new JSONObject(request.toString()).put("composerRequestId", "live").toString());
        rejects("LIVE_TASK_MISMATCH", () -> store.requireLiveTask("live", "thread", "Query", "210"));
        TaskRequests.write(new File(directory, "request.json"), new JSONObject(request.toString()).put("prompt", "Changed").toString());
        rejects("LIVE_TASK_UNCONFIRMED", () -> store.requireLiveTask("live", "thread", "Query", "210"));
        store.steer("steer-only", "thread", "turn", "Query", () -> {}, JSONObject::new);
        rejects("LIVE_TASK_UNCONFIRMED", () -> store.requireLiveTask("steer-only", "thread", "Query", "210"));
        store.submit("unknown", "thread", "Query", () -> {}, before -> { throw new java.io.IOException("lost"); });
        rejects("LIVE_TASK_UNCONFIRMED", () -> store.requireLiveTask("unknown", "thread", "Query", "210"));
        store.submit("rejected", "thread", "Query", () -> { throw new java.io.IOException("THREAD_BUSY"); }, before -> {
            throw new AssertionError("Rejected publication ran");
        });
        rejects("LIVE_TASK_UNCONFIRMED", () -> store.requireLiveTask("rejected", "thread", "Query", "210"));
        if (!receipt.equals(Files.readString(new File(root, "live.json").toPath())) || new File(directory, "cancel").exists())
            throw new AssertionError("Receipt verification changed publication or cancellation state");
    }
    private static void await(java.util.concurrent.CountDownLatch latch) throws Exception {
        if(!latch.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Submission interleaving blocked");
    }
    private static void awaitQueued(Thread thread,java.util.concurrent.FutureTask<JSONObject> task) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(thread.getState()!=Thread.State.WAITING) {
            if(task.isDone()) {task.get();throw new AssertionError("Same-thread callback bypassed the in-flight publication");}
            if(System.nanoTime()>=deadline)throw new AssertionError("Submission did not reach its thread sequencing wait");
            Thread.yield();
        }
    }
    private static void sameThreadSequencing() throws Exception {
        AgentGatewaySubmissions store=new AgentGatewaySubmissions(Files.createTempDirectory("gateway-sequence-").toFile());
        java.util.concurrent.CountDownLatch publishing=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean busy=new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger publications=new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.FutureTask<JSONObject> first=new java.util.concurrent.FutureTask<>(()->store.submit("first","same-thread","First",()->{},before->{
            if(Thread.holdsLock(store))throw new AssertionError("Publisher holds the global submissions monitor");
            publications.incrementAndGet();publishing.countDown();await(release);busy.set(true);return "123";
        }));
        Thread firstThread=new Thread(first,"first-thread-publication");firstThread.setDaemon(true);firstThread.start();
        try {
            await(publishing);
            java.util.concurrent.FutureTask<JSONObject> second=new java.util.concurrent.FutureTask<>(()->store.submit("second","same-thread","Second",()->{
                if(Thread.holdsLock(store))throw new AssertionError("Preflight holds the global submissions monitor");
                if(!busy.get())throw new AssertionError("Second preflight ran before first publication made the thread busy");
                throw new java.io.IOException("THREAD_BUSY");
            },before->{publications.incrementAndGet();return "456";}));
            Thread secondThread=new Thread(second,"second-thread-publication");secondThread.setDaemon(true);secondThread.start();awaitQueued(secondThread,second);
            java.util.concurrent.FutureTask<JSONObject> steer=new java.util.concurrent.FutureTask<>(()->store.steer("steer","same-thread","old-turn","Steer",()->{
                if(!busy.get())throw new AssertionError("Steer preflight bypassed same-thread publication");
                throw new java.io.IOException("TURN_CHANGED");
            },()->{throw new AssertionError("Denied steer mutated the turn");}));
            Thread steerThread=new Thread(steer,"sequenced-thread-steer");steerThread.setDaemon(true);steerThread.start();awaitQueued(steerThread,steer);
            // Different threads and a duplicate request must complete while the original is blocked.
            JSONObject independent=store.submit("independent","other-thread","Independent",()->{},before->"789");
            if(!independent.getString("state").equals("queued"))throw new AssertionError("Different thread was serialized globally");
            JSONObject duplicate=store.submit("first","same-thread","First",()->{throw new AssertionError("Duplicate preflight ran");},before->{throw new AssertionError("Duplicate published");});
            if(!duplicate.getBoolean("replayed")||!duplicate.getString("state").equals("deliveryUnknown"))throw new AssertionError("In-flight duplicate lost its replay receipt");
            release.countDown();
            JSONObject sent=first.get(5,java.util.concurrent.TimeUnit.SECONDS),denied=second.get(5,java.util.concurrent.TimeUnit.SECONDS),noSteer=steer.get(5,java.util.concurrent.TimeUnit.SECONDS);
            if(!sent.getString("state").equals("queued")||!denied.getString("state").equals("notSent")||!denied.getString("code").equals("THREAD_BUSY")
                    ||!noSteer.getString("state").equals("notSent")||!noSteer.getString("code").equals("TURN_CHANGED")||publications.get()!=1)
                throw new AssertionError("Same-thread preflight/publication sequencing was lost");
            JSONObject replay=store.submit("second","same-thread","Second",()->{throw new AssertionError("Denied replay preflight ran");},before->{throw new AssertionError("Denied replay published");});
            if(!replay.getBoolean("replayed")||!replay.getString("state").equals("notSent"))throw new AssertionError("Sequenced denial was not durable");
        } finally {release.countDown();}
    }

    private static void fallbackSequencing() throws Exception {
        AgentGatewaySubmissions store=new AgentGatewaySubmissions(Files.createTempDirectory("gateway-fallback-sequence-").toFile());
        java.util.concurrent.CountDownLatch publishing=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger publications=new java.util.concurrent.atomic.AtomicInteger();
        AgentGatewaySubmissions.Fallback fallback=new AgentGatewaySubmissions.Fallback() {
            public void check() {
                if(Thread.holdsLock(store))throw new AssertionError("Fallback check holds the global monitor");
            }
            public String publish(TaskRequests.BeforePublish before) throws Exception {
                if(Thread.holdsLock(store))throw new AssertionError("Fallback publication holds the global monitor");
                publications.incrementAndGet();before.persist("900");publishing.countDown();await(release);return "900";
            }
            public boolean published(String task) {
                if(Thread.holdsLock(store))throw new AssertionError("Fallback reconciliation holds the global monitor");
                return task.equals("900");
            }
        };
        java.util.concurrent.Callable<JSONObject> request=()->store.steer("ended","same-thread","old-turn","Extra",()->{
            if(Thread.holdsLock(store))throw new AssertionError("Steer preflight holds the global monitor");
            throw new java.io.IOException("TURN_CHANGED");
        },()->{throw new AssertionError("Rejected steer ran");},fallback);
        java.util.concurrent.FutureTask<JSONObject> first=new java.util.concurrent.FutureTask<>(request);
        Thread firstThread=new Thread(first,"fallback-publication");firstThread.setDaemon(true);firstThread.start();
        try {
            await(publishing);
            java.util.concurrent.FutureTask<JSONObject> duplicate=new java.util.concurrent.FutureTask<>(request);
            Thread duplicateThread=new Thread(duplicate,"fallback-duplicate");duplicateThread.setDaemon(true);duplicateThread.start();awaitQueued(duplicateThread,duplicate);
            java.util.concurrent.FutureTask<JSONObject> send=new java.util.concurrent.FutureTask<>(()->store.submit("send","same-thread","Next",()->{
                if(release.getCount()!=0)throw new AssertionError("Send bypassed fallback publication");
                throw new java.io.IOException("THREAD_BUSY");
            },before->{throw new AssertionError("Busy send published");}));
            Thread sendThread=new Thread(send,"send-after-fallback");sendThread.setDaemon(true);sendThread.start();awaitQueued(sendThread,send);
            JSONObject independent=store.submit("other","other-thread","Independent",()->{},before->"901");
            if(!independent.getString("state").equals("queued"))throw new AssertionError("Fallback blocked an unrelated thread");
            release.countDown();
            JSONObject sent=first.get(5,java.util.concurrent.TimeUnit.SECONDS),replay=duplicate.get(5,java.util.concurrent.TimeUnit.SECONDS),denied=send.get(5,java.util.concurrent.TimeUnit.SECONDS);
            if(!sent.getString("state").equals("accepted")||!sent.getJSONObject("result").getBoolean("queuedAfterTurn")
                    ||!replay.getBoolean("replayed")||!replay.getString("taskId").equals("900")||publications.get()!=1
                    ||!denied.getString("state").equals("notSent")||!denied.getString("code").equals("THREAD_BUSY"))
                throw new AssertionError("Fallback sequencing or duplicate fencing was lost");
        } finally {release.countDown();}
    }
}
