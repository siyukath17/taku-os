package org.agentos.agent.bridge

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.util.function.Consumer
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.Promise
import expo.modules.kotlin.functions.Queues
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

/** A Panel navigation failure carrying the native code, so the chat can show the real reason. */
private class PanelTargetException(code: String) : CodedException(code, code, null)

/** The Panel a conversation was opened for; navigation context only, never content or authority. */
internal data class PanelTarget(val panelId: String, val sourceVersion: Long, val originThreadId: String, val launchId: String) {
  fun toJson(): String = JSONObject()
    .put("panelId", panelId)
    .put("sourceVersion", sourceVersion)
    .put("originThreadId", originThreadId)
    .put("launchId", launchId)
    .toString()

  fun toEvent(): Map<String, Any> =
    mapOf("panelId" to panelId, "sourceVersion" to sourceVersion.toDouble(), "originThreadId" to originThreadId, "launchId" to launchId)
}

/** No execution or credentials live in this client; the system gateway owns all decisions. */
class TakuAgentModule : Module() {
  companion object {

    /**
     * Standard Android extras the exported conversation Activity accepts. A Panel target is
     * navigation context; `panels/continue` revalidates the thread and version at the actual send.
     */
    const val EXTRA_THREAD_ID = "threadId"
    const val EXTRA_PANEL_ID = "panelId"
    const val EXTRA_SOURCE_VERSION = "sourceVersion"
    const val EXTRA_ORIGIN_THREAD_ID = "originThreadId"

    /** How long an awaited launch route may wait for `panels/get` before JS uses its default. */
    private const val ROUTE_TIMEOUT_MS = 3_000L
    private const val OPEN_ACCESS = "content://org.agentos.assistant.open-access"
    private const val CONVERSATION = "org.agentos.agent.MainActivity"
    private const val TAG = "TakuAgentPanels"
  }

