package com.tajchert.trefoil.widget

import android.content.Intent
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import com.tajchert.trefoil.parseUsageWidgetIntent
import com.tajchert.trefoil.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UsageWidgetPipelineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val environment = SavedEnvironment("sample", "Saved name", "https://example.invalid", "fake")

    @Test fun storeRoundTripsMergesAndDeletesWithoutRedundantNotifications() = runBlocking {
        val file = File(temporary.root, "snapshot.json")
        var updates = 0
        val store = UsageWidgetStore(AtomicFile(file)) { updates++ }
        store.update { mergeWidgetSnapshot(it, environment, emptyList(), 100, true) }
        store.update { mergeWidgetSnapshot(it, environment, emptyList(), 200, true) }
        assertEquals(1, updates)
        val report = UsageWidgetSamples.report(UsageRange.Month).copy(sources = listOf(UsageSourceState("codex", "failed", "private path")))
        store.update { mergeWidgetReport(it, report, 300) }
        val restored = UsageWidgetStore(AtomicFile(file)) {}.read()!!
        assertEquals(store.snapshot.value, restored)
        assertNull(restored.reports[UsageRange.Month]!!.sources.single().message)
        assertFalse(file.readText().contains("private path"))
        store.update { null }
        assertNull(store.read())
        assertFalse(file.exists())
        assertEquals(3, updates)
    }

    @Test fun mapsEnabledProvidersAndOmitsServerMessages() {
        val providers = widgetProviders(listOf(ProviderInstance("a", "codex", usageLimits = ProviderUsageLimits("now", unavailableMessage = "private")),
            ProviderInstance("b", "claudeAgent", enabled = false)))
        assertEquals(listOf("a"), providers.map { it.instanceId })
        assertEquals("codex", providers.single().displayName)
        assertNull(providers.single().limits?.unavailableMessage)
    }

    @Test fun mergesReportsAndDoesNotChurnUnchangedLimits() {
        val sample = UsageWidgetSamples.snapshot(1_000)
        val mapped = mergeWidgetSnapshot(sample, environment, sample.providers, 2_000, true)
        assertEquals("Saved name", mapped.machineName)
        assertEquals(sample.limitsUpdatedAtMillis, mapped.limitsUpdatedAtMillis)
        assertEquals(mapped, mergeWidgetSnapshot(mapped, environment, mapped.providers, 3_000, true))
        val report = UsageWidgetSamples.report(UsageRange.Today, 4_000)
        val merged = mergeWidgetReport(mapped, report, 5_000)!!
        assertEquals(3, merged.reports.size)
        assertEquals(report, merged.reports[UsageRange.Today])
        assertSame(merged, mergeWidgetReport(merged, report, 6_000))
        assertSame(merged, mergeWidgetReport(merged, report.copy(environmentId = "other"), 6_000))
    }

    @Test fun environmentSwitchDiscardsReportsAndErrors() {
        val old = UsageWidgetSamples.snapshot().copy(lastError = "failed")
        val next = mergeWidgetSnapshot(old, environment.copy(id = "new"), emptyList(), 10, false)
        assertEquals("new", next.environmentId)
        assertTrue(next.reports.isEmpty())
        assertNull(next.lastError)
        assertNull(next.limitsUpdatedAtMillis)
        val changed = mergeWidgetSnapshot(next, environment.copy(id = "new"), old.providers, 20, false)
        assertEquals(20L, changed.limitsUpdatedAtMillis)
    }

    @Test fun throttleIsPerEnvironmentAndIncludesFailedAttempts() {
        val throttle = WidgetFetchThrottle()
        assertTrue(throttle.acquire("a", 0))
        assertFalse(throttle.acquire("a", WIDGET_FETCH_INTERVAL_MS - 1))
        assertTrue(throttle.acquire("b", 1))
        assertTrue(throttle.acquire("a", WIDGET_FETCH_INTERVAL_MS))
    }

    @Test fun workerSkipsLiveConnectionsAndOnlyPeriodicFreshSnapshots() {
        val snapshot = UsageWidgetSamples.snapshot().copy(updatedAtMillis = 100)
        assertTrue(skipWidgetNetwork(snapshot, "sample", false, 9999999))
        assertFalse(skipWidgetNetwork(snapshot, "other", false, 101))
        assertTrue(skipWidgetNetwork(snapshot, null, true, 101))
        assertFalse(skipWidgetNetwork(snapshot, null, true, 100 + WIDGET_FETCH_INTERVAL_MS))
        assertFalse(skipWidgetNetwork(snapshot, null, true, 99))
    }

    @Test fun backgroundSshRequiresSavedCredentialsAndPinnedKey() {
        assertTrue(widgetRefreshEligible(environment, emptyList()))
        val ssh = environment.copy(ssh = SshProfile("host", username = "user", fingerprint = "pin", keyType = "ed25519", password = "fake"))
        assertTrue(widgetRefreshEligible(ssh, emptyList()))
        assertFalse(widgetRefreshEligible(ssh.copy(ssh = ssh.ssh!!.copy(password = null)), emptyList()))
        val unpinned = ssh.copy(ssh = ssh.ssh!!.copy(fingerprint = ""))
        assertFalse(widgetRefreshEligible(unpinned, emptyList()))
        val keys = listOf(SshHostKey("HOST", 22, "ed25519", "vault-pin"))
        assertTrue(widgetRefreshEligible(unpinned, keys))
        assertEquals("vault-pin", widgetPinnedFingerprint(ssh, keys))
        assertFalse(widgetRefreshEligible(ssh.copy(ssh = ssh.ssh!!.copy(port = 0)), keys))
    }

    @Test fun parsesOnlyUsageActionsWithSafeDefaultTab() {
        assertNull(parseUsageWidgetIntent(null))
        assertNull(parseUsageWidgetIntent(Intent(Intent.ACTION_MAIN).putExtra(EXTRA_OPEN_USAGE_TAB, "Cost")))
        assertEquals(UsageWidgetTab.Limits, parseUsageWidgetIntent(Intent(ACTION_OPEN_USAGE)))
        assertEquals(UsageWidgetTab.Limits, parseUsageWidgetIntent(Intent(ACTION_OPEN_USAGE).putExtra(EXTRA_OPEN_USAGE_TAB, "unknown")))
        UsageWidgetTab.entries.forEach {
            assertEquals(it, parseUsageWidgetIntent(Intent(ACTION_OPEN_USAGE).putExtra(EXTRA_OPEN_USAGE_TAB, it.name)))
        }
    }

    @Test fun statusLeadsWithSavedRefreshHint() {
        val now = 10 * 60 * 60_000L
        val snapshot = UsageWidgetSamples.snapshot(now).copy(lastError = "Open Trefoil to refresh this machine.")
        assertEquals("Open Trefoil to refresh this machine · Updated 3h 0m ago", widgetStatus(snapshot, now - 3 * 60 * 60_000L, now))
        assertEquals("Updated 3h 0m ago", widgetStatus(snapshot.copy(lastError = null), now - 3 * 60 * 60_000L, now))
    }
}
