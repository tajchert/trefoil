package com.tajchert.trefoil.screenshots

import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.MachineProbe
import com.tajchert.trefoil.core.model.SavedEnvironment
import com.tajchert.trefoil.core.model.SshHostKey
import com.tajchert.trefoil.core.model.SshProfile
import com.tajchert.trefoil.ui.ShellActions

/** Fake data for shell and connection previews. Hosts use the reserved `.example.test` domain. */
internal object ShellFixtures {
    val buildServer = SavedEnvironment(
        id = "env-build", name = "Build server", baseUrl = "",
        accessToken = "fake-token",
        ssh = SshProfile(host = "build.example.test", username = "dev", fingerprint = "SHA256:fakeFingerprintForPreviewsOnly000000000", keyType = "ssh-ed25519"),
    )
    val homeLab = SavedEnvironment(id = "env-home", name = "Home lab", baseUrl = "http://192.168.1.20:3773", accessToken = "fake-token", allowInsecureHttp = true)
    val saved = listOf(ScreenshotFixtures.workstation, ScreenshotFixtures.laptop, buildServer, homeLab)

    val probes = mapOf(
        ScreenshotFixtures.laptop.id to MachineProbe.Unreachable,
        buildServer.id to MachineProbe.Checking,
        homeLab.id to MachineProbe.AuthFailed,
    )

    val noActions = ShellActions(onMachines = {}, onConnectMachine = {}, onScreens = {}, onUsage = {}, onDisconnect = {})

    val hostKey = SshHostKey(
        host = "build.example.test", port = 22, keyType = "ssh-ed25519",
        fingerprint = "SHA256:fakeFingerprintForPreviewsOnly000000000",
    )

    fun connected(): ClientState = ScreenshotFixtures.connectedState()
    fun noThreads(): ClientState = ScreenshotFixtures.connectedState(emptyList())
    fun needsApproval(): ClientState = ScreenshotFixtures.connectedState(
        ScreenshotFixtures.threads.filter { it.pendingRequestKind != null || it.id == "t-recent" },
    )
    fun withConnection(status: ConnectionStatus, error: String? = null): ClientState =
        connected().copy(connection = status, error = error)
    /** Nothing connected: the Machines first-run state. */
    fun nothingConnected(): ClientState = ClientState()
}
