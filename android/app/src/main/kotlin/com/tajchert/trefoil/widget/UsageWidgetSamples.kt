package com.tajchert.trefoil.widget

import com.tajchert.trefoil.core.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Fake data for widget previews (`providePreview`) and tests. Never shown as real usage. */
object UsageWidgetSamples {
    fun snapshot(now: Long = System.currentTimeMillis()): UsageWidgetSnapshot {
        fun at(minutes: Long) = Instant.ofEpochMilli(now + minutes * 60_000).toString()
        val claude = UsageWidgetProvider("claude", "claudeAgent", "Claude", ProviderUsageLimits(at(-3), listOf(
            UsageLimitWindow("session", "session", "5h", 62.0, at(125), 300),
            UsageLimitWindow("weekly", "weekly", "Weekly", 41.0, at(3 * 1440 + 200), 10080),
            UsageLimitWindow("weekly-opus", "weekly", "Opus weekly", 88.0, at(3 * 1440 + 200), 10080),
        )))
        val codex = UsageWidgetProvider("codex", "codex", "Codex", ProviderUsageLimits(at(-3), listOf(
            UsageLimitWindow("weekly", "weekly", "Weekly", 20.0, at(5 * 1440 + 30), 10080),
        )))
        val cursor = UsageWidgetProvider("cursor", "cursor", "Cursor", ProviderUsageLimits(at(-3), unavailableReason = "unsupported"))
        return UsageWidgetSnapshot(
            environmentId = "sample", machineName = "Studio", updatedAtMillis = now - 4 * 60_000, limitsUpdatedAtMillis = now - 3 * 60_000,
            providers = listOf(claude, codex, cursor),
            reports = UsageRange.entries.associateWith { report(it, now) }, backgroundRefresh = true,
        )
    }

    fun report(range: UsageRange, now: Long = System.currentTimeMillis()): UsageReport {
        val today = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
        val days = (range.days - 1 downTo 0).map { back ->
            val day: LocalDate = today.minusDays(back.toLong())
            val cost = if (back % 7 == 5) 0.0 else 3.0 + (back * 37 % 11) * 1.35
            UsageDayTotal(day.toString(), cost, (cost * 410_000).toLong())
        }
        val total = days.sumOf { it.costUsd }
        fun tokens(share: Double) = (total * 410_000 * share).toLong().let { t -> UsageTokens(t / 4, t / 2, t / 20, t / 5, t / 40) }
        val providers = listOf(
            UsageBreakdown("claude", costUsd = total * 0.64, tokens = tokens(0.58), records = 420),
            UsageBreakdown("codex", costUsd = total * 0.31, tokens = tokens(0.37), records = 310),
            UsageBreakdown("cursor", costUsd = total * 0.05, tokens = tokens(0.05), records = 40, unpricedRecords = 3),
        )
        val models = listOf(
            UsageBreakdown("claude", "claude-opus-5-5", total * 0.44, tokens(0.31), records = 180),
            UsageBreakdown("codex", "gpt-6.1-sol", total * 0.24, tokens(0.25), records = 200),
            UsageBreakdown("claude", "claude-sonnet-5-5", total * 0.20, tokens(0.27), records = 240),
            UsageBreakdown("codex", "gpt-6.1-terra", total * 0.07, tokens(0.12), records = 110),
            UsageBreakdown("cursor", "composer-2", total * 0.05, tokens(0.05), records = 40, unpricedRecords = 3),
        )
        return UsageReport(
            environmentId = "sample", range = range, sinceDay = days.first().day, untilDay = days.last().day, timeZone = "UTC",
            readAt = Instant.ofEpochMilli(now - 4 * 60_000).toString(), totalCostUsd = total,
            tokens = providers.fold(UsageTokens()) { sum, p -> sum + p.tokens }, cacheSavingsUsd = total * 0.38,
            providers = providers, models = models, days = days, unpricedRecords = 3, pricingStatus = "fresh",
        )
    }
}
