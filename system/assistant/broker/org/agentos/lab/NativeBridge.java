package org.agentos.lab;

import android.net.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/** Root-owned, UID-scoped IPC for native RPC and ephemeral interactive data. No credential logs. */
final class NativeBridge {
    private static final java.util.concurrent.locks.ReentrantLock stateLock = new java.util.concurrent.locks.ReentrantLock();
    static CleanupWork.Held lock() { return CleanupWork.lock(stateLock); }
    static boolean lockHeld() { return stateLock.isHeldByCurrentThread(); }

 static final class SteerNotSubmitted extends IOException { SteerNotSubmitted(){super("TURN_CHANGED");} }
 static final MagpieDecisions decisions=new MagpieDecisions();
 static final class Call {
  interface Guard { void check()throws Exception; }
  final String method; JSONObject params; final CompletableFuture<JSONObject> result=new CompletableFuture<>();
  WorkspaceAdmissions.Ticket admission;
  String workspaceRequestId="";
  volatile boolean nativeSent,responseReceived;
  Guard workspaceGuard;
  boolean cleanup;
  Call(String m,JSONObject p){method=m;params=p;}
 }
 static final ScheduledExecutorService streamExpiry=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"session-stream-expiry");t.setDaemon(true);return t;});
 static final ByokProviders byok=new ByokProviders();
 static final BlockingQueue<Call> inbox=new LinkedBlockingQueue<>(64);
 static final WorkspaceAdmissions admissions=new WorkspaceAdmissions(() -> DedicatedScreensOnDevice.shared());
 static boolean enqueue(Call call)throws Exception {
  if(!call.cleanup && (call.method.equals("thread/archive")||call.method.equals("thread/delete"))){
   call.result.complete(SessionRemovalRuntime.submit(call.params.getString("threadId"),call.method.equals("thread/delete")?"delete":"archive"));
   return true;
  }
  if(call.params!=null && WorkspaceAdmissions.execution(call.method) && SessionRemovalRuntime.pending(call.params.optString("threadId")))
   throw new IOException("SCREEN_SESSION_REMOVING");
  return enqueuePrepared(call);
 }
 private static boolean enqueuePrepared(Call call)throws Exception {
  call.admission=admissions.queue(call.method,call.params==null?"":call.params.optString("threadId"),call.workspaceRequestId);
  if(inbox.offer(call))return true;admissions.failedBeforeSend(call.admission);return false;
 }
 /** Cancellation is per member; joining happens outside the shared state lock. */
 static void cancelRemovedSession(String thread)throws Exception {
  Set<Thread> stopped=new LinkedHashSet<>();Exception first=null;
  try(CleanupWork.Held held=lock()){
   Set<String> turns=new LinkedHashSet<>();JSONObject current=state.get(thread);
   if(current!=null)turns.add(current.optString("activeTurnId"));
   for(JSONObject request:requests.values()){
    JSONObject params=request.optJSONObject("params");
    if(params!=null&&thread.equals(params.optString("threadId")))turns.add(params.optString("turnId"));
   }
   for(Map.Entry<String,Set<Thread>> entry:phoneWorkers.entrySet())if(entry.getKey().startsWith(thread+"\n"))stopped.addAll(entry.getValue());
   phonePolicyChanges.keySet().removeIf(owner->owner.startsWith(thread+"\n"));
   if(turns.isEmpty())turns.add("");
   for(String turn:turns)try{stopPhoneTurn(thread,turn);}catch(Exception error){if(first==null)first=error;else first.addSuppressed(error);}
   for(Thread worker:stopped)worker.interrupt();
  }
  for(Thread worker:stopped)try{
   worker.join(CleanupWork.timeout());
   if(worker.isAlive())throw new TimeoutException("SESSION_LOCAL_WORKER_STILL_RUNNING");
  }catch(Exception error){if(first==null)first=error;else first.addSuppressed(error);}
  try{PhoneGestures.cancelThreadChecked(thread);}catch(Exception error){if(first==null)first=error;else first.addSuppressed(error);}
  if(first!=null)throw first;
 }
 static JSONObject askCleanupCodex(String method,JSONObject params)throws Exception {
  Call call=new Call(method,params);call.cleanup=true;
  if(!inbox.offer(call))throw new IOException("WORKSPACE_RUNTIME_BUSY");
  try{return call.result.get(CleanupWork.timeout(),TimeUnit.MILLISECONDS);}
  finally{
   call.result.cancel(false);inbox.remove(call);
   calls.entrySet().removeIf(entry->entry.getValue()==call);
  }
 }
 /** A request to Codex from a broker thread, never the app-server pump (it would wait for itself). */
 static JSONObject askCodex(String method, JSONObject params) throws Exception {
  if (CleanupWork.active()) return askCleanupCodex(method, params);
  Call call = new Call(method, params);
  if (!enqueue(call)) throw new IOException("WORKSPACE_RUNTIME_BUSY");
  return call.result.get(25, TimeUnit.SECONDS);
 }
 static final Map<Integer,Call> calls=new ConcurrentHashMap<>();
 static final Map<String,JSONObject> requests=new LinkedHashMap<>();
 static final Map<String,JSONObject> state=new LinkedHashMap<>();
 static final Map<String,String> phonePermissions=new HashMap<>();
 static final Map<String,Set<Thread>> phoneWorkers=new HashMap<>();
 static final Map<String,List<CompletableFuture<String>>> phonePolicyChanges=new HashMap<>();
 /** A policy change belongs to this running turn, never a later turn of the same thread. */
 static CompletableFuture<String> phonePolicyChanged(String thread){ try (CleanupWork.Held held2 = lock()) {
  JSONObject current=state.get(thread);String turn=current==null?"":current.optString("activeTurnId");
  if(turn.isEmpty()||turn.equals(current.optString("phoneStoppedTurnId")))return null;
  CompletableFuture<String> notice=new CompletableFuture<>();
  phonePolicyChanges.computeIfAbsent(thread+"\n"+turn,k->new ArrayList<>()).add(notice);
  return notice;
 } }
 static void setPhonePermission(String thread,String mode){ try (CleanupWork.Held held3 = lock()) {phonePermissions.put(thread,mode);} }
 static final Map<String,List<JSONObject>> audio=new HashMap<>();
 static final JSONArray globalEvents=new JSONArray();static JSONObject protocol;static long audioSequence;static boolean started, initializing;
 static JSONObject protocol() throws Exception { try (CleanupWork.Held held4 = lock()) {
  if(protocol==null)try(ZipFile apk=new ZipFile(HarnessCatalog.apk());InputStream in=apk.getInputStream(apk.getEntry("assets/native/protocol.json"))){protocol=new JSONObject(read(in,1000000));}
  return protocol;
 } }
 static JSONArray methods() throws Exception{return protocol().getJSONArray("methods");}
 static boolean allowed(String method)throws Exception {JSONArray list=methods();for(int i=0;i<list.length();i++)if(list.getJSONObject(i).getString("method").equals(method))return true;return false;}
 static void start()throws Exception {
  SessionRemovalRuntime.initialize();
  try (CleanupWork.Held held5 = lock()) {
  if(started)return;WorkspaceExecutionRuntime.initialize();LocalServerSocket server=new LocalServerSocket("taku-agent-native");started=true;
  try{AudioConfigBridge.start();}catch(Exception unavailable){System.err.println("audio config bridge unavailable");}
  Thread listener=new Thread(()->{while(true)try{LocalSocket socket=server.accept();Thread worker=new Thread(()->serve(socket),"native-ui-rpc");worker.setDaemon(true);worker.start();}catch(Exception ignored){break;}},"native-ui-listener");listener.setDaemon(true);listener.start();
 } }
 static String read(InputStream in,int limit)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>limit)throw new IOException("Message exceeds the limit");}return out.toString("UTF-8");}
 static boolean pluginClient(JSONObject input) {
  if("pluginCatalogAdd".equals(input.optString("op")))return true;
  if(!"rpc".equals(input.optString("op")))return false;
  String method=input.optString("method");
  if(Arrays.asList("plugin/list","plugin/install","plugin/uninstall","mcpServerStatus/list","config/mcpServer/reload").contains(method))return true;
  if(!method.equals("config/value/write"))return false;
  JSONObject params=input.optJSONObject("params");
  return params!=null && params.optString("keyPath").matches("plugins\\.\"[A-Za-z0-9_.@-]+\"\\.enabled")
    && params.opt("value") instanceof Boolean && "upsert".equals(params.optString("mergeStrategy"));
 }
 static void serve(LocalSocket socket){try(LocalSocket s=socket){int uid=s.getPeerCredentials().getUid();s.setSoTimeout(45000);JSONObject input=new JSONObject(read(s.getInputStream(),2000000));if(uid!=1000&&uid!=0&&!(uid==2000&&pluginClient(input))&&!(pluginClient(input)&&AppPermissionPolicy.isRootSocketAllowed(uid)))return;if("subscribeSession".equals(input.optString("op"))){serveSession(s,input);return;}JSONObject answer;
  try { switch(input.getString("op")){
   case "state":answer=state(input.optString("threadId"));break;
   case "pendingPhoneQuestions":answer=pendingPhoneQuestions();break;
   case "workspaces":answer=WorkspaceControl.control(input);break;
   case "appPermissions":answer=AppPermissionPolicy.control(input,uid);break;
   case "sessionRemovals":answer=SessionRemovalRuntime.control(input);break;
   case "dedicatedScreensDebug":answer=dedicatedScreensDebug(uid,input);break;
   case "protocol":answer=protocol();break;
   case "pluginCatalogAdd":answer=PluginCreator.catalogAdd(java.nio.file.Paths.get(input.getString("path")));break;
   case "audio":answer=audio(input.optString("threadId"),input.optLong("after"));break;
   case "respond":answer=respond(input.getString("key"),input.getJSONObject("response"));break;
   case "reject":reject(input.getString("key"));answer=new JSONObject();break;
   case "modelConnection":Call local=new Call("taku/modelConnection",input);if(!enqueue(local))throw new IOException("Runtime is busy. Please retry later");answer=local.result.get(40,TimeUnit.SECONDS);break;
   case "decision":if(input.optString("action").equals("decide")){long revision=SystemAi.config().getLong("revision");SystemAi.requireEnabled();answer=decisions.control(input);SystemAi.requireEnabled();if(revision!=SystemAi.config().getLong("revision"))throw new IOException("System AI settings changed; this result was cancelled");}else answer=decisions.control(input);break;
   case "skillCredential":answer=SkillCredentials.control(input);break;
   case "workspaceTextFiles":answer=WorkspaceTextFiles.control(input);break;
   case "memory":answer=MemoryBridge.control(input);break;
   case "imeReplies":answer=ImeReplies.control(input,uid);break;
   case "systemAi":answer=SystemAi.control(input);break;
   case "acpRuntime":answer=AcpConversations.runtimeControl(input);break;
   case "connect":answer=TaskBroker.CONNECT.control(input);break;
   case "acpPrewarm":answer=AcpConversations.prewarm(input);break;
   case "byok":answer=byok.control(input);break;
   case "ui":answer=SystemAi.ui(input);break;
   case "matter":answer=MatterBridge.control(input);break;
   case "matterAudio":answer=AudioBridge.control(input);break;
   case "mainReply":answer=AgentViews.mainReplyRead(input);break;
   case "acpInterrupt":answer=AcpConversations.interrupt(input.getString("threadId"),input.getString("turnId"));break;
   case "acpSteer":answer=AcpConversations.steer(input);break;
   case "liveTask":Call live=new Call("taku/liveTask",input);if(!enqueue(live))throw new IOException("BUSY");answer=live.result.get(40,TimeUnit.SECONDS);break;
   case "magpieSettingsChanged":MagpieSettings.schedule();answer=new JSONObject();break;
   case "rpc": {
    String method=input.getString("method");
    if(!allowed(method))throw new IOException("This interface is not part of the locked native protocol");
    JSONObject params=input.optJSONObject("params");
    if(method.equals("plugin/list")) {
     if(params==null)params=new JSONObject();
     if(params.isNull("marketplaceKinds"))params.put("marketplaceKinds",new JSONArray().put("local"));
    }
    Call call=new Call(method,params);
    if(!enqueue(call))throw new IOException("Runtime is busy. Please retry later");
    answer=call.result.get(40,TimeUnit.SECONDS);
    break;
   }
   default:throw new IOException("Unsupported native operation");
  } } catch(Exception failure) { JSONObject error=new JSONObject().put("ok",false).put("message",publicError(failure));Throwable cause=failure;while(cause.getCause()!=null)cause=cause.getCause();String steerCode=cause instanceof SteerNotSubmitted?"STEER_NOT_SUBMITTED":AcpConversations.steerErrorCode(cause);if(steerCode==null&&"THREAD_UNAVAILABLE".equals(cause.getMessage()))steerCode="THREAD_UNAVAILABLE";if(steerCode!=null)error.put("code",steerCode);s.getOutputStream().write(error.toString().getBytes(StandardCharsets.UTF_8)); return; }
  s.getOutputStream().write(new JSONObject().put("ok",true).put("result",answer).toString().getBytes(StandardCharsets.UTF_8));
 }catch(Exception failure){try{socket.getOutputStream().write(new JSONObject().put("ok",false).put("message",publicError(failure)).toString().getBytes(StandardCharsets.UTF_8));socket.close();}catch(Exception ignored){}}}
 /**
  * Root-only entry for scripted e2e (no model): run a phone tool as a session, or end its turn;
  * idleTimeout shortens how long screens that go idle afterwards stay open.
  */
 static JSONObject dedicatedScreensDebug(int uid, JSONObject input) throws Exception {
  if (uid != 0) throw new IOException("DEBUG_ROOT_ONLY");
  String thread = input.optString("threadId");
  switch (input.getString("action")) {
   case "tool": {
    String turn = UUID.randomUUID().toString();
    PhoneGestures.startTurn(thread, turn);
    JSONObject arguments = input.optJSONObject("arguments");
    try {
     return PhoneNativeTools.execute(new JSONObject().put("threadId", thread).put("turnId", turn)
       .put("tool", input.getString("tool")).put("arguments", arguments == null ? new JSONObject() : arguments));
    } finally {
     PhoneGestures.endTurn(thread, turn);
    }
   }
   case "turnEnded": {
    Thread release = DedicatedScreensOnDevice.shared().turnEnded(thread);
    if (release != null) release.join(30000);
    return new JSONObject().put("released", release != null);
   }
   case "idleTimeout": {
    long ms = input.getLong("ms");
    DedicatedScreensOnDevice.shared().idleTimeout(ms);
    return new JSONObject().put("idleMs", ms);
   }
   default:
    throw new IOException("UNKNOWN_DEBUG_ACTION");
  }
 }
 static void serveSession(LocalSocket socket,JSONObject input)throws Exception {
  try(SessionEvents.Subscription subscription=AgentViews.subscribe(input)){
   // Neither this writer nor its bounded queue executes on the app-server pump.
   ScheduledFuture<?> expiry=streamExpiry.schedule(()->{try{socket.close();}catch(IOException ignored){}},5,TimeUnit.MINUTES);
   try{
    socket.setSoTimeout(15000);
    OutputStream out=socket.getOutputStream();
    while(!subscription.ended()){
     String line=subscription.next(10000);
     if(line==null){if(subscription.ended())break;line=AgentViews.events.heartbeat(subscription.thread);}
     out.write((line+"\n").getBytes(StandardCharsets.UTF_8));out.flush();
    }
   }finally{expiry.cancel(false);}
  }
 }
 static JSONObject workspaceRuntime(String thread)throws Exception { try (CleanupWork.Held held6 = lock()) {
  JSONObject current=state.get(thread);JSONObject result=new JSONObject().put("activeTurnId",current==null?"":current.optString("activeTurnId"))
    .put("waitingForUser",hasPhoneQuestion(thread)).put("runtimeReady",CodexAppServer.shared!=null);
  int workers=0;for(Map.Entry<String,Set<Thread>> entry:phoneWorkers.entrySet())if(entry.getKey().startsWith(thread+"\n"))workers+=entry.getValue().size();
  result.put("workers",workers);
  result.put("pendingExecutions",admissions.pending(thread)).put("realtimeActive",admissions.realtimeActive(thread));
  if(current!=null)for(String key:new String[]{"completedTurn","cancellationCleanup"})if(current.has(key))result.put(key,new JSONObject(current.getJSONObject(key).toString()));
  return result;
 } }
 static String publicError(Throwable e){Throwable value=e;while(value.getCause()!=null)value=value.getCause();String text=value.getMessage();if(text==null||text.isEmpty())text="The native interface cannot complete this operation right now";return redact(text).substring(0,Math.min(500,redact(text).length()));}
 static String redact(String s){return s.replaceAll("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+","[Authorization information hidden]").replaceAll("(?i)(Bearer\\s+|(?:token|secret|password|api_key)=)[^\\s&\"']+","$1[hidden]").replaceAll("sk-[A-Za-z0-9_-]{20,}","[Key hidden]").replaceAll("apikey_[A-Za-z0-9_-]{8,}","[Key hidden]");}
 static JSONArray toolDefinitions()throws Exception {return PhoneNativeTools.definitions();}
 /** Inbox calls run one at a time. A blocking call made while handling one (liveTask's goal read,
  * workspace preflight) still pumps provider events but never starts another inbox call. */
 static void tick()throws Exception {if(initializing||CodexAppServer.booting||draining)return;draining=true;try{drainInbox();}finally{draining=false;}}
 private static boolean draining;
 private static void drainInbox()throws Exception {for(int n=0;n<4;n++){Call call=inbox.poll();if(call==null)break;if(call.result.isDone())continue;try{if(call.method.equals("taku/modelConnection")){call.result.complete(ModelConnection.control(call.params));continue;}if(call.method.equals("taku/liveTask")){call.result.complete(CodexAppServer.liveTask(call.params));continue;}if(AcpConversations.servesNativeRead(call.method,call.params)){call.result.complete(AcpConversations.nativeRead(call.params));continue;}if(call.method.equals("model/list")){JSONObject acpModels=AcpConversations.nativeModels(call.method);if(acpModels!=null){call.result.complete(acpModels);continue;}}if(Set.of("turn/start","turn/steer","thread/realtime/start").contains(call.method)&&call.params!=null&&!DedicatedScreensOnDevice.shared().agentAdmitted(call.params.optString("threadId")))throw new IOException("SCREEN_AGENT_PAUSED");AcpConversations.requireThreadAvailable(call.params,call.cleanup||CodexAppServer.shared!=null);initializing=true;CodexAppServer server;try{server=CodexAppServer.shared("native:"+call.method);}finally{initializing=false;}if(call.method.equals("thread/start")){JSONObject p=call.params;if(p==null)p=new JSONObject();if(!p.has("cwd"))p.put("cwd","/data/local/agentos/work");if(!p.has("approvalPolicy"))p.put("approvalPolicy","never");if(!p.has("sandbox"))p.put("sandbox","danger-full-access");if(!p.has("dynamicTools"))p.put("dynamicTools",toolDefinitions());call.params=p;}server.nativeRequest(call);}catch(Exception e){admissions.failedBeforeSend(call.admission);call.result.completeExceptionally(e);}}}
 static boolean complete(JSONObject response)throws Exception {int id=response.optInt("id",-1);Call c=calls.remove(id);if(c==null)return false;c.responseReceived=true;if(response.has("error")){JSONObject error=response.getJSONObject("error");if(c.method.equals("turn/steer")&&error.optInt("code")==-32600&&error.optString("message").equals("no active turn to steer"))c.result.completeExceptionally(new SteerNotSubmitted());else c.result.completeExceptionally(new IOException(error.optString("message","The native interface returned an error")));return true;}JSONObject result=response.optJSONObject("result");if(result==null)result=new JSONObject().put("value",response.opt("result"));if(!c.cleanup){if(c.method.equals("thread/list"))SessionRemovalRuntime.filter(result);observe(c.method,c.params,result);}c.result.complete(result);return true;}
 static void observe(String method,JSONObject params,JSONObject result)throws Exception { try(CleanupWork.Held observeHeld=lock()){
  CodexAppServer.shared.rememberModel(method,params,result);
  if(method.equals("turn/start")&&params!=null)CodexAppServer.shared.nativeTurnStarted(params.optString("threadId"));
  if(method.equals("thread/realtime/stop")&&params!=null)admissions.realtimeStopped(params.getString("threadId"));
  if(method.equals("turn/interrupt")&&params!=null){PhoneGestures.endTurn(params.optString("threadId"),params.optString("turnId"));cancelPhoneWorkers(params.optString("threadId"),params.optString("turnId"));requests.values().removeIf(q->q.optJSONObject("params")!=null && q.optJSONObject("params").optString("threadId").equals(params.optString("threadId")));}
  if(method.equals("turn/interrupt")&&params!=null)AgentViews.interruptReply(params.optString("threadId"),params.optString("turnId"));
  if(result.has("thread")){
   JSONObject thread=result.getJSONObject("thread");String id=thread.getString("id");
   DedicatedScreensOnDevice.shared().observeRemovalThread(thread);
   if(method.equals("thread/start")||method.equals("thread/read")||method.equals("thread/resume"))threadAvailability(id,"current");
   if((method.equals("thread/read")||method.equals("thread/resume"))&&!MemoryGen.owns(id))AgentViews.prepareReply(id,HarnessCatalog.replyContract(),true);
   boolean metadata=method.equals("thread/resume")&&params!=null&&params.optBoolean("excludeTurns")||method.equals("thread/read")&&(params==null||!params.optBoolean("includeTurns"));
   if(metadata)AgentViews.saveMetadata(thread);else AgentViews.save(thread);
   if(method.equals("thread/start"))CodexAppServer.shared.rememberNativeThread(result);
   JSONObject view=AgentViews.snapshot(id);if(view!=null)result.put("thread",view);
  }
  if(method.equals("thread/turns/list")&&params!=null){String id=params.getString("threadId");if(!MemoryGen.owns(id))AgentViews.prepareReply(id,HarnessCatalog.replyContract(),true);boolean descending=!params.optString("sortDirection","desc").equals("asc"),hasCursor=!params.optString("cursor").isEmpty();AgentViews.mergeTurnPage(id,result.getJSONArray("data"),descending,descending?hasCursor:!hasCursor,params.optString("itemsView").equals("full"));JSONObject view=AgentViews.snapshot(id);if(view!=null)result.put("data",view.getJSONArray("turns"));}
 } }

 static JSONObject state(String thread)throws Exception { try (CleanupWork.Held held9 = lock()) {
  JSONObject r=state.containsKey(thread)?new JSONObject(state.get(thread).toString()):new JSONObject();JSONArray pending=new JSONArray();for(JSONObject q:requests.values()){String t=q.optJSONObject("params").optString("threadId");if(t.isEmpty()||t.equals(thread))pending.put(q);}r.put("executionScreen",DedicatedScreensOnDevice.shared().describe(thread));r.put("requests",pending);r.put("runtimeEvents",new JSONArray(globalEvents.toString()));r.put("ready",CodexAppServer.shared!=null||AcpConversations.standalone(thread));return r;
 } }
 /** Ordered, read-only snapshot for the system question cards. */
 static JSONObject pendingPhoneQuestions() throws Exception {
  try (CleanupWork.Held held = lock()) {
   JSONArray pending = new JSONArray();
   for (JSONObject request : requests.values()) {
    String method = request.getString("method");
    if (!method.equals("phone/askUser") && !method.equals("phone/confirmAction")) continue;
    JSONObject params = request.getJSONObject("params");
    if (!phoneTurnActive(params)) continue;
    JSONObject question = new JSONObject()
      .put("threadId", params.getString("threadId"))
      .put("turnId", params.getString("turnId"))
      .put("arguments", new JSONObject(params.getJSONObject("arguments").toString()));
    pending.put(new JSONObject().put("key", request.getString("key")).put("method", method).put("params", question));
   }
   return new JSONObject().put("requests", pending);
  }
 }
 static void threadAvailability(String thread,String state){AgentViews.threadAvailability(thread,state);}
 static void cancellation(String thread,JSONObject result)throws Exception { try (CleanupWork.Held held10 = lock()) {
  JSONObject current=state.computeIfAbsent(thread,k->new JSONObject());current.put("cancellationCleanup",result);
  if(!result.optBoolean("complete"))current.put("error","The conversation was interrupted, but the underlying process has not confirmed stopping. Check the results of any actions.");
 } }
 static JSONObject audio(String thread,long after)throws Exception { try (CleanupWork.Held held11 = lock()) {JSONArray data=new JSONArray();for(JSONObject a:audio.getOrDefault(thread,Collections.emptyList()))if(a.getLong("sequence")>after)data.put(a);return new JSONObject().put("frames",data);} }
 static void event(JSONObject event)throws Exception { try (CleanupWork.Held held12 = lock()) {
  MemoryGen.event(event);
  String method=event.optString("method");JSONObject p=event.optJSONObject("params");if(p==null)return;
  if(p.optJSONObject("thread")!=null)DedicatedScreensOnDevice.shared().observeRemovalThread(p.getJSONObject("thread"));
  if(method.equals("serverRequest/resolved")){
   Object id=p.opt("requestId");
   for(Iterator<JSONObject> it=requests.values().iterator();it.hasNext();){
    JSONObject q=it.next();
    if(Objects.equals(String.valueOf(q.opt("id")),String.valueOf(id))){it.remove();questionGone(q);}
   }
   return;
  }
  String thread=p.optString("threadId");if(thread.isEmpty()){if(method.startsWith("fs/")||method.startsWith("hook/")||method.startsWith("remoteControl/")||method.equals("warning")||method.equals("configWarning")||method.equals("model/rerouted")||method.startsWith("project/")){globalEvents.put(new JSONObject().put("method",method).put("detail",redact(p.toString())));while(globalEvents.length()>16)globalEvents.remove(0);}return;}JSONObject s=state.computeIfAbsent(thread,k->new JSONObject());
  if(method.equals("turn/started")){PhoneGestures.startTurn(thread,p.getJSONObject("turn").getString("id"));s.put("activeTurnId",p.getJSONObject("turn").getString("id"));phonePolicyChanges.keySet().removeIf(owner->owner.startsWith(thread+"\n")&&!owner.equals(thread+"\n"+s.optString("activeTurnId")));s.remove("error");s.remove("plan");s.remove("cancellationCleanup");if(!admissions.turnStarted(thread)){s.put("phoneStoppedTurnId",p.getJSONObject("turn").getString("id"));enqueue(new Call("turn/interrupt",new JSONObject().put("threadId",thread).put("turnId",p.getJSONObject("turn").getString("id"))));tellParentIfClosed(thread);}}
  if(method.equals("turn/completed")){JSONObject completed=p.getJSONObject("turn");PhoneGestures.endTurn(thread,completed.getString("id"));cancelPhoneWorkers(thread,completed.getString("id"));
   phonePolicyChanges.remove(thread+"\n"+completed.getString("id"));
   DedicatedScreensOnDevice.shared().turnEnded(thread);
   s.put("completedTurn",new JSONObject().put("id",completed.getString("id")).put("status",completed.getString("status")));s.remove("activeTurnId");JSONObject error=completed.optJSONObject("error");if(error!=null)s.put("error",redact(error.optString("message")));requests.values().removeIf(q->q.optJSONObject("params").optString("threadId").equals(thread));}
  if(method.equals("turn/plan/updated"))s.put("plan",p);
  if(method.equals("turn/diff/updated"))s.put("diff",redact(p.optString("diff")));
  if(method.equals("thread/tokenUsage/updated"))s.put("usage",p.optJSONObject("tokenUsage"));
  if(method.equals("thread/goal/updated"))s.put("goal",p.optJSONObject("goal"));
  if(method.equals("thread/goal/cleared"))s.remove("goal");
  if(method.equals("thread/queue/changed"))s.put("queue",p);
  if(method.equals("item/commandExecution/outputDelta")||method.equals("item/mcpToolCall/progress")){
   JSONObject outputs=s.optJSONObject("outputs");if(outputs==null){outputs=new JSONObject();s.put("outputs",outputs);}String id=p.optString("itemId");String previous=outputs.optString(id);String next=previous+redact(p.optString("delta",p.optString("message")));outputs.put(id,next.substring(Math.max(0,next.length()-16000)));
  }
  if(method.startsWith("thread/realtime/")){
   if(method.equals("thread/realtime/outputAudio/delta")){JSONObject a=new JSONObject(p.getJSONObject("audio").toString()).put("sequence",++audioSequence);List<JSONObject> frames=audio.computeIfAbsent(thread,k->new ArrayList<>());frames.add(a);while(frames.size()>24)frames.remove(0);}
   else if(method.contains("transcript/")){String transcript=s.optString("transcript")+p.optString("delta",p.optString("text"));s.put("transcript",transcript.substring(Math.max(0,transcript.length()-16000)));}
   else {s.put("realtime",new JSONObject().put("event",method).put("detail",redact(p.toString())));if(method.endsWith("closed")){audio.remove(thread);admissions.realtimeStopped(thread);}}
  }
 } }
 /** A sub-agent whose screen the user closed was woken again: its parent is told why it never answers. */
 static void tellParentIfClosed(String thread) {
  DedicatedScreens.ParentNote note = DedicatedScreensOnDevice.shared().closedNote(thread);
  if (note != null) tellRunningTurn(note.parent(), note.text());
 }
 /**
  * Into the session's running turn only: a turn started for it would wake a session the user may
  * have stopped. Nobody waits for Codex's answer: a session left untold only waits longer.
  */
 static void tellRunningTurn(String thread, String text) {
  String why;
  try {
   String turn = workspaceRuntime(thread).optString("activeTurnId");
   if (turn.isEmpty()) return;
   JSONObject words = new JSONObject().put("type", "text").put("text", text);
   JSONObject params = new JSONObject().put("threadId", thread).put("expectedTurnId", turn);
   params.put("input", new JSONArray().put(words));
   if (enqueue(new Call("turn/steer", params))) return;
   why = "Codex queue full";
  } catch (Exception error) {
   why = error.getMessage();
  }
  android.util.Log.w("TakuDedicatedScreens", "closed screen not told to " + thread + ": " + why);
 }
 static void request(CodexAppServer server,JSONObject event)throws Exception { try (CleanupWork.Held held13 = lock()) {
  String method=event.getString("method");JSONObject params=event.getJSONObject("params");
  if(method.equals("currentTime/read")){server.nativeReply(event.get("id"),new JSONObject().put("currentTimeAt",System.currentTimeMillis()/1000));return;}
  if(method.equals("item/tool/call")&&retiredPanelTool(params)){server.nativeReply(event.get("id"),PhoneNativeTools.failure("Scenario panel tools have been retired. Answer directly or use existing native capabilities."));return;}
  if(method.equals("item/tool/call")&&(params.optString("tool").startsWith("taku_phone")||params.optString("namespace").equals("taku_phone"))){
   if(!phoneTurnActive(params)){server.nativeReply(event.get("id"),PhoneNativeTools.failure("This execution turn has stopped; the late phone operation was not performed"));return;}
   if(phonePolicyChanges.containsKey(params.optString("threadId")+"\n"+params.optString("turnId"))){executePhone(server,event.get("id"),params);return;}
   String tool=params.optString("tool"), mode=phonePermissions.getOrDefault(params.optString("threadId"),"full");
   boolean confirmation=matchesTool(tool,"confirm_action");
   if(matchesTool(tool,"ask_user") || confirmation) {
    PhoneGestures.releaseTurn(params.optString("threadId"),params.optString("turnId"));
    if(hasPhoneQuestion(params.optString("threadId"))) {
     server.nativeReply(event.get("id"),PhoneNativeTools.failure("Wait for the user to answer the current question before asking for another choice"));return;
    }
    JSONObject snapshot=new JSONObject(params.toString());
    try { if(confirmation)snapshot.put("arguments",PhoneConfirmation.validate(params.getJSONObject("arguments")));else PhoneAskUser.validate(params.getJSONObject("arguments")); }
    catch(Exception invalid) { server.nativeReply(event.get("id"),PhoneNativeTools.failure(confirmation?"A confirmation card requires a clear title, summary, 1 to 8 facts, and a confirmation button":"A question must be within 160 characters and contain 1 to 3 clear options"));return; }
    JSONObject shown=params.getJSONObject("arguments");
    DedicatedScreensOnDevice.shared().asked(params.optString("threadId"),shown.optString(confirmation?"title":"question"),shown.optString("returnTo"));
    String key=UUID.randomUUID().toString();requests.put(key,new JSONObject().put("key",key).put("id",event.get("id")).put("method",confirmation?"phone/confirmAction":"phone/askUser").put("params",snapshot));return;
   }
   boolean observation=tool.endsWith("device_info")||matchesTool(tool,"screen_status")||tool.endsWith("observe_background")||tool.endsWith("memory_search")||tool.endsWith("memory_get")||matchesTool(tool,"apps")||(matchesTool(tool,"matter")&&params.optJSONObject("arguments")!=null&&params.getJSONObject("arguments").optString("action").equals("read"))||(matchesTool(tool,"audio")&&params.optJSONObject("arguments")!=null&&(params.getJSONObject("arguments").optString("action").equals("capabilities")||params.getJSONObject("arguments").optString("operation").equals("status")));
   if(!observation&&mode.equals("readOnly")){server.nativeReply(event.get("id"),PhoneNativeTools.failure("This conversation is read-only; the phone operation was blocked"));return;}
   if(!observation&&hasPhoneQuestion(params.optString("threadId"))){server.nativeReply(event.get("id"),PhoneNativeTools.failure("Wait for the user to handle the current confirmation before continuing phone operations"));return;}
   if(!observation&&mode.equals("ask")){
    String key=UUID.randomUUID().toString();JSONObject visible=new JSONObject(params.toString()).put("reason",matchesTool(tool,"prepare_foreground_draft")?"Allow preparing a draft in the current conversation input field without sending it?":"Allow this phone operation on the independent background screen: "+tool+"？");
    requests.put(key,new JSONObject().put("key",key).put("id",event.get("id")).put("method","phone/action/requestApproval").put("params",visible));return;
   }
   executePhone(server,event.get("id"),params);return;
  }
  String key=UUID.randomUUID().toString();requests.put(key,new JSONObject().put("key",key).put("id",event.get("id")).put("method",method).put("params",params));
 } }
 static boolean matchesTool(String tool,String name){return tool.equals(name)||tool.endsWith("."+name)||tool.endsWith("/"+name)||tool.endsWith("__"+name);}
 /** A frozen thread can still call the retired namespace; answer it with a real failure, never an approval card. */
 static boolean retiredPanelTool(JSONObject params){
  String tool=params.optString("tool");
  return params.optString("namespace").equals("taku_panel")||tool.startsWith("taku_panel.")||tool.startsWith("taku_panel/")||tool.startsWith("taku_panel__");
 }
 static boolean phoneTurnActive(JSONObject params){if(SessionRemovalRuntime.pending(params.optString("threadId")))return false;JSONObject s=state.get(params.optString("threadId"));String turn=params.optString("turnId");return s!=null&&!turn.isEmpty()&&turn.equals(s.optString("activeTurnId"))&&!turn.equals(s.optString("phoneStoppedTurnId"));}
 static boolean hasPhoneQuestion(String thread){return requests.values().stream().anyMatch(q->q.optJSONObject("params")!=null && thread.equals(q.optJSONObject("params").optString("threadId")) && (q.optString("method").equals("phone/askUser")||q.optString("method").equals("phone/confirmAction")||q.optString("method").equals("phone/action/requestApproval")));}
 static void executePhone(CodexAppServer server,Object id,JSONObject params){ try (CleanupWork.Held held14 = lock()) {
  if(!phoneTurnActive(params)){try{server.nativeReply(id,PhoneNativeTools.failure("This execution turn has stopped; the phone operation was not performed"));}catch(Exception ignored){}return;}
  String owner=params.optString("threadId")+"\n"+params.optString("turnId");
  List<CompletableFuture<String>> changes=phonePolicyChanges.containsKey(owner)?new ArrayList<>(phonePolicyChanges.get(owner)):List.of();
  Thread worker=new Thread(()->{
   try{
    if(Thread.currentThread().isInterrupted())return;
    JSONObject result;
    if(changes.isEmpty())result=PhoneNativeTools.execute(params);
    else {StringJoiner text=new StringJoiner("\n");for(CompletableFuture<String> change:changes)text.add(change.get());result=PhoneNativeTools.failure(text.toString());}
    if(!Thread.currentThread().isInterrupted()){
     server.nativeReply(id,result);
     // Keep the barrier until delivery, so concurrent calls cannot act before reevaluation.
     try (CleanupWork.Held held23 = lock()){List<CompletableFuture<String>> pending=phonePolicyChanges.get(owner);if(pending!=null){pending.removeAll(changes);if(pending.isEmpty())phonePolicyChanges.remove(owner);}}
    }
   }catch(Exception error){if(!Thread.currentThread().isInterrupted() && !(error instanceof InterruptedException))try{server.nativeReply(id,PhoneNativeTools.failure(publicError(error)));}catch(Exception ignored){}
   }finally{try (CleanupWork.Held held24 = lock()){Set<Thread> workers=phoneWorkers.get(owner);if(workers!=null){workers.remove(Thread.currentThread());if(workers.isEmpty())phoneWorkers.remove(owner);}}}
  },"phone-native-tool");
  phoneWorkers.computeIfAbsent(owner,k->new HashSet<>()).add(worker);worker.start();
 } }
 static void cancelPhoneWorkers(String thread,String turn){ try (CleanupWork.Held held16 = lock()) {
  for(Map.Entry<String,Set<Thread>> entry:phoneWorkers.entrySet())if(entry.getKey().equals(thread+"\n"+turn) || (turn.isEmpty()&&entry.getKey().startsWith(thread+"\n")))for(Thread worker:entry.getValue())worker.interrupt();
 } }
 static void stopPhoneTurn(String thread,String turn)throws Exception{ try (CleanupWork.Held held18 = lock()) {
  state.computeIfAbsent(thread,k->new JSONObject()).put("phoneStoppedTurnId",turn);
  phonePolicyChanges.remove(thread+"\n"+turn);
  cancelPhoneWorkers(thread,turn);
  requests.values().removeIf(q->{JSONObject p=q.optJSONObject("params");return p!=null&&thread.equals(p.optString("threadId"))&&(turn.equals(p.optString("turnId"))||p.optString("turnId").isEmpty());});
 } }
 static void reject(String key)throws Exception { try (CleanupWork.Held held19 = lock()) {JSONObject r=requests.remove(key);if(r!=null){questionGone(r);CodexAppServer.shared.nativeReject(r.get("id"));}} }
 /** A validated answer to a card ends it; adds the Apps handed over while it waited, if any. */
 static JSONObject handedOver(JSONObject params,JSONObject answer)throws Exception {
  JSONObject note=DedicatedScreensOnDevice.shared().questionEnded(params.optString("threadId"));
  return note==null?answer:answer.put("handedOver",note);
 }
 /** A card gone unanswered no longer holds the session's Apps for the user. */
 static void questionGone(JSONObject request)throws Exception {
  String method=request.optString("method");JSONObject params=request.optJSONObject("params");
  if(params!=null&&(method.equals("phone/askUser")||method.equals("phone/confirmAction")))DedicatedScreensOnDevice.shared().questionEnded(params.optString("threadId"));
 }
 static JSONObject respond(String key,JSONObject payload)throws Exception { try (CleanupWork.Held held20 = lock()) {
  JSONObject r=requests.get(key);if(r==null)throw new IOException("The request has ended. Check the latest task status");
  String method=r.optString("method");JSONObject params=r.getJSONObject("params");Object id=r.get("id");
  if(Set.of("phone/action/requestApproval","phone/confirmAction","phone/askUser").contains(method)&&!phoneTurnActive(params)){
   requests.remove(key);throw new IOException("The turn associated with this confirmation has stopped");
  }
  if(payload==null)throw new IOException("Invalid response format");
  CodexAppServer server=CodexAppServer.shared;if(server==null)throw new IOException("The runtime is temporarily unavailable. Check the latest task status");
  JSONObject reply;boolean executePhoneApproved=false;
  if(method.equals("phone/action/requestApproval")) {
   Object decision=payload.get("decision");
   if(!(decision instanceof String)||!Set.of("accept","decline","cancel").contains(decision))throw new IOException("Select an action that is actually displayed");
   executePhoneApproved=decision.equals("accept");reply=executePhoneApproved?null:PhoneNativeTools.failure("The user cancelled this phone operation");
  }else if(method.equals("phone/confirmAction")) {
   JSONObject answer=PhoneConfirmation.answer(params.getJSONObject("arguments"),payload)
     .put("confirmationId",key).put("threadId",params.optString("threadId")).put("turnId",params.optString("turnId"));
   reply=PhoneNativeTools.text(handedOver(params,answer).toString());
  }else if(method.equals("phone/askUser")) {
   reply=PhoneNativeTools.text(handedOver(params,PhoneAskUser.answer(params.getJSONObject("arguments"),payload)).toString());
  }else reply=new JSONObject(payload.toString());
  // Consume only a fully validated response, before publishing it or starting its action.
  // Delivery can be uncertain after a write failure: never restore a replayable approval.
  requests.remove(key);
  try { if(executePhoneApproved)executePhone(server,id,params);else server.nativeReply(id,reply); }
  catch(Exception error){IOException uncertain=new IOException("Response delivery has not been confirmed. Check the latest task status and do not confirm again.");uncertain.addSuppressed(error);throw uncertain;}
  return new JSONObject();
 } }
 static void closed(){ try (CleanupWork.Held held21 = lock()) {
  AgentViews.runtimeClosed();admissions.clear();PhoneGestures.cancelAll();
  // No turn running now ever ends, so nothing else would free the dedicated screens.
  DedicatedScreensOnDevice.shared().codexGone();
  for(Set<Thread> group:phoneWorkers.values())for(Thread worker:group)worker.interrupt();phoneWorkers.clear();phonePolicyChanges.clear();requests.clear();state.clear();audio.clear();phonePermissions.clear();while(globalEvents.length()>0)globalEvents.remove(0);for(Call c:calls.values())c.result.completeExceptionally(new IOException("The runtime restarted"));calls.clear();
 } }
}
