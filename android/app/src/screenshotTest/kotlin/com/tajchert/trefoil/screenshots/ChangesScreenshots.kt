package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.core.model.CheckpointFile
import com.tajchert.trefoil.core.model.CheckpointStatus
import com.tajchert.trefoil.core.model.ThreadCheckpoint
import com.tajchert.trefoil.ui.TrefoilTheme
import com.tajchert.trefoil.ui.diff.ChangesContent
import com.tajchert.trefoil.ui.diff.TurnDiffState
import com.tajchert.trefoil.ui.diff.newestFirst

/** Synthetic turns for the Changes screenshots (no real threads or paths). */
private object ChangesFixtures {
    private fun turn(count: Int, vararg files: CheckpointFile) =
        ThreadCheckpoint(id = "checkpoint-$count", turnCount = count, status = CheckpointStatus.Ready, files = files.toList())

    val turns: List<ThreadCheckpoint> = listOf(
        turn(1, CheckpointFile("app/src/main/kotlin/demo/Greeter.kt", "modified", 6, 3)),
        turn(
            2, CheckpointFile("app/src/main/kotlin/demo/Greeter.kt", "modified", 14, 4), CheckpointFile("docs/USAGE.md", "modified", 1, 1),
            CheckpointFile("app/src/test/kotlin/demo/GreeterTest.kt", "added", 22, 0),
        ),
        turn(3, CheckpointFile("app/src/main/kotlin/demo/Greeter.kt", "modified", 5, 2), CheckpointFile("docs/USAGE.md", "modified", 1, 1)),
    ).newestFirst()
}

@Composable
private fun ChangesShot(
    dark: Boolean = false, turns: List<ThreadCheckpoint> = ChangesFixtures.turns, selected: Int? = 3, diff: TurnDiffState? = TurnDiffState.Loaded(DiffFixtures.small),
    twoPane: Boolean? = null, onClose: (() -> Unit)? = {}, offline: Boolean = false,
) {
    TrefoilTheme(darkTheme = dark) {
        ChangesContent(
            turns = turns, selectedTurn = selected, onSelectTurn = {}, diff = diff, offline = offline, onReload = {},
            hideTests = false, onHideTestsChange = {}, wrap = false, onWrapChange = {}, onClose = onClose, twoPane = twoPane,
        )
    }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 720)
@Composable
fun Changes_Phone_Light() { ChangesShot() }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Changes_Phone_Dark() { ChangesShot(dark = true) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 480)
@Composable
fun Changes_Phone_Offline() { ChangesShot(offline = true) }

@PreviewTest
@Preview(showBackground = true, widthDp = 960, heightDp = 640)
@Composable
fun Changes_Expanded_TwoPane_Light() { ChangesShot(onClose = null) }

@PreviewTest
@Preview(showBackground = true, widthDp = 960, heightDp = 640, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Changes_Expanded_TwoPane_Dark() { ChangesShot(dark = true, onClose = null) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 480)
@Composable
fun Changes_Empty_Light() { ChangesShot(turns = emptyList(), selected = null, diff = null) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 480)
@Composable
fun Changes_Error_Light() { ChangesShot(diff = TurnDiffState.Failed("The server could not read this checkpoint.")) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 480, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Changes_Error_Dark() { ChangesShot(dark = true, diff = TurnDiffState.Failed("The server could not read this checkpoint.")) }
