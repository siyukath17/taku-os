package org.agentos.agent.home.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/** The real selected Android assistant lives in Taku Agent, independent of the backend UI. */
class SystemAssistantService : VoiceInteractionService() {
  private var questionCardsBound = false
  private val questionCards = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, service: IBinder) = Unit
    override fun onServiceDisconnected(name: ComponentName) = Unit
    override fun onBindingDied(name: ComponentName) {
      unbindQuestionCards()
      if (active === this@SystemAssistantService) bindQuestionCards()
    }
  }

  override fun onReady() {
    super.onReady()
    active = this
    bindQuestionCards()
  }

  override fun onShutdown() {
    if (active === this) active = null
    unbindQuestionCards()
    super.onShutdown()
  }

  override fun onDestroy() {
    if (active === this) active = null
    unbindQuestionCards()
    super.onDestroy()
  }

  private fun bindQuestionCards() {
    if (questionCardsBound) return
    val intent = Intent().setClassName("org.agentos.assistant", "org.agentos.assistant.ConversationQuestionCardsService")
    questionCardsBound = try {
      bindService(intent, questionCards, Context.BIND_AUTO_CREATE)
    } catch (error: SecurityException) {
      android.util.Log.w("SystemAssistantService", "Question cards service is unavailable", error)
      false
    }
  }

  private fun unbindQuestionCards() {
    if (!questionCardsBound) return
    questionCardsBound = false
    unbindService(questionCards)
  }

  companion object {
    @Volatile private var active: SystemAssistantService? = null

    fun requestCapture(token: String, manual: Boolean) {
      if (!token.matches(Regex("[a-f0-9-]{36}"))) throw AssistantException("INVALID_ARGUMENT", "invalid capture token")
      val service = active ?: throw AssistantException("UNAVAILABLE", "system assistant is not ready")
      service.showSession(Bundle().apply {
        putString("jevTestToken", token)
        putBoolean("manualScreen", manual)
      }, VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }
  }
}

class SystemAssistantSessionService : VoiceInteractionSessionService() {
  override fun onNewSession(args: Bundle?): VoiceInteractionSession = SystemAssistantSession(this)
}

/** Android requires this metadata; actual microphone/ASR ownership stays in Audio Engine. */
class SystemRecognitionService : RecognitionService() {
  override fun onStartListening(intent: Intent?, callback: Callback) { runCatching { callback.error(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) } }
  override fun onStopListening(callback: Callback) {}
  override fun onCancel(callback: Callback) {}
}

/** Shared by Android system entry and the device-local Agent API. */
object SystemAssistantEntry {
  const val ACTION_VOICE_ASSIST = "android.intent.action.VOICE_ASSIST"
  const val EXTRA_OPEN = "org.agentos.agent.extra.OPEN_ASSISTANT"
  const val HOME = "org.agentos.agent.home.HomeActivity"
  const val SERVICE = "org.agentos.agent.home.assistant.SystemAssistantService"
  const val TRIGGER = "org.agentos.assistant.extra.TRIGGER" // Existing gesture protocol stays compatible.
  const val GESTURE_ID = "org.agentos.assistant.extra.GESTURE_ID"

  fun home(context: Context): Intent = Intent(Intent.ACTION_MAIN)
    .addCategory(Intent.CATEGORY_HOME).setClassName(context.packageName, HOME)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

  /**
   * Where a system invocation lands. Navigation-handle gestures carry [TRIGGER]; anything else
   * (long-press power, `invocation_type` 6, `cmd voiceinteraction show`) is a hardware entry that
   * opens the text overlay over the current app instead of sending the user to Home. Push-to-talk
   * flags are deliberately not mapped to a hold: nothing would ever release that hold.
   */
  enum class Route(val mode: String, val navGesture: Boolean) {
    NavHold("voice", true), NavDoubleTap("text", true), Hardware("text", false);
    /** Hardware entries attach the invocation screenshot to their first message, once. */
    val autoScreenshot: Boolean get() = this == Hardware
  }

  fun routeFor(trigger: String?): Route = when (trigger) {
    "nav_handle_long_press" -> Route.NavHold
    "nav_handle_double_tap" -> Route.NavDoubleTap
    else -> Route.Hardware
  }

  fun route(args: Bundle?): Route = routeFor(args?.getString(TRIGGER))

  fun launch(context: Context, args: Bundle?): Intent {
    val route = route(args)
    return Intent(context, AssistantOverlayActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      .putExtra("mode", route.mode)
      .apply { if (route.navGesture) putExtra("gestureId", args?.getString(GESTURE_ID).orEmpty()) }
  }
}
