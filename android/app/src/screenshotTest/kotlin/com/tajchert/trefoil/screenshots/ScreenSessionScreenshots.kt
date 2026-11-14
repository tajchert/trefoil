package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ScreenCatalog
import com.tajchert.trefoil.ui.TrefoilTheme
import com.tajchert.trefoil.ui.screens.ScreenSessionsScreen
import com.tajchert.trefoil.ui.screens.ScreenViewerState

@Composable
private fun SessionsShot(catalog: ScreenCatalog, dark: Boolean = false) {
    val state: ClientState = ScreenshotFixtures.connectedState().copy(selectedThreadId = "t-approval", screens = catalog)
    TrefoilTheme(darkTheme = dark) {
        ScreenSessionsScreen(
            state, ScreenViewerState(), fullscreen = false, onFullscreen = {}, onRefresh = {},
            onOpen = {}, onClose = {}, onLeave = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun ScreenSessions_List_Light() { SessionsShot(UsageFixtures.screenCatalog) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ScreenSessions_List_Dark() { SessionsShot(UsageFixtures.screenCatalog, dark = true) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun ScreenSessions_Empty() { SessionsShot(ScreenCatalog(loaded = true)) }
