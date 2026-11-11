package com.tajchert.trefoil.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentsTest {
    private val claude = Subagent(
        id = "n1", threadId = "parent", runId = "run-1", origin = SUBAGENT_ORIGIN_PROVIDER_NATIVE,
        driver = "claudeAgent", status = "running", nativeTaskRef = SubagentNativeTaskRef("task-1", "strong"),
    )
    private val delegated = Subagent(id = "n2", threadId = "parent", origin = SUBAGENT_ORIGIN_APP_OWNED, childThreadId = "child", status = "pending")

    @Test fun statusBuckets() {
        assertTrue(isSubagentActiveStatus("waiting")); assertFalse(isSubagentActiveStatus("idle"))
        assertTrue(isSubagentTerminalStatus("interrupted")); assertFalse(isSubagentTerminalStatus("idle"))
    }

    @Test fun stopFollowsMobileTargetRules() {
        assertTrue(claude.canStop)
        assertTrue(delegated.canStop)
        assertFalse(claude.copy(status = "completed").canStop)
        assertFalse(claude.copy(nativeTaskRef = SubagentNativeTaskRef("task-1", "weak")).canStop)
        assertFalse(claude.copy(nativeTaskRef = SubagentNativeTaskRef(null, "strong")).canStop)
        assertFalse(claude.copy(runId = null).canStop)
        assertFalse(claude.copy(driver = "codex").canStop)
        assertFalse(claude.copy(workflow = SubagentWorkflow("wf")).canStop)
        assertFalse(delegated.copy(childThreadId = null, remoteChild = SubagentRemoteChild("env", "t", "Laptop")).canStop)
    }

    @Test fun providerNativeChildThread() {
        assertTrue(ThreadSummary("c", "p", "t", relationshipToParent = "subagent", creationSource = "provider").isProviderNativeSubagentThread())
        assertFalse(ThreadSummary("c", "p", "t", relationshipToParent = "subagent", creationSource = "mcp").isProviderNativeSubagentThread())
    }
}
