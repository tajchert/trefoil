package com.tajchert.trefoil

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.ui.TrefoilTheme
import com.tajchert.trefoil.ui.screens.*

/** Debug-only localhost media fixture. No vault, real credentials, T3 connection, or user thread. */
class ScreenStreamingFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val port = intent?.data?.getQueryParameter("port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return finish()
        val deviceMode = intent?.data?.getQueryParameter("mode") == "device"
        val environment = SavedEnvironment("fixture", "Isolated stream fixture", "http://127.0.0.1:$port/", "fixture-token")
        val target: ScreenTarget = if (deviceMode) ScreenTarget.Device("fixture", "fixture-host", "fixture-device", "android", name = "Synthetic H.264 device")
            else ScreenTarget.Browser("fixture", "fixture", "Remote browser fixture", "http://localhost:5173", true)
        val mediaRoot = if (deviceMode) "device-hub" else "preview-stream"
        fun access(attempt: Int) = ScreenAccess(environment.id, attempt.toLong(), target,
            "http://127.0.0.1:$port/api/$mediaRoot", "ws://127.0.0.1:$port/api/$mediaRoot",
            buildMap { put("wsTicket", "fixture-$attempt"); if (deviceMode) put("hostId", "fixture-host") }, interactive = true)
        setContent {
            TrefoilTheme {
                var fullscreen by rememberSaveable { mutableStateOf(false) }
                var attempt by rememberSaveable { mutableIntStateOf(1) }
                var viewer by remember { mutableStateOf(ScreenViewerState(environment.id, target, access(attempt))) }
                val state = ClientState(environment = environment, connection = ConnectionStatus.Connected, selectedThreadId = target.threadId, canWrite = true,
                    screens = ScreenCatalog(browsers = listOfNotNull(target as? ScreenTarget.Browser),
                        devices = listOfNotNull(target as? ScreenTarget.Device), loaded = true))
                ScreenSessionsScreen(state, viewer, fullscreen, { fullscreen = it }, {}, {
                    attempt++; viewer = ScreenViewerState(environment.id, target, access(attempt))
                }, { fullscreen = false; viewer = ScreenViewerState() }, { finish() },
                    Modifier.fillMaxSize().then(if (fullscreen) Modifier else Modifier.systemBarsPadding()), isAccessCurrent = { true })
            }
        }
    }
}
