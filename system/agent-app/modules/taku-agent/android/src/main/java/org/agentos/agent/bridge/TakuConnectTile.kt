package org.agentos.agent.bridge

import android.content.Intent
import org.json.JSONObject

/**
 * Pure state for the Taku Connect Quick Settings tile, kept free of framework calls so the
 * subtitle rule and the launch target are unit-tested without a device.
 *
 * The count comes from the gateway's `connect/list` (the same authority the Connect screen reads),
 * and only enabled nodes count: a disabled node is a configured device the user switched off.
 */
internal object TakuConnectTile {
  const val CONNECT_URI = "taku-agent://connect"

  /** What the tile shows: always active, with a device count when one is known. */
  data class Display(val enabledDevices: Int?)

  /** Enabled node count from a `connect/list` gateway envelope, or null when it is unusable. */
  fun enabledDevices(envelope: String?): Int? = try {
    val response = JSONObject(envelope ?: return null)
    if (!response.optBoolean("ok")) null
    else {
      val nodes = response.optJSONObject("result")?.optJSONArray("nodes")
      nodes?.let { list -> (0 until list.length()).count { list.optJSONObject(it)?.optBoolean("enabled") == true } }
    }
  } catch (_: Exception) {
    null
  }

  /** A failed or unknown read degrades to the plain title; zero devices still reads as a count. */
  fun display(envelope: String?): Display = Display(enabledDevices(envelope))

  /** The activity the tile opens: this app's own deep link to the Connect orbit, in a new task. */
  data class Launch(val action: String, val uri: String, val packageName: String, val flags: Int)

  fun launch(packageName: String): Launch = Launch(
    Intent.ACTION_VIEW,
    CONNECT_URI,
    packageName,
    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
  )
}
