package org.agentos.agent.home.ui

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.interfaces.fabric.ReactSurface

/**
 * Keeps one running React Native surface (`TakuFeedCard`) per visible feed card. Lazy lists
 * dispose items that scroll away; detached surfaces stay alive (bounded LRU) so scrolling
 * back does not re-run the card's JavaScript.
 */
class ReactCardPool(private val activity: Activity, private val limit: Int = 14) {
  val host: ReactHost? = (activity.application as? ReactApplication)?.reactHost
  private val surfaces = LinkedHashMap<String, ReactSurface>(16, 0.75f, true)
  private val attached = HashMap<String, Int>()

  /** The card's surface view, detached from any previous parent; null when React Native is unavailable. */
  fun acquire(cardId: String): View? = acquire(cardId, COMPONENT, Bundle().apply { putString("cardId", cardId) })

  /**
   * The root surface of one desktop Panel page (`TakuPanel`, props `{panelId}`); null when React
   * Native is unavailable. Each page owns this root and its subscriptions, so pages mount and
   * release independently.
   */
  /** [bottomInset] (dp) is the height of the bottom glass the page body runs under; the root pads its scroll end by it. */
  fun acquirePanel(panelId: String, bottomInset: Float): View? = acquire(panelKey(panelId), PANEL_COMPONENT, Bundle().apply {
    putString("panelId", panelId)
    putDouble("bottomInset", bottomInset.toDouble())
  })

  /** Stops one Panel page's root and nothing else; the page's next mount creates a fresh surface. */
  fun releasePanel(panelId: String) {
    release(panelKey(panelId))
    drop(panelKey(panelId))
  }

  private fun panelKey(panelId: String) = "$PANEL_COMPONENT:$panelId"

  private fun acquire(key: String, component: String, props: Bundle): View? {
    val reactHost = host ?: return null
    return try {
      val surface = surfaces.getOrPut(key) { reactHost.createSurface(activity, component, props).also { it.start() } }
      attached[key] = (attached[key] ?: 0) + 1
      surface.view?.also { (it.parent as? ViewGroup)?.removeView(it) }
    } catch (e: RuntimeException) {
      Log.e(TAG, "cannot start surface $key", e)
      null
    }
  }

  fun release(cardId: String) {
    val count = (attached[cardId] ?: 0) - 1
    if (count <= 0) attached.remove(cardId) else attached[cardId] = count
    trim()
  }

  /** Stops a surface immediately (card removed or retracted). */
  fun drop(cardId: String) {
    if (cardId in attached) return
    surfaces.remove(cardId)?.stop()
  }

  private fun trim() {
    while (surfaces.size > limit) {
      val victim = surfaces.keys.firstOrNull { it !in attached } ?: return
      surfaces.remove(victim)?.stop()
    }
  }

  fun destroy() {
    surfaces.values.forEach { runCatching { it.stop() } }
    surfaces.clear(); attached.clear()
  }

  private companion object {
    const val COMPONENT = "TakuFeedCard"
    const val PANEL_COMPONENT = "TakuPanel"
    const val TAG = "TakuHomeCards"
  }
}
