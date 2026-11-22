package org.agentos.agent.home

import org.agentos.agent.home.assistant.SystemAssistantEntry
import org.agentos.agent.home.assistant.SystemAssistantEntry.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemAssistantRouteTest {
  @Test fun powerKeyOpensTextOverlayInPlaceWithOneShotScreenshot() {
    // Long-press power / invocation_type 6 carries no navigation-handle trigger.
    val route = SystemAssistantEntry.routeFor(null)
    assertEquals(Route.Hardware, route)
    assertEquals("text", route.mode)
    assertFalse("hardware entry is not a held gesture", route.navGesture)
    assertTrue(route.autoScreenshot)
    assertEquals(Route.Hardware, SystemAssistantEntry.routeFor(""))
    assertEquals(Route.Hardware, SystemAssistantEntry.routeFor("unknown_trigger"))
  }

  @Test fun navigationHandleGesturesKeepTheirModesAndNeverAutoAttach() {
    val hold = SystemAssistantEntry.routeFor("nav_handle_long_press")
    assertEquals(Route.NavHold, hold)
    assertEquals("voice", hold.mode)
    assertTrue(hold.navGesture)
    assertFalse(hold.autoScreenshot)
    val tap = SystemAssistantEntry.routeFor("nav_handle_double_tap")
    assertEquals(Route.NavDoubleTap, tap)
    assertEquals("text", tap.mode)
    assertTrue(tap.navGesture)
    assertFalse(tap.autoScreenshot)
  }
}
