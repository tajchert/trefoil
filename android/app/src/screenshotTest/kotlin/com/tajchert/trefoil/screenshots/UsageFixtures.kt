package com.tajchert.trefoil.screenshots

import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.ProviderInstance
import com.tajchert.trefoil.core.model.ProviderUsageLimits
import com.tajchert.trefoil.core.model.ScreenCatalog
import com.tajchert.trefoil.core.model.ScreenTarget
import com.tajchert.trefoil.core.model.UsageLimitWindow
import com.tajchert.trefoil.core.model.UsageRange
import com.tajchert.trefoil.ui.UsageUiState
import com.tajchert.trefoil.widget.UsageWidgetSamples
import java.time.Instant

/** Fake usage and screen-session data for preview screenshots. */
object UsageFixtures {
    private val now = System.currentTimeMillis()
    // Fixed so the chart's date labels do not change from day to day.
    private val reportNow = Instant.parse("2026-06-15T12:00:00Z").toEpochMilli()

    // +30s keeps "in 2h 5m" labels stable while rendering.
    private fun at(minutes: Long) = Instant.ofEpochMilli(now + minutes * 60_000 + 30_000).toString().toString()
    // No window duration: the pace tick and "ahead of pace" label would drift with render time.
    @Suppress("UNUSED_PARAMETER")
    private fun window(id: String, kind: String, label: String, used: Double, resetMin: Long, durationMin: Int) =
        UsageLimitWindow(id, kind, label, used, at(resetMin), null)

    val providers = listOf(
        ProviderInstance("claude", "claudeAgent", "Claude", usageLimits = ProviderUsageLimits(at(-3), listOf(
            window("session", "session", "5h", 62.0, 125, 300),
            window("weekly", "weekly", "Weekly", 41.0, 3 * 1440 + 200, 10080),
        ))),
        ProviderInstance("codex", "codex", "Codex", usageLimits = ProviderUsageLimits(at(-3), listOf(
            window("weekly", "weekly", "Weekly", 20.0, 5 * 1440 + 30, 10080),
        ))),
        ProviderInstance("cursor", "cursor", "Cursor", usageLimits = ProviderUsageLimits(at(-3), unavailableReason = "unsupported")),
    )

    val nearLimit = listOf(
        ProviderInstance("claude", "claudeAgent", "Claude", usageLimits = ProviderUsageLimits(at(-3), listOf(
            window("session", "session", "5h", 78.0, 95, 300),
            window("weekly", "weekly", "Weekly", 93.0, 2 * 1440, 10080),
            window("weekly-opus", "weekly", "Opus weekly", 97.0, 2 * 1440, 10080),
        ))),
    )

    val exceeded = listOf(
        ProviderInstance("claude", "claudeAgent", "Claude", usageLimits = ProviderUsageLimits(at(-3), listOf(
            window("session", "session", "5h", 100.0, 40, 300),
            window("weekly", "weekly", "Weekly", 100.0, 4 * 1440, 10080),
        ))),
        ProviderInstance("codex", "codex", "Codex", usageLimits = ProviderUsageLimits(at(-3), listOf(
            window("weekly", "weekly", "Weekly", 96.0, 1440, 10080),
        ))),
    )

    fun state(providers: List<ProviderInstance> = this.providers) = ClientState(
        environment = ScreenshotFixtures.workstation, connection = ConnectionStatus.Connected,
        providers = providers, canRead = true, canWrite = true,
    )

    fun usage(range: UsageRange = UsageRange.Week) = UsageUiState(
        range = range, reports = UsageRange.entries.associateWith { UsageWidgetSamples.report(it, reportNow) },
    )

    val screenCatalog = ScreenCatalog(
        browsers = listOf(
            ScreenTarget.Browser("t-approval", "tab-1", "Docs preview", "http://localhost:5173/docs", interactive = true),
            ScreenTarget.Browser("t-approval", "tab-2", "Storybook", "http://localhost:6006/"),
        ),
        devices = listOf(
            ScreenTarget.Device("t-approval", "host-1", "emulator-5554", "android", "Pixel 9 emulator"),
            ScreenTarget.Device("t-approval", "host-1", "sim-1", "ios", "iPhone 17 simulator"),
        ),
        loaded = true,
    )
}
