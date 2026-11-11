package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.*
import org.junit.Assert.*
import org.junit.Test

class SubagentPresentationTest {
    private fun at(sec: Int) = "2026-06-20T00:00:%02d.000Z".format(sec)
    private fun agent(id: String, status: String = "running", runId: String? = "run-1", startedAt: String? = at(0), updatedAt: String? = at(1), workflow: SubagentWorkflow? = null) =
        Subagent(id, "thread-1", runId, "app_owned", "codex", childThreadId = "child", prompt = "Do the thing", title = "Worker", status = status, startedAt = startedAt, updatedAt = updatedAt, workflow = workflow)
    private fun run(id: String, status: String, ordinal: Int = id.removePrefix("run-").toInt(), started: Boolean = true) =
        ConversationRun(id, status, startedAt = if (started) at(0) else null, ordinal = ordinal)
    private val finished = run("run-1", "completed")
    private val active = run("run-1", "running")
    private val wf = SubagentWorkflow()
    private fun ids(r: SubagentRoster?) = r?.subagents?.map { it.id }
    private fun pill(runs: List<ConversationRun>, vararg s: Subagent) = subagentPill(subagentRoster(runs, s.toList()))?.label

    @Test fun emptyListHasNoRoster() { assertNull(subagentRoster(listOf(active), emptyList())); assertNull(subagentPill(null)) }

    @Test fun workflowsStayAfterLaterTurn() {
        for (status in listOf("running", "completed")) {
            val r = subagentRoster(listOf(finished, run("run-2", status)), listOf(
                agent("workflow", "completed", workflow = wf), agent("old", "completed"),
                agent("recent", status, runId = "run-2", updatedAt = at(9))))
            assertEquals(listOf("recent", "workflow"), ids(r))
            assertNotNull(subagentPill(r))
        }
    }

    @Test fun activeRunWinsAndSortsByStart() {
        val r = subagentRoster(listOf(run("run-0", "completed"), active), listOf(
            agent("b", startedAt = at(5)), agent("old", "completed", runId = "run-0"), agent("a", startedAt = at(2))))
        assertEquals("run-1", r?.runId); assertEquals(listOf("a", "b"), ids(r)); assertTrue(r!!.turnActive)
    }

    @Test fun sortTiesBreakById() {
        val r = subagentRoster(listOf(active), listOf(agent("z"), agent("c"), agent("m")))
        assertEquals(listOf("c", "m", "z"), ids(r))
    }

    @Test fun fallsBackToMostRecentlyUpdatedRun() {
        val r = subagentRoster(listOf(finished, run("run-2", "completed")), listOf(
            agent("old", "completed", updatedAt = at(1)), agent("recent", "completed", runId = "run-2", updatedAt = at(9))))
        assertEquals("run-2", r?.runId); assertFalse(r!!.turnActive); assertEquals(listOf("recent"), ids(r))
    }

    @Test fun listsEveryLiveAgentFromEarlierTurns() {
        val runs = listOf("run-103", "run-105", "run-107", "run-109").map { run(it, "completed") }
        val r = subagentRoster(runs, listOf(
            agent("settled-103", "completed", "run-103", at(0), at(4)), agent("bg-103", "running", "run-103", at(1), at(5)),
            agent("bg-105", "running", "run-105", at(2), at(6)), agent("bg-107", "running", "run-107", at(3), at(7))))
        assertEquals(listOf("bg-103", "bg-105", "bg-107"), ids(r)); assertEquals("3/3", subagentPill(r)?.label)
    }

    @Test fun activeTurnKeepsEarlierLiveAgent() {
        val r = subagentRoster(listOf(run("run-0", "completed"), active), listOf(
            agent("background", runId = "run-0"), agent("earlier-done", "completed", "run-0"),
            agent("working", startedAt = at(5)), agent("done", "completed", startedAt = at(6))))
        assertEquals(listOf("background", "working", "done"), ids(r)); assertEquals("2/3", subagentPill(r)?.label)
        assertEquals("2 of 3 agents working", subagentPill(r)?.accessibilityLabel)
    }

    @Test fun rolledBackRunsDropTheirAgents() {
        val r = subagentRoster(listOf(run("run-0", "rolled_back"), finished), listOf(agent("rolled-back", runId = "run-0"), agent("live", updatedAt = at(9))))
        assertEquals(listOf("live"), ids(r))
    }

    @Test fun workflowMembersWithoutRunAreListed() {
        val r = subagentRoster(emptyList(), listOf(agent("m0", runId = null, startedAt = at(1)), agent("m1", "completed", null, at(2))))
        assertEquals(listOf("m0", "m1"), ids(r)); assertEquals("1/2", subagentPill(r)?.label)
    }

