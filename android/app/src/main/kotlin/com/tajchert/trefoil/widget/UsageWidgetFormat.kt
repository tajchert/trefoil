package com.tajchert.trefoil.widget

import androidx.annotation.DrawableRes
import com.tajchert.trefoil.R
import com.tajchert.trefoil.ui.formatUsageDuration

// Pure helpers shared by all usage widgets. Formatting of money, tokens, pace and reset text
// lives in ui/UsagePresentation.kt (formatUsageUsd, formatUsageTokens, usagePace, formatUsageReset); reuse those.

/** Snapshots older than this are labelled stale (the periodic refresh runs every 30 minutes). */
const val WIDGET_STALE_AFTER_MS = 2 * 60 * 60_000L

/** "Updated just now" / "Updated 5m ago" / "Updated 3h 10m ago". */
fun widgetFreshness(updatedAtMillis: Long, now: Long): String {
    val age = (now - updatedAtMillis).coerceAtLeast(0)
    return if (age < 60_000) "Updated just now" else "Updated ${formatUsageDuration(age)} ago"
}

fun isWidgetStale(updatedAtMillis: Long, now: Long): Boolean = now - updatedAtMillis > WIDGET_STALE_AFTER_MS

/** Monochrome provider mark (tint it), or null for unknown drivers. Same mapping as ui/ProviderIconUi.kt. */
@DrawableRes
fun widgetProviderIcon(driver: String): Int? = when (driver) {
    "codex" -> R.drawable.ic_provider_codex
    "claudeAgent" -> R.drawable.ic_provider_claude
    "cursor" -> R.drawable.ic_provider_cursor
    "grok" -> R.drawable.ic_provider_grok
    "opencode" -> R.drawable.ic_provider_opencode
    else -> null
}
