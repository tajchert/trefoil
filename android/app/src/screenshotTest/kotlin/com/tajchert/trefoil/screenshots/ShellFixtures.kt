package com.tajchert.trefoil.screenshots

import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.ConversationMessage
import com.tajchert.trefoil.core.model.InspectorThread
import com.tajchert.trefoil.core.model.LocalServerCatalog
import com.tajchert.trefoil.core.model.MachineProbe
import com.tajchert.trefoil.core.model.SavedEnvironment
import com.tajchert.trefoil.core.model.SideThreadKind
import com.tajchert.trefoil.core.model.SshHostKey
import com.tajchert.trefoil.core.model.SshProfile
import com.tajchert.trefoil.ui.ShellActions
import com.tajchert.trefoil.ui.threadIndicator

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
    /** Connected list with no thread waiting for the user, nothing selected. */
    fun quiet(): ClientState = FormFactorFixtures.listState().let { state ->
        state.copy(threads = state.threads.filter { threadIndicator(it).kind != "attention" && threadIndicator(it).kind != "input" })
    }
    /**
     * The Add screenshot previews conversation with everything the side panel can show: its dev servers (5173 started by the thread),
     * the screen catalog and a running subagent roster.
     */
    fun sidePanel(): ClientState = FormFactorFixtures.conversationState().copy(
        subagents = ConversationFixtures.subagents,
        localServers = LocalServerCatalog(
            servers = listOf(PortsFixtures.vite.copy(agentThreadId = "t-chat", terminalThreadId = "t-chat"), PortsFixtures.api.copy(terminalThreadId = "t-chat")),
            live = true, supported = true,
        ),
        screens = FormFactorFixtures.screensState().screens,
    )
    /**
     * The Thread tab: the sidePanel conversation with the running subagent "Audit SSH host key handling" open read-only beside it. The child is a
     * thread of the selected one, and its streamed rows arrive as [ClientState.inspector].
     */
    fun inspectorThread(): ClientState = sidePanel().let { state ->
        val child = ConversationFixtures.subagentThread.copy(parentThreadId = ConversationFixtures.thread.id, relationshipToParent = "subagent")
        state.copy(
            threads = state.threads + child, shellLive = true,
            inspector = InspectorThread(
                child.id, SideThreadKind.Subagent, ready = true,
                messages = listOf(
                    ConversationMessage(id = "i1", role = "user", text = "Audit how host keys are pinned and what happens when one changes.", ordinal = 1),
                    ConversationMessage(id = "i2", role = "assistant", text = "Host keys are pinned on first use. A changed key fails closed before authentication, and the mismatch is reported to the caller.", ordinal = 4),
                ),
                activities = listOf(ConversationFixtures.readActivity.copy(ordinal = 2), ConversationFixtures.readActivity2.copy(ordinal = 3)),
            ),
        )
    }
    /** Nothing connected: the Machines first-run state. */
    fun nothingConnected(): ClientState = ClientState()
}
