package com.tajchert.trefoil

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.tajchert.trefoil.core.model.SshConnectionInput
import com.tajchert.trefoil.core.ssh.SshTunnelConnector
import com.tajchert.trefoil.ui.TrefoilTheme
import kotlinx.coroutines.*

/** Debug-only public-credential loopback fixture. Never opens a vault or real T3 environment. */
class SshShutdownFixtureActivity : ComponentActivity() {
    private var status by mutableStateOf("Starting isolated SSH shutdown check…")
    private var task: Job? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val port = intent.data?.getQueryParameter("port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return finish()
        val fingerprint = intent.data?.getQueryParameter("fingerprint")?.takeIf { it.startsWith("SHA256:") } ?: return finish()
        setContent { TrefoilTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("SSH shutdown fixture", style = MaterialTheme.typography.titleLarge)
                Text("Twelve connect/close cycles against the isolated loopback test server.")
                Text(status)
            }
        } } }
        task = lifecycleScope.launch {
            try {
                repeat(12) { index ->
                    status = "Cycle ${index + 1} / 12"
                    val tunnel = SshTunnelConnector().open(SshConnectionInput("Public fixture", "127.0.0.1", "user", port, password = "pw"), fingerprint)
                    try {
                        val scope = tunnel.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(tunnel) as CoroutineScope
                        val jobs = scope.coroutineContext.job.children.toList()
                        withContext(Dispatchers.IO) { tunnel.close(); tunnel.close() }
                        withTimeout(3000) { jobs.joinAll() }
                        check(!tunnel.active.value)
                    } finally { withContext(NonCancellable + Dispatchers.IO) { tunnel.close() } }
                }
                val spinning = withContext(Dispatchers.IO) { Thread.getAllStackTraces().values.count { trace ->
                    trace.any { it.className.endsWith("LocalPortForwarder") && it.methodName == "listen" }
                } }
                status = "Passed 12 / 12 cycles. Live SSH forwarding loops: $spinning."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { status = "Fixture failed: ${error.javaClass.simpleName}. ${error.message.orEmpty().take(200)}" }
        }
    }
    override fun onStop() { task?.cancel(); super.onStop() }
}
