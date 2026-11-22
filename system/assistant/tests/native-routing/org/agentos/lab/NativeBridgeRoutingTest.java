package org.agentos.lab;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises actual pending routing and cancellation, without a model, Android UI or network. */
public final class NativeBridgeRoutingTest {
    private static int checks;
    private static final String THREAD="thread-one",TURN="turn-one";
    private static void check(boolean value,String message) { checks++;if(!value)throw new AssertionError(message); }
    private static JSONObject args() throws Exception {
        return new JSONObject().put("title","Confirm actual action").put("summary","Review the actual details")
            .put("confirmLabel","Confirm").put("fields",new JSONArray().put(new JSONObject()
                .put("label","Target").put("value","Verified target").put("style","field")));
    }
    private static JSONObject call(int id,String thread,String turn,String tool,JSONObject args) throws Exception {
        return new JSONObject().put("id",id).put("method","item/tool/call").put("params",new JSONObject()
            .put("threadId",thread).put("turnId",turn).put("namespace","taku_phone").put("tool",tool).put("arguments",args));
    }
    private static void start(String thread,String turn) throws Exception {
        NativeBridge.event(new JSONObject().put("method","turn/started").put("params",new JSONObject()
            .put("threadId",thread).put("turn",new JSONObject().put("id",turn))));
    }
    private static void complete(String thread,String turn) throws Exception {
        NativeBridge.event(new JSONObject().put("method","turn/completed").put("params",new JSONObject()
            .put("threadId",thread).put("turn",new JSONObject().put("id",turn).put("status","interrupted"))));
    }
    private static String key(String thread) throws Exception {
        JSONArray pending=NativeBridge.state(thread).getJSONArray("requests");
        if(pending.length()!=1)throw new AssertionError("Expected exactly one pending call for "+thread);
        return pending.getJSONObject(0).getString("key");
    }
    private static void steerRejectionClassification() throws Exception {
        String[] methods={"turn/steer","turn/steer","turn/start","turn/steer"};
        int[] codes={-32600,-32603,-32600,-32600};
        String[] messages={"no active turn to steer","no active turn to steer","no active turn to steer","socket lost"};
        for(int i=0;i<methods.length;i++) {
            int id=9000+i;NativeBridge.Call call=new NativeBridge.Call(methods[i],new JSONObject());
            NativeBridge.calls.put(id,call);
            NativeBridge.complete(new JSONObject().put("id",id).put("error",new JSONObject().put("code",codes[i]).put("message",messages[i])));
            try {call.result.get();throw new AssertionError("Expected RPC failure");}
            catch(java.util.concurrent.ExecutionException error) {
                check((error.getCause() instanceof NativeBridge.SteerNotSubmitted)==(i==0),"Only exact native no-active-turn rejection permits fallback");
            }
        }
    }
    private static void rejectReply(String key,String decision) throws Exception {
        rejectPayload(key,new JSONObject().put("decision",decision));
    }
    private static void rejectPayload(String key,JSONObject response) throws Exception {
        try { NativeBridge.respond(key,response); }
        catch(Exception expected){checks++;return;}throw new AssertionError("Accepted invalid or expired reply");
    }
    private static void awaitCleanup() throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(System.nanoTime()<end) {
            try(CleanupWork.Held bridgeHeld=NativeBridge.lock()) { if(NativeBridge.phoneWorkers.isEmpty())return; }
            Thread.sleep(5);
        }
        throw new AssertionError("Worker registration leaked");
    }
    private static void awaitReply(CodexAppServer server,int id) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(!server.replies.containsKey(id) && System.nanoTime()<end)Thread.sleep(5);
        check(server.replies.containsKey(id),"Expected reply for "+id);
    }
    private static void archivedSessions()throws Exception {
        for(String method:new String[]{"thread/archive","thread/delete"}){
            AgentViews.threads.put("screen-thread",new JSONObject());
            NativeBridge.observe(method,new JSONObject().put("threadId","screen-thread"),new JSONObject());
            check(AgentViews.threads.containsKey("screen-thread"),"Raw "+method+" observation cannot impersonate durable completion");
        }
        check(DedicatedScreensOnDevice.shared().gone.isEmpty(),
            "Only the durable removal worker may perform family cleanup");
        AgentViews.threads.clear();
    }
    /** A removed ACP conversation answers every thread-scoped native call THREAD_UNAVAILABLE, never starting Codex. */
    private static void removedAcpConversationNeverStartsCodex(CodexAppServer server)throws Exception {
        String removed="55555555-2222-4333-8444-555555555555";
        AcpConversations.REMOVED.add(removed);
        for(boolean running:new boolean[]{false,true}){
            CodexAppServer.shared=running?server:null;CodexAppServer.coldStarts.clear();int sent=server.nextRequest;
            for(String method:new String[]{"thread/read","thread/resume","thread/name/set","thread/turns/list","turn/start","turn/interrupt"}){
                NativeBridge.Call call=new NativeBridge.Call(method,new JSONObject().put("threadId",removed).put("includeTurns",true));
                check(NativeBridge.enqueue(call),"removed "+method+" is accepted for an answer");NativeBridge.tick();
                try{call.result.get(1,TimeUnit.SECONDS);throw new AssertionError("removed "+method+" answered");}
                catch(java.util.concurrent.ExecutionException error){
                    check("THREAD_UNAVAILABLE".equals(error.getCause().getMessage()),"removed "+method+": THREAD_UNAVAILABLE, got "+error.getCause());
                }
            }
            check(CodexAppServer.coldStarts.isEmpty(),"removed conversation: zero Codex starts "+CodexAppServer.coldStarts);
            check(server.nextRequest==sent&&NativeBridge.calls.isEmpty(),"removed conversation: zero Codex requests (running="+running+")");
        }
        CodexAppServer.shared=server;AcpConversations.REMOVED.clear();NativeBridge.inbox.clear();
    }
    private static void workspaceAdmissionRouting(CodexAppServer server)throws Exception {
        String thread="workspace-thread";DedicatedScreens registry=DedicatedScreensOnDevice.shared();
        NativeBridge.Call queued=new NativeBridge.Call("turn/start",new JSONObject().put("threadId",thread));
        check(NativeBridge.enqueue(queued)&&NativeBridge.workspaceRuntime(thread).getInt("pendingExecutions")==1,"queued execution is visible to workspace stop proof");
        registry.revision++;registry.resumeRequired=true;
        NativeBridge.tick();
        check(queued.result.isCompletedExceptionally()&&NativeBridge.workspaceRuntime(thread).getInt("pendingExecutions")==0,"dispatch rejects an old queued grant even when owner is agent again");
        NativeBridge.Call sent=new NativeBridge.Call("turn/start",new JSONObject().put("threadId",thread));NativeBridge.enqueue(sent);NativeBridge.tick();
        int id=sent.admission.requestId;NativeBridge.admissions.response(id,true);NativeBridge.complete(new JSONObject().put("id",id).put("result",new JSONObject()));
        check(NativeBridge.workspaceRuntime(thread).getInt("pendingExecutions")==1,"response before turn event remains unresolved in actual runtime snapshot");
        registry.revision++;registry.resumeRequired=true;
        start(thread,"late-start");
        NativeBridge.request(server,call(991,thread,"late-start","tap",new JSONObject()));
        check(!server.replies.get(991).getBoolean("success")&&registry.resumeRequired,"late turn event cannot reactivate managed phone input or clear resume state");
        complete(thread,"late-start");
        NativeBridge.Call fresh=new NativeBridge.Call("turn/start",new JSONObject().put("threadId",thread));NativeBridge.enqueue(fresh);NativeBridge.tick();
        start(thread,"fresh-start");
        check(!registry.resumeRequired&&NativeBridge.workspaceRuntime(thread).getInt("pendingExecutions")==1,"fresh started event clears resume state while preserving outstanding reply proof");
        id=fresh.admission.requestId;NativeBridge.admissions.response(id,true);NativeBridge.complete(new JSONObject().put("id",id).put("result",new JSONObject()));
        check(NativeBridge.workspaceRuntime(thread).getInt("pendingExecutions")==0,"fresh start fully drains after both native acknowledgments");
        NativeBridge.closed();NativeBridge.inbox.clear();server.replies.clear();registry.resumeRequired=false;
    }
    /** F3: a closed sub-agent woken again is refused, and its parent is told why if its turn runs. */
    private static void closedSubAgentWoken() throws Exception {
        DedicatedScreens registry = DedicatedScreensOnDevice.shared();
        String parent = "a1000000-0000-4000-8000-000000000001", sub = "a1000000-0000-4000-8000-000000000002";
        DedicatedScreensOnDevice.shared().closed.put(sub, new DedicatedScreens.ParentNote(parent, "it stopped"));
        start(parent, "parent-turn");
        NativeBridge.inbox.clear();
        registry.resumeRequired = true;
        start(sub, "woken-turn");
        registry.resumeRequired = false;
        java.util.List<NativeBridge.Call> sent = new java.util.ArrayList<>(NativeBridge.inbox);
        check(sent.size() == 2
                && sent.get(0).method.equals("turn/interrupt") && sent.get(0).params.getString("threadId").equals(sub)
                && sent.get(0).params.getString("turnId").equals("woken-turn"),
                "F3: the woken turn is interrupted: " + sent.size());
        JSONObject steer = sent.get(1).params;
        check(sent.get(1).method.equals("turn/steer") && steer.getString("threadId").equals(parent)
                && steer.getString("expectedTurnId").equals("parent-turn")
                && steer.getJSONArray("input").getJSONObject(0).getString("text").equals("it stopped"),
                "F3: into the parent's running turn goes why: " + steer);

        complete(parent, "parent-turn");
        NativeBridge.inbox.clear();
        registry.resumeRequired = true;
        start(sub, "woken-again");
        registry.resumeRequired = false;
        check(NativeBridge.inbox.size() == 1 && NativeBridge.inbox.peek().method.equals("turn/interrupt"),
                "F3: a parent not running is not woken to be told");

        DedicatedScreensOnDevice.shared().closed.clear();
        start(parent, "parent-turn-2");
        NativeBridge.inbox.clear();
        registry.resumeRequired = true;
        start(sub, "taken-over-turn");
        registry.resumeRequired = false;
        check(NativeBridge.inbox.size() == 1 && NativeBridge.inbox.peek().method.equals("turn/interrupt"),
                "a turn refused for another reason tells nobody");
        NativeBridge.closed();NativeBridge.inbox.clear();
    }
    private static void memoryEventRouting()throws Exception {
        String thread="memory-event-thread",turn="memory-event-turn";
        JSONObject usage=new JSONObject().put("total",new JSONObject().put("totalTokens",42));
        JSONObject[] events={
            new JSONObject().put("method","turn/started").put("params",new JSONObject().put("threadId",thread).put("turn",new JSONObject().put("id",turn))),
            new JSONObject().put("method","item/agentMessage/delta").put("params",new JSONObject().put("threadId",thread).put("turnId",turn).put("itemId","item-one").put("delta","原始 delta\n")),
            new JSONObject().put("method","item/completed").put("params",new JSONObject().put("threadId",thread).put("turnId",turn).put("item",new JSONObject().put("id","item-one").put("type","agentMessage").put("text","完整正文\n"))),
            new JSONObject().put("method","thread/tokenUsage/updated").put("params",new JSONObject().put("threadId",thread).put("tokenUsage",usage)),
            new JSONObject().put("method","turn/completed").put("params",new JSONObject().put("threadId",thread).put("turn",new JSONObject().put("id",turn).put("status","completed")))
        };
        MemoryGen.events.clear();
        for(int i=0;i<events.length;i++) {
            NativeBridge.event(events[i]);
            check(MemoryGen.events.size()==i+1 && MemoryGen.events.get(i).similar(events[i]),
                "Memory observer receives each original event once and in order: "+events[i].getString("method"));
            if(i==0)check(turn.equals(NativeBridge.state(thread).optString("activeTurnId")),
                "Memory forwarding does not consume the native turn-start event");
        }
        JSONObject state=NativeBridge.state(thread);
        check(state.getJSONObject("usage").similar(usage),"Memory forwarding preserves native token usage");
        check(!state.has("activeTurnId") && state.getJSONObject("completedTurn").getString("status").equals("completed"),
            "Memory forwarding preserves native terminal-state cleanup");
        check(DedicatedScreensOnDevice.shared().turnsEnded.equals(java.util.List.of(thread)),
            "turn/completed hands the thread's dedicated screen back exactly once");
        NativeBridge.closed();MemoryGen.events.clear();
    }
    private static void policyChangeNotices(CodexAppServer server) throws Exception {
        int id = 600;
        for (String thread : new String[]{"policy-main", "policy-sub-agent"}) {
            String turn = thread + "-turn";
            start(thread, turn);
            var first = NativeBridge.phonePolicyChanged(thread);
            var second = NativeBridge.phonePolicyChanged(thread);
            int before = PhoneNativeTools.executions.get(), tap = id++, ask = id++;
            NativeBridge.request(server, call(tap, thread, turn, "tap", new JSONObject()));
            NativeBridge.request(server, call(ask, thread, turn, "ask_user", new JSONObject()));
            check(!server.replies.containsKey(tap) && !NativeBridge.hasPhoneQuestion(thread),
                    "Spec 2: pending cleanup waits off the pump and does not show the requested card");
            first.complete("A released; reevaluate");
            second.complete("B cleanup failed; reevaluate");
            awaitReply(server, tap); awaitReply(server, ask); awaitCleanup();
            check(server.replies.get(tap).getString("text").contains("A released")
                            && server.replies.get(tap).getString("text").contains("B cleanup failed")
                            && server.replies.get(ask).getString("text").contains("reevaluate"),
                    "Spec 2: main and child receive actual outcomes through their own tool replies");
            check(PhoneNativeTools.executions.get() == before
                            && !server.replies.get(tap).getBoolean("success")
                            && !server.replies.get(ask).getBoolean("success")
                            && NativeBridge.workspaceRuntime(thread).getString("activeTurnId").equals(turn),
                    "Spec 2: notice calls do not execute their operations or stop the turn");
            int retry = id++;
            NativeBridge.request(server, call(retry, thread, turn, "tap", new JSONObject()));
            awaitReply(server, retry); awaitCleanup();
            check(server.replies.get(retry).getString("text").equals("executed")
                            && PhoneNativeTools.executions.get() == before + 1,
                    "Spec 2: the reevaluated next operation runs normally");
            var late = NativeBridge.phonePolicyChanged(thread);
            complete(thread, turn);
            start(thread, "next-turn");
            late.complete("old turn only");
            int next = id++;
            NativeBridge.request(server, call(next, thread, "next-turn", "tap", new JSONObject()));
            awaitReply(server, next); awaitCleanup();
            check(server.replies.get(next).getString("text").equals("executed"),
                    "Spec 2: an old permission change never reaches a new turn");
        }
        start("policy-delivery", "turn");
        NativeBridge.phonePolicyChanged("policy-delivery").complete("reevaluate before retrying");
        server.failBeforeReply = 650;
        NativeBridge.request(server, call(650, "policy-delivery", "turn", "tap", new JSONObject()));
        awaitCleanup(); server.failBeforeReply = null;
        NativeBridge.request(server, call(651, "policy-delivery", "turn", "tap", new JSONObject()));
        awaitReply(server, 651); awaitCleanup();
        check(server.replies.get(651).getString("text").contains("reevaluate"),
                "Spec 2: failed notice delivery cannot clear the reevaluation barrier");
        NativeBridge.phonePolicyChanged("policy-delivery");
        NativeBridge.stopPhoneTurn("policy-delivery", "turn");
        check(NativeBridge.phonePolicyChanges.isEmpty(), "Spec 2: stopping clears unconsumed notices");
        NativeBridge.closed(); server.replies.clear(); PhoneNativeTools.executions.set(0);
    }

    private static void pendingQuestionsAcrossSessions(CodexAppServer server) throws Exception {
        String parent = "question-parent", child = "question-child", turn = "question-turn";
        start(parent, turn);
        start(child, turn);
        JSONObject question = new JSONObject().put("question", "Model or version?")
                .put("options", new JSONArray().put("Model").put("Version"));
        NativeBridge.request(server, call(801, parent, turn, "ask_user", question));
        NativeBridge.request(server, call(802, child, turn, "confirm_action", args()));
        String parentKey = key(parent), childKey = key(child);
        JSONArray shown = NativeBridge.pendingPhoneQuestions().getJSONArray("requests");
        check(shown.length() == 2 && shown.getJSONObject(0).getString("key").equals(parentKey)
                && shown.getJSONObject(1).getString("key").equals(childKey),
                "System cards see both sessions in publication order");
        shown.getJSONObject(0).getJSONObject("params").getJSONObject("arguments").put("question", "Changed");
        check(NativeBridge.state(parent).getJSONArray("requests").getJSONObject(0)
                .getJSONObject("params").getJSONObject("arguments").getString("question").equals("Model or version?"),
                "Reading the cards leaves the original question intact");

        NativeBridge.respond(parentKey, new JSONObject().put("optionIndex", 0));
        shown = NativeBridge.pendingPhoneQuestions().getJSONArray("requests");
        check(shown.length() == 1 && shown.getJSONObject(0).getString("key").equals(childKey)
                && server.replies.get(801).getBoolean("success") && !server.replies.containsKey(802),
                "Answering the parent withdraws only its card");
        NativeBridge.stopPhoneTurn(child, turn);
        check(NativeBridge.pendingPhoneQuestions().getJSONArray("requests").length() == 0,
                "Cancelled child questions are withdrawn");
        complete(parent, turn);
        complete(child, turn);
    }

    public static void main(String[] unused) throws Exception {
        steerRejectionClassification();
        NativeBridge.closed();CodexAppServer server=new CodexAppServer();CodexAppServer.shared=server;
        try {
            memoryEventRouting();
            closedSubAgentWoken();
            workspaceAdmissionRouting(server);
            removedAcpConversationNeverStartsCodex(server);
            archivedSessions();
            policyChangeNotices(server);
            pendingQuestionsAcrossSessions(server);
            start(THREAD,TURN);
            JSONObject original=args(),event=call(1,THREAD,TURN,"confirm_action",original);
            NativeBridge.request(server,event);String first=key(THREAD);
            original.put("title","Forged mutation").getJSONArray("fields").getJSONObject(0).put("value","Forged target");
            event.getJSONObject("params").put("turnId","forged-turn");
            JSONObject pending=NativeBridge.state(THREAD).getJSONArray("requests").getJSONObject(0);
            check(pending.getString("method").equals("phone/confirmAction"),"Native confirmation method");
            check(pending.getJSONObject("params").getString("turnId").equals(TURN),"Pending call owns turn snapshot");
            check(pending.getJSONObject("params").getJSONObject("arguments").getString("title").equals("Confirm actual action"),"Pending owns content snapshot");
            check(!server.replies.containsKey(1),"Publishing does not approve");
            NativeBridge.request(server,call(2,THREAD,TURN,"tap",new JSONObject()));
            check(!server.replies.get(2).getBoolean("success") && PhoneNativeTools.executions.get()==0,"Pending confirmation blocks mutation");
            NativeBridge.request(server,call(3,THREAD,TURN,"confirm_action",args()));
            check(!server.replies.get(3).getBoolean("success") && NativeBridge.requests.size()==1,"One active question per thread");
            rejectReply(first,"accept");check(NativeBridge.requests.containsKey(first),"Invalid decision leaves call answerable");
            NativeBridge.respond(first,new JSONObject().put("decision","confirm").put("snapshot",new JSONObject()).put("userProvided",false));
            JSONObject answer=new JSONObject(server.replies.get(1).getString("text"));
            check(answer.getString("decision").equals("confirm") && answer.getBoolean("userProvided"),"Real confirmation returned");
            check(answer.getString("confirmationId").equals(first) && answer.getString("threadId").equals(THREAD)
                && answer.getString("turnId").equals(TURN),"Confirmation bound to actual call and turn");
            check(answer.getJSONObject("snapshot").getJSONArray("fields").getJSONObject(0).getString("value").equals("Verified target"),"Response contains the shown target");
            check(answer.getString("contentHash").matches("[0-9a-f]{64}"),"Actual snapshot hash");
            check(!NativeBridge.requests.containsKey(first),"Confirmation consumed once");rejectReply(first,"confirm");
            for(String decision:new String[]{"modify","cancel"}) {
                int id=decision.equals("modify")?4:5;
                NativeBridge.request(server,call(id,THREAD,TURN,"taku_phone__confirm_action",args()));
                NativeBridge.respond(key(THREAD),new JSONObject().put("decision",decision));
                check(new JSONObject(server.replies.get(id).getString("text")).getString("decision").equals(decision),"Preserves "+decision);
            }
            check(PhoneNativeTools.executions.get()==0,"Confirmation itself never performs the action");
            JSONObject bad=args().put("title",42);NativeBridge.request(server,call(6,THREAD,TURN,"confirm_action",bad));
            check(!server.replies.get(6).getBoolean("success") && NativeBridge.requests.isEmpty(),"Malformed confirmation rejected");

            // A synchronous send callback may immediately dispatch the next tool for this turn.
            NativeBridge.request(server,call(40,THREAD,TURN,"confirm_action",args()));String racing=key(THREAD);
            int executedBeforeRace=PhoneNativeTools.executions.get();
            server.replyHook=(id,reply)->{if(id.equals(40)) {
                check(!NativeBridge.hasPhoneQuestion(THREAD),"Confirmation consumed before response delivery begins");
                rejectReply(racing,"confirm");
                NativeBridge.request(server,call(41,THREAD,TURN,"prepare_foreground_draft",new JSONObject()));
            }};
            NativeBridge.respond(racing,new JSONObject().put("decision","confirm"));server.replyHook=null;
            awaitReply(server,41);awaitCleanup();
            check(server.replies.get(41).getBoolean("success") && PhoneNativeTools.executions.get()==executedBeforeRace+1,
                "Next mutation proceeds during confirmation delivery without duplicate execution");

            JSONObject question=new JSONObject().put("question","Which date?").put("options",new JSONArray().put("Today").put("Tomorrow"));
            NativeBridge.request(server,call(42,THREAD,TURN,"ask_user",question));String asking=key(THREAD);
            rejectPayload(asking,new JSONObject().put("optionIndex","0"));
            check(NativeBridge.requests.containsKey(asking) && !server.replies.containsKey(42),"Invalid choice is not consumed or delivered");
            server.replyHook=(id,reply)->{if(id.equals(42)) {
                check(!NativeBridge.hasPhoneQuestion(THREAD),"Choice consumed before response delivery begins");
                NativeBridge.request(server,call(43,THREAD,TURN,"prepare_foreground_draft",new JSONObject()));
            }};
            NativeBridge.respond(asking,new JSONObject().put("optionIndex",0));server.replyHook=null;
            awaitReply(server,43);awaitCleanup();check(server.replies.get(43).getBoolean("success"),"Next mutation proceeds during choice delivery");

            // A card's start and end reach the dedicated screens; a hand-over is added to the answer.
            DedicatedScreens screens=DedicatedScreensOnDevice.shared();screens.asked.clear();screens.ended.clear();
            NativeBridge.request(server,call(45,THREAD,TURN,"ask_user",new JSONObject(question.toString()).put("returnTo","Booking page, 2 people")));
            check(screens.asked.equals(java.util.List.of(THREAD+" Which date? Booking page, 2 people")),"ask_user reports its question and returnTo: "+screens.asked);
            screens.handedOver=new JSONObject().put("returnTo","Booking page, 2 people");
            NativeBridge.respond(key(THREAD),new JSONObject().put("optionIndex",1));
            JSONObject told=new JSONObject(server.replies.get(45).getString("text"));
            check(told.getString("answer").equals("Tomorrow") && told.getJSONObject("handedOver").getString("returnTo").equals("Booking page, 2 people"),"The answer carries the hand-over: "+told);
            NativeBridge.request(server,call(46,THREAD,TURN,"confirm_action",args().put("returnTo","")));
            check(screens.asked.get(1).equals(THREAD+" Confirm actual action "),"confirm_action reports its title: "+screens.asked);
            NativeBridge.respond(key(THREAD),new JSONObject().put("decision","confirm"));
            check(!new JSONObject(server.replies.get(46).getString("text")).has("handedOver"),"Nothing handed over, nothing added");
            NativeBridge.request(server,call(47,THREAD,TURN,"ask_user",question));
            check(screens.asked.get(2).equals(THREAD+" Which date? "),"A card without returnTo reports it empty");
            NativeBridge.event(new JSONObject().put("method","serverRequest/resolved").put("params",new JSONObject().put("threadId",THREAD).put("requestId",47)));
            check(!NativeBridge.hasPhoneQuestion(THREAD) && screens.ended.equals(java.util.List.of(THREAD,THREAD,THREAD)),"A card Codex resolved unanswered ends too: "+screens.ended);

            NativeBridge.setPhonePermission(THREAD,"ask");
            NativeBridge.request(server,call(44,THREAD,TURN,"tap",new JSONObject()));String approving=key(THREAD);
            rejectPayload(approving,new JSONObject().put("decision",true));rejectReply(approving,"not-a-decision");
            check(NativeBridge.requests.containsKey(approving),"Invalid action decision leaves approval answerable");
            server.replyHook=(id,reply)->{if(id.equals(44))check(!NativeBridge.hasPhoneQuestion(THREAD),"Action approval consumed before reply");};
            NativeBridge.respond(approving,new JSONObject().put("decision","decline"));server.replyHook=null;
            NativeBridge.setPhonePermission(THREAD,"full");

            for(int id:new int[]{50,51}) {
                NativeBridge.request(server,call(id,THREAD,TURN,"confirm_action",args()));String uncertain=key(THREAD);
                if(id==50)server.failBeforeReply=id;else server.failAfterReply=id;
                try { NativeBridge.respond(uncertain,new JSONObject().put("decision","confirm"));throw new AssertionError("Transport failure hidden"); }
                catch(IOException expected) {check(expected.getMessage().contains("delivery has not been confirmed"),"Transport uncertainty is explicit");}
                check(!NativeBridge.requests.containsKey(uncertain),"Uncertain delivery never restores a replayable confirmation");
                rejectReply(uncertain,"confirm");
                check(server.replies.containsKey(id)==(id==51),"Both pre-delivery and post-delivery failure tested");
                server.failBeforeReply=null;server.failAfterReply=null;
            }

            // Simulate a draft helper blocked in execution; stop must work before any server acknowledgment.
            PhoneNativeTools.nextBlockingCall();
            NativeBridge.request(server,call(10,THREAD,TURN,"prepare_foreground_draft",new JSONObject().put("block",true)));
            check(PhoneNativeTools.entered.await(2,TimeUnit.SECONDS),"Worker entered");
            NativeBridge.request(server,call(11,THREAD,TURN,"confirm_action",args()));
            String stale=key(THREAD);JSONObject stalePending=new JSONObject(NativeBridge.requests.get(stale).toString());
            start("thread-two","turn-two");NativeBridge.request(server,call(12,"thread-two","turn-two","confirm_action",args()));
            String other=key("thread-two");
            NativeBridge.stopPhoneTurn(THREAD,TURN);
            check(PhoneNativeTools.interrupted.await(2,TimeUnit.SECONDS),"Stop immediately interrupts worker without turn/completed");
            check(!NativeBridge.requests.containsKey(stale) && NativeBridge.requests.containsKey(other),"Stop clears only matching pending");
            check(!NativeBridge.phoneTurnActive(call(0,THREAD,TURN,"tap",new JSONObject()).getJSONObject("params")),"Stopped turn inactive immediately");
            awaitCleanup();check(!server.replies.containsKey(10),"Cancelled worker cannot publish successful result");
            rejectReply(stale,"confirm");
            int before=PhoneNativeTools.executions.get();
            NativeBridge.request(server,call(13,THREAD,TURN,"prepare_foreground_draft",new JSONObject()));
            NativeBridge.request(server,call(14,THREAD,TURN,"confirm_action",args()));
            NativeBridge.executePhone(server,15,call(0,THREAD,TURN,"tap",new JSONObject()).getJSONObject("params"));
            check(!server.replies.get(13).getBoolean("success") && !server.replies.get(14).getBoolean("success")
                && !server.replies.get(15).getBoolean("success"),"Late request and direct execution rejected");
            check(PhoneNativeTools.executions.get()==before,"No late worker started");
            // Even an unexpectedly retained old pending row cannot authorize a stopped turn.
            NativeBridge.requests.put(stale,stalePending);rejectReply(stale,"confirm");
            check(!NativeBridge.requests.containsKey(stale) && !server.replies.containsKey(11),"Stopped pending fails closed");
            NativeBridge.respond(other,new JSONObject().put("decision","cancel"));
            check(server.replies.get(12).getBoolean("success"),"Other thread remains usable");
            start(THREAD,"turn-next");
            NativeBridge.request(server,call(16,THREAD,"turn-next","prepare_foreground_draft",new JSONObject()));
            awaitReply(server,16);check(server.replies.get(16).getBoolean("success"),"New turn can execute normally");awaitCleanup();
            // Replayed start for a cancelled id must not reactivate it.
            start(THREAD,TURN);NativeBridge.request(server,call(17,THREAD,TURN,"tap",new JSONObject()));
            check(!server.replies.get(17).getBoolean("success"),"Stopped turn id stays stopped");

            start(THREAD,"turn-completion");PhoneNativeTools.nextBlockingCall();
            NativeBridge.request(server,call(20,THREAD,"turn-completion","tap",new JSONObject().put("block",true)));
            check(PhoneNativeTools.entered.await(2,TimeUnit.SECONDS),"Completion-test worker entered");
            NativeBridge.request(server,call(21,THREAD,"turn-completion","confirm_action",args()));
            complete(THREAD,"turn-completion");
            check(PhoneNativeTools.interrupted.await(2,TimeUnit.SECONDS),"Completion interrupts worker");
            check(NativeBridge.state(THREAD).getJSONArray("requests").length()==0,"Completion clears pending");awaitCleanup();
            NativeBridge.request(server,call(22,THREAD,"turn-completion","tap",new JSONObject()));
            check(!server.replies.get(22).getBoolean("success"),"Completed turn cannot execute late calls");

            start(THREAD,"turn-close");PhoneNativeTools.nextBlockingCall();
            NativeBridge.request(server,call(30,THREAD,"turn-close","tap",new JSONObject().put("block",true)));
            check(PhoneNativeTools.entered.await(2,TimeUnit.SECONDS),"Close-test worker entered");
            int codexGoneBefore = DedicatedScreensOnDevice.shared().codexGone.get();
            NativeBridge.closed();check(PhoneNativeTools.interrupted.await(2,TimeUnit.SECONDS),"Runtime close interrupts worker");
            check(DedicatedScreensOnDevice.shared().codexGone.get() == codexGoneBefore + 1, "Runtime close closes every dedicated screen");
            check(NativeBridge.state.isEmpty() && NativeBridge.requests.isEmpty(),"Runtime close discards lifecycle and pending");
            NativeBridge.request(server,call(31,THREAD,"turn-close","tap",new JSONObject()));
            check(!server.replies.get(31).getBoolean("success"),"Closed runtime rejects stale tool");
            System.out.println("NativeBridgeRoutingTest: "+checks+" checks passed (test doubles; no device actions)");
        } finally { NativeBridge.closed(); }
    }
}
