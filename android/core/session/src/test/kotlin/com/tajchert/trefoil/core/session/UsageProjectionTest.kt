package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class UsageProjectionTest {
    @Test fun aggregatesAndFillsDaysSkippingMalformedBuckets() {
        val report = usageSummary(summary(), "env", UsageRange.Week)
        assertEquals(6.0, report.totalCostUsd, 0.0)
        assertEquals(36L, report.tokens.total)
        assertEquals(3L, report.tokens.reasoning)
        assertEquals(1.5, report.cacheSavingsUsd, 0.0)
        assertEquals(3, report.unpricedRecords)
        assertEquals(listOf("future", "claude"), report.providers.map { it.provider })
        assertEquals(2, report.models.size)
        assertEquals(7, report.days.size)
        assertEquals(0L, report.days[1].tokens)
        assertEquals(36L, report.days.first().tokens)
        assertEquals("future-status", report.pricingStatus)
        assertEquals(UsageSourceState("future", "future-status", "Scanning"), report.sources.single())
        assertTrue(report.refreshing)
        assertFalse(report.toString().contains("private-path"))
    }

    @Test fun limitsKeepRawPercentAndUnknownKindsButDropMalformedWindows() {
        val limits = providerUsageLimits(obj("""{"checkedAt":"now","windows":[null,{},
            {"id":"week","kind":"future","label":"Weekly","usedPercent":125},
            {"id":"bad","kind":"weekly","label":"Bad","usedPercent":"NaN"}],
            "unavailable":{"reason":"probeFailed","message":"Try later"},"credentialFingerprint":"secret"}"""))!!
        assertEquals(125.0, limits.windows.single().usedPercent, 0.0)
        assertEquals("future", limits.windows.single().kind)
        assertEquals("Try later", limits.notice)
        assertEquals("This account has no subscription limits.", providerUsageLimits(obj("""{"checkedAt":"now","windows":[],"unavailable":{"reason":"unsupported"}}"""))!!.notice)
        assertNull(providerUsageLimits(obj("{}")))
    }

    @Test fun windowIncludesTodayAcrossYearBoundary() {
        assertEquals("2025-12-27" to "2026-01-02", usageWindow(UsageRange.Week, LocalDate.parse("2026-01-02")))
        assertEquals("2026-01-02" to "2026-01-02", usageWindow(UsageRange.Today, LocalDate.parse("2026-01-02")))
    }

    @Test fun breakdownTiesSortByTokensAndSameModelStaysSeparatePerProvider() {
        val value = summary(false)
        val buckets = listOf(bucket("claude", 0), bucket("future", 0).replace("\"outputTokens\":6", "\"outputTokens\":60"))
        val report = usageSummary(JsonObject(value + ("buckets" to JsonArray(buckets.map { obj(it) }))), "env", UsageRange.Week)
        assertEquals(listOf("future", "claude"), report.providers.map { it.provider })
        assertEquals(listOf("future", "claude"), report.models.map { it.provider })
        assertEquals(2, report.models.size)
        assertFalse(report.refreshing)
        assertEquals("No limits reported.", providerUsageLimits(obj("""{"checkedAt":"now","windows":[]}"""))!!.notice)
    }

    companion object {
        fun obj(value: String) = Json.parseToJsonElement(value).jsonObject
        fun summary(refreshing: Boolean = true) = obj("""{"readAt":"now","sinceDay":"2026-01-01","untilDay":"2026-01-07","timeZone":"UTC",
            "buckets":[null,{},${bucket("claude", 2)},${bucket("future", 4)},${bucket("claude", 0)}],
            "pricing":{"status":"future-status"},"sources":[null,{},
            {"fingerprint":{"provider":"future","hostId":"private-host","resolvedHomePath":"private-path"},"status":"future-status","message":"Scanning","refreshing":$refreshing}]}""")
        private fun bucket(provider: String, cost: Int) = """{"day":"2026-01-01","provider":"$provider","model":"model","totals":{"uncachedInputTokens":1,"cachedInputTokens":2,"cacheCreationTokens":3,"outputTokens":6,"reasoningTokens":1},"costUsd":$cost,"cacheSavingsUsd":0.5,"records":2,"unpricedRecords":1}"""
    }
}
