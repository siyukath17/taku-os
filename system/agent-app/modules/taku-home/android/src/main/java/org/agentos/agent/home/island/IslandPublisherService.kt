package org.agentos.agent.home.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import org.agentos.agent.home.assistant.AssistantSession
import org.agentos.agent.home.assistant.AssistantState
import org.agentos.agent.home.assistant.IslandMode
import org.agentos.agent.home.assistant.VoicePhase
import org.agentos.agent.home.assistant.VoiceState
import org.agentos.agent.home.assistant.VoiceTarget
import org.json.JSONArray
import org.json.JSONObject

/** Bound by SystemUI, not an Activity. Backend thread state survives UI/content-host recreation. */
@OptIn(FlowPreview::class)
class IslandPublisherService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val binder = Binder()
  private val manager by lazy { getSystemService(NotificationManager::class.java) }
  private val prefs by lazy { getSharedPreferences("system_island_session", MODE_PRIVATE) }
  private var fingerprint: String? = null
  private var lastExpansionRequest = 0L
  private var lastRequest = IslandMode.Hidden
  private var postedTag: String? = null

  override fun onCreate() {
    super.onCreate()
    manager.createNotificationChannel(NotificationChannel(CHANNEL, "Taku activities", NotificationManager.IMPORTANCE_LOW))
    val session = AssistantSession.get(this)
    val saved = prefs.getString("thread", null)
    if (session.state.value.threadId == null && !saved.isNullOrBlank() &&
        System.currentTimeMillis() - prefs.getLong("created", 0) in 0..28_800_000) {
      session.resume(saved)
      session.restoreIslandIdentity(prefs.getString("activity", null))
      lastExpansionRequest = session.state.value.expansionRequest
      lastRequest = session.state.value.island // Recovery must not unexpectedly expand over an app.
    }
    SystemIslandBridge.observe(this)
    scope.launch {
      combine(session.state, session.voice.state) { state, voice -> state to voice }.sample(250).collect { (state, voice) ->
        // Agent readers observe the same state published to the native SystemUI card.
        contentResolver.notifyChange(Uri.parse("content://org.agentos.agent.home-assistant"), null)
        publish(state, voice)
      }
    }
  }

  private fun publish(state: AssistantState, voice: VoiceState) {
    if (state.island == IslandMode.Hidden) {
      manager.activeNotifications.filter { it.id == ID && it.tag?.startsWith(TAG) == true }.forEach { manager.cancel(it.tag, ID) }
      prefs.edit().clear().apply()
      fingerprint = null
      postedTag = null
      lastRequest = IslandMode.Hidden
      return
    }
    val thread = state.threadId ?: "entry"
    val tag = "$TAG:${state.activityId ?: thread}"
    if (postedTag != null && postedTag != tag) manager.cancel(postedTag, ID)
    if (postedTag == null) manager.activeNotifications.filter { it.id == ID && it.tag?.startsWith(TAG) == true && it.tag != tag }.forEach { manager.cancel(it.tag, ID) }
    postedTag = tag
    val token = IslandIdentity.token(state.threadId, state.activityId)
    if (prefs.getString("activity", null) != state.activityId) prefs.edit().putLong("created", System.currentTimeMillis()).apply()
    prefs.edit().putString("activity", state.activityId).putString("token", token).apply()
    if (prefs.getString("thread", null) != state.threadId) {
      prefs.edit().putString("thread", state.threadId).putLong("created", System.currentTimeMillis()).apply()
    }
    // A hold whose microphone has not started recording yet shows no listening cue at all.
    val holdStarting = voice.target == VoiceTarget.Hold && voice.phase == VoicePhase.Preparing
    val falKeyMissing = state.error == AssistantSession.VOICE_FAL_KEY_MISSING
    val title = when {
      falKeyMissing -> "fal API key missing"
      state.error != null -> "Taku couldn’t finish"
      state.needsUser -> "Taku needs your confirmation"
      // Only once the microphone records (Capturing), never while it is still starting.
      voice.listening && !holdStarting -> "Taku is listening..."
      state.working -> "Taku is working"
      else -> "Taku"
    }
    val body = assistantCardHeadline(state, voice)
    val listening = assistantCardListening(state, voice)
    val short = if (state.buildingApp) "Building..." else ""
    val actions = Bundle()
    val ids = JSONArray()
    val suggestions = JSONArray()
    fun add(id: String, label: String, intent: PendingIntent) {
      if (ids.length() >= 4) return
      actions.putParcelable(id, Notification.Action.Builder(null as Icon?, label, intent).build())
      ids.put(id)
    }
    if (state.working || state.needsUser) add("stop", "Stop", action("stop", thread, state.activityId))
    // Voice needs a fal key: tapping the prompt (or its action) opens the fal setup page directly.
    val falSetup = if (!falKeyMissing) null else PendingIntent.getActivity(this, ID + 2,
      org.agentos.agent.home.onboarding.ServiceSetup.intent(this, org.agentos.agent.home.onboarding.OnboardingService.FAL),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    if (falSetup != null) add("falKey", "Add fal key", falSetup)
    val open = falSetup ?: PendingIntent.getActivity(this, ID, Intent().setClassName(packageName, "org.agentos.agent.MainActivity")
      .putExtra("threadId", state.threadId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    add("open", "Open conversation", action("open", thread, state.activityId))
    if (state.canRetry) add("retry", if (state.canContinue) "Continue" else "Try again", action("retry", thread, state.activityId))
    if ((state.error != null && state.retryText != null) || state.retainedVoice != null) {
      val input = PendingIntent.getActivity(this, ID + 1,
        Intent(this, org.agentos.agent.home.assistant.AssistantOverlayActivity::class.java)
          .putExtra("mode", "text").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
      add("input", if (state.retainedVoice != null) "Show dictation" else "Edit", input)
    }
    if (!state.working && !listening) state.turn?.suggestions?.take(2)?.forEachIndexed { index, suggestion ->
      val id = "reply_$index"
      add(id, suggestion.label, action("reply", thread, state.activityId, suggestion.id))
      if (actions.containsKey(id)) suggestions.put(id)
    }
    val steps = JSONArray(state.turn?.steps.orEmpty().takeLast(8).map { step ->
      JSONObject().put("kind", step.kind.name.lowercase()).put("text", bounded(step.text, 120)).put("running", step.running)
    })
    val payload = JSONObject().put("version", 1).put("category", "assistant").put("compact", if (state.buildingApp) "building" else "hidden").put("relevance", if (state.needsUser) 1 else .5)
      .put("bigIsland", JSONObject()
        .put("leading", JSONObject().put("type", "pic").put("pic", "taku").put("animated", if (state.working) "working" else if (voice.listening && !holdStarting) "listening" else "none"))
        .put("trailing", JSONObject().put("type", "text").put("text", if (state.buildingApp) short else "Taku")))
      .put("expanded", JSONObject().put("template", "assistant")
        .put("title", title).put("body", bounded(body, 1200)).put("steps", if (listening) JSONArray() else steps)
        .put("actions", ids).put("primaryAction", if (falSetup != null) "falKey" else "open")
        .put("assistant", JSONObject().put("token", token).put("phase", if (listening) "listening" else if (state.working) "working" else "message")
          .put("suggestions", suggestions)
          .put("heightDp", if (listening) kotlin.math.ceil(68 + 27.9 * resources.configuration.fontScale).toInt().coerceIn(96, 340) else 300)))
      .put("islandTimeoutS", 28800).put("statusBar", "auto")
    // UTF-8, not String.length: leave ample headroom for CJK/emoji and structural overhead.
    while (payload.toString().toByteArray(Charsets.UTF_8).size > 8192 && payload.getJSONObject("expanded").getString("body").isNotEmpty()) {
      val text = payload.getJSONObject("expanded").getString("body")
      payload.getJSONObject("expanded").put("body", bounded(text, (text.codePointCount(0, text.length) / 2)))
    }
    if (payload.toString().toByteArray(Charsets.UTF_8).size > 8192) payload.getJSONObject("expanded").put("steps", JSONArray())
    val value = payload.toString() + state.working + state.island.name + tag + state.turn?.suggestions?.take(2).toString()
    if (fingerprint != value) {
      val icon = Icon.createWithResource(this, applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info)
      val extras = Bundle().apply {
        putString("taku.island.v1", payload.toString())
        putBundle("taku.island.pics", Bundle().apply { putParcelable("taku", icon) })
        putBundle("taku.island.actions", actions)
      }
      val notification = Notification.Builder(this, CHANNEL).setSmallIcon(icon).setContentTitle(title)
        .setContentText(bounded(body, 200)).setContentIntent(open).setOnlyAlertOnce(true)
        .setCategory(Notification.CATEGORY_SERVICE).setOngoing(state.working || voice.listening)
        .setShortCriticalText(short).setRequestPromotedOngoing(state.buildingApp).addExtras(extras)
      for (index in 0 until ids.length()) actions.getParcelable(ids.getString(index), Notification.Action::class.java)?.let(notification::addAction)
      runCatching { manager.notify(tag, ID, notification.build()) }
        .onSuccess { fingerprint = value }
        .onFailure { android.util.Log.w("TakuIsland", "Notification unavailable", it) }
    }
    // The text input stays open under the expanded card (Figma 1-3): SystemUI's card never takes
    // focus and moves below the status bar while the keyboard shows, so a reply appears at the top
    // as soon as it arrives instead of after the keyboard closes.
    if (state.island != lastRequest || state.expansionRequest != lastExpansionRequest) {
      if (state.island == IslandMode.Expanded || state.island == IslandMode.Full) SystemIslandBridge.request(this, "expand")
      else if (state.island == IslandMode.Collapsed) SystemIslandBridge.request(this, "collapse")
      lastRequest = state.island
      lastExpansionRequest = state.expansionRequest
    }
  }

  private fun action(kind: String, thread: String, activity: String?, suggestion: String = ""): PendingIntent {
    val intent = Intent(this, IslandActionReceiver::class.java).setAction(kind)
      .setData(Uri.Builder().scheme("taku-island").authority(kind).appendPath(thread).appendPath(activity ?: "entry").appendPath(suggestion).build())
      .putExtra("thread", thread).putExtra("activity", activity ?: "entry").putExtra("suggestion", suggestion)
    return PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }
  private fun bounded(value: String, count: Int) = value.substring(0, value.offsetByCodePoints(0, minOf(count, value.codePointCount(0, value.length))))
  override fun onBind(intent: Intent?): IBinder = binder
  override fun onDestroy() { scope.cancel(); super.onDestroy() } // Do not cancel backend work or its last notification.
  companion object { const val CHANNEL = "taku_island"; const val TAG = "taku-island"; const val ID = 2801 }
}
