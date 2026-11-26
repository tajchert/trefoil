package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.core.model.DiffDocument
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TrefoilTheme
import com.tajchert.trefoil.ui.diff.DiffViewer

@Composable
private fun DiffShot(document: DiffDocument, dark: Boolean = false, wrap: Boolean = false, toolbar: Boolean = false) {
    TrefoilTheme(darkTheme = dark) {
        Box(Modifier.fillMaxSize().background(Tf.colors.bg)) {
            DiffViewer(document, wrap = wrap, onWrapChange = if (toolbar) ({}) else null)
        }
    }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 640)
@Composable
fun Diff_Small_Light() { DiffShot(DiffFixtures.small) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 640, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Diff_Small_Dark() { DiffShot(DiffFixtures.small, dark = true) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 640)
@Composable
fun Diff_LargeCollapsed_Light() { DiffShot(DiffFixtures.largeCollapsed) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 640)
@Composable
fun Diff_RenameBinary_Light() { DiffShot(DiffFixtures.renameBinary) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 640, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Diff_RenameBinary_Dark() { DiffShot(DiffFixtures.renameBinary, dark = true) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 640)
@Composable
fun Diff_Truncated_Light() { DiffShot(DiffFixtures.truncated) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 480)
@Composable
fun Diff_Wrap_Off() { DiffShot(DiffFixtures.longLines, wrap = false, toolbar = true) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 480)
@Composable
fun Diff_Wrap_On() { DiffShot(DiffFixtures.longLines, wrap = true, toolbar = true) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 480, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Diff_Wrap_On_Dark() { DiffShot(DiffFixtures.longLines, dark = true, wrap = true, toolbar = true) }
