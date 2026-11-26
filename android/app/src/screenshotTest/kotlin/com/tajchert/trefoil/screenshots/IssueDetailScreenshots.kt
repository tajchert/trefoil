package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.IssueDetailUiState
import com.tajchert.trefoil.IssueLinkAction
import com.tajchert.trefoil.ui.TrefoilTheme
import com.tajchert.trefoil.ui.issues.IssueDetailActions
import com.tajchert.trefoil.ui.issues.IssueDetailContent
import com.tajchert.trefoil.ui.issues.IssueLinkToolbarButton
import com.tajchert.trefoil.ui.issues.LocalIssueTimeZone
import java.time.ZoneOffset

@Composable
private fun IssueDetailShot(state: IssueDetailUiState, dark: Boolean = false, extras: @Composable RowScope.() -> Unit = {}) {
    TrefoilTheme(darkTheme = dark) {
        CompositionLocalProvider(LocalIssueTimeZone provides ZoneOffset.UTC) {
            IssueDetailContent(state, IssueDetailActions({}, {}, {}, {}, {}, {}), toolbarExtras = extras)
        }
    }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun IssueDetail_Header_Light() { IssueDetailShot(IssueDetailFixtures.header) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun IssueDetail_Header_Dark() { IssueDetailShot(IssueDetailFixtures.header, dark = true) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 1400)
@Composable
fun IssueDetail_Activity_Light() { IssueDetailShot(IssueDetailFixtures.activity) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 1000)
@Composable
fun IssueDetail_LoadOlder_Light() { IssueDetailShot(IssueDetailFixtures.loadOlderFailed) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun IssueDetail_LinkedPullRequests_Light() { IssueDetailShot(IssueDetailFixtures.pullRequestsState) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun IssueDetail_LinkedThreads_Light() { IssueDetailShot(IssueDetailFixtures.threads) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 600)
@Composable
fun IssueDetail_Unavailable_Light() { IssueDetailShot(IssueDetailFixtures.unavailable) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 1000)
@Composable
fun IssueDetail_Disconnected_Light() { IssueDetailShot(IssueDetailFixtures.disconnected) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 400)
@Composable
fun IssueDetail_LinkButton_Light() { IssueDetailShot(IssueDetailFixtures.header) { IssueLinkToolbarButton(IssueLinkAction.Link, pending = false, onLink = {}, onUnlink = {}) } }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun IssueDetail_UnlinkButton_Dark() { IssueDetailShot(IssueDetailFixtures.header, dark = true) { IssueLinkToolbarButton(IssueLinkAction.Unlink, pending = false, onLink = {}, onUnlink = {}) } }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 400)
@Composable
fun IssueDetail_LinkPending_Light() { IssueDetailShot(IssueDetailFixtures.header) { IssueLinkToolbarButton(IssueLinkAction.Link, pending = true, onLink = {}, onUnlink = {}) } }
