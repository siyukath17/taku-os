package org.agentos.lab;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

final class CodexAppServer {
    interface ReplyHook { void beforeReply(Object id,JSONObject reply) throws Exception; }
    static boolean booting;
    static CodexAppServer shared;
    static JSONObject currentRequest;
    final Map<Object,JSONObject> replies=new ConcurrentHashMap<>();
    volatile ReplyHook replyHook;
    volatile Object failBeforeReply,failAfterReply;
    static CodexAppServer shared() { return shared; }
    static CodexAppServer shared(String cause) { return shared; }
    static JSONObject liveTask(JSONObject input) { throw new AssertionError("Live task control requires the production runtime"); }
    void nativeReply(Object id,JSONObject reply) throws Exception {
        ReplyHook hook=replyHook;if(hook!=null)hook.beforeReply(id,reply);
        if(id.equals(failBeforeReply))throw new IOException("Synthetic transport failure before delivery");
        replies.put(id,new JSONObject(reply.toString()));
        if(id.equals(failAfterReply))throw new IOException("Synthetic transport failure after delivery");
    }
    void nativeReject(Object id) throws Exception { replies.put(id,new JSONObject().put("rejected",true)); }
    interface RequestHook { void request(int id, NativeBridge.Call call) throws Exception; }
    volatile RequestHook requestHook;
    int nextRequest;
    void nativeRequest(NativeBridge.Call call)throws Exception {
        if(call.result.isDone())return;
        int id=++nextRequest;
        if(!call.cleanup)NativeBridge.admissions.beforeSend(id,call.admission);
        call.nativeSent=true;NativeBridge.calls.put(id,call);
        if(requestHook!=null)requestHook.request(id,call);
    }
    void rememberModel(String method,JSONObject params,JSONObject result) {}
    void nativeTurnStarted(String thread) {}
    void rememberNativeThread(JSONObject result) {}
}
