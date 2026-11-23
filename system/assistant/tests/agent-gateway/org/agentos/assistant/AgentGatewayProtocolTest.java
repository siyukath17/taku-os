package org.agentos.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

public final class AgentGatewayProtocolTest {
    private static final String THREAD = "00000000-0000-4000-8000-000000000001";
    private static final String KEY = "00000000-0000-4000-8000-000000000002";
    private static int assertions;
    private interface Checked { void run() throws Exception; }
    private static void check(boolean value) { assertions++; if (!value) throw new AssertionError(); }
    private static void rejects(Checked check) throws Exception {
        assertions++; try { check.run(); } catch (Exception expected) { return; }
        throw new AssertionError("Expected rejection");
    }
    private static void rejectsWithCode(String code, Checked check) throws Exception {
        assertions++;
        try { check.run(); }
        catch (java.io.IOException expected) {
            if (code.equals(expected.getMessage())) return;
            throw new AssertionError("Expected " + code + ", got " + expected.getMessage(), expected);
        }
        throw new AssertionError("Expected " + code);
    }
    private static void primaryReplies() throws Exception {
        JSONObject provenance = new JSONObject().put("kind", "primary-assistant-reply").put("threadId", THREAD)
                .put("turnId", "turn").put("contractHash", "a".repeat(64)).put("anchorOrdinal", 1)
                .put("anchorHash", "b".repeat(64)).put("sourceItemIds", new JSONArray().put("source-a").put("source-b"));
        JSONObject reply = new JSONObject().put("schema", "taku.main-reply/1").put("id", "reply-1").put("key", "primary-card")
                .put("revision", 3).put("readableText", "Coffee\nTotal 12.50 USD").put("provenance", provenance)
                .put("lifecycle", new JSONObject().put("phase", "completed"))
                .put("card", new JSONObject().put("request", new JSONObject().put("version", 2).put("key", "primary-card").put("mode", "card")
                        .put("title", "Coffee").put("summary", "Total 12.50 USD").put("blocks", new JSONArray()).put("actions", new JSONArray()))
                        .put("view", new JSONObject().put("catalogVersion", "taku-ui-1").put("signature", "fixture")
                                .put("metrics", new JSONObject().put("level", "primary").put("ms", 0).put("inputTokens", JSONObject.NULL).put("llmUsage", JSONObject.NULL)))
                        .put("assembly", new JSONObject()));
        reply.getJSONObject("card").getJSONObject("view").put("spec", new JSONObject().put("root", "root").put("elements", new JSONObject()
                .put("root", new JSONObject().put("type", "Card").put("props", new JSONObject().put("title", new JSONObject().put("$state", "/data/title")))
                        .put("children", new JSONArray().put("authorization")))
                .put("authorization", new JSONObject().put("type", "Text").put("props", new JSONObject().put("text", new JSONObject().put("$state", "/data/summary")))
                        .put("children", new JSONArray()))));
        JSONArray items = new JSONArray().put(new JSONObject().put("id", "source-a").put("type", "agentMessage")
                .put("replyId", "reply-1").put("text", reply.getString("readableText")))
                .put(new JSONObject().put("id", "source-b").put("type", "agentMessage").put("text", ""));
        JSONObject event = new JSONObject().put("schema", "taku.session-events/1").put("brokerEpoch", KEY).put("threadId", THREAD)
                .put("seq", 12).put("method", "session/reply").put("params", new JSONObject().put("turnId", "turn").put("reply", reply).put("items", items));
        JSONObject visible = AgentGatewayProtocol.sessionEvent(event, THREAD).getJSONObject("params");
        check(visible.length() == 3 && visible.getJSONObject("reply").similar(reply) && visible.getJSONArray("items").similar(items));
        JSONObject nativeTurn = new JSONObject().put("id", "turn").put("mainReplies", new JSONObject().put("reply-1", reply)).put("items", items);
        JSONObject nativeThread = new JSONObject().put("id", THREAD).put("turns", new JSONArray().put(nativeTurn)).put("accessToken", "private");
        JSONObject snapshot = AgentGatewayProtocol.threadView(nativeThread);
        check(!snapshot.has("accessToken") && snapshot.getJSONArray("turns").getJSONObject(0).getJSONObject("mainReplies").getJSONObject("reply-1").similar(reply));
        JSONObject terminal = new JSONObject(event.toString()).put("method", "turn/completed").put("params", new JSONObject().put("turn", nativeTurn));
        check(AgentGatewayProtocol.sessionEvent(terminal, THREAD).getJSONObject("params").getJSONObject("turn").getJSONObject("mainReplies").getJSONObject("reply-1").similar(reply));
        JSONObject baseline = new JSONObject(event.toString()).put("method", "session/snapshot").put("params", new JSONObject().put("thread", nativeThread));
        check(AgentGatewayProtocol.sessionEvent(baseline, THREAD).getJSONObject("params").getJSONObject("thread").getJSONArray("turns")
                .getJSONObject(0).getJSONObject("mainReplies").getJSONObject("reply-1").similar(reply));
        JSONObject wrong = new JSONObject(event.toString());
        wrong.getJSONObject("params").getJSONObject("reply").getJSONObject("provenance").put("turnId", "other");
        rejects(() -> AgentGatewayProtocol.sessionEvent(wrong, THREAD));
        JSONObject missingItems = new JSONObject(event.toString()); missingItems.getJSONObject("params").remove("items");
        rejects(() -> AgentGatewayProtocol.sessionEvent(missingItems, THREAD));
        JSONObject extra = new JSONObject(event.toString()); extra.getJSONObject("params").put("request", new JSONObject());
        rejects(() -> AgentGatewayProtocol.sessionEvent(extra, THREAD));
        JSONObject input = new JSONObject().put("threadId", THREAD).put("turnId", "turn").put("replyId", "reply-1");
        JSONObject canonical = new JSONObject().put("ok", true).put("threadId", THREAD).put("turnId", "turn").put("reply", reply);
        int[] reads = {0};
        AgentGatewayProtocol.NativeRead nativeRead = request -> {
            reads[0]++;
            check(request.length() == 5 && request.getString("op").equals("mainReply") && request.getString("action").equals("read"));
            check(request.getString("threadId").equals(THREAD) && request.getString("turnId").equals("turn") && request.getString("replyId").equals("reply-1"));
            return canonical;
        };
        JSONObject read = AgentGatewayProtocol.replyRead(input, nativeRead);
        check(read.getJSONObject("reply").similar(visible.getJSONObject("reply")) && reads[0] == 1);
        for (String field : new String[]{"request", "view", "limit", "action"}) {
            JSONObject invalid = new JSONObject(input.toString()).put(field, "untrusted");
            check(AgentGatewayProtocol.replyRead(invalid, nativeRead).getJSONObject("error").getString("code").equals("INVALID_ARGUMENT"));
        }
        for (JSONObject invalid : new JSONObject[]{new JSONObject(input.toString()).put("threadId", "../thread"),
                new JSONObject(input.toString()).put("turnId", 3), new JSONObject(input.toString()).put("replyId", JSONObject.NULL),
                new JSONObject(input.toString()).put("replyId", "x".repeat(161)), new JSONObject().put("threadId", THREAD)})
            check(AgentGatewayProtocol.replyRead(invalid, nativeRead).getJSONObject("error").getString("code").equals("INVALID_ARGUMENT"));
        check(reads[0] == 1);
        for (String code : new String[]{"THREAD_UNAVAILABLE", "TURN_UNAVAILABLE", "REPLY_UNAVAILABLE", "INVALID_ARGUMENT"}) {
            JSONObject error = AgentGatewayProtocol.replyReadError(code);
            check(AgentGatewayProtocol.replyRead(input, request -> error).similar(error));
        }
        check(AgentGatewayProtocol.replyRead(input, request -> {throw new java.io.IOException("socket lost");})
                .getJSONObject("error").getString("code").equals("REPLY_UNAVAILABLE"));
        check(AgentGatewayProtocol.replyRead(input, request -> {throw new java.io.IOException();})
                .getJSONObject("error").getString("code").equals("REPLY_UNAVAILABLE"));
        JSONObject summaries = AgentGatewayProtocol.replyRead(new JSONObject().put("threadId", THREAD).put("turnId", "turn"), request -> {
            check(request.length() == 4 && !request.has("replyId"));
            return new JSONObject().put("ok", true).put("threadId", THREAD).put("turnId", "turn").put("replies", new JSONArray()
                    .put(new JSONObject().put("id", "reply-1").put("readableText", "Coffee\nTotal 12.50 USD").put("hasCard", true)));
        });
        check(summaries.getJSONArray("replies").length() == 1 && !summaries.has("reply"));
        JSONObject protectedRead = new JSONObject(input.toString()).put("action", "read");
        check(AgentGatewayProtocol.request("mainReply", protectedRead.toString()).getString("replyId").equals("reply-1"));
        rejectsWithCode("INVALID_ARGUMENT", () -> AgentGatewayProtocol.request("mainReply", new JSONObject(protectedRead.toString()).put("action", "write").toString()));
    }
    public static void main(String[] args) throws Exception {
        JSONObject send = new JSONObject().put("threadId", THREAD).put("requestId", KEY).put("text", "Hello");
        check(AgentGatewayProtocol.request("sendMessage", send.toString()).getString("text").equals("Hello"));
        JSONObject live = new JSONObject(send.toString()).put("taskId", "12345");
        for (String action : new String[]{"live/task-read", "live/task-replace", "live/task-cancel"}) {
            check(AgentGatewayProtocol.request(action, live.toString()).similar(live));
            for (String field : new String[]{"threadId", "taskId", "requestId", "text"}) {
                JSONObject missing = new JSONObject(live.toString()); missing.remove(field);
                rejects(() -> AgentGatewayProtocol.request(action, missing.toString()));
                JSONObject wrongType = new JSONObject(live.toString()).put(field, 12);
                rejects(() -> AgentGatewayProtocol.request(action, wrongType.toString()));
            }
            for (String field : new String[]{"turnId", "action", "permissionMode", "goalActive"})
                rejects(() -> AgentGatewayProtocol.request(action, new JSONObject(live.toString()).put(field, "untrusted").toString()));
            rejects(() -> AgentGatewayProtocol.request(action, new JSONObject(live.toString()).put("threadId", "../thread").toString()));
            rejects(() -> AgentGatewayProtocol.request(action, new JSONObject(live.toString()).put("requestId", "../receipt").toString()));
            rejects(() -> AgentGatewayProtocol.request(action, new JSONObject(live.toString()).put("taskId", "../12345").toString()));
            rejects(() -> AgentGatewayProtocol.request(action, new JSONObject(live.toString()).put("taskId", "1".repeat(21)).toString()));
            rejects(() -> AgentGatewayProtocol.request(action, new JSONObject(live.toString()).put("text", " ").toString()));
            rejects(() -> AgentGatewayProtocol.request(action, new JSONObject(live.toString()).put("text", "x".repeat(16001)).toString()));
        }
        rejects(() -> AgentGatewayProtocol.request("rpc", "{}"));
        rejects(() -> AgentGatewayProtocol.request("sendMessage", new JSONObject(send.toString()).put("command", "sh").toString()));
        rejects(() -> AgentGatewayProtocol.request("sendMessage", new JSONObject(send.toString()).put("text", " ").toString()));
        rejects(() -> AgentGatewayProtocol.request("sendMessage", new JSONObject(send.toString()).put("threadId", "../../secret").toString()));
        rejects(() -> AgentGatewayProtocol.request("readSession", new JSONObject().put("threadId", THREAD).put("refresh", "true").toString()));
        rejects(() -> AgentGatewayProtocol.request("openSettings", "{\"page\":\"file:///data\"}"));
        check(AgentGatewayProtocol.request("bindSystemAi", "{\"scene\":\"memory.text\",\"group\":\"memory\"}").getString("group").equals("memory"));
        rejects(() -> AgentGatewayProtocol.request("bindSystemAi", "{\"scene\":\"memory.text\",\"group\":\"memory\",\"credentials\":\"secret\"}"));
        rejects(() -> AgentGatewayProtocol.request("readSystemAi", "{\"command\":\"settings\"}"));
        check(AgentGatewayProtocol.request("openMagpieManagement", "{}").length() == 0);
        for (String surface : new String[]{"onResume", "overlay", "focus"})
            check(AgentGatewayProtocol.request("prewarmAgent", "{\"surface\":\"" + surface + "\"}").getString("surface").equals(surface));
        rejectsWithCode("UNSUPPORTED_SURFACE", () -> AgentGatewayProtocol.request("prewarmAgent", "{\"surface\":\"codex\"}"));
        rejects(() -> AgentGatewayProtocol.request("prewarmAgent", "{}"));
        rejects(() -> AgentGatewayProtocol.request("prewarmAgent", "{\"surface\":\"focus\",\"runtime\":\"codex\"}"));
        check(AgentGatewayProtocol.request("readAccount", "{}").length() == 0);
        rejects(() -> AgentGatewayProtocol.request("readAccount", "{\"refreshToken\":true}"));
        check(AgentGatewayProtocol.request("connectChatGpt", "{}").length() == 0);
        rejects(() -> AgentGatewayProtocol.request("connectChatGpt", "{\"url\":\"https://example.com\"}"));
        check(AgentGatewayProtocol.request("cancelChatGpt", "{}").length() == 0);
        check(AgentGatewayProtocol.request("readJev", "{}").length() == 0);
        check(AgentGatewayProtocol.request("enableJev", "{}").length() == 0);
        rejects(() -> AgentGatewayProtocol.request("enableJev", "{\"model\":\"x\"}"));
        check(AgentGatewayProtocol.request("connectJev", "{\"key\":\"sk-or-v1-abc\"}").getString("key").equals("sk-or-v1-abc"));
        rejects(() -> AgentGatewayProtocol.request("connectJev", "{}"));
        rejects(() -> AgentGatewayProtocol.request("connectJev", "{\"key\":\"\"}"));
        rejects(() -> AgentGatewayProtocol.request("connectJev", "{\"key\":\"k\",\"model\":\"x\"}"));
        check(AgentGatewayProtocol.request("openSettings", "{\"page\":\"account\"}").getString("page").equals("account"));
        rejectsWithCode("INVALID_SETTINGS_PAGE", () -> AgentGatewayProtocol.request("openSettings", "{\"page\":\"login\"}"));
        rejects(() -> AgentGatewayProtocol.request("openMagpieManagement", "{\"url\":\"https://example.com\"}"));
        rejectsWithCode("UNSUPPORTED_PLUGIN_OPERATION", () -> AgentGatewayProtocol.request("pluginManager", "{\"operation\":\"rpc\"}"));
        rejectsWithCode("INVALID_argument", () -> AgentGatewayProtocol.request("pluginManager", "{\"operation\":\"show\",\"argument\":\"\"}"));
        rejectsWithCode("INVALID_name", () -> AgentGatewayProtocol.request("pluginManager", "{\"operation\":\"call-tool\",\"name\":false}"));
        rejectsWithCode("INVALID_arguments", () -> AgentGatewayProtocol.request("pluginManager", "{\"operation\":\"call-tool\",\"arguments\":[]}"));
        rejectsWithCode("UNSUPPORTED_PARAMETER", () -> AgentGatewayProtocol.request("pluginManager", "{\"operation\":\"ping\",\"command\":\"sh\"}"));
        check(AgentGatewayProtocol.request("reorderSystemAi", "{\"group\":\"memory\",\"members\":[\"a\",\"b\"]}").getJSONArray("members").length()==2);
        rejects(() -> AgentGatewayProtocol.request("steer", new JSONObject().put("threadId", THREAD)
                .put("expectedTurnId", "turn-1").put("text", "extra").toString()));
        rejects(() -> AgentGatewayProtocol.request("cancelQueued", new JSONObject().put("threadId", THREAD)
                .put("taskId", "../ready").toString()));
        check(AgentGatewayProtocol.request("cancelQueued", new JSONObject().put("threadId", THREAD)
                .put("taskId", "12345").toString()).getString("taskId").equals("12345"));
        rejects(() -> AgentGatewayProtocol.request("continueTask", new JSONObject().put("threadId", THREAD)
                .put("taskId", "../ready").toString()));
        rejects(() -> AgentGatewayProtocol.request("continueTask", new JSONObject().put("threadId", THREAD)
                .put("taskId", "12345").put("text", "resend me").toString()));
        check(AgentGatewayProtocol.request("continueTask", new JSONObject().put("threadId", THREAD)
                .put("taskId", "12345").toString()).getString("taskId").equals("12345"));
        JSONObject threadWorkspaces = new JSONObject().put("threadId", THREAD);
        JSONObject homeWorkspaces = new JSONObject().put("scope", "home");
        check(AgentGatewayProtocol.request("getWorkspaces", threadWorkspaces.toString()).similar(threadWorkspaces));
        check(AgentGatewayProtocol.request("getWorkspaces", homeWorkspaces.toString()).similar(homeWorkspaces));
        rejects(() -> AgentGatewayProtocol.request("getWorkspaces", "{}"));
        rejectsWithCode("UNSUPPORTED_PARAMETER", () -> AgentGatewayProtocol.request("getWorkspaces",
                new JSONObject().put("scope", "home").put("threadId", THREAD).toString()));
        for (Object scope : new Object[]{"desktop", "", true, JSONObject.NULL}) {
            rejectsWithCode("INVALID_WORKSPACE_SCOPE", () -> AgentGatewayProtocol.request("getWorkspaces",
                    new JSONObject().put("scope", scope).toString()));
        }
        rejects(() -> AgentGatewayProtocol.request("openWorkspace", new JSONObject().put("threadId", THREAD)
                .put("screenKey", "default").put("generation", KEY).toString()));
        JSONObject workspaceLease = new JSONObject().put("threadId", THREAD)
                .put("screenKey", "task-0123456789abcdef0123").put("generation", KEY);
        check(AgentGatewayProtocol.request("openWorkspace", workspaceLease.toString()).similar(workspaceLease));
        JSONObject homeWorkspace = new JSONObject(workspaceLease.toString()).put("source", "home");
        check(AgentGatewayProtocol.request("openWorkspace", homeWorkspace.toString()).similar(homeWorkspace));
        for (Object source : new Object[]{"session", "", true, JSONObject.NULL}) {
            homeWorkspace.put("source", source);
            rejectsWithCode("INVALID_WORKSPACE_SOURCE",
                    () -> AgentGatewayProtocol.request("openWorkspace", homeWorkspace.toString()));
        }
        JSONObject question = new JSONObject().put("key", KEY).put("method", "phone/confirmAction")
                .put("params", new JSONObject().put("threadId", THREAD));
        JSONObject auth = new JSONObject().put("key", "auth").put("method", "account/chatgptAuthTokens/refresh")
                .put("params", new JSONObject().put("threadId", THREAD).put("accessToken", "sensitive"));
        JSONObject other = new JSONObject(question.toString()); other.getJSONObject("params").put("threadId", KEY);
        JSONObject state = new JSONObject().put("ready", true).put("requests", new JSONArray().put(question).put(auth).put(other))
                .put("credentials", new JSONObject().put("accessToken", "sensitive"));
        JSONObject view = AgentGatewayProtocol.nativeView(state, THREAD);
        check(view.getJSONArray("requests").length() == 1);
        check(view.getInt("unsupportedRequestCount") == 1);
        check(!view.has("credentials"));
        check(AgentGatewayProtocol.pending(state, THREAD, KEY).getString("method").equals("phone/confirmAction"));
        rejects(() -> AgentGatewayProtocol.pending(state, KEY, "auth"));
        rejects(() -> AgentGatewayProtocol.pending(state, THREAD, "auth"));
        JSONObject cleaned = (JSONObject) AgentGatewayProtocol.clean(new JSONObject().put("text", "ordinary conversation")
                .put("nested", new JSONObject().put("AccessToken", "secret").put("api_key", "secret").put("value", 1)), 0);
        check(cleaned.getJSONObject("nested").length() == 1);
        check(cleaned.getString("text").equals("ordinary conversation"));
        JSONObject secretQuestion = new JSONObject().put("key", KEY).put("method", "item/tool/requestUserInput")
                .put("params", new JSONObject().put("threadId", THREAD).put("questions", new JSONArray()
                        .put(new JSONObject().put("id", "secret").put("isSecret", true))));
        JSONObject secretState = new JSONObject().put("requests", new JSONArray().put(secretQuestion));
        check(AgentGatewayProtocol.nativeView(secretState, THREAD).getJSONArray("requests").length() == 0);
        rejects(() -> AgentGatewayProtocol.pending(secretState, THREAD, KEY));
        check(((JSONObject) AgentGatewayProtocol.clean(new JSONObject().put("access_token", "secret"), 0)).length() == 0);
        JSONObject globalAuth = new JSONObject(auth.toString()); globalAuth.getJSONObject("params").remove("threadId");
        JSONObject globalView = AgentGatewayProtocol.nativeView(new JSONObject().put("requests", new JSONArray().put(globalAuth)), THREAD);
        check(globalView.getInt("unsupportedRequestCount") == 1 && globalView.getJSONArray("requests").length() == 0);
        JSONObject subscribe = new JSONObject().put("threadId", THREAD).put("brokerEpoch", KEY).put("afterSeq", 12);
        check(AgentGatewayProtocol.request("subscribeSession", subscribe.toString()).getLong("afterSeq") == 12);
        rejects(() -> AgentGatewayProtocol.request("subscribeSession", new JSONObject(subscribe.toString()).put("afterSeq", -1).toString()));
        rejects(() -> AgentGatewayProtocol.request("subscribeSession", new JSONObject(subscribe.toString()).put("afterSeq", "12").toString()));
        rejects(() -> AgentGatewayProtocol.request("subscribeSession", new JSONObject(subscribe.toString()).put("afterSeq", 1.5).toString()));
        rejects(() -> AgentGatewayProtocol.request("subscribeSession", new JSONObject().put("threadId", THREAD).put("afterSeq", 1).toString()));
        rejects(() -> AgentGatewayProtocol.request("subscribeSession", new JSONObject(subscribe.toString()).put("method", "account/read").toString()));
        check(AgentGatewayProtocol.request("unsubscribeSession", new JSONObject().put("subscriptionId", KEY).toString()).getString("subscriptionId").equals(KEY));
        check(AgentGatewayProtocol.request("readSession", new JSONObject().put("threadId", THREAD).put("statusOnly", true).toString()).getBoolean("statusOnly"));
        rejects(() -> AgentGatewayProtocol.request("readSession", new JSONObject().put("threadId", THREAD).put("statusOnly", true).put("refresh", true).toString()));
        JSONObject delta = new JSONObject().put("schema", "taku.session-events/1").put("brokerEpoch", KEY).put("threadId", THREAD)
                .put("seq", 1).put("method", "item/agentMessage/delta").put("params", new JSONObject().put("turnId", "turn")
                        .put("itemId", "item").put("delta", "中文🙂").put("authorization", "secret").put("extra", "private"));
        JSONObject visibleDelta = AgentGatewayProtocol.sessionEvent(delta, THREAD);
        check(visibleDelta.getJSONObject("params").length() == 3 && visibleDelta.getJSONObject("params").getString("delta").equals("中文🙂"));
        rejects(() -> AgentGatewayProtocol.sessionEvent(delta, KEY));
        rejects(() -> AgentGatewayProtocol.sessionEvent(new JSONObject(delta.toString()).put("method", "account/updated"), THREAD));
        JSONObject streamSnapshot = new JSONObject(delta.toString()).put("method", "session/snapshot").put("params", new JSONObject().put("thread",
                new JSONObject().put("id", THREAD).put("turns", new JSONArray()).put("accessToken", "secret")));
        check(!AgentGatewayProtocol.sessionEvent(streamSnapshot, THREAD).getJSONObject("params").getJSONObject("thread").has("accessToken"));
        rejects(() -> AgentGatewayProtocol.sessionEvent(new JSONObject(streamSnapshot.toString()).put("params", new JSONObject().put("thread", new JSONObject().put("id", KEY))), THREAD));
        // Generated UI: bounded Jev questions, a larger fence only for the catalog prompt and saved layouts.
        JSONObject uiQuestion = new JSONObject().put("type", "choice").put("instructions", "Pick the root").put("criteria", new JSONObject().put("card", "A card"));
        JSONObject decide = new JSONObject().put("state", new JSONObject()).put("questions", new JSONObject().put("root", uiQuestion));
        check(AgentGatewayProtocol.request("decide", decide.toString()).has("questions"));
        rejects(() -> AgentGatewayProtocol.request("decide", new JSONObject(decide.toString()).put("questions", new JSONObject()).toString()));
        JSONObject manyOptions = new JSONObject();
        for (int i = 0; i < 241; i++) manyOptions.put("option_" + i, "Option " + i);
        check(AgentGatewayProtocol.request("decide", new JSONObject(decide.toString()).put("questions", new JSONObject()
                .put("reaction", new JSONObject(uiQuestion.toString()).put("criteria", manyOptions))).toString()).has("questions"));
        rejects(() -> AgentGatewayProtocol.request("decide", new JSONObject(decide.toString()).put("questions", new JSONObject()
                .put("root", new JSONObject(uiQuestion.toString()).put("criteria", new JSONObject()))).toString()));
        rejects(() -> AgentGatewayProtocol.request("decide", new JSONObject(decide.toString()).put("questions", new JSONObject().put("root", new JSONObject(uiQuestion.toString()).put("type", "noul"))).toString()));
        rejects(() -> AgentGatewayProtocol.request("decide", new JSONObject(decide.toString()).put("model", "other").toString()));
        JSONObject generate = new JSONObject().put("requestId", KEY).put("instructions", "x".repeat(70_000)).put("prompt", "Water tracker").put("catalogVersion", "taku-ui-1");
        rejects(() -> AgentGatewayProtocol.request("generateUi", generate.toString()));
        check(AgentGatewayProtocol.request("generateUi", new JSONObject(generate.toString()).put("instructions", "x".repeat(59_000)).toString()).has("prompt"));
        rejects(() -> AgentGatewayProtocol.request("generateUi", new JSONObject(generate.toString()).put("instructions", "x").put("catalogVersion", "../v").toString()));
        JSONObject pin = new JSONObject().put("source",new JSONObject().put("kind","matter").put("matterId",THREAD+"/coffee-weekday")).put("requestId",KEY);
        check(PanelProtocol.parse("panels/pin",pin).getJSONObject("source").getString("matterId").equals(THREAD+"/coffee-weekday"));
        rejects(() -> PanelProtocol.parse("panels/pin",new JSONObject(pin.toString()).put("request",new JSONObject())));
        rejects(() -> PanelProtocol.parse("panels/pin",new JSONObject(pin.toString()).put("source",new JSONObject().put("kind","matter").put("matterId",THREAD+"/../escape"))));
        rejects(() -> AgentGatewayProtocol.request("sendMessage", new JSONObject(send.toString()).put("text", "x".repeat(60_000)).toString()));
        check(PanelProtocol.parse("panels/list",new JSONObject().put("cursor",JSONObject.NULL)).length()==1);
        check(PanelProtocol.INSTANCE.describe().getString("version").equals("taku.panels/1"));
        for(Object limit:new Object[]{0,101,1.5,"2"})rejects(()->PanelProtocol.parse("panels/list",new JSONObject().put("limit",limit)));
        rejects(()->PanelProtocol.parse("panels/rename",new JSONObject().put("panelId",THREAD).put("requestId",KEY).put("expectedMetadataRevision",-1).put("title","name")));
        check(PanelProtocol.parse("panels/rename",new JSONObject().put("panelId",THREAD).put("requestId",KEY).put("expectedMetadataRevision",0).put("title","  "+"𐐀".repeat(120)+"  ")).getString("title").codePointCount(0,240)==120);
        primaryReplies();
        // Taku Connect: the Agent UI gateway admits every connect/* action and hands appearance
        // (including shortName and its null clear) to TakuConnectContract unchanged.
        check(AgentGatewayProtocol.ACTIONS.containsAll(TakuConnectContract.ACTIONS));
        JSONObject connectAppearance = new JSONObject().put("id", "taku_mini").put("shortName", JSONObject.NULL).put("displayName", "Mac mini");
        JSONObject connectRequest = TakuConnectContract.request("connect/appearance", AgentGatewayProtocol.request("connect/appearance", connectAppearance.toString()), null);
        check(connectRequest.isNull("shortName") && connectRequest.getString("displayName").equals("Mac mini") && connectRequest.getString("action").equals("appearance"));
        System.out.println("AgentGatewayProtocolTest: " + assertions + " assertions passed");
    }
}
