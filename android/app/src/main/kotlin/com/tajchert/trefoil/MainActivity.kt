package com.tajchert.trefoil

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tajchert.trefoil.ui.TrefoilApp
import com.tajchert.trefoil.ui.FileDownloadUi
import com.tajchert.trefoil.downloads.CompletedDownload
import com.tajchert.trefoil.downloads.DownloadFileActions
import android.widget.Toast

class MainActivity : ComponentActivity() {
    private val model: TrefoilViewModel by viewModels()
    private val fileActions by lazy { DownloadFileActions(this) }
    private fun openDownloaded(file: CompletedDownload, install: Boolean = false, share: Boolean = false) {
        try {
            if ((install || file.isApk && !share) && !fileActions.canInstallPackages()) {
                Toast.makeText(this, "Allow installs for Trefoil, then return and tap Install.", Toast.LENGTH_LONG).show()
                startActivity(fileActions.unknownSourcesIntent())
            } else startActivity(when { share -> fileActions.shareIntent(file); install -> fileActions.installIntent(file); else -> fileActions.openIntent(file) })
        } catch (_: Exception) { Toast.makeText(this, "Could not open this download. Check that the file is still available.", Toast.LENGTH_LONG).show() }
    }
    override fun onStart() { super.onStart(); model.resume() }
    override fun onStop() { model.pause(); super.onStop() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val screenViewer by model.screenSessions.state.collectAsStateWithLifecycle()
            val downloads by model.fileDownloads.state.collectAsStateWithLifecycle()
            val environments by model.environments.collectAsStateWithLifecycle()
            val draft by model.draft.collectAsStateWithLifecycle()
            val notice by model.notice.collectAsStateWithLifecycle()
            val working by model.working.collectAsStateWithLifecycle()
            val sshHostKey by model.sshHostKey.collectAsStateWithLifecycle()
            val sshLogin by model.sshLogin.collectAsStateWithLifecycle()
            val connectionAttempt by model.connectionAttempt.collectAsStateWithLifecycle()
            val machineProbes by model.machineProbes.collectAsStateWithLifecycle()
            TrefoilApp(
                state = state.copy(error = notice ?: state.error, busy = working || state.busy),
                savedEnvironments = environments,
                onPair = model::pair,
                onConnect = model::connect,
                onSelectThread = model::selectThread,
                onSend = model::send,
                onInterrupt = model::interrupt,
                onThreadAction = model::threadAction,
                onUndoSettlement = model::undoSettlement,
                onLoadActivity = model::loadActivity,
                screenViewer = screenViewer,
                onRefreshScreens = model.screenSessions::refresh,
                onOpenScreen = model.screenSessions::open,
                onCloseScreen = model.screenSessions::close,
                isScreenAccessCurrent = model.screenSessions::isCurrent,
                fileDownloadUi = FileDownloadUi(state.environment?.id, state.selectedThreadId,
                    state.connection == com.tajchert.trefoil.core.model.ConnectionStatus.Connected && (state.canRead || state.canWrite), downloads,
                    { environmentId, threadId, reference -> model.fileDownloads.download(reference, environmentId, threadId) },
                    { environmentId, threadId, reference -> model.fileDownloads.cancel(reference, environmentId, threadId) },
                    { openDownloaded(it) }, { openDownloaded(it, share = true) }, { openDownloaded(it, install = true) }),
                onRespond = model::respond,
                draft = draft,
                sendFailed = model.sendFailed,
                onStartDraft = model::startDraft,
                onCancelDraft = model::cancelDraft,
                onSetRuntimeMode = model::setRuntimeMode,
                onSetModel = model::setModel,
                onReconnect = model::reconnect,
                onDisconnect = model::disconnect,
                onForget = model::forget,
                onSshConnect = model::connectSsh,
                sshHostKey = sshHostKey,
                onTrustSsh = model::trustSsh,
                onCancelSsh = model::cancelSsh,
                sshLogin = sshLogin,
                connectionAttempt = connectionAttempt,
                onSshKeyPickerActive = { model.sshFilePickerActive = it },
                machineProbes = machineProbes,
            )
        }
    }
}
