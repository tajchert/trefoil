package com.tajchert.trefoil.screenshots

import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.ConversationMessage
import com.tajchert.trefoil.core.model.ModelOption
import com.tajchert.trefoil.core.model.Project
import com.tajchert.trefoil.core.model.SavedEnvironment
import com.tajchert.trefoil.core.model.ThreadSummary
import java.time.Instant

/**
 * Fake data for preview screenshot tests. Never put real machines, tokens, threads or conversation
 * content here. Times are relative to render time ([ago]) so relative labels ("5m", "2h") stay stable.
 */
object ScreenshotFixtures {
    fun ago(minutes: Long): String = Instant.now().minusSeconds(minutes * 60).toString()

    val workstation = SavedEnvironment(id = "env-workstation", name = "Workstation", baseUrl = "https://workstation.example.test", accessToken = "fake-token")
    val laptop = SavedEnvironment(id = "env-laptop", name = "Laptop", baseUrl = "https://laptop.example.test", accessToken = "fake-token")
    val environments = listOf(workstation, laptop)

    val trefoil = Project(id = "p-trefoil", title = "trefoil", path = "/home/dev/trefoil")
    val website = Project(id = "p-website", title = "website", path = "/home/dev/website")
    val projects = listOf(trefoil, website)

    val models = listOf(ModelOption(instanceId = "codex", model = "gpt-5", label = "GPT-5"))

    fun thread(
        id: String, title: String, project: Project = trefoil, status: String = "idle",
        updatedMinutesAgo: Long = 30, latestMessage: String = "", branch: String? = null,
        pendingRequestKind: String? = null, pinned: Boolean = false,
    ) = ThreadSummary(
        id = id, projectId = project.id, title = title, status = status,
        createdAt = ago(updatedMinutesAgo + 60), updatedAt = ago(updatedMinutesAgo),
        latestUserMessageAt = ago(updatedMinutesAgo), latestMessage = latestMessage, branch = branch,
        pendingRequestKind = pendingRequestKind, pinnedAt = if (pinned) ago(600) else null,
    )

    val threads = listOf(
        thread("t-approval", "Add SSH host key check", pendingRequestKind = "approval", updatedMinutesAgo = 3,
            latestMessage = "Run ./gradlew :core:ssh:test?", branch = "feat/host-keys"),
        thread("t-pinned", "Release checklist", pinned = true, updatedMinutesAgo = 240, latestMessage = "All checks green."),
        thread("t-recent", "Fix dark mode contrast", project = website, updatedMinutesAgo = 45, latestMessage = "Updated the palette tokens."),
        thread("t-older", "Refactor usage widget", updatedMinutesAgo = 60 * 26, latestMessage = "Done, see the diff."),
    )

    fun messages(): List<ConversationMessage> = listOf(
        ConversationMessage(id = "m1", role = "user", text = "Can you add a dark theme preview?", createdAt = ago(10)),
        ConversationMessage(id = "m2", role = "assistant", text = "Sure. I added **light** and **dark** previews.", createdAt = ago(9)),
    )

    fun connectedState(threads: List<ThreadSummary> = this.threads) = ClientState(
        environment = workstation, connection = ConnectionStatus.Connected, projects = projects,
        threads = threads, models = models, canRead = true, canWrite = true, canWriteThreads = true,
    )
}
