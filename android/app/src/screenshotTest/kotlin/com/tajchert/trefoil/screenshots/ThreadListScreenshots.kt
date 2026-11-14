package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.ui.ThreadList
import com.tajchert.trefoil.ui.TrefoilTheme

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun ThreadList_Populated_Light() {
    TrefoilTheme(darkTheme = false) { ThreadList(ScreenshotFixtures.connectedState(), select = {}, create = {}) }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ThreadList_Populated_Dark() {
    TrefoilTheme(darkTheme = true) { ThreadList(ScreenshotFixtures.connectedState(), select = {}, create = {}) }
}
