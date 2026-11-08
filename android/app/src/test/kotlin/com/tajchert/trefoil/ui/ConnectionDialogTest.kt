package com.tajchert.trefoil.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.tajchert.trefoil.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectionDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sshFieldsSurviveTestingAndFailureAndUsernameIsTrimmed() {
        val busy = mutableStateOf(false)
        val attempt = mutableStateOf(ConnectionAttempt())
        var submitted: SshConnectionInput? = null
        compose.setContent { MaterialTheme { SshConnectionDialog(busy.value, null, {}, {}, attempt.value) { submitted = it } } }
        compose.onNodeWithText("Display name").performTextInput("My server")
        compose.onNodeWithText("IP address or hostname").performTextInput(" host.local ")
        compose.onNodeWithText("SSH username").performTextInput("alice ")
        compose.onNodeWithText("SSH password").performScrollTo().performTextInput("secret")
        compose.onNodeWithText("Connect", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals("alice", submitted?.username); busy.value = true; attempt.value = ConnectionAttempt(1, ConnectionKind.Ssh, ConnectionPhase.Testing) }
        compose.onNodeWithText("SSH username").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("alice ").assertExists()
        compose.runOnIdle { busy.value = false; attempt.value = ConnectionAttempt(1, ConnectionKind.Ssh, ConnectionPhase.Failed, "Authentication failed. Check your credentials.") }
        compose.onNodeWithText("Authentication failed. Check your credentials.").assertIsDisplayed()
        compose.onNodeWithText("SSH username").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("alice ").assertExists()
    }

    @Test fun sshFormSurvivesHostKeyOverlayAndClosesAfterSuccessfulAttempt() {
        val attempt = mutableStateOf(ConnectionAttempt())
        val hostKey = mutableStateOf<SshHostKey?>(null)
        compose.setContent { TrefoilApp(
            state = ClientState(), savedEnvironments = emptyList(), onPair = { _, _, _ -> }, onConnect = {},
            onSelectThread = {}, onSend = {}, onInterrupt = {}, onRespond = { _, _, _ -> },
            draft = null, onStartDraft = {}, onCancelDraft = {}, onSetRuntimeMode = {}, onSetModel = {}, onReconnect = {}, onDisconnect = {}, onForget = {},
            onSshConnect = { attempt.value = ConnectionAttempt(1, ConnectionKind.Ssh, ConnectionPhase.Testing) },
            sshHostKey = hostKey.value, onTrustSsh = { hostKey.value = null }, connectionAttempt = attempt.value,
        ) }
        compose.onNodeWithText("Connect over SSH").performScrollTo().performClick()
        compose.onNodeWithText("Display name").performTextInput("My server")
        compose.onNodeWithText("IP address or hostname").performTextInput("host.local")
        compose.onNodeWithText("SSH username").performTextInput("alice")
        compose.onNodeWithText("SSH password").performScrollTo().performTextInput("secret")
        compose.onNodeWithText("Connect", useUnmergedTree = true).performClick()
        compose.onNodeWithText("SSH username").assertExists()
        compose.runOnIdle {
            attempt.value = ConnectionAttempt(1, ConnectionKind.Ssh, ConnectionPhase.AwaitingTrust)
            hostKey.value = SshHostKey("host.local", 22, "ssh-ed25519", "SHA256:fixture")
        }
        compose.onNodeWithText("Verify SSH host key").assertIsDisplayed()
        compose.onNodeWithText("Trust and connect").performClick()
        compose.onNodeWithText("SSH username").performScrollTo()
        compose.onNodeWithText("alice").assertExists()
        compose.runOnIdle { attempt.value = ConnectionAttempt(2, ConnectionKind.Ssh, ConnectionPhase.Failed, "Authentication failed.") }
        compose.onNodeWithText("Authentication failed.").assertIsDisplayed()
        compose.onNodeWithText("Connect", useUnmergedTree = true).performClick()
        compose.runOnIdle { attempt.value = ConnectionAttempt(3, ConnectionKind.Ssh, ConnectionPhase.Succeeded) }
        compose.onNodeWithText("SSH username").assertDoesNotExist()
    }

    @Test fun pastedPublicKeyShowsInlineAndIsRevealed() {
        compose.setContent { MaterialTheme { SshConnectionDialog(false, SshConnectionInput("Server", "host", "alice", authMethod = SshAuthMethod.PrivateKey), {}, {}) {} } }
        compose.onNodeWithText("Paste private key").performScrollTo().performTextInput("ssh-ed25519 AAAA public-comment")
        compose.onNodeWithText("This is a public key. Choose the private key file without the .pub extension.").assertIsDisplayed()
    }

    @Test fun pairingFieldsRetainedDuringFailureAndPrivateHttpRequiresOptIn() {
        val busy = mutableStateOf(false)
        val attempt = mutableStateOf(ConnectionAttempt())
        var accepted: Boolean? = null
        compose.setContent { MaterialTheme { PairDialog(busy.value, {}, attempt.value) { _, _, insecure -> accepted = insecure } } }
        compose.onNodeWithText("Display name").performTextInput("LAN")
        compose.onNodeWithText("Pairing link").performTextInput("http://192.168.1.5:3773/?token=secret")
        compose.onNodeWithText("Allow unencrypted HTTP on this private network").performScrollTo().assertExists()
        compose.onNode(isToggleable()).assertIsOff()
        compose.onNodeWithText("Pair").performClick()
        compose.runOnIdle { assertNull(accepted) }
        compose.onNodeWithText("Choose whether to allow HTTP on this private network.").assertIsDisplayed()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("Pair").performClick()
        compose.runOnIdle { assertEquals(true, accepted); busy.value = true }
        compose.onNodeWithText("Display name").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("LAN").assertExists()
        compose.runOnIdle { busy.value = false; attempt.value = ConnectionAttempt(1, ConnectionKind.Pairing, ConnectionPhase.Failed, "Pairing failed. Check the link.") }
        compose.onNodeWithText("Pairing failed. Check the link.").assertIsDisplayed()
    }
}
