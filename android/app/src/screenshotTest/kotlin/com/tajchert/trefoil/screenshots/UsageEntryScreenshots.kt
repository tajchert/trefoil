package com.tajchert.trefoil.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.ui.MachineMenuItems
import com.tajchert.trefoil.ui.MachinesScreen
import com.tajchert.trefoil.ui.ShellMenuItems
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.ThreadList
import com.tajchert.trefoil.ui.TrefoilTheme

// Entry points into the Usage screen. DropdownMenu popups are not captured, so the menu items are
// rendered in a Column with the same container color and shape as the real popup, overlaid on the screen.

private fun usageState(): ClientState = ShellFixtures.connected().copy(providers = UsageFixtures.providers)

@Composable
private fun Phone(content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    TrefoilTheme(darkTheme = false) { Box(Modifier.fillMaxSize().background(Tf.colors.bg)) { content() } }
}

@Composable
private fun MachinesWithUsage(state: ClientState) =
    MachinesScreen(state, ShellFixtures.saved, onConnect = {}, onForget = {}, onPair = {}, onSsh = {}, onDisconnect = {}, onBack = {}, probes = ShellFixtures.probes, onUsage = {})

@Composable
private fun MenuSurface(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(modifier, color = Tf.colors.sheet, shape = RoundedCornerShape(16.dp), shadowElevation = 3.dp, tonalElevation = 3.dp) {
        Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 112.dp, max = 280.dp).padding(vertical = 8.dp), content = content)
    }
}

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun UsageEntry_MachineCard_Light() = Phone { MachinesWithUsage(usageState()) }

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun UsageEntry_MachineMenu_Light() = Phone {
    val state = usageState()
    MachinesWithUsage(state)
    MenuSurface(Modifier.align(Alignment.TopEnd).padding(top = 150.dp, end = 16.dp)) {
        MachineMenuItems(current = true, live = true, state = state, onUsage = {}, onDisconnect = {}, onForget = {}, close = {})
    }
}

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun UsageEntry_ThreadListMenu_Light() = Phone {
    val state = usageState()
    ThreadList(state, select = {}, create = {}, shell = ShellFixtures.noActions)
    MenuSurface(Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 8.dp)) {
        ShellMenuItems(state, ShellFixtures.noActions, close = {})
    }
}

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 400)
@Composable fun UsageEntry_ThreadListHeader_Light() = Phone {
    ThreadList(usageState(), select = {}, create = {}, shell = ShellFixtures.noActions)
}
