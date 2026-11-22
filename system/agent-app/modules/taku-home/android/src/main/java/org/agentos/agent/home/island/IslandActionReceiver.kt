package org.agentos.agent.home.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.agentos.agent.home.assistant.AssistantSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class IslandActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val session = AssistantSession.get(context)
    val thread = intent.getStringExtra("thread") ?: return
    val activity = intent.getStringExtra("activity") ?: return
    if (session.state.value.threadId == null && thread != "entry" &&
        context.getSharedPreferences("system_island_session", Context.MODE_PRIVATE).getString("thread", null) == thread) {
      session.resume(thread)
      session.restoreIslandIdentity(context.getSharedPreferences("system_island_session", Context.MODE_PRIVATE).getString("activity", null))
    }
    if ((session.state.value.threadId ?: "entry") != thread || (session.state.value.activityId ?: "entry") != activity) return
    val pending = goAsync()
    scope.launch {
      try {
        withTimeout(5000) {
          session.state.first { state -> (state.threadId ?: "entry") != thread || (state.activityId ?: "entry") != activity || when (intent.action) {
            "stop" -> state.taskId != null || state.turn?.active == true
            "reply" -> state.turn?.suggestions?.any { it.id == intent.getStringExtra("suggestion") } == true
            "retry" -> state.canRetry
            else -> true
          } }
        }
        if ((session.state.value.threadId ?: "entry") != thread || (session.state.value.activityId ?: "entry") != activity) return@launch
        when (intent.action) {
          "open" -> session.openConversation()
          "stop" -> session.stop()
          "reply" -> session.reply(intent.getStringExtra("suggestion").orEmpty())
          "retry" -> session.retry()
        }
      } catch (failure: Exception) { android.util.Log.w("TakuIsland", "Action rejected", failure) }
      finally { pending.finish() }
    }
  }
  companion object { private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
}
