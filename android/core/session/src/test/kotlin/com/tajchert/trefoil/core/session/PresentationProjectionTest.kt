package com.tajchert.trefoil.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class PresentationProjectionTest {
    private fun fixture(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test fun shellPreservesOrderingAndAttentionMetadataWithoutInventingMissingDates() {
        val thread = threadSummary(fixture("""{
            "id":"t", "projectId":"p", "title":"Design", "status":"waiting",
            "createdAt":"2026-11-01T10:00:00Z", "updatedAt":"2026-11-06T10:00:00Z",
            "latestUserAuthoredMessageAt":"2026-11-05T10:00:00Z", "pinnedAt":"2026-11-02T10:00:00Z",
            "pinOrderKey":"a0", "activeOrderKey":"b0", "settledOverride":"active",
            "snoozedUntil":"2026-11-07T10:00:00Z", "unsettledAt":"2026-11-06T11:00:00Z",
            "latestRunRequestedAt":"2026-11-06T09:00:00Z", "latestRunCompletedAt":"2026-11-06T10:00:00Z",
            "pendingRuntimeRequest":{"kind":"approval"}, "latestVisibleMessage":{"text":"Ready"},
            "modelSelection":{"model":"fixture-model"}, "branch":"feature/ui", "futureField":true
        }"""))
        assertEquals("waiting", thread.status)
        assertEquals("2026-11-05T10:00:00Z", thread.latestUserAuthoredMessageAt)
        assertEquals("2026-11-07T10:00:00Z", thread.snoozedUntil)
        assertEquals("2026-11-06T11:00:00Z", thread.unsettledAt)
        assertEquals("2026-11-06T09:00:00Z", thread.latestRunRequestedAt)
        assertEquals("2026-11-06T10:00:00Z", thread.latestRunCompletedAt)
        assertEquals("a0", thread.pinOrderKey)
        assertEquals("b0", thread.activeOrderKey)
        assertEquals("approval", thread.pendingRequestKind)
        assertEquals("Ready", thread.latestMessage)
        assertEquals("fixture-model", thread.model)
        assertEquals("feature/ui", thread.branch)
        assertFalse(thread.archived)
        assertNull(thread.archivedAt)
        assertNull(thread.settledAt)
    }

    @Test fun archivedSnapshotMembershipAndArchivedTimestampBothIdentifyArchivedThreads() {
        val legacy = fixture("""{"id":"a","projectId":"p","title":"Archived","runSummary":{"status":"completed"}}""")
        assertTrue(threadSummary(legacy, archived = true).archived)
        assertEquals("completed", threadSummary(legacy).status)
        assertNull(threadSummary(legacy).createdAt)
        assertTrue(threadSummary(fixture("""{"archivedAt":"2026-11-01T00:00:00Z"}""")).archived)
    }

    @Test fun messagesResolveTimelineOrdinalFromMatchingItemsAndKeepSourceOrder() {
        val projection = fixture("""{
            "messages":[
              {"id":"user","role":"user","text":"Ask","runId":null,"createdAt":"2026-11-06T10:00:00Z"},
              {"id":"answer","role":"assistant","text":"Done","streaming":true,"runId":"run"}],
            "turnItems":[
              {"id":"u","type":"user_message","messageId":"user","ordinal":4,"runId":"run"},
              {"id":"tool","type":"command_execution","ordinal":5,"runId":"run","title":"Run tests","input":"./gradlew test","output":"Passed","status":"completed","startedAt":"2026-11-06T10:00:01Z","completedAt":"2026-11-06T10:00:02Z"},
              {"id":"a","type":"assistant_message","messageId":"answer","ordinal":6,"runId":"run"}]
        }""")
        val messages = conversationMessages(projection)
        assertEquals(listOf("user", "answer"), messages.map { it.id })
        assertEquals(listOf(4, 6), messages.map { it.ordinal })
        assertEquals("run", messages.first().runId)
        assertTrue(messages.last().streaming)
        val activity = toolActivities(projection).single()
        assertEquals(5, activity.ordinal)
        assertEquals("Run tests", activity.title)
        assertEquals("./gradlew test\n\nPassed", activity.detail)
        assertEquals("completed", activity.status)
        assertEquals("command_execution", activity.kind)
        assertEquals("run", activity.runId)
        assertEquals("2026-11-06T10:00:02Z", activity.completedAt)
    }

    @Test fun additiveUnknownActivityAndLegacyMessagesRemainReadable() {
        val projection = fixture("""{"messages":[{"id":"m","role":"assistant","text":"Hello"}],"turnItems":[{"id":"future","type":"future_activity","text":"detail","future":{"value":1}}]}""")
        val message = conversationMessages(projection).single()
        assertNull(message.ordinal)
        assertNull(message.runId)
        assertFalse(message.streaming)
        val activity = toolActivities(projection).single()
        assertEquals("future activity", activity.title)
        assertEquals("detail", activity.detail)
        assertNull(activity.ordinal)
        assertNull(activity.startedAt)
    }
    @Test fun visibleProjectionPositionsOverrideSourceOrdinalsAndHideNonVisibleRows() {
        val projection = fixture("""{
          "messages":[{"id":"hidden","role":"assistant","text":"hidden"},{"id":"local","role":"assistant","text":"Canonical body"}],
          "turnItems":[{"id":"hidden-tool","type":"command_execution","ordinal":2,"input":"hidden"}],
          "visibleTurnItems":[
            {"position":9,"sourceThreadId":"local-thread","sourceItemId":"local-item","item":{"id":"local-item","type":"assistant_message","messageId":"local","ordinal":0,"runId":"local-run","text":""}},
            {"position":8,"sourceThreadId":"local-thread","sourceItemId":"tool-item","item":{"id":"tool-item","type":"dynamic_tool","toolName":"Read source","ordinal":1,"runId":"local-run","status":"completed","input":{"path":"src/main.kt"},"output":[{"text":"ok"}]}},
            {"position":3,"sourceThreadId":"parent-thread","sourceItemId":"inherited-item","item":{"id":"inherited-item","type":"user_message","messageId":"inherited","ordinal":100,"text":"Inherited prompt","runId":"parent-run","startedAt":"2026-11-06T10:00:00Z"}}
          ]
        }""")
        val messages = conversationMessages(projection)
        assertEquals(listOf("inherited", "local"), messages.map { it.id })
        assertEquals(listOf(3, 9), messages.map { it.ordinal })
        assertEquals("Inherited prompt", messages.first().text)
        assertEquals("Canonical body", messages.last().text)
        val activity = toolActivities(projection).single()
        assertEquals("Read source", activity.title)
        assertEquals("completed", activity.status)
        assertEquals(8, activity.ordinal)
        assertEquals("{\"path\":\"src/main.kt\"}\n\n[{\"text\":\"ok\"}]", activity.detail)
    }

    @Test fun emptyVisibleProjectionIsAuthoritative() {
        val projection = fixture("""{"messages":[{"id":"hidden","role":"assistant","text":"hidden"}],"turnItems":[{"id":"hidden-tool","type":"command_execution","input":"hidden"}],"visibleTurnItems":[]}""")
        assertTrue(conversationMessages(projection).isEmpty())
        assertTrue(toolActivities(projection).isEmpty())
    }

    @Test fun shellDistinguishesThreadWaitFromRuntimeApprovalAndPreservesActivityStatus() {
        val waiting = threadSummary(fixture("""{"status":"completed","activityRunStatus":"waiting","blockedOnThreadWait":true}"""))
        assertEquals("completed", waiting.status)
        assertEquals("waiting", waiting.activityRunStatus)
        assertEquals(true, waiting.blockedOnThreadWait)
        assertNull(waiting.pendingRequestKind)
        assertNull(threadSummary(fixture("""{}""")).blockedOnThreadWait)
        assertEquals(false, threadSummary(fixture("""{"blockedOnThreadWait":false}""")).blockedOnThreadWait)
    }

}
