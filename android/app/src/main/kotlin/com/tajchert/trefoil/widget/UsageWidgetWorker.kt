package com.tajchert.trefoil.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.session.T3Client
import com.tajchert.trefoil.core.ssh.SshTunnel
import com.tajchert.trefoil.core.ssh.SshTunnelConnector
import com.tajchert.trefoil.security.EnvironmentVault
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

internal fun widgetPinnedFingerprint(environment: SavedEnvironment, keys: List<SshHostKey>): String? {
    val profile = environment.ssh ?: return null
    return keys.firstOrNull { it.host.equals(profile.host, true) && it.port == profile.port }?.fingerprint?.takeIf { it.isNotBlank() }
        ?: profile.fingerprint.takeIf { it.isNotBlank() }
}

internal fun widgetRefreshEligible(environment: SavedEnvironment, keys: List<SshHostKey>): Boolean {
    if (environment.ssh == null) return true
    val input = environment.sshInput()
    return sshInputValidationError(input) == null && widgetPinnedFingerprint(environment, keys) != null &&
        when (input.authMethod) {
            SshAuthMethod.Password -> input.password.isNotEmpty()
            SshAuthMethod.PrivateKey -> input.privateKey.isNotEmpty()
        }
}

internal fun skipWidgetNetwork(snapshot: UsageWidgetSnapshot, liveEnvironmentId: String?, periodic: Boolean, now: Long): Boolean =
    liveEnvironmentId == snapshot.environmentId || (periodic && now - snapshot.updatedAtMillis in 0 until WIDGET_FETCH_INTERVAL_MS)

class UsageWidgetWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val store = UsageWidgetStore.get(applicationContext)
        var targetId: String? = null
        try {
            val snapshot = store.read() ?: return Result.success()
            targetId = snapshot.environmentId
            if (skipWidgetNetwork(snapshot, UsageWidgetLiveConnection.environmentId(), inputData.getBoolean("periodic", false),
                    System.currentTimeMillis())) return Result.success()
            val vault = EnvironmentVault(applicationContext)
            val (environment, keys) = withContext(Dispatchers.IO) {
                vault.read().firstOrNull { it.id == snapshot.environmentId } to vault.readHostKeys()
            }
            if (environment == null) {
                store.update { it?.takeUnless { value -> value.environmentId == snapshot.environmentId } }
                return Result.success()
            }
            if (!widgetRefreshEligible(environment, keys)) {
                store.update { if (it?.environmentId == environment.id) it.copy(backgroundRefresh = false,
                    lastError = "Open Trefoil to refresh this machine.", lastErrorAtMillis = System.currentTimeMillis()) else it }
                return Result.success()
            }
            refresh(environment, keys, store)
        } catch (_: TimeoutCancellationException) {
            store.update { if (it?.environmentId == targetId && it != null) it.copy(lastError = "Usage refresh timed out.",
                lastErrorAtMillis = System.currentTimeMillis()) else it }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            store.update { if (it?.environmentId == targetId && it != null) it.copy(lastError = "Could not refresh usage.",
                lastErrorAtMillis = System.currentTimeMillis()) else it }
        } finally {
            withContext(NonCancellable) { UsageWidgets.updateAll(applicationContext) }
        }
        return Result.success()
    }

    private suspend fun refresh(environment: SavedEnvironment, keys: List<SshHostKey>, store: UsageWidgetStore) = coroutineScope {
        val connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = T3Client(connectionScope)
        var tunnel: SshTunnel? = null
        try {
            withTimeout(30_000) {
                val endpoint = if (environment.ssh == null) environment else {
                    val opened = SshTunnelConnector().open(environment.sshInput(), widgetPinnedFingerprint(environment, keys))
                    tunnel = opened
                    environment.copy(baseUrl = opened.baseUrl)
                }
                client.connect(endpoint)
                client.state.first { it.connection == ConnectionStatus.Connected || it.connection == ConnectionStatus.Disconnected }
                    .let { check(it.connection == ConnectionStatus.Connected) }
            }
            client.refreshUsageLimits(false)
            val reports = UsageRange.entries.map { client.usageReport(it, ZoneId.systemDefault().id, false) }
            val now = System.currentTimeMillis()
            store.update { old ->
                // A foreground environment switch/forget always wins over this older background read.
                if (old?.environmentId != environment.id || UsageWidgetLiveConnection.environmentId() != null) old else {
                    var next: UsageWidgetSnapshot? = mergeWidgetSnapshot(old, environment, widgetProviders(client.state.value.providers), now, true)
                    reports.forEach { next = mergeWidgetReport(next, it, now) }
                    next?.copy(updatedAtMillis = now, lastError = null, lastErrorAtMillis = null)
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try { client.disconnect() } finally {
                    client.close()
                    try { tunnel?.close() } finally { connectionScope.cancel() }
                }
            }
        }
    }
}
