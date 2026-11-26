package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.IssueListUiState
import com.tajchert.trefoil.ui.ShellStart
import com.tajchert.trefoil.ui.TrefoilApp
import com.tajchert.trefoil.ui.TrefoilTheme
import com.tajchert.trefoil.ui.issues.IssueListActions
import com.tajchert.trefoil.ui.issues.IssuesScreen
import com.tajchert.trefoil.ui.issues.IssuesUi
import com.tajchert.trefoil.ui.issues.LocalIssueClock
import java.time.Instant

private val pinnedClock: () -> Long = { Instant.parse(IssueListFixtures.NOW).toEpochMilli() }

@Composable
private fun IssueListShot(state: IssueListUiState, dark: Boolean = false, selected: Boolean = false) {
    TrefoilTheme(darkTheme = dark) {
        CompositionLocalProvider(LocalIssueClock provides pinnedClock) {
            IssuesScreen(state, ScreenshotFixtures.projects, IssueDetailFixtures.ref.takeIf { selected }, IssueListActions({}, {}, {}, {}, {}, onBack = {}))
        }
    }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun IssueList_Rows_Light() { IssueListShot(IssueListFixtures.list) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun IssueList_Rows_Dark() { IssueListShot(IssueListFixtures.list, dark = true, selected = true) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun IssueList_Unconfigured_Light() { IssueListShot(IssueListFixtures.unconfigured) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun IssueList_ProjectErrors_Light() { IssueListShot(IssueListFixtures.errors) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun IssueList_Filters_Light() { IssueListShot(IssueListFixtures.filtered) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 600)
@Composable
fun IssueList_NoMatch_Light() { IssueListShot(IssueListFixtures.noMatch) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 600)
@Composable
fun IssueList_Unavailable_Light() { IssueListShot(IssueListFixtures.unavailable) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun IssueList_Offline_Light() { IssueListShot(IssueListFixtures.offline) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun IssueList_Capped_Light() { IssueListShot(IssueListFixtures.capped) }

/** The whole shell on a tablet: the rail's Issues entry selected, the list beside the opened issue. */
@PreviewTest
@Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable
fun IssueList_TwoPane_Light() {
    val state = FormFactorFixtures.listState().copy(issuesSupported = true)
    val issues = IssuesUi(IssueListFixtures.list, IssueDetailFixtures.header.copy(environmentId = IssueListFixtures.ENV))
    CompositionLocalProvider(LocalIssueClock provides pinnedClock) {
        TrefoilApp(state, listOf(ScreenshotFixtures.workstation), { _, _, _ -> }, {}, {}, {}, {}, { _, _, _ -> }, null, {}, {}, {}, {}, {}, {}, {},
            issues = issues, start = ShellStart(showIssues = true))
    }
}
