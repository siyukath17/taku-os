package org.agentos.agent.home

import org.agentos.agent.home.assistant.AssistantState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Issue #25 F3: an undelivered request comes back for edit / retry; a failed task is continued instead. */
class AssistantFailureTest {
  @Test fun microphonePermissionIsDistinctFromDisabledVoicePolicy() {
    assertEquals("VOICE_PERMISSION_REQUIRED", org.agentos.agent.home.assistant.AssistantSession.voiceError("PERMISSION_DENIED"))
    assertEquals("VOICE_DISABLED", org.agentos.agent.home.assistant.AssistantSession.voiceError("POLICY_DISABLED"))
    assertEquals("VOICE_BUSY", org.agentos.agent.home.assistant.AssistantSession.voiceError("RESOURCE_BUSY"))
  }
  // A delivered request that fails as a task or turn is continued, never put back or resent.
  @Test fun asyncTaskFailureKeepsTheDraftAndOffersContinue() {
    val s = AssistantState(threadId = "t", pending = "Find a flight to Kyoto", task = "failed", taskId = "42")
      .failedWith("TASK_FAILED", "Find a flight to Kyoto", "42")
    assertEquals("TASK_FAILED", s.error)
    assertEquals("", s.draft)
    assertNull(s.failedText)
    assertNull(s.retryText)
    assertEquals("42", s.failedTaskId)
    assertNull(s.pending)
    assertFalse(s.sending)
    check(s.canContinue && s.canRetry)
  }

  @Test fun aTaskFailureNeverTouchesANewerDraft() {
    val s = AssistantState(pending = "first", draft = "second thought").failedWith("TASK_FAILED", "first", "7")
    assertEquals("second thought", s.draft)
    assertNull(s.failedText)
    assertEquals("7", s.failedTaskId)
  }

  @Test fun turnFailureWaitsForItsTaskBeforeContinue() {
    val s = AssistantState(task = "failed").failedWith("TURN_FAILED", "  hello  ")
    assertEquals("", s.draft)
    assertNull(s.retryText)
    assertFalse(s.canContinue)
    assertFalse(s.canRetry)
    val known = s.failedWith("TASK_FAILED", null, "9")
    check(known.canContinue)
  }

  @Test fun continueIsNotOfferedWhileWorking() {
    val s = AssistantState(failedTaskId = "9", error = "TASK_FAILED", task = "queued")
    assertFalse(s.canContinue)
  }

  @Test fun aRejectedSendStillComesBackAsDraft() {
    val s = AssistantState().failedWith("DRAFT_REVISION_CONFLICT", "  hello  ")
    assertEquals("hello", s.draft)
    assertEquals("hello", s.retryText)
    assertNull(s.failedTaskId)
  }

  @Test fun unknownTextKeepsEarlierFailure() {
    val s = AssistantState(failedText = "earlier").failedWith("NETWORK_UNAVAILABLE", null)
    assertEquals("earlier", s.failedText)
    assertEquals("", s.draft)
  }

  // Issue #25 F3 follow-up: offline past the grace period ends "working" with the text restored.
  @Test fun givingUpOfflineEndsWorkingAndRestoresTheRequest() {
    val running = AssistantState(threadId = "t", taskId = "k", task = "running", pending = "Find a flight", online = false)
    check(running.working)
    val gaveUp = running.failedWith("NETWORK_UNAVAILABLE", running.pending).copy(abandoned = true)
    assertFalse(gaveUp.working)
    assertEquals("NETWORK_UNAVAILABLE", gaveUp.error)
    assertEquals("Find a flight", gaveUp.draft)
    assertEquals("Find a flight", gaveUp.retryText)
    // The broker still reports the task running (it never fails offline): still not working.
    assertFalse(gaveUp.copy(task = "running").working)
  }

  @Test fun anAbandonedActiveTurnIsNotWorkingAndKeepsANewerDraft() {
    val turn = org.agentos.agent.home.assistant.TurnView(turnId = "u1", active = true, failed = false, userText = "Book it", headline = null, reply = null, steps = emptyList(), suggestions = emptyList())
    val s = AssistantState(turn = turn, draft = "something newer").failedWith("NETWORK_UNAVAILABLE", turn.userText).copy(abandoned = true)
    assertFalse(s.working)
    assertEquals("something newer", s.draft)
    assertEquals("Book it", s.failedText)
  }

  @Test fun aNewSendClearsAbandoned() {
    val s = AssistantState(abandoned = true, error = "NETWORK_UNAVAILABLE").copy(sending = true, pending = "again", error = null, abandoned = false)
    check(s.working)
  }
}
