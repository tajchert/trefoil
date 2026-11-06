package com.tajchert.trefoil.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class PresentationProjectionTest {
    private fun fixture(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test fun providerCatalogAndThreadMetadataKeepRoutingIdentitySeparateFromModelNames() {
        val providers = providerInstances(fixture("""{"providers":[
          {"instanceId":"work-account","driver":"codex","displayName":"Work","accentColor":"#123456","enabled":false},
          {"instanceId":"custom-server","driver":"claudeAgent","enabled":true}
        ]}"""))
        assertEquals(listOf("work-account", "custom-server"), providers.map { it.instanceId })
        assertEquals("codex", providers.first().driver)
        assertEquals("Work", providers.first().displayName)
        assertEquals("#123456", providers.first().accentColor)
        assertFalse(providers.first().enabled)
        val thread = threadSummary(fixture("""{"providerInstanceId":"work-account",
          "modelSelection":{"instanceId":"custom-server","model":"codex-named-model","options":{"effort":"high"}},
          "providerInstanceHistory":["old-account","work-account"],
          "latestRunId":"latest","activeRunId":"active","latestRunStartedAt":"2026-11-06T10:00:00Z",
          "activityRunStartedAt":null}"""))
        assertEquals("work-account", thread.providerInstanceId)
        assertEquals("custom-server", thread.modelInstanceId)
        assertEquals(listOf("old-account", "work-account"), thread.providerInstanceHistory)
        assertEquals("latest", thread.latestRunId)
        assertEquals("active", thread.activeRunId)
        assertEquals("2026-11-06T10:00:00Z", thread.latestRunStartedAt)
        assertTrue(thread.activityRunStartedAtPresent)
        assertNull(thread.activityRunStartedAt)
        assertFalse(threadSummary(fixture("""{}""")).activityRunStartedAtPresent)
    }

    @Test fun runsAndAttemptsPreserveCompletionAndRecoveryBoundaries() {
        val projection = fixture("""{"runs":[{"id":"run","ordinal":2,"status":"completed",
          "requestedAt":"2026-11-06T10:00:00Z","startedAt":"2026-11-06T10:00:01Z",
          "completedAt":"2026-11-06T10:00:06Z","workStartedAt":"2026-11-06T09:59:00Z",
          "activeAttemptId":"retry","userMessageId":"prompt","providerInstanceId":"work",
          "modelSelection":{"instanceId":"work","model":"model"}}],
          "attempts":[{"id":"initial","runId":"run","attemptOrdinal":1,"status":"superseded","reason":"initial"},
          {"id":"retry","runId":"run","attemptOrdinal":2,"status":"completed","reason":"provider_recovery",
          "rootNodeId":"node","providerTurnId":"turn","providerInstanceId":"work",
          "startedAt":"2026-11-06T10:00:03Z","completedAt":"2026-11-06T10:00:06Z"}]}""")
        val run = conversationRuns(projection).single()
        assertEquals("run", run.id)
        assertEquals("completed", run.status)
        assertEquals("2026-11-06T10:00:00Z", run.requestedAt)
        assertEquals("2026-11-06T10:00:01Z", run.startedAt)
        assertEquals("2026-11-06T10:00:06Z", run.completedAt)
        assertEquals("2026-11-06T09:59:00Z", run.workStartedAt)
        assertEquals("retry", run.activeAttemptId)
        assertEquals("prompt", run.userMessageId)
        assertEquals("work", run.providerInstanceId)
        val attempts = runAttempts(projection)
        assertEquals(listOf("initial", "retry"), attempts.map { it.id })
        assertEquals(listOf(1, 2), attempts.map { it.attemptOrdinal })
        assertEquals("provider_recovery", attempts.last().reason)
        assertEquals("turn", attempts.last().providerTurnId)
        assertEquals("2026-11-06T10:00:06Z", attempts.last().completedAt)
    }

    @Test fun activityDetailsAndAttemptIdsUseStructuredWireFields() {
        val projection = fixture("""{"messages":[{"id":"answer","role":"assistant","text":"done"}],
          "attempts":[{"id":"retry","runId":"run","rootNodeId":"root","status":"completed"}],
          "providerTurns":[{"id":"turn","runAttemptId":"retry"}],"turnItems":[
          {"id":"command","type":"command_execution","runId":"run","providerTurnId":"turn","nodeId":"node",
          "input":"./gradlew test","output":"failed","exitCode":1,"outputIndicatesFailure":true,"status":"completed"},
          {"id":"file","type":"file_change","fileName":"Main.kt","diffStr":"+hello","oldStr":"old","newStr":"new"},
          {"id":"search","type":"file_search","pattern":"hello","results":[{"fileName":"Main.kt","line":7,"preview":"hello"}]},
          {"id":"error","type":"error","failure":{"class":"unknown","message":"Process failed","code":null,"retryable":false}},
          {"id":"a","type":"assistant_message","messageId":"answer","providerTurnId":"turn","runId":"run"}
        ]}""")
        val command = toolActivities(projection).first()
        assertEquals("./gradlew test", command.input)
        assertEquals("failed", command.output)
        assertEquals(1, command.exitCode)
        assertEquals(true, command.outputIndicatesFailure)
        assertEquals("retry", command.runAttemptId)
        assertEquals("node", command.nodeId)
        val file = toolActivities(projection)[1]
        assertEquals("Main.kt", file.fileName)
        assertEquals("+hello", file.diffStr)
        assertTrue(file.detail.contains("+hello"))
        assertTrue(toolActivities(projection)[2].searchResults.orEmpty().contains("Main.kt"))
        assertEquals("hello", toolActivities(projection)[2].input)
        assertEquals("Process failed", toolActivities(projection)[3].failure)
        assertEquals("retry", conversationMessages(projection).single().runAttemptId)
    }

    @Test fun omittedOutputHydrationPreservesProjectedPositionAndSourceIdentity() {
        val projection = fixture("""{"visibleTurnItems":[{"position":8,"sourceThreadId":"parent","sourceItemId":"source",
          "item":{"id":"projected","type":"command_execution","threadId":"parent","runId":"run","ordinal":91,
          "status":"completed","updatedAt":"2026-11-06T10:00:00Z","input":"test","outputOmitted":true}}]}""")
        val full = fixture("""{"id":"source","type":"command_execution","threadId":"parent","runId":"run","ordinal":91,
          "status":"completed","updatedAt":"2026-11-06T10:00:00Z","input":"test","output":"Test output","exitCode":0}""")
        val hydrated = toolActivities(hydrateActivityProjection(projection, "projected", full)).single()
        assertEquals("projected", hydrated.id)
        assertEquals(8, hydrated.ordinal)
        assertEquals("parent", hydrated.sourceThreadId)
        assertEquals("source", hydrated.sourceItemId)
        assertEquals("run", hydrated.runId)
        assertEquals("completed", hydrated.status)
        assertEquals("Test output", hydrated.output)
        assertFalse(hydrated.outputOmitted)
    }

    @Test fun attemptResolutionTraversesExecutionNodesAndRejectsDifferentRunsAndCycles() {
        val projection = fixture("""{"attempts":[{"id":"retry","runId":"run","rootNodeId":"root","status":"completed"}],
          "nodes":[{"id":"child","rootNodeId":"root","parentNodeId":null},
          {"id":"parent","rootNodeId":"other","parentNodeId":"child"},
          {"id":"cycle","rootNodeId":"unknown","parentNodeId":"cycle"}],"turnItems":[
          {"id":"tool","type":"dynamic_tool","nodeId":"parent","runId":"run"},
          {"id":"other","type":"dynamic_tool","nodeId":"child","runId":"different-run"},
          {"id":"cycle-tool","type":"dynamic_tool","nodeId":"cycle","runId":"run"}
        ]}""")
        val activities = toolActivities(projection)
        assertEquals("retry", activities[0].runAttemptId)
        assertNull(activities[1].runAttemptId)
        assertNull(activities[2].runAttemptId)
    }

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
