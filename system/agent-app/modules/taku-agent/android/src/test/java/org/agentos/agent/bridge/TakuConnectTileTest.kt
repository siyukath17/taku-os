package org.agentos.agent.bridge

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TakuConnectTileTest {
  private fun node(enabled: Boolean) = """{"id":"n","url":"http://h","enabled":$enabled,"hasToken":true}"""
  private fun list(vararg enabled: Boolean) = """{"ok":true,"result":{"nodes":[${enabled.joinToString(",") { node(it) }}]}}"""

  @Test fun countsOnlyEnabledNodes() {
    assertEquals(5, TakuConnectTile.enabledDevices(list(true, true, false, true, true, true)))
  }

  @Test fun anEmptyListIsZeroDevicesNotAFailure() {
    assertEquals(0, TakuConnectTile.enabledDevices(list()))
  }

  @Test fun aGatewayErrorDegradesToThePlainTitle() {
    assertNull(TakuConnectTile.display("""{"ok":false,"error":{"code":"GATEWAY_UNAVAILABLE"}}""").enabledDevices)
  }

  @Test fun anUnreadableResponseDegradesToThePlainTitle() {
    assertNull(TakuConnectTile.display(null).enabledDevices)
    assertNull(TakuConnectTile.display("not json").enabledDevices)
    assertNull(TakuConnectTile.display("""{"ok":true,"result":{}}""").enabledDevices)
  }

  @Test fun clickOpensTheConnectOrbitInThisApp() {
    val launch = TakuConnectTile.launch("org.agentos.agent")
    assertEquals(Intent.ACTION_VIEW, launch.action)
    assertEquals("taku-agent://connect", launch.uri)
    assertEquals("org.agentos.agent", launch.packageName)
    assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, launch.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
  }
}
