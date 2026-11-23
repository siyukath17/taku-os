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

  /** A `connect/list` envelope in the broker's real shape: routes, appearance (with shortName) and a demo node. */
  @Test fun countsBrokerShapedNodesIncludingSwitchedOnDemos() {
    val real = """{"id":"taku_mini","url":"http://192.168.1.5:7878","host":"192.168.1.5","network":"lan",""" +
      """"routes":[{"label":"局域网","kind":"lan","url":"http://192.168.1.5:7878"}],"enabled":true,"hasToken":true,""" +
      """"appearance":{"displayName":"书房 Mac mini","shortName":"Mac mini","deviceType":"mac_mini"}}"""
    val parked = """{"id":"taku_tv","url":"http://192.168.1.30:7878","enabled":false,"hasToken":true,"appearance":{}}"""
    // A demo node counts while switched on, exactly as the Connect orbit shows it online.
    val demoOn = """{"id":"demo_tesla","demo":true,"enabled":true,"demoStatus":"82% · 410 km","appearance":{"shortName":"Tesla"}}"""
    val demoOff = """{"id":"demo_tv","demo":true,"enabled":false,"appearance":{}}"""
    assertEquals(2, TakuConnectTile.enabledDevices("""{"ok":true,"result":{"nodes":[$real,$parked,$demoOn,$demoOff]}}"""))
  }

  @Test fun malformedEntriesAndMissingEnabledAreSkipped() {
    val nodes = """[{"id":"b","enabled":1},{"id":"c"},null,"taku_x",[true],{"id":"d","enabled":true}]"""
    assertEquals(1, TakuConnectTile.enabledDevices("""{"ok":true,"result":{"nodes":$nodes}}"""))
  }

  @Test fun aMissingOkFlagIsAFailureNotZero() {
    assertNull(TakuConnectTile.enabledDevices("""{"result":{"nodes":[{"id":"a","enabled":true}]}}"""))
    assertNull(TakuConnectTile.enabledDevices("""{"ok":true,"result":{"nodes":{"id":"a"}}}"""))
  }

  @Test fun clickOpensTheConnectOrbitInThisApp() {
    val launch = TakuConnectTile.launch("org.agentos.agent")
    assertEquals(Intent.ACTION_VIEW, launch.action)
    assertEquals("taku-agent://connect", launch.uri)
    assertEquals("org.agentos.agent", launch.packageName)
    assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, launch.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
  }
}
