package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.core.model.ProviderInstance
import com.tajchert.trefoil.core.model.UsageFailure
import com.tajchert.trefoil.core.model.UsageRange
import com.tajchert.trefoil.ui.UsageScreen
import com.tajchert.trefoil.ui.UsageUiState
import com.tajchert.trefoil.ui.TrefoilTheme

@Composable
private fun UsageShot(
    tab: Int, dark: Boolean = false, usage: UsageUiState = UsageFixtures.usage(),
    providers: List<ProviderInstance> = UsageFixtures.providers,
) {
    TrefoilTheme(darkTheme = dark) {
        UsageScreen(UsageFixtures.state(providers), usage, onBack = {}, onRefresh = {}, onSelectRange = {}, onRetry = {}, initialTab = tab)
    }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Usage_Limits_Light() { UsageShot(0) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Usage_Limits_Dark() { UsageShot(0, dark = true) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Usage_Limits_NearLimit() { UsageShot(0, providers = UsageFixtures.nearLimit) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Usage_Limits_Exceeded() { UsageShot(0, providers = UsageFixtures.exceeded) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Usage_Cost_Week() { UsageShot(1) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Usage_Cost_Month_Dark() { UsageShot(1, dark = true, usage = UsageFixtures.usage(UsageRange.Month)) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Usage_Cost_Today() { UsageShot(1, usage = UsageFixtures.usage(UsageRange.Today)) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Usage_Tokens_Week() { UsageShot(2) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Usage_Tokens_Month() { UsageShot(2, usage = UsageFixtures.usage(UsageRange.Month)) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Usage_Cost_Loading() { UsageShot(1, usage = UsageUiState(range = UsageRange.Week, loading = true)) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Usage_Cost_Error() {
    UsageShot(1, usage = UsageUiState(range = UsageRange.Week, failure = UsageFailure.Failed, failureMessage = "Could not read usage from the server."))
}
