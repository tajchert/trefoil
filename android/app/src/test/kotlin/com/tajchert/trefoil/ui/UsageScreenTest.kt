package com.tajchert.trefoil.ui

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.tajchert.trefoil.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UsageScreenTest {
    @get:Rule val compose = createComposeRule()
    private val state = ClientState(environment = SavedEnvironment("m", "Mac mini", "https://fixture.example", "fixture"))
    private val report = UsageReport(
        "m", UsageRange.Week, "2026-01-01", "2026-01-07", "UTC", "2026-01-07T12:00:00Z",
        totalCostUsd = 12.34, tokens = UsageTokens(uncachedInput = 1000, cachedInput = 200, output = 300), cacheSavingsUsd = 2.0,
        providers = listOf(UsageBreakdown("codex", costUsd = 10.0)),
        models = listOf(UsageBreakdown("codex", "model-fixture", costUsd = 10.0)),
        days = listOf(UsageDayTotal("2026-01-01", 2.34, 500), UsageDayTotal("2026-01-07", 10.0, 1000)),
        pricingStatus = "fresh",
    )

    @Test fun limitsShowWindowsNoticeAndAccessibleRemaining() {
        val providers = listOf(
            ProviderInstance("a", "codex", "Work", usageLimits = ProviderUsageLimits("2026-01-01T00:00:00Z", listOf(UsageLimitWindow("w", "session", "Session", 38.0)))),
            ProviderInstance("b", "claudeAgent", "Personal", usageLimits = ProviderUsageLimits("", unavailableReason = "unsupported")),
            ProviderInstance("c", "hidden", enabled = false, usageLimits = ProviderUsageLimits("")),
        )
        compose.setContent { TrefoilTheme { UsageScreen(state.copy(providers = providers), UsageUiState(limitsError = "Refresh failed"), {}, {}, {}, {}) } }
        compose.onNodeWithText("Work").assertExists()
        compose.onNodeWithText("Session").assertExists()
        compose.onNodeWithText("62% left").assertExists()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "62% left")).assertExists()
        compose.onNodeWithText("This account has no subscription limits.").assertExists()
        compose.onNodeWithText("Refresh failed").assertExists()
        compose.onNodeWithText("hidden").assertDoesNotExist()
    }

    @Test fun costShowsTotalsChartAndBreakdownsAndTokensSwitch() {
        compose.setContent { TrefoilTheme { UsageScreen(state, UsageUiState(reports = mapOf(UsageRange.Week to report)), {}, {}, {}, {}) } }
        compose.onNodeWithText("Cost").performClick()
        compose.onNodeWithText("$12.34").assertExists()
        compose.onNodeWithText("Estimated from model pricing").assertExists()
        compose.onNodeWithText("Cache saved $2.00").assertExists()
        compose.onNodeWithContentDescription("Daily cost. Peak 2026-01-07: $10.00").assertExists()
        compose.onNodeWithText("Codex").assertExists()
        compose.onNodeWithText("model-fixture").performScrollTo().assertExists()
        compose.onNodeWithText("Tokens").performClick()
        compose.onNodeWithText("1.5K").assertExists()
        compose.onNodeWithText("Input 1.2K · Output 300 · Cached 200").assertExists()
    }

    @Test fun rangeChipAndToolbarInvokeCallbacks() {
        var selected: UsageRange? = null
        var backs = 0
        var refreshes = 0
        compose.setContent { TrefoilTheme { UsageScreen(state, UsageUiState(), { backs++ }, { refreshes++ }, { selected = it }, {}) } }
        compose.onNodeWithText("Mac mini").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Refresh usage").performClick()
        compose.onNodeWithText("Cost").performClick()
        compose.onNodeWithText("30 days").performClick()
        compose.runOnIdle { assertEquals(UsageRange.Month, selected); assertEquals(1, backs); assertEquals(1, refreshes) }
    }

    @Test fun permissionFailureExplainsRequiredScope() {
        compose.setContent { TrefoilTheme { UsageScreen(state, UsageUiState(failure = UsageFailure.NoPermission), {}, {}, {}, {}) } }
        compose.onNodeWithText("Cost").performClick()
        compose.onNodeWithText("This connection can't read usage. Pair again with a token that has diagnostics access.").assertExists()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test fun failedReadCanRetryAndKeepsLastReport() {
        var retries = 0
        var usage by mutableStateOf(UsageUiState(failure = UsageFailure.Failed, failureMessage = "Fixture failure"))
        compose.setContent { TrefoilTheme { UsageScreen(state, usage, {}, {}, {}, { retries++ }) } }
        compose.onNodeWithText("Cost").performClick()
        compose.onNodeWithText("Fixture failure").assertExists()
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(1, retries); usage = usage.copy(reports = mapOf(UsageRange.Week to report)) }
        compose.onNodeWithText("Fixture failure").assertExists()
        compose.onNodeWithText("$12.34").assertExists()
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(2, retries) }
    }
}
