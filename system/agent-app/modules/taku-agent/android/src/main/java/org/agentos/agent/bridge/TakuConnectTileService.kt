package org.agentos.agent.bridge

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The "Taku Connect" Quick Settings tile. It shows how many Connect devices are enabled and opens
 * the Connect orbit (`taku-agent://connect`), collapsing the shade.
 *
 * The gateway read happens off the main thread; until it answers the tile shows the last known
 * count, and a failed read falls back to the plain title rather than a stale or invented number.
 */
class TakuConnectTileService : TileService() {
  private val main = Handler(Looper.getMainLooper())
  private val worker: ExecutorService = Executors.newSingleThreadExecutor()
  @Volatile private var listening = false

  override fun onStartListening() {
    super.onStartListening()
    listening = true
    render(TakuConnectTile.Display(cachedCount()))
    worker.execute {
      val display = TakuConnectTile.display(readList())
      main.post {
        if (!listening) return@post
        storeCount(display.enabledDevices)
        render(display)
      }
    }
  }

  override fun onStopListening() {
    listening = false
    super.onStopListening()
  }

  override fun onDestroy() {
    worker.shutdownNow()
    super.onDestroy()
  }

  override fun onClick() {
    super.onClick()
    if (isLocked) unlockAndRun { openConnect() } else openConnect()
  }

  private fun openConnect() {
    val spec = TakuConnectTile.launch(packageName)
    val intent = Intent(spec.action, Uri.parse(spec.uri)).setPackage(spec.packageName).addFlags(spec.flags)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
      startActivityAndCollapse(pending)
    } else {
      @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
      startActivityAndCollapse(intent)
    }
  }

  private fun render(display: TakuConnectTile.Display) {
    val tile = qsTile ?: return
    tile.label = getString(R.string.taku_connect_title)
    tile.state = Tile.STATE_ACTIVE
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      tile.subtitle = display.enabledDevices?.let { resources.getQuantityString(R.plurals.taku_connect_devices, it, it) }
    }
    tile.updateTile()
  }

  private fun readList(): String? = try {
    val response = contentResolver.call(Uri.parse("content://$AUTHORITY"), "connect/list", "{}", null)
    response?.getString("result")
  } catch (_: Exception) {
    null
  }

  private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
  private fun cachedCount(): Int? = prefs().getInt(KEY_COUNT, -1).takeIf { it >= 0 }
  private fun storeCount(count: Int?) {
    prefs().edit().apply { if (count == null) remove(KEY_COUNT) else putInt(KEY_COUNT, count) }.apply()
  }

  private companion object {
    const val AUTHORITY = "org.agentos.assistant.agent-gateway"
    const val PREFS = "taku_connect_tile"
    const val KEY_COUNT = "enabled_devices"
  }
}