    @Test fun pillShowsFinishedAgentsWhileLatestTurn() {
        assertEquals("2 done", pill(listOf(finished), agent("a", "completed"), agent("b", "failed")))
        assertEquals("1 agent done", subagentPill(subagentRoster(listOf(finished), listOf(agent("a", "completed"))))?.accessibilityLabel)
        assertEquals("2 agents done", subagentPill(subagentRoster(listOf(finished), listOf(agent("a", "completed"), agent("b", "failed"))))?.accessibilityLabel)
    }

    @Test fun pillHidesAfterLaterTurnRanWithoutAgents() {
        assertNull(pill(listOf(finished, run("run-2", "completed")), agent("a", "completed")))
    }

    @Test fun pillReturnsWhenLaterTurnRolledBackQueuedOrNeverStarted() {
        assertEquals("1 done", pill(listOf(finished, run("run-2", "rolled_back")), agent("a", "completed")))
        assertEquals("1 done", pill(listOf(finished, run("run-2", "queued", started = false)), agent("a", "completed")))
        assertEquals("1 done", pill(listOf(finished, run("run-2", "cancelled", started = false)), agent("a", "completed")))
    }

    @Test fun latestTurnUsesOrdinalNotPosition() {
        assertEquals("1 done", pill(listOf(run("run-1", "completed", ordinal = 3), run("run-2", "completed", ordinal = 2)), agent("a", "completed")))
    }

    @Test fun latestTurnAgentsWinOverLateBackgroundFinish() {
        val r = subagentRoster(listOf(finished, run("run-2", "completed")), listOf(
            agent("late", "completed", updatedAt = at(9)), agent("latest", "completed", "run-2", updatedAt = at(5))))
        assertEquals(listOf("latest"), ids(r)); assertEquals("1 done", subagentPill(r)?.label)
    }

    @Test fun idleCountsInNeitherBucket() {
        val r = subagentRoster(listOf(active), listOf(agent("a", "completed"), agent("b", "idle"), agent("c", "failed")))!!
        assertEquals(0, r.liveCount); assertEquals(2, r.settledCount); assertEquals(3, r.subagents.size)
        assertEquals("2 done", subagentPill(r)?.label)
        assertEquals("1 done", pill(listOf(finished), agent("a", "completed"), agent("b", "idle")))
    }

    @Test fun settledWorkflowStaysReachableWithoutCountingIdle() {
        val r = subagentRoster(listOf(finished, run("run-2", "completed")), listOf(
            agent("workflow", "completed", workflow = wf), agent("idle", "idle", "run-2", updatedAt = at(9))))
        assertEquals(listOf("idle", "workflow"), ids(r))
        assertEquals(SubagentPill("1 done", "1 agent done"), subagentPill(r))
    }

    @Test fun rowTitleRules() {
        val base = agent("a")
        assertEquals("Worker", subagentRowTitle(base))
        assertEquals("Explore", subagentRowTitle(base.copy(title = "Subagent: Explore")))
        assertEquals("Code Reviewer", subagentRowTitle(base.copy(title = "/root/team/code_reviewer")))
        assertEquals("Do the thing", subagentRowTitle(base.copy(title = "  ")))
        assertEquals("Subagent", subagentRowTitle(base.copy(title = null, prompt = " ")))
        val long = subagentRowTitle(base.copy(title = null, prompt = "x".repeat(100)))
        assertEquals(80, long.length); assertTrue(long.endsWith("..."))
        assertEquals("x".repeat(80), subagentRowTitle(base.copy(title = null, prompt = "x".repeat(80))))
    }

    @Test fun statusLabelsAndTones() {
        val expected = mapOf("pending" to ("Working" to SubagentTone.Working), "running" to ("Working" to SubagentTone.Working), "waiting" to ("Waiting" to SubagentTone.Working),
            "idle" to ("Idle" to SubagentTone.Stopped), "completed" to ("Completed" to SubagentTone.Completed), "failed" to ("Failed" to SubagentTone.Failed),
            "cancelled" to ("Cancelled" to SubagentTone.Stopped), "interrupted" to ("Interrupted" to SubagentTone.Stopped), "mystery" to ("Stopped" to SubagentTone.Stopped))
        for ((status, want) in expected) { assertEquals(status, want.first, subagentStatusLabel(status)); assertEquals(status, want.second, subagentTone(status)) }
    }

    @Test fun detailPicksResultWhenTerminalAndProgressWhenLive() {
        val a = agent("a", "completed").copy(progress = "step", result = "done")
        assertEquals("done", subagentDetail(a)); assertEquals("step", subagentDetail(a.copy(status = "running")))
        assertEquals("step", subagentDetail(a.copy(result = "  ")))
        assertEquals("done", subagentDetail(a.copy(status = "running", progress = null)))
        assertNull(subagentDetail(a.copy(progress = null, result = null)))
    }

