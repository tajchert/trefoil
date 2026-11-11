package com.tajchert.trefoil.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MachinesRenderTest {
    @get:Rule val compose = createComposeRule()

    private val macMini = SavedEnvironment("mini", "Mac mini", "http://192.168.10.199:3773", "t")
    private val laptop = SavedEnvironment("book", "MacBook Pro", "https://macbook.example.com", "t")
    private val server = SavedEnvironment("home", "home-server", "http://127.0.0.1:41000", "t", ssh = SshProfile("home.tailnet.ts.net", 22, "mike", "SHA256:fixture", "ssh-ed25519"))
    private val saved = listOf(macMini, laptop, server)
    private val connected = ClientState(
        environment = macMini, connection = ConnectionStatus.Connected, canWrite = true,
        threads = listOf(
            ThreadSummary("a", "p", "Working one", "running"), ThreadSummary("b", "p", "Working two", "running"),
            ThreadSummary("c", "p", "Needs approval", "running", pendingRequestKind = "approval"),
            ThreadSummary("d", "p", "Needs input", "idle", pendingRequestKind = "user-input"),
            ThreadSummary("e", "p", "Done", "completed"),
        ),
    )

    private fun render(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent { TrefoilTheme(darkTheme = dark) { Box(Modifier.size(390.dp, 844.dp).background(Tf.colors.bg)) { content() } } }
        compose.waitForIdle()
        val output = File("build/reports/ui/design-$name-${if (dark) "dark" else "light"}.png").apply { parentFile?.mkdirs() }
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(output.length() > 0)
    }

    @Composable private fun Machines(state: ClientState, saved: List<SavedEnvironment>, onBack: (() -> Unit)?) =
        MachinesScreen(state, saved, {}, {}, {}, {}, {}, onBack, Modifier.fillMaxSize())

    @Composable private fun Sheet() {
        val state = ClientState(
            canWrite = true, projects = listOf("backend", "kanarek-android", "mac-mini-setup", "sroka", "t3code", "trefoil", "zappka", "alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta", "iota", "kappa", "lambda", "mu")
                .mapIndexed { i, t -> Project("p$i", t) },
            models = listOf(ModelOption("codex", "gpt-6.1-sol", "GPT-6.1-Sol")), defaultRuntimeMode = RuntimeMode.FullAccess,
        )
        Box(Modifier.fillMaxSize().background(Tf.colors.scrim), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.fillMaxWidth().height(660.dp).background(Tf.colors.sheet, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))) {
                Column {
                    Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp, bottom = 8.dp).size(36.dp, 4.dp).background(Tf.colors.s3, RoundedCornerShape(2.dp)))
                    NewThreadSheetContent(state, {}, { _, _ -> })
                }
            }
        }
    }

    @Test fun machinesDark() = render("machines", true) { Machines(connected, saved, {}) }
    @Test fun machinesLight() = render("machines", false) { Machines(connected, saved, {}) }
    @Test fun onboardingDark() = render("machines-onboarding", true) { Machines(ClientState(), emptyList(), null) }
    @Test fun onboardingLight() = render("machines-onboarding", false) { Machines(ClientState(), saved, null) }
    @Test fun newThreadDark() = render("newthread", true) { Sheet() }
    @Test fun newThreadLight() = render("newthread", false) { Sheet() }

    @Test fun forgetAsksForConfirmationAndDisconnectIsOfferedForCurrentOnly() {
        var forgotten: SavedEnvironment? = null
        var disconnected = false
        compose.setContent { TrefoilTheme { MachinesScreen(connected, saved, {}, { forgotten = it }, {}, {}, { disconnected = true }, null) } }
        compose.onNodeWithContentDescription("Options for MacBook Pro").performClick()
        compose.onNodeWithText("Disconnect").assertDoesNotExistOrFail()
        compose.onNodeWithText("Forget").performClick()
        assertEquals(null, forgotten)
        compose.onNodeWithText("Forget MacBook Pro?").assertExistsOrFail()
        compose.onNodeWithText("Forget").performClick()
        compose.runOnIdle { assertEquals(laptop, forgotten) }
        compose.onNodeWithContentDescription("Options for Mac mini").performClick()
        compose.onNodeWithText("Disconnect").performClick()
        compose.runOnIdle { assertTrue(disconnected) }
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistOrFail() = assertDoesNotExist()
private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsOrFail() = assertExists()
