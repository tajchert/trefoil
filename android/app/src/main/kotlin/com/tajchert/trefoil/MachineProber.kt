package com.tajchert.trefoil

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.ssh.SshConnectionException
import com.tajchert.trefoil.core.ssh.SshHostKeyException
import kotlinx.coroutines.*

/** A short-lived SSH tunnel opened only to probe; [close] must be safe to call from IO. */
interface ProbeTunnel { val baseUrl: String; fun close() }

/**
 * Checks saved machines that are not the active connection. Read-only: direct machines get
 * the descriptor + session check; SSH machines are probed only with saved credentials, through
 * a tunnel that verifies the pinned host key and never prompts or trusts a new one.
 */
class MachineProber(
    private val direct: suspend (SavedEnvironment, String) -> MachineProbe,
    private val openSsh: suspend (SshConnectionInput, String?) -> ProbeTunnel,
    private val directTimeoutMillis: Long = 8_000,
    /** SSH login plus runtime discovery needs a few round trips before the HTTP check. */
    private val sshTimeoutMillis: Long = 15_000,
) {
    suspend fun probe(environment: SavedEnvironment, hostKeys: List<SshHostKey>): MachineProbe = try {
        if (environment.ssh == null) withTimeoutOrNull(directTimeoutMillis) { direct(environment, environment.baseUrl) } ?: MachineProbe.Unreachable
        else probeSsh(environment, hostKeys)
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { MachineProbe.Unreachable }

    private suspend fun probeSsh(environment: SavedEnvironment, hostKeys: List<SshHostKey>): MachineProbe {
        val input = environment.sshInput()
        val hasCredentials = when (input.authMethod) {
            SshAuthMethod.Password -> input.password.isNotEmpty()
            SshAuthMethod.PrivateKey -> input.privateKey.isNotEmpty()
        }
        if (!hasCredentials) return MachineProbe.Unknown
        if (sshInputValidationError(input) != null) return MachineProbe.NeedsAttention
        val profile = environment.ssh!!
        val pinned = hostKeys.firstOrNull { it.host.equals(input.host, ignoreCase = true) && it.port == input.port }?.fingerprint
            ?: profile.fingerprint.takeIf { it.isNotBlank() }
            // Without a pinned key the probe would have to ask for trust; that only happens on an explicit connect.
            ?: return MachineProbe.NeedsAttention
        return withTimeoutOrNull(sshTimeoutMillis) {
            val tunnel = try { openSsh(input, pinned) }
            catch (e: CancellationException) { throw e }
            catch (_: SshHostKeyException) { return@withTimeoutOrNull MachineProbe.NeedsAttention }
            catch (e: SshConnectionException) { return@withTimeoutOrNull sshFailure(e) }
            try { direct(environment, tunnel.baseUrl) }
            finally { withContext(NonCancellable + Dispatchers.IO) { runCatching { tunnel.close() } } }
        } ?: MachineProbe.Unreachable
    }

    private fun sshFailure(error: SshConnectionException): MachineProbe {
        val message = error.message.orEmpty()
        return when {
            message.startsWith("SSH authentication failed") || message.startsWith("Could not unlock the private key") -> MachineProbe.AuthFailed
            message.contains("identity changed") || message.startsWith("Unsupported T3 runtime") || message.contains("compatible protocol") -> MachineProbe.NeedsAttention
            else -> MachineProbe.Unreachable
        }
    }
}

/** What to do about auto-connect on app open, given the probes so far. */
sealed interface AutoConnectDecision {
    data object Wait : AutoConnectDecision
    data object None : AutoConnectDecision
    data class Connect(val environmentId: String) : AutoConnectDecision
}

/**
 * Prefer the last-used machine when it is reachable; otherwise connect only when exactly one
 * machine is reachable. Waits while a deciding probe is still running.
 */
fun autoConnectDecision(lastUsedId: String?, environments: List<SavedEnvironment>, probes: Map<String, MachineProbe>): AutoConnectDecision {
    val lastUsed = environments.firstOrNull { it.id == lastUsedId }
    if (lastUsed != null) when (probes[lastUsed.id]) {
        MachineProbe.Online -> return AutoConnectDecision.Connect(lastUsed.id)
        MachineProbe.Checking -> return AutoConnectDecision.Wait
        else -> {}
    }
    if (environments.any { probes[it.id] == MachineProbe.Checking }) return AutoConnectDecision.Wait
    val online = environments.filter { probes[it.id] == MachineProbe.Online }
    return if (online.size == 1) AutoConnectDecision.Connect(online.single().id) else AutoConnectDecision.None
}
