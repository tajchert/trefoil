package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ThreadDetailsTest {
    @Test fun timerFollowsActivityOwnerAndExplicitNullDoesNotFallBack() {
        val thread = ThreadSummary("t", "p", "Work", "running", latestRunId = "new", activeRunId = "new",
            latestRunStartedAt = "2026-11-06T10:02:00Z", activityRunStartedAt = "2026-11-06T10:00:00Z", activityRunStartedAtPresent = true)
        assertEquals("2026-11-06T10:00:00Z", threadWorkStartedAt(thread))
        assertNull(threadWorkStartedAt(thread.copy(activityRunStartedAt = null)))
        assertEquals("2026-11-06T10:02:00Z", threadWorkStartedAt(thread.copy(activityRunStartedAtPresent = false)))
        assertNull(threadWorkStartedAt(thread.copy(activityRunStartedAtPresent = false, activeRunId = "different")))
        assertNull(threadWorkStartedAt(thread.copy(activityRunStartedAtPresent = false, latestRunCompletedAt = "2026-11-06T10:05:00Z")))
    }
    @Test fun durationMatchesMobileThresholdsAndClampsClockSkew() {
        assertEquals("0s", formatWorkDuration(-1))
        assertEquals("59s", formatWorkDuration(59_999))
        assertEquals("1m", formatWorkDuration(60_000))
        assertEquals("59m", formatWorkDuration(3_599_999))
        assertEquals("1h 1m", formatWorkDuration(3_660_000))
    }
    @Test fun providerStackUsesCatalogAndKeepsOnlyTwoPreviousInstances() {
        val providers = listOf(ProviderInstance("team", "codex", "Work"), ProviderInstance("old", "claudeAgent"), ProviderInstance("another", "cursor"))
        val thread = ThreadSummary("t", "p", "Work", model = "claude-model-name-is-not-the-provider",
            providerInstanceId = "team", modelInstanceId = "another", providerInstanceHistory = listOf("unknown", "old", "another", "team"))
        assertEquals(listOf("claudeAgent", "cursor", "codex"), threadProviders(thread, providers).map { it.driver })
        assertTrue(threadProviders(thread.copy(providerInstanceId = "unconfigured", providerInstanceHistory = emptyList()), providers).isEmpty())
    }
    @Test fun clockWakesOnSecondBoundariesAndExactSnoozeDeadline() {
        assertEquals(500L, nextThreadClockDelay(1500, true, emptyList()))
        assertEquals(58_500L, nextThreadClockDelay(1500, false, emptyList()))
        assertEquals(100L, nextThreadClockDelay(1500, false, listOf(1600, 1700)))
        assertEquals(100L, nextThreadClockDelay(1600, false, listOf(1600, 1700)))
    }
    @Test fun waitingAndWorkingTimeHaveDifferentAnchors() {
        val now = java.time.Instant.parse("2026-11-06T10:02:30Z").toEpochMilli()
        val working = ThreadSummary("t", "p", "Work", "running", activityRunStartedAt = "2026-11-06T10:00:00Z", activityRunStartedAtPresent = true)
        assertEquals("Working 2m", threadStatusLabel(working, now))
        val waiting = working.copy(status = "idle", latestRunCompletedAt = "2026-11-06T10:02:00Z")
        assertEquals("Waiting 30s", threadStatusLabel(waiting, now))
    }
}