  private val authority = "org.agentos.assistant.agent-gateway"
  /** Written from the intent callbacks and the route read; read by JS on its own thread. */
  @Volatile private var latestThreadId: String? = null
  private val mainHandler = Handler(Looper.getMainLooper())
  private val composerPicker by lazy {
    ComposerPicker(mainHandler, appContext.backgroundCoroutineScope, ::callGateway) { threadId ->
      sendEvent("onComposerAttachment", mapOf("threadId" to threadId))
    }
  }
  private var observingIme = false
  private var imeObserver: ImeInsetsObserver? = null
  /** Window backdrop of the full-screen chat (2-1); null while another screen shows. */
  private var backdrop: Backdrop? = null
  private var backdropApplication: android.app.Application? = null
  @Volatile private var chatActivity: android.app.Activity? = null
  /** Follows `taku_chat_appearance` while JS listens for `onChatAppearance`. */
  private var appearanceObserver: android.database.ContentObserver? = null
  private val backdropLifecycle = object : android.app.Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: android.app.Activity) {
      if (activity.javaClass.name != CONVERSATION) return
      chatActivity = activity
      mainHandler.post {
        if (chatActivity === activity) { applyBackdrop(backdrop); attachImeObserver() }
      }
    }
    override fun onActivityDestroyed(activity: android.app.Activity) {
      composerPicker.onActivityDestroyed(activity)
      if (backdrop?.activity === activity) releaseBackdrop(backdrop)
      if (chatActivity === activity) { chatActivity = null; detachImeObserver() }
    }
    override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {
      if (activity.javaClass.name != CONVERSATION) return
      chatActivity = activity
      composerPicker.onActivityAvailable(activity)
    }
    override fun onActivityStarted(activity: android.app.Activity) = Unit
    override fun onActivityPaused(activity: android.app.Activity) = Unit
    override fun onActivityStopped(activity: android.app.Activity) = Unit
    override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
  }

  /** ReactHost is shared with Home; conversation routes/windows belong only to MainActivity. */
  private fun conversationActivity(): android.app.Activity? =
    conversationHost(chatActivity, appContext.currentActivity) {
      it.javaClass.name == CONVERSATION && !it.isDestroyed && !it.isFinishing
    }

  // Each lease is one surface's demand: a new subscription ID never stops another surface's reader.
  private val sessionLock = Any()
  private val sessionReaders = HashMap<String, SessionEventReader>()
  private val matterLock = Any()
  private val matterReaders = HashMap<String, MatterEventReader>()

  /** The Panel half of the conversation route: one fenced `panels/get` per route. */
  private val panelLaunch by lazy {
    val context = appContext.reactContext?.applicationContext ?: throw IOException("Agent UI is not ready")
    PanelLaunch(appContext.backgroundCoroutineScope,
      consumed = { PanelLaunchIntents.consumed(context, it) },
      persistConsumption = { PanelLaunchIntents.consume(context, it) },
    ) { path -> panelAnswer(path) }
  }
  /** Where the conversation activity is started, so hidden chat cards can drop their demand. */
  private var conversationVisibility: ConversationVisibility? = null
  private var conversationApplication: Application? = null

  private fun stopMatter(subscriptionId: String) {
    synchronized(matterLock) { matterReaders.remove(subscriptionId) }?.stop()
  }

  private fun stopSession(subscriptionId: String) {
    synchronized(sessionLock) { sessionReaders.remove(subscriptionId) }?.stop()
  }

  /**
   * Readers are released by their exact lease ID, so leaving one entry (chat to desktop, or the
   * reverse) never stops the other. Only the shared host going away drops all of them.
   */
  private fun stopAllReaders() {
    val sessions = synchronized(sessionLock) { sessionReaders.values.toList().also { sessionReaders.clear() } }
    val matters = synchronized(matterLock) { matterReaders.values.toList().also { matterReaders.clear() } }
    sessions.forEach { it.stop() }
    matters.forEach { it.stop() }
  }

  private fun detachImeObserver() {
    imeObserver?.let { (it.parent as? ViewGroup)?.removeView(it) }
    imeObserver = null
  }

  private fun attachImeObserver() {
    if (!observingIme || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val activity = conversationActivity() ?: return
    val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
    if (imeObserver?.parent === content) {
      imeObserver?.refresh()
      return
    }
    detachImeObserver()
    val observer = ImeInsetsObserver(activity, content) { geometry ->
      if (observingIme) sendEvent("onImeInsets", geometry)
    }
    imeObserver = observer
    // Our non-interactive observer owns its Insets callbacks. Never replace React's listeners.
    content.addView(observer, ViewGroup.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
  }

  private class Backdrop(val sigmaDp: Double, val tint: Int, val fallback: Int) {
    var listener: Consumer<Boolean>? = null
    /** Where [listener] is registered; unregistering must not depend on the current activity. */
    var windowManager: android.view.WindowManager? = null
    var activity: android.app.Activity? = null
  }

  private fun releaseBackdrop(previous: Backdrop?) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      previous?.listener?.let { listener -> previous.windowManager?.removeCrossWindowBlurEnabledListener(listener) }
      previous?.activity?.window?.setBackgroundBlurRadius(0)
    }
    previous?.activity?.window?.setBackgroundDrawableResource(R.color.taku_conversation_background)
    previous?.listener = null
    previous?.windowManager = null
    previous?.activity = null
  }

  /**
   * The chat window alone blurs whatever lies under it (Feed, Home or another app, all alike)
   * with the system cross-window blur, under [Backdrop.tint]. Where the device cannot blur (no
   * GPU blur, battery saver, Android 11) the window shows the opaque [Backdrop.fallback] instead.
   * Android 16's Kawase blur shows a grid at this radius; Android 17's renders it smoothly.
   */
  private fun applyBackdrop(next: Backdrop?) {
    val previous = backdrop
    val activity = if (next == null) null else conversationActivity()
    if (next != null && next === previous && next.activity === activity) return
    backdrop = null
    releaseBackdrop(previous)
    // JS can mount before ReactActivity resumes. Keep the request and attach on foreground;
    // never tint HomeActivity merely because it is still ReactHost's current activity.
    backdrop = next
    if (next == null || activity == null) return
    val window = activity.window
    next.activity = activity
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      window.setBackgroundDrawable(ColorDrawable(next.fallback))
      return
    }
    // Figma blur R is a Gaussian of σ = R/2; SurfaceFlinger's blur radius r gives σ ≈ 0.57735 r.
    val sigmaPx = next.sigmaDp * activity.resources.displayMetrics.density
    val radius = (sigmaPx / 0.57735).toInt().coerceAtLeast(0)
    val listener = Consumer<Boolean> { enabled ->
      if (backdrop !== next || next.activity !== activity) return@Consumer
      window.setBackgroundDrawable(ColorDrawable(if (enabled) next.tint else next.fallback))
      window.setBackgroundBlurRadius(if (enabled) radius else 0)
      android.util.Log.d("TakuBackdrop", "blur=$enabled radius=${if (enabled) radius else 0} window=${activity.javaClass.simpleName}")
    }
    next.listener = listener
    next.windowManager = activity.windowManager
    // Android dispatches the current blur state to a newly registered listener.
    activity.windowManager.addCrossWindowBlurEnabledListener(listener)
  }

  /**
   * Reads one Panel through the public Interface and accepts it as a continuation target only when
   * the native record says the original thread can be continued. No task is created here.
   */
  private fun resolveTarget(panelId: String, launchId: String): PanelTarget {
    val context = appContext.reactContext ?: throw PanelTargetException("AGENT_UI_UNAVAILABLE")
    val response = context.contentResolver.call(
      Uri.parse(OPEN_ACCESS), "panels/get", JSONObject().put("panelId", panelId).toString(), null,
    ) ?: throw PanelTargetException("PANEL_NOT_FOUND")
    val envelope = try {
      JSONObject(response.getString("result") ?: "")
    } catch (e: Exception) {
      throw PanelTargetException("PANEL_NOT_FOUND")
    }
    if (!envelope.optBoolean("ok")) throw PanelTargetException(envelope.optString("code").ifBlank { "PANEL_NOT_FOUND" })
    val detail = envelope.optJSONObject("result") ?: throw PanelTargetException("PANEL_NOT_FOUND")
    val panel = detail.optJSONObject("panel") ?: throw PanelTargetException("PANEL_NOT_FOUND")
    val canContinue = detail.optJSONObject("capabilities")?.optBoolean("canContinue") == true
    if (!canContinue) {
      val code = detail.optJSONObject("sourceState")?.optString("code")?.takeIf { it.isNotBlank() }
      throw PanelTargetException(code ?: "SOURCE_THREAD_NOT_FOUND")
    }
    val originThreadId = panel.optString("originThreadId")
    if (originThreadId.isBlank()) throw PanelTargetException("SOURCE_THREAD_NOT_FOUND")
    val sourceVersion = panel.optLong("sourceVersion", -1L)
    if (sourceVersion < 0) throw PanelTargetException("PANEL_NOT_FOUND")
    return PanelTarget(panel.optString("panelId", panelId), sourceVersion, originThreadId, launchId)
  }

  /** The route a conversation intent names, or null when it names no thread. */
  private fun routePath(intent: Intent?): LaunchRoute.Path? {
    val threadId = intent?.getStringExtra(EXTRA_THREAD_ID)?.takeIf { it.isNotBlank() } ?: return null
    return LaunchRoute.Path(
      threadId = threadId,
      panelId = intent.getStringExtra(EXTRA_PANEL_ID)?.takeIf { it.isNotBlank() },
      originThreadId = intent.getStringExtra(EXTRA_ORIGIN_THREAD_ID)?.takeIf { it.isNotBlank() },
      launchId = intent.getStringExtra(PanelLaunchIntents.EXTRA_LAUNCH_ID)?.takeIf { it.isNotBlank() },
    )
  }

  /** The conversation that is open now: the live intent first, else the route already remembered. */
  private fun openRoute(): LaunchRoute.Path? = routePath(conversationActivity()?.intent) ?: panelLaunch.path()

  /**
   * Reads one Panel through `panels/get` and accepts it as a continuation target only when the
   * native record says the original thread can be continued and it is this intent's thread. The
   * target stays navigation context; a refusal keeps its native code so JS can tell the user why
   * instead of silently sending an ordinary message.
   */
  private fun panelAnswer(path: LaunchRoute.Path): LaunchAnswer {
    val panelId = path.panelId ?: return LaunchAnswer.NoPanel
    val launchId = path.launchId ?: return LaunchAnswer.Rejected("PANEL_TARGET_INVALID")
    val target = try {
      resolveTarget(panelId, launchId)
    } catch (error: PanelTargetException) {
      Log.w(TAG, "panel target rejected: ${error.code}")
      return LaunchAnswer.Rejected(error.code)
    } catch (error: Exception) {
      Log.w(TAG, "panel target unavailable: ${error.message}")
      return LaunchAnswer.Rejected(PanelLaunch.SOURCE_UNAVAILABLE)
    }
    val belongsToThread = target.originThreadId == path.threadId &&
      (path.originThreadId == null || path.originThreadId == target.originThreadId)
    return if (belongsToThread) LaunchAnswer.Accepted(target) else LaunchAnswer.Rejected("SOURCE_THREAD_NOT_FOUND")
  }

  /**
   * Announces a route to JS: the thread, plus the Panel target it was opened for. The route is
   * checked again on the main thread, so an event queued just before a newer intent cannot pull
   * the chat back to the conversation the user already left.
   */
  private fun deliverRoute(pending: PanelLaunch.Resolution, answer: LaunchAnswer) {
    mainHandler.post {
      if (!panelLaunch.current(pending)) return@post
      val event = HashMap<String, Any>(2)
      event["threadId"] = pending.path.threadId
      event["generation"] = pending.generation.toString()
      (answer as? LaunchAnswer.Accepted)?.let { event["panelTarget"] = it.target.toEvent() }
      if (answer is LaunchAnswer.Rejected) event["errorCode"] = answer.code
      sendEvent("onOpenThread", event)
    }
  }

  private fun rememberLaunchIntent(intent: Intent?, announce: Boolean) {
    val path = routePath(intent) ?: return
    latestThreadId = path.threadId
    val pending = if (announce) panelLaunch.reopen(path) else panelLaunch.observe(path)
    if (!announce) return
    // Every request announces once: a repeat intent still moves the chat to that conversation,
    // and a route superseded before its answer lands announces nothing.
    appContext.backgroundCoroutineScope.launch {
      deliverRoute(pending, pending.answer.await())
    }
  }

  /**
   * Reads the credential on the main thread, where its fields live, then sends it to the gateway
   * as a read-only descriptor from the background scope. Empties [fieldIds] once accepted.
   */
  private fun submitCredential(action: String, params: String, fieldIds: List<String>, promise: Promise, credential: () -> ByteArray) {
    val context = appContext.reactContext
    if (context == null) { promise.reject("NATIVE_UNAVAILABLE", "Agent UI is not ready", null); return }
    val bytes = try { credential() } catch (error: IOException) {
      promise.reject(error.message ?: "SECRET_FIELD_UNAVAILABLE", "Secret field is unavailable", null); return
    }
    appContext.backgroundCoroutineScope.launch {
      try {
        val envelope = SecretFields.descriptor(context, bytes).use { descriptor ->
          callGateway(context.contentResolver, action, params, Bundle().apply { putParcelable("credential", descriptor) })
        }
        // A connection test keeps the token typed, so the same field can then be saved.
        if (action != "connect/test" && JSONObject(envelope).optBoolean("ok")) mainHandler.post { fieldIds.forEach(SecretFields::clear) }
        promise.resolve(envelope)
      } catch (error: Exception) {
        val code = error.message?.takeIf { Regex("[A-Z][A-Z_]{2,80}").matches(it) } ?: "GATEWAY_UNAVAILABLE"
        promise.reject(code, "Could not submit the credential", null)
      }
    }
  }

  private fun callGateway(resolver: ContentResolver, action: String, params: String, extras: Bundle?): String {
    val response = resolver.call(Uri.parse("content://$authority"), action, params, extras)
      ?: throw IOException("Taku Agent gateway is unavailable")
    val envelope = response.getString("result") ?: throw IOException("Invalid gateway response")
    val resultUri = JSONObject(envelope).optString("resultUri")
    if (resultUri.isEmpty()) return envelope
    val uri = Uri.parse(resultUri)
    require(uri.scheme == "content" && uri.authority == authority && uri.path?.startsWith("/result/") == true) {
      "Invalid gateway result URI"
    }
    val stream = resolver.openInputStream(uri) ?: throw IOException("Gateway result expired")
    return stream.use { input ->
      val output = ByteArrayOutputStream()
      val buffer = ByteArray(8192)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (output.size() + count > 8 * 1024 * 1024) throw IOException("Gateway result exceeds client limit")
        output.write(buffer, 0, count)
      }
      output.toString("UTF-8")
    }
  }

  override fun definition() = ModuleDefinition {
    Name("TakuAgent")
    Events("onOpenConversation", "onOpenThread", "onDraft", "onImeInsets", "onSessionEventBatch", "onMatterEventBatch", "onConversationVisibility", "onSecretSubmit", "onComposerAttachment", "onChatAppearance")
    OnCreate {
      SecretFields.onSubmit = { fieldId -> sendEvent("onSecretSubmit", mapOf("fieldId" to fieldId)) }
      val application = appContext.reactContext?.applicationContext as? Application
        ?: appContext.currentActivity?.application
      // Expo can create this module after MainActivity has already resumed. Seed
      // ownership now, before ReactHost switches to Home and the callback is lost.
      chatActivity = conversationActivity()
      backdropApplication = application
      application?.registerActivityLifecycleCallbacks(backdropLifecycle)
      // A picker that was open when the process died reports to the Activity Android restored.
      mainHandler.post { conversationActivity()?.let(composerPicker::onActivityAvailable) }
      conversationApplication = application
      conversationVisibility = application?.let { host ->
        ConversationVisibility(
          hostsConversation = { it.javaClass.name == CONVERSATION },
          onChange = { visible -> mainHandler.post { sendEvent("onConversationVisibility", mapOf("visible" to visible)) } },
        ).also { it.attach(host, appContext.currentActivity) }
      }
    }
    /** The chat background, `dark` (default) or `light`; see [ChatAppearance]. */
    Function("chatAppearance") {
      val context = appContext.reactContext?.applicationContext ?: return@Function ChatAppearance.DARK
      ChatAppearance.read(context)
    }
    /** Saves the chat background; answers `{ok, appearance}` or `{ok:false, code}`. */
    Function("setChatAppearance") { value: String ->
      try {
        val context = appContext.reactContext?.applicationContext ?: throw IllegalStateException("SETTINGS_WRITE_FAILED")
        JSONObject().put("ok", true).put("appearance", ChatAppearance.write(context, value)).toString()
      } catch (e: IllegalArgumentException) {
        JSONObject().put("ok", false).put("code", e.message).toString()
      } catch (e: IllegalStateException) {
        JSONObject().put("ok", false).put("code", e.message).toString()
      }
    }
    OnStartObserving("onChatAppearance") {
      val context = appContext.reactContext?.applicationContext ?: return@OnStartObserving
      appearanceObserver?.let { context.contentResolver.unregisterContentObserver(it) }
      appearanceObserver = ChatAppearance.observe(context) { value -> sendEvent("onChatAppearance", mapOf("appearance" to value)) }
    }
    OnStopObserving("onChatAppearance") {
      val context = appContext.reactContext?.applicationContext ?: return@OnStopObserving
      appearanceObserver?.let { context.contentResolver.unregisterContentObserver(it) }
      appearanceObserver = null
    }
    OnStartObserving("onImeInsets") {
      mainHandler.post { observingIme = true; attachImeObserver() }
    }
    OnStopObserving("onImeInsets") {
      mainHandler.post { observingIme = false; detachImeObserver() }
    }
    OnActivityEntersForeground {
      mainHandler.post {
        attachImeObserver()
        conversationVisibility?.seed(appContext.currentActivity)
        rememberLaunchIntent(conversationActivity()?.intent, announce = false)
      }
    }
    OnActivityDestroys { mainHandler.post {
      detachImeObserver()
      if (backdrop?.activity?.isDestroyed == true) releaseBackdrop(backdrop)
    } }
    OnDestroy {
      SecretFields.onSubmit = null
      composerPicker.close()
      conversationVisibility?.let { tracker -> conversationApplication?.let(tracker::detach) }
      conversationVisibility = null
      conversationApplication = null
      stopAllReaders()
      mainHandler.post {
        observingIme = false; detachImeObserver(); applyBackdrop(null)
        backdropApplication?.unregisterActivityLifecycleCallbacks(backdropLifecycle)
        backdropApplication = null; chatActivity = null
      }
    }

    Function("subscribeSession") { subscriptionId: String, params: String ->
      val resolver = appContext.reactContext?.contentResolver ?: throw IOException("Agent UI is not ready")
      val reader = SessionEventReader(resolver, authority, mainHandler, subscriptionId, params) { batch ->
        sendEvent("onSessionEventBatch", batch)
      }
      synchronized(sessionLock) {
        sessionReaders.remove(subscriptionId)?.stop()
        sessionReaders[subscriptionId] = reader
      }
      reader.start()
    }
    Function("unsubscribeSession") { subscriptionId: String ->
      stopSession(subscriptionId)
    }

    Function("subscribeMatter") { subscriptionId: String, params: String ->
      val resolver = appContext.reactContext?.contentResolver ?: throw IOException("Agent UI is not ready")
      val reader = MatterEventReader(resolver, authority, mainHandler, subscriptionId, params) { batch ->
        sendEvent("onMatterEventBatch", batch)
      }
      synchronized(matterLock) {
        matterReaders.remove(subscriptionId)?.stop()
        matterReaders[subscriptionId] = reader
      }
      reader.start()
    }
    Function("unsubscribeMatter") { subscriptionId: String ->
      stopMatter(subscriptionId)
    }

    OnNewIntent { intent ->
      if (intent.data == null) sendEvent("onOpenConversation", emptyMap<String, Any>())
      if (ConversationDrafts.hasPending()) sendEvent("onDraft", emptyMap<String, Any>())
      if (intent.getStringExtra(EXTRA_THREAD_ID)?.isNotBlank() == true) {
        // Retain the route when JS has not attached its listener or its context is rebuilt.
        rememberLaunchIntent(intent, announce = true)
      }
    }

    AsyncFunction("pickComposerAttachment") { kind: String, threadId: String, expectedRevision: String, promise: Promise ->
      val activity = conversationActivity()?.takeIf { it === appContext.currentActivity }
      composerPicker.pick(activity, kind, threadId, expectedRevision, appContext.permissions, promise)
    }.runOnQueue(Queues.MAIN)

    AsyncFunction("call") { action: String, params: String ->
      val context = appContext.reactContext ?: throw IOException("Agent UI is not ready")
      callGateway(context.contentResolver, action, params, null)
    }.runOnQueue(appContext.backgroundCoroutineScope)

    Function("launchThreadId") {
      latestThreadId ?: conversationActivity()?.intent?.getStringExtra(EXTRA_THREAD_ID) ?: ""
    }

    Function("isLaunchCurrent") { generation: String ->
      generation.toLongOrNull()?.let { panelLaunch.current(it) } ?: false
    }

    Function("isPanelLaunchConsumed") { launchId: String ->
      val context = appContext.reactContext ?: throw IOException("Agent UI is not ready")
      PanelLaunchIntents.consumed(context, launchId)
    }

    AsyncFunction("consumePanelLaunch") { launchId: String ->
      require(launchId.isNotBlank()) { "Missing Panel launch identity" }
      panelLaunch.consume(launchId)
      mainHandler.post {
        conversationActivity()?.let { activity ->
          if (activity.intent?.getStringExtra(PanelLaunchIntents.EXTRA_LAUNCH_ID) == launchId) {
            activity.intent = Intent(activity.intent).also(PanelLaunchIntents::clearPanel)
          }
        }
      }
      Unit
    }

    Function("selectConversation") { threadId: String? ->
      panelLaunch.path()?.takeIf { it.panelId != null }?.launchId?.let(panelLaunch::consume)
      panelLaunch.clear()
      latestThreadId = threadId ?: ""
      if (threadId != null) panelLaunch.observe(LaunchRoute.Path(threadId, null, null))
      conversationActivity()?.let { activity ->
        val intent = Intent(activity.intent).apply {
          PanelLaunchIntents.clearPanel(this)
        }
        if (threadId == null) intent.removeExtra(EXTRA_THREAD_ID) else intent.putExtra(EXTRA_THREAD_ID, threadId)
        activity.intent = intent
      }
    }

    /**
     * The Panel this conversation was opened for, including its durable launch identity, or "".
     * It suspends until that route's Panel has been validated, so a cold
     * Panel launch is never mistaken for an ordinary conversation while `panels/get` runs. The
     * send itself goes through `panels/continue`.
     */
    AsyncFunction("launchPanelTarget") Coroutine { refresh: Boolean ->
      val path = openRoute() ?: return@Coroutine ""
      latestThreadId = path.threadId
      if (refresh) panelLaunch.reopen(path) else panelLaunch.observe(path)
      val answer = panelLaunch.answer(ROUTE_TIMEOUT_MS)
      // The answered route may be newer than the intent this call started with; the thread the
      // chat must restore is that one, not the superseded one.
      panelLaunch.path()?.let { latestThreadId = it.threadId }
      when (answer) {
        LaunchAnswer.NoPanel -> ""
        is LaunchAnswer.Accepted -> answer.target.toJson()
        is LaunchAnswer.Rejected -> throw PanelTargetException(answer.code)
        // The wait follows a superseded route, so an answer here is already the current one.
        LaunchAnswer.Superseded -> throw PanelTargetException(PanelLaunch.SOURCE_UNAVAILABLE)
      }
    }

    /** Whether the conversation activity is started; a stopped chat is behind the desktop. */
    Function("conversationVisible") { conversationVisibility?.visible ?: true }

    /**
     * Opens the Panel's original conversation with the standard Android extras, so the same route
     * works from the GUI and from `am start`. No task is sent and no content is transferred.
     */
    AsyncFunction("openPanelConversation") { panelId: String ->
      val context = appContext.reactContext ?: throw IOException("Agent UI is not ready")
      val target = resolveTarget(panelId, UUID.randomUUID().toString())
      val path = LaunchRoute.Path(target.originThreadId, target.panelId, target.originThreadId, target.launchId)
      panelLaunch.seed(path, LaunchAnswer.Accepted(target))
      latestThreadId = target.originThreadId
      context.startActivity(
        Intent().setClassName(context.packageName, CONVERSATION)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
          .putExtra(EXTRA_THREAD_ID, target.originThreadId)
          .putExtra(EXTRA_PANEL_ID, target.panelId)
          .putExtra(EXTRA_SOURCE_VERSION, target.sourceVersion.toString())
          .putExtra(EXTRA_ORIGIN_THREAD_ID, target.originThreadId)
          .putExtra(PanelLaunchIntents.EXTRA_LAUNCH_ID, target.launchId),
      )
    }

    // Editable composer text from Home; never sent without the user pressing Send.
    Function("launchDraft") { ConversationDrafts.take() }

    Function("requestId") { UUID.randomUUID().toString() }

    View(EdgeBlurView::class) {
      Prop("topSharp") { view: EdgeBlurView, value: Float -> view.topSharp = value }
      Prop("topFull") { view: EdgeBlurView, value: Float -> view.topFull = value }
      Prop("bottomSharp") { view: EdgeBlurView, value: Float -> view.bottomSharp = value }
      Prop("bottomFull") { view: EdgeBlurView, value: Float -> view.bottomFull = value }
      Prop("sigma") { view: EdgeBlurView, value: Float -> view.sigma = value }
      Prop("fade") { view: EdgeBlurView, value: Boolean -> view.fade = value }
      Prop("uniformSigma") { view: EdgeBlurView, value: Float -> view.uniformSigma = value }
      OnViewDidUpdateProps { view: EdgeBlurView -> view.update() }
    }

    AsyncFunction("setWindowBackdrop") { enabled: Boolean, sigmaDp: Double, tint: String, fallback: String ->
      android.util.Log.d("TakuBackdrop", "enabled=$enabled host=${appContext.currentActivity?.javaClass?.simpleName} chat=${chatActivity?.javaClass?.simpleName}")
      applyBackdrop(if (enabled) Backdrop(sigmaDp, Color.parseColor(tint), Color.parseColor(fallback)) else null)
    }.runOnQueue(Queues.MAIN)

    View(SecretFieldView::class) {
      Prop("fieldId") { view: SecretFieldView, value: String -> view.fieldId = value }
      Prop("placeholder") { view: SecretFieldView, value: String -> view.placeholder(value) }
      Prop("textColor") { view: SecretFieldView, value: String -> view.textColor(value) }
      Prop("placeholderColor") { view: SecretFieldView, value: String -> view.placeholderColor(value) }
      Prop("revealed") { view: SecretFieldView, value: Boolean -> view.reveal(value) }
      Prop("autoFocus") { view: SecretFieldView, value: Boolean -> view.autoFocus = value }
    }

    /**
     * Sends a gateway call whose secret is the text of a mounted [SecretFieldView]. The value
     * goes from the field to the gateway as a file descriptor and is never returned to React.
     * The field is emptied once the gateway accepts it.
     */
    AsyncFunction("submitSecret") { fieldId: String, action: String, params: String, promise: Promise ->
      if (action != "accounts/saveKey" && action != "manageAssistant" && action != "connect/add" && action != "connect/test") {
        promise.reject("INVALID_ARGUMENT", "Unsupported secret action", null); return@AsyncFunction
      }
      submitCredential(action, params, listOf(fieldId), promise) { SecretFields.bytes(fieldId) }
    }.runOnQueue(Queues.MAIN)

    /**
     * The same for a form with several secret fields: [fields] maps each form field name to its
     * mounted [SecretFieldView], and the gateway receives them as one JSON object.
     */
    AsyncFunction("submitSecretFields") { fields: Map<String, String>, action: String, params: String, promise: Promise ->
      if (action != "requests/submit") {
        promise.reject("INVALID_ARGUMENT", "Unsupported secret action", null); return@AsyncFunction
      }
      submitCredential(action, params, fields.values.toList(), promise) {
        val secrets = JSONObject()
        for ((name, fieldId) in fields) {
          val bytes = SecretFields.bytes(fieldId)
          try { secrets.put(name, String(bytes, Charsets.UTF_8)) } finally { bytes.fill(0) }
        }
        secrets.toString().toByteArray(Charsets.UTF_8)
      }
    }.runOnQueue(Queues.MAIN)

    AsyncFunction("clearSecret") { fieldId: String ->
      SecretFields.clear(fieldId)
    }.runOnQueue(Queues.MAIN)

    // Credential entry and one-time sign-in codes stay out of screenshots and the recents preview.
    AsyncFunction("setSecureScreen") { enabled: Boolean ->
      val window = appContext.currentActivity?.window ?: return@AsyncFunction Unit
      val flag = android.view.WindowManager.LayoutParams.FLAG_SECURE
      if (enabled) window.addFlags(flag) else window.clearFlags(flag)
      Unit
    }.runOnQueue(Queues.MAIN)

    // The composer asks for the keyboard explicitly once focused (WindowInsetsController on 11+).
    AsyncFunction("showKeyboard") {
      val activity = conversationActivity() ?: return@AsyncFunction Unit
      val focused = activity.currentFocus
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.window.insetsController?.show(WindowInsets.Type.ime())
      else if (focused != null) (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(focused, 0)
      Unit
    }.runOnQueue(Queues.MAIN)

    // Collapse (2-1 → 1-1): the home island takes over this thread, then Home comes forward.
    AsyncFunction("collapseToIsland") { threadId: String ->
      val activity = conversationActivity() ?: throw IOException("Agent UI is not ready")
      val response = activity.contentResolver.call(
        Uri.parse("content://org.agentos.agent.home-assistant"), "assistant/resume",
        JSONObject().put("threadId", threadId).put("home", false).toString(), null,
      ) ?: throw IOException("Taku Home is unavailable")
      val result = JSONObject(response.getString("result") ?: "{}")
      if (!result.optBoolean("ok")) throw IOException(result.optString("code", "HOME_UNAVAILABLE"))
      activity.startActivity(Intent().setClassName(activity.packageName, "org.agentos.agent.home.HomeActivity"))
      Unit
    }.runOnQueue(Queues.MAIN)

    AsyncFunction("close") {
      conversationActivity()?.let { activity ->
        if (activity.intent?.data != null) {
          activity.intent = Intent(activity.intent).apply {
            data = null
            action = Intent.ACTION_MAIN
          }
        }
        activity.moveTaskToBack(true)
      }
      Unit
    }.runOnQueue(Queues.MAIN)
  }
}
