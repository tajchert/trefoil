package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConnectionAttempt
import com.tajchert.trefoil.core.model.ConnectionKind
import com.tajchert.trefoil.core.model.ConnectionPhase
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.SavedEnvironment
import com.tajchert.trefoil.ui.ConnectionBanner
import com.tajchert.trefoil.ui.MachineRail
import com.tajchert.trefoil.ui.MachinesScreen
import com.tajchert.trefoil.ui.NewThreadSheetContent
import com.tajchert.trefoil.ui.PairDialog
import com.tajchert.trefoil.ui.SshConnectionDialog
import com.tajchert.trefoil.ui.SshHostKeyDialog
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.ThreadList
import com.tajchert.trefoil.ui.ThreadListEmpty
import com.tajchert.trefoil.ui.TrefoilTheme

private const val NIGHT = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun Phone(dark: Boolean = false, content: @Composable () -> Unit) {
    TrefoilTheme(darkTheme = dark) { Box(Modifier.fillMaxSize().background(Tf.colors.bg)) { content() } }
}

@Composable
private fun BannerList(status: ConnectionStatus, error: String? = null, dark: Boolean = false) = Phone(dark) {
    val state = ShellFixtures.withConnection(status, error)
    ThreadList(state, select = {}, create = {}, banner = { ConnectionBanner(state, reconnect = {}) })
}

@Composable
private fun Machines(saved: List<SavedEnvironment>, state: ClientState, dark: Boolean = false) = Phone(dark) {
    MachinesScreen(state, saved, onConnect = {}, onForget = {}, onPair = {}, onSsh = {}, onDisconnect = {}, onBack = {}, probes = ShellFixtures.probes)
}

// ThreadList states

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun ThreadList_Empty_Light() = Phone { ThreadList(ShellFixtures.noThreads(), select = {}, create = {}) }

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = NIGHT)
@Composable fun ThreadList_Empty_Dark() = Phone(true) { ThreadList(ShellFixtures.noThreads(), select = {}, create = {}) }

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 400)
@Composable fun ThreadList_SearchNoResults_Light() = Phone { ThreadListEmpty(searching = true) }

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun ThreadList_NeedsApproval_Light() = Phone { ThreadList(ShellFixtures.needsApproval(), select = {}, create = {}) }

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = NIGHT)
@Composable fun ThreadList_NeedsApproval_Dark() = Phone(true) { ThreadList(ShellFixtures.needsApproval(), select = {}, create = {}) }

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun ThreadList_BannerDisconnected_Light() = BannerList(ConnectionStatus.Disconnected)

// No Reconnecting/Connecting banner preview: ConnectionBanner hides itself for 1.5 s after connecting
// starts (LaunchedEffect delay), so a static render never shows it.

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun ThreadList_BannerError_Light() = BannerList(ConnectionStatus.Error, "Could not reach the server. Check the network.")

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = NIGHT)
@Composable fun ThreadList_BannerError_Dark() = BannerList(ConnectionStatus.Error, "Could not reach the server. Check the network.", dark = true)

// Machines

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun Machines_Mixed_Light() = Machines(ShellFixtures.saved, ShellFixtures.connected())

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = NIGHT)
@Composable fun Machines_Mixed_Dark() = Machines(ShellFixtures.saved, ShellFixtures.connected(), dark = true)

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun Machines_Connecting_Light() = Machines(ShellFixtures.saved, ShellFixtures.withConnection(ConnectionStatus.Connecting))

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun Machines_Empty_Light() = Machines(emptyList(), ShellFixtures.nothingConnected())

// Dialogs (content rendered directly; layoutlib does not host dialog windows)

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun PairDialog_Idle_Light() = Phone { PairDialog(busy = false, dismiss = {}, pair = { _, _, _ -> }) }

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun PairDialog_Failed_Light() = Phone {
    PairDialog(busy = false, dismiss = {}, attempt = ConnectionAttempt(1, ConnectionKind.Pairing, ConnectionPhase.Failed, "The pairing link has expired."), pair = { _, _, _ -> })
}

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun SshConnectionDialog_Empty_Light() = Phone { SshConnectionDialog(busy = false, initial = null, dismiss = {}, pickerActive = {}, connect = {}) }

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = NIGHT)
@Composable fun SshConnectionDialog_Empty_Dark() = Phone(true) { SshConnectionDialog(busy = false, initial = null, dismiss = {}, pickerActive = {}, connect = {}) }

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun SshHostKeyDialog_Light() = Phone { SshHostKeyDialog(ShellFixtures.hostKey, trust = {}, cancel = {}) }

// Sheet and tablet

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun NewThreadSheet_Light() = Phone { Column(Modifier.fillMaxWidth()) { NewThreadSheetContent(ScreenshotFixtures.connectedState(), dismiss = {}, start = { _, _ -> }) } }

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun Tablet_RailAndList_Light() = Phone {
    val state = ShellFixtures.connected()
    Row(Modifier.fillMaxSize()) {
        MachineRail(state, ShellFixtures.saved, needsYou = 1, onSelect = {}, actions = ShellFixtures.noActions, probes = ShellFixtures.probes)
        Box(Modifier.width(420.dp)) { ThreadList(state, select = {}, create = {}, shell = ShellFixtures.noActions, railLayout = true) }
    }
}
