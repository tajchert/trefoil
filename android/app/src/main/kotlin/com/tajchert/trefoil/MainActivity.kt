package com.tajchert.trefoil

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tajchert.trefoil.ui.TrefoilApp

class MainActivity : ComponentActivity() {
    private val model: TrefoilViewModel by viewModels()
    override fun onStart() { super.onStart(); model.resume() }
    override fun onStop() { model.pause(); super.onStop() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val environments by model.environments.collectAsStateWithLifecycle()
            val notice by model.notice.collectAsStateWithLifecycle()
            val working by model.working.collectAsStateWithLifecycle()
            val sshHostKey by model.sshHostKey.collectAsStateWithLifecycle()
            val sshLogin by model.sshLogin.collectAsStateWithLifecycle()
            val connectionAttempt by model.connectionAttempt.collectAsStateWithLifecycle()
            TrefoilApp(
                state = state.copy(error = notice ?: state.error, busy = working || state.busy),
                savedEnvironments = environments,
                onPair = model::pair,
                onConnect = model::connect,
                onSelectThread = model::selectThread,
                onSend = model::send,
                onInterrupt = model::interrupt,
                onLoadActivity = model::loadActivity,
                onRespond = model::respond,
                onCreateThread = model::createThread,
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
            )
        }
    }
}