    @Test fun detailCleansMarkdownAndBoilerplate() {
        val a = agent("a", "completed")
        assertEquals("See docs and code", subagentDetail(a.copy(result = "- See [docs](https://x.y/a(b)) and `code`")))
        assertEquals("one two", subagentDetail(a.copy(result = "  one\n\n two  ")))
        assertNull(subagentDetail(a.copy(result = "Child task ended with status failed")))
    }

    @Test fun detailTruncatesAt280() {
        val a = agent("a", "completed")
        assertEquals("y".repeat(280), subagentDetail(a.copy(result = "y".repeat(280))))
        assertEquals("y".repeat(280) + "…", subagentDetail(a.copy(result = "y".repeat(300))))
        val pair = "😀"
        val cut = subagentDetail(a.copy(result = "z".repeat(279) + pair + "tail"))!!
        assertEquals("z".repeat(279) + "…", cut)
    }

    @Test fun elapsedRules() {
        val base = agent("a", "completed").copy(startedAt = at(0), completedAt = at(5))
        assertEquals(5_000L, subagentElapsedMs(base, 99_000_000_000_000))
        assertNull(subagentElapsedMs(base.copy(startedAt = null), 0))
        assertNull(subagentElapsedMs(base.copy(completedAt = null), 0))
        val start = java.time.Instant.parse(at(0)).toEpochMilli()
        assertEquals(7_000L, subagentElapsedMs(base.copy(status = "running", completedAt = null), start + 7_000))
        assertNull(subagentElapsedMs(base.copy(status = "running"), start - 5))
        assertNull(subagentElapsedMs(base.copy(completedAt = at(0)), 0))
        assertNull(subagentElapsedMs(base.copy(startedAt = "garbage"), 0))
    }

    @Test fun durationFormat() {
        assertEquals("0ms", formatSubagentDuration(-1)); assertEquals("1ms", formatSubagentDuration(0)); assertEquals("350ms", formatSubagentDuration(350))
        assertEquals("1.2s", formatSubagentDuration(1_234)); assertEquals("2s", formatSubagentDuration(2_000)); assertEquals("10s", formatSubagentDuration(9_960))
        assertEquals("42s", formatSubagentDuration(42_000)); assertEquals("59s", formatSubagentDuration(58_600)); assertEquals("1m", formatSubagentDuration(59_500))
        assertEquals("1h 2m 3s", formatSubagentDuration(3_723_000)); assertEquals("1h", formatSubagentDuration(3_600_000)); assertEquals("2m 5s", formatSubagentDuration(125_000))
    }

    @Test fun modelLine() {
        val providers = listOf(ProviderInstance("codex", "codex"), ProviderInstance("team", "claudeAgent", "Work"))
        val models = listOf(ModelOption("codex", "gpt-5.4", "GPT-5.4"))
        val a = agent("a").copy(providerInstanceId = "codex", model = "gpt-5.4", selectionInstanceId = "codex", selectionModel = "gpt-5.4", selectionOptions = mapOf("effort" to "high"))
        assertEquals("Codex · GPT-5.4 · High", subagentModelLine(a, providers, models))
        assertEquals("Codex · GPT-5.4", subagentModelLine(a.copy(selectionModel = "other"), providers, models))
        assertEquals("Codex · gpt-5.4 · High", subagentModelLine(a, providers, emptyList()))
        assertEquals("Work · claude-x", subagentModelLine(a.copy(providerInstanceId = null, selectionInstanceId = "team", model = "claude-x", selectionModel = null), providers, models))
        assertEquals("GPT-5.4 · High", subagentModelLine(a.copy(providerInstanceId = null, selectionInstanceId = "codex"), emptyList(), models))
        assertEquals("Codex · GPT-5.4 · Low", subagentModelLine(a.copy(model = null, selectionOptions = mapOf("reasoningEffort" to "low")), providers, models))
        assertNull(subagentModelLine(agent("b"), providers, models))
    }

    @Test fun workflowSummary() {
        assertNull(subagentWorkflowSummary(agent("a")))
        val w = SubagentWorkflow("w", listOf(SubagentWorkflowAgent(0, "a", "completed"), SubagentWorkflowAgent(1, "b", "running"), SubagentWorkflowAgent(2, "c", "failed")))
        assertEquals("1/3 agents done", subagentWorkflowSummary(agent("w", workflow = w)))
        assertEquals("0/0 agents done", subagentWorkflowSummary(agent("w", workflow = wf)))
    }

    @Test fun openThreadId() {
        assertEquals("child", subagentOpenThreadId(agent("a")))
        assertNull(subagentOpenThreadId(agent("a").copy(childThreadId = " ")))
        assertNull(subagentOpenThreadId(agent("a").copy(childThreadId = null, remoteChild = SubagentRemoteChild("env", "t", "Box"))))
    }
}
