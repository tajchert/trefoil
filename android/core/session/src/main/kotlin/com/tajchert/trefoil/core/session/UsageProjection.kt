/* Adapted from T3 Code usage contracts (MIT), pinned in protocol/USAGE.md. */
package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.protocol.*
import kotlinx.serialization.json.*
import java.time.LocalDate

internal fun usageWindow(range: UsageRange, today: LocalDate): Pair<String, String> =
    today.minusDays(range.days.toLong() - 1).toString() to today.toString()

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
private fun JsonObject.number(key: String): Double? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
private fun JsonObject.count(key: String): Long? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }

internal fun providerUsageLimits(value: JsonObject): ProviderUsageLimits? {
    val checkedAt = value.text("checkedAt") ?: return null
    val windows = value.objects("windows").mapNotNull { window ->
        UsageLimitWindow(
            window.text("id") ?: return@mapNotNull null, window.text("kind") ?: return@mapNotNull null,
            window.text("label") ?: return@mapNotNull null, window.number("usedPercent") ?: return@mapNotNull null,
            window.text("resetsAt"), (window["windowDurationMins"] as? JsonPrimitive)?.intOrNull,
        )
    }
    return ProviderUsageLimits(checkedAt, windows, value.obj("unavailable").text("reason"), value.obj("unavailable").text("message"))
}

internal fun usageSummary(value: JsonObject, environmentId: String, range: UsageRange): UsageReport {
    val since = LocalDate.parse(requireNotNull(value.text("sinceDay")))
    val until = LocalDate.parse(requireNotNull(value.text("untilDay")))
    require(!until.isBefore(since) && until.toEpochDay() - since.toEpochDay() < 366) { "Invalid usage window" }
    require(value["buckets"] is JsonArray && value["sources"] is JsonArray && value["pricing"] is JsonObject)
    val buckets = value.objects("buckets").mapNotNull { bucket ->
        val day = runCatching { LocalDate.parse(bucket.text("day")) }.getOrNull() ?: return@mapNotNull null
        if (day < since || day > until) return@mapNotNull null
        val totals = bucket.obj("totals")
        val tokens = UsageTokens(
            totals.count("uncachedInputTokens") ?: return@mapNotNull null,
            totals.count("cachedInputTokens") ?: return@mapNotNull null,
            totals.count("cacheCreationTokens") ?: return@mapNotNull null,
            totals.count("outputTokens") ?: return@mapNotNull null,
            totals.count("reasoningTokens") ?: return@mapNotNull null,
        )
        val records = bucket.count("records")?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return@mapNotNull null
        val unpriced = bucket.count("unpricedRecords")?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return@mapNotNull null
        val row = UsageBreakdown(bucket.text("provider") ?: return@mapNotNull null,
            bucket.text("model") ?: return@mapNotNull null, bucket.number("costUsd") ?: return@mapNotNull null, tokens, unpriced, records)
        Triple(day.toString(), row, bucket.number("cacheSavingsUsd") ?: return@mapNotNull null)
    }
    fun aggregate(rows: List<UsageBreakdown>, provider: String, model: String? = null) = UsageBreakdown(
        provider, model, rows.sumOf { it.costUsd }, rows.fold(UsageTokens()) { total, row -> total + row.tokens },
        rows.sumOf { it.unpricedRecords }, rows.sumOf { it.records },
    )
    val rows = buckets.map { it.second }
    val total = aggregate(rows, "")
    val order = compareByDescending<UsageBreakdown> { it.costUsd }.thenByDescending { it.tokens.total }
    val byDay = buckets.groupBy { it.first }
    val sources = value.objects("sources").mapNotNull { source ->
        val provider = source.obj("fingerprint").text("provider") ?: return@mapNotNull null
        val status = source.text("status") ?: return@mapNotNull null
        UsageSourceState(provider, status, source.text("message")) to ((source["refreshing"] as? JsonPrimitive)?.booleanOrNull == true)
    }
    return UsageReport(
        environmentId, range, since.toString(), until.toString(), requireNotNull(value.text("timeZone")), requireNotNull(value.text("readAt")),
        total.costUsd, total.tokens, buckets.sumOf { it.third },
        rows.groupBy { it.provider }.map { (provider, items) -> aggregate(items, provider) }.sortedWith(order),
        rows.groupBy { it.provider to it.model }.map { (key, items) -> aggregate(items, key.first, key.second) }.sortedWith(order),
        generateSequence(since) { it.plusDays(1) }.takeWhile { it <= until }.map { day ->
            val items = byDay[day.toString()].orEmpty().map { it.second }
            UsageDayTotal(day.toString(), items.sumOf { it.costUsd }, items.sumOf { it.tokens.total })
        }.toList(), total.unpricedRecords, requireNotNull(value.obj("pricing").text("status")), sources.map { it.first }, sources.any { it.second },
    )
}
