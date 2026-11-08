package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ThreadSettingsTest {
    private fun projection(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test fun threadThatNeverRanIsNotLocked() {
        assertFalse(providerLocked(projection("""{"thread":{"id":"t"},"runs":[]}""")))
        assertFalse(providerLocked(JsonObject(emptyMap())))
    }

    @Test fun activeRunWithoutSessionLocks() {
        assertTrue(providerLocked(projection("""{"thread":{"id":"t"},"runs":[{"id":"r","status":"preparing"}]}""")))
    }

    @Test fun sessionCapabilityDecidesAndMissingCapabilityLocks() {
        fun withSession(capabilities: String) = projection("""{"thread":{"id":"t","activeProviderThreadId":"pt"},"runs":[{"id":"r","status":"completed"}],
            "providerThreads":[{"id":"pt","appThreadId":"t","providerSessionId":"s","providerInstanceId":"codex"}],
            "providerSessions":[{"id":"s","status":"ready","capabilities":$capabilities}]}""")
        assertFalse(providerLocked(withSession("""{"sessions":{"supportsProviderSwitchingViaHandoff":true}}""")))
        assertTrue(providerLocked(withSession("""{"sessions":{"supportsProviderSwitchingViaHandoff":false}}""")))
        assertTrue(providerLocked(withSession("""{}""")))
    }

    @Test fun finishedThreadIsFreeOnlyWhileItsNativeThreadCanBeHandedOff() {
        fun finished(nativeRef: String) = projection("""{"thread":{"id":"t","activeProviderThreadId":"pt","modelSelection":{"instanceId":"codex","model":"m"}},
            "runs":[{"id":"r","status":"completed"}],
            "providerThreads":[{"id":"pt","appThreadId":"t","providerSessionId":null,"providerInstanceId":"codex","nativeThreadRef":$nativeRef}],
            "providerSessions":[]}""")
        assertFalse(providerLocked(finished("""{"id":"native"}""")))
        assertTrue(providerLocked(finished("null")))
        assertFalse(providerLocked(projection("""{"thread":{"id":"t","historyOrigin":"v1_import"},"runs":[{"id":"r","status":"completed"}]}""")))
    }

    @Test fun setupStatusesQueueEvenWhenTheProjectionLags() {
        val idle = ClientState(selectedThreadId = "t", threads = listOf(ThreadSummary("t", "p", "T")))
        assertEquals("start_immediately", messageDispatchMode(idle))
        assertEquals("queue_after_active", messageDispatchMode(idle.copy(runs = listOf(ConversationRun("r", "starting")))))
        assertEquals("queue_after_active", messageDispatchMode(idle.copy(threads = listOf(ThreadSummary("t", "p", "T", activityRunStatus = "preparing")))))
        assertEquals("queue_after_active", messageDispatchMode(idle.copy(threads = listOf(ThreadSummary("t", "p", "T", status = "starting")))))
        assertEquals("queue_after_active", messageDispatchMode(idle.copy(activeRunId = "r")))
        assertEquals("start_immediately", messageDispatchMode(idle.copy(runs = listOf(ConversationRun("r", "completed")))))
    }

    @Test fun compatibleRuntimeModeFallsBackToFirstAllowed() {
        val limited = ModelOption("codex", "m", supportedRuntimeModes = listOf(RuntimeMode.FullAccess, RuntimeMode.ApprovalRequired))
        assertEquals(RuntimeMode.ApprovalRequired, compatibleRuntimeMode(RuntimeMode.Auto, limited))
        assertEquals(RuntimeMode.FullAccess, compatibleRuntimeMode(RuntimeMode.FullAccess, limited))
        assertEquals(RuntimeMode.Auto, compatibleRuntimeMode(RuntimeMode.Auto, ModelOption("x", "y")))
    }

    @Test fun onlyAnInterruptOnlyExitIsUncertain() {
        assertTrue(exitInterruptedOnly(projection("""{"_tag":"Failure","cause":[{"_tag":"Interrupt"}]}""")))
        assertFalse(exitInterruptedOnly(projection("""{"_tag":"Failure","cause":[{"_tag":"Fail","error":{}}]}""")))
        assertFalse(exitInterruptedOnly(projection("""{"_tag":"Failure","cause":[{"_tag":"Die","defect":"x"},{"_tag":"Interrupt"}]}""")))
        assertFalse(exitInterruptedOnly(projection("""{"_tag":"Failure","cause":{"_tag":"Fail"}}""")))
        assertFalse(exitInterruptedOnly(projection("""{"_tag":"Failure","cause":{}}""")))
    }
}
