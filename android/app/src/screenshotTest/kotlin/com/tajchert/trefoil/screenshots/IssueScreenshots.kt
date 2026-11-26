package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.core.model.ThreadIssueLink
import com.tajchert.trefoil.ui.ShellStart
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TrefoilApp
import com.tajchert.trefoil.ui.TrefoilTheme
import com.tajchert.trefoil.ui.adaptive.Fold
import com.tajchert.trefoil.ui.adaptive.LocalFold
import com.tajchert.trefoil.ui.issues.ThreadIssueChip
import com.tajchert.trefoil.ui.issues.ThreadIssueLinksContent

@Composable
private fun ChipFrame(dark: Boolean = false, links: List<ThreadIssueLink>) {
    TrefoilTheme(darkTheme = dark) {
        Surface(Modifier.fillMaxWidth(), color = Tf.colors.bg) { Column(Modifier.padding(16.dp)) { ThreadIssueChip(links, onClick = {}) } }
    }
}

@Composable
private fun SheetFrame(dark: Boolean, onUnlink: ((ThreadIssueLink) -> Unit)? = null) {
    TrefoilTheme(darkTheme = dark) {
        Surface(Modifier.fillMaxWidth(), color = Tf.colors.sheet) { ThreadIssueLinksContent(IssueFixtures.three, onOpen = {}, onUnlink = onUnlink) }
    }
}

/** The thread screen with linked issues on the selected thread; the header budget decides whether the chip fits. */
@Composable
private fun App(links: List<ThreadIssueLink>, fold: Fold? = null) = CompositionLocalProvider(LocalFold provides fold) {
    TrefoilApp(IssueFixtures.withIssues(FormFactorFixtures.conversationState(), links), listOf(ScreenshotFixtures.workstation),
        { _, _, _ -> }, {}, {}, {}, {}, { _, _, _ -> }, null, {}, {}, {}, {}, {}, {}, {}, start = ShellStart(showThread = true))
}

/** One linked issue: its key. */
@PreviewTest @Preview(showBackground = true, widthDp = 200, heightDp = 64)
@Composable fun Issue_Chip_One_Light() = ChipFrame(links = IssueFixtures.one)

/** Three linked issues: the first key and "+2". */
@PreviewTest @Preview(showBackground = true, widthDp = 200, heightDp = 64)
@Composable fun Issue_Chip_Plus2_Light() = ChipFrame(links = IssueFixtures.three)

@PreviewTest @Preview(showBackground = true, widthDp = 200, heightDp = 64, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable fun Issue_Chip_Plus2_Dark() = ChipFrame(dark = true, links = IssueFixtures.three)

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 480)
@Composable fun Issue_LinksSheet_Light() = SheetFrame(dark = false)

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 480, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable fun Issue_LinksSheet_Dark() = SheetFrame(dark = true)

/** Rows with the Unlink action T7 turns on. */
@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 480)
@Composable fun Issue_LinksSheet_Unlink_Light() = SheetFrame(dark = false, onUnlink = {})

/** A wide conversation pane keeps the chip in the header. */
@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun Issue_Header_Wide_Light() = App(IssueFixtures.three)

/** A narrow conversation pane (fold portrait) demotes the chip into the thread menu as "Issues (N)", so the header shows none. */
@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun Issue_Header_NarrowDemoted_Light() = App(IssueFixtures.three)
