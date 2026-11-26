package com.tajchert.trefoil

import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import com.tajchert.trefoil.ui.shell.keyboardShortcutGroups
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tajchert.trefoil.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.net.Uri
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.activity.enableEdgeToEdge
import com.tajchert.trefoil.ui.adaptive.ProvideWindowFold
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import com.tajchert.trefoil.ui.diff.ChangesUi
import com.tajchert.trefoil.ui.pr.PullRequestUi
import com.tajchert.trefoil.ui.ComposerDraftUi
import com.tajchert.trefoil.ui.PromptSuggestionUi
import com.tajchert.trefoil.ui.SideChatUi
import com.tajchert.trefoil.ui.TrefoilApp
import com.tajchert.trefoil.ui.issues.IssuesUi
import com.tajchert.trefoil.notifications.AgentAlertNotifier
import com.tajchert.trefoil.notifications.OpenThreadRequest
import com.tajchert.trefoil.ui.FileDownloadUi
import com.tajchert.trefoil.downloads.CompletedDownload
import com.tajchert.trefoil.downloads.DownloadFileActions
import android.widget.Toast

class MainActivity : ComponentActivity() {
    private var usageRequest by mutableStateOf<Pair<Long, UsageWidgetTab>?>(null)
    private fun handleUsageIntent(intent: Intent?) {
        parseUsageWidgetIntent(intent)?.let { usageRequest = ((usageRequest?.first ?: 0) + 1) to it }
    }
    private var pairLinkRequest by mutableStateOf<Pair<Long, String>?>(null)
    private fun handlePairLinkIntent(intent: Intent?) {
        val link = parsePairLinkIntent(intent) ?: return
        pairLinkRequest = ((pairLinkRequest?.first ?: 0) + 1) to link
        // The link is a single-use credential: drop it from the activity intent so nothing can replay it.
        setIntent(Intent(Intent.ACTION_MAIN))
    }
    private fun handleOpenThreadIntent(intent: Intent?) {
        val request = parseOpenThreadIntent(intent, AgentAlertNotifier.tapToken(this)) ?: return
        // Held by the view model, so a cold start (machines still loading) or a rotation while the machine connects keeps it.
        model.openThreadFromNotification(request)
        // Consumed once: recreation or a later resume must not reopen the thread.
        setIntent(Intent(Intent.ACTION_MAIN))
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsageIntent(intent)
        handlePairLinkIntent(intent)
        handleOpenThreadIntent(intent)
    }
    /** The system Keyboard Shortcut Helper (Meta+/) lists the same table the app dispatches from. */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        data += keyboardShortcutGroups()
    }
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
    /** Default-network changes in the foreground (and in the background while keep-alive runs): a better-route or link check, or a pending reconnect once online. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { model.onDefaultNetwork(network.networkHandle) }
        override fun onLost(network: Network) { model.onDefaultNetworkLost(network.networkHandle) }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            model.onDefaultNetworkTransports(network.networkHandle, capabilities.isPlainCellular())
        }
    }
    private var networkCallbackRegistered = false
    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        // Too many callbacks or a missing permission: route checks still run every minute.
        networkCallbackRegistered = try { connectivity.registerDefaultNetworkCallback(networkCallback, Handler(Looper.getMainLooper())); true }
        catch (_: RuntimeException) { false }
    }
    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        try { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback) } catch (_: RuntimeException) {}
        networkCallbackRegistered = false
    }
    private val notificationPermission: androidx.activity.result.ActivityResultLauncher<String> = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Denied changes nothing else: the keep-alive service still runs, and Android only hides its notifications.
        permissionRequests.finished()
    }
    /** Every notification permission request goes through here, so one user action never opens two dialogs. */
    private val permissionRequests: SinglePermissionRequest = SinglePermissionRequest { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    private fun notificationPermissionGranted() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun requestNotificationPermission() {
        if (notificationPermissionGranted()) return
        // A toggle's request counts as the one-time request too.
        uiPrefs.edit().putBoolean(AGENT_NOTIFICATION_PERMISSION_ASKED, true).apply()
        permissionRequests.request()
    }
    private val uiPrefs by lazy { getSharedPreferences("trefoil.ui", MODE_PRIVATE) }
    /**
     * Agent notifications are on by default, so on Android 13+ the permission is asked once without a toggle: the first time a
     * machine is connected while the setting is on and the permission is missing. Denial changes nothing else.
     */
    private fun askNotificationPermissionOnce() {
        if (!shouldAskAgentNotificationPermission(Build.VERSION.SDK_INT, connected = true, model.agentNotifications.value, notificationPermissionGranted(), uiPrefs.getBoolean(AGENT_NOTIFICATION_PERMISSION_ASKED, false))) return
        uiPrefs.edit().putBoolean(AGENT_NOTIFICATION_PERMISSION_ASKED, true).apply()
        permissionRequests.request()
    }
    /** The "Keep connected in background" toggle; turning it on asks for the notification permission on Android 13+. */
    fun onKeepConnectedChange(enabled: Boolean) {
        model.setKeepConnected(enabled)
        if (enabled) requestNotificationPermission()
    }
    /** The "Notify about agent activity" toggle; turning it on asks for the notification permission on Android 13+. */
    fun onAgentNotificationsChange(enabled: Boolean) {
        model.setAgentNotifications(enabled)
        if (enabled) requestNotificationPermission()
    }
    override fun onStart() {
        super.onStart(); model.resume()
        registerNetworkCallback()
    }
    override fun onStop() {
        model.pause()
        // While keep-alive runs, link checks and reconnect-when-online need network changes in the background too.
        if (!model.keepAliveActive.value) unregisterNetworkCallback()
        super.onStop()
    }
    override fun onDestroy() {
        // Accepted edge case: a destroyed activity (keep-alive still running) drops network changes until the next onStart.
        unregisterNetworkCallback()
        super.onDestroy()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only a fresh launch consumes the widget intent; recreation (rotation) must not reopen Usage.
        if (savedInstanceState == null) { handleUsageIntent(intent); handlePairLinkIntent(intent); handleOpenThreadIntent(intent) }
        enableEdgeToEdge()
        lifecycleScope.launch {
            // A forwarded dev server opens in the device browser; the loopback listener lives only while the forward does.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.portForwards.externalUrls.collect { request ->
                    val opened = try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(request.url)).addCategory(Intent.CATEGORY_BROWSABLE)); true }
                    catch (_: Exception) { model.externalOpenFailed(); false }
                    // Only a URL a browser took holds the forward; this runs before onStop, so the hold starts in the foreground.
                    if (opened) model.portForwards.externalOpened(request)
                }
            }
        }
        lifecycleScope.launch {
            // The permission dialog needs a started activity; a connection made in the background asks on the next start.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(model.state.map { it.connection == com.tajchert.trefoil.core.model.ConnectionStatus.Connected }.distinctUntilChanged(), model.agentNotifications) { connected, on -> connected && on }
                    .collect { if (it) askNotificationPermissionOnce() }
            }
        }
        lifecycleScope.launch {
            // Keep-alive ending in the background: stop listening for network changes until the next start.
            model.keepAliveActive.collect { active -> if (!active && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) unregisterNetworkCallback() }
        }
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val screenViewer by model.screenSessions.state.collectAsStateWithLifecycle()
            val downloads by model.fileDownloads.state.collectAsStateWithLifecycle()
            val environments by model.environments.collectAsStateWithLifecycle()
            val draft by model.draft.collectAsStateWithLifecycle()
            val notice by model.notice.collectAsStateWithLifecycle()
            val sendOutcomes by model.sendOutcomes.collectAsStateWithLifecycle()
            val sendsInFlight by model.sendsInFlight.collectAsStateWithLifecycle()
            val stopPending by model.stopPending.collectAsStateWithLifecycle()
            val queueOperations by model.queueOperationPending.collectAsStateWithLifecycle()
            val working by model.working.collectAsStateWithLifecycle()
            val sshHostKey by model.sshHostKey.collectAsStateWithLifecycle()
            val sshLogin by model.sshLogin.collectAsStateWithLifecycle()
            val connectionAttempt by model.connectionAttempt.collectAsStateWithLifecycle()
            val machineProbes by model.machineProbes.collectAsStateWithLifecycle()
            val usage by model.usage.collectAsStateWithLifecycle()
            val issueList by model.issues.collectAsStateWithLifecycle()
            val issueDetail by model.issueDetail.collectAsStateWithLifecycle()
            val issuesUi = remember(issueList, issueDetail) {
                IssuesUi(issueList, issueDetail, model.issuesController::open, model.issuesController::setQuery, model.issuesController::refresh,
                    model.issuesController::loadMore, model.issuesController::retry, model.issueDetailController::open,
                    model.issueDetailController::refresh, model.issueDetailController::retry, model.issueDetailController::loadOlderComments,
                    model::openIssueThread,
                    model.issuesController::resolveLink)
            }
            val keepConnected by model.keepConnected.collectAsStateWithLifecycle()
            val hideTestFiles by model.hideTestFiles.collectAsStateWithLifecycle()
            val changesUi = remember(hideTestFiles) { ChangesUi(model::loadTurnDiff, hideTestFiles, model::setHideTestFiles) }
            val pullRequestUi = remember {
                PullRequestUi(
                    model::prPanel, model::refreshPullRequest, model::loadPullRequestDiff, model::runPullRequestAction, model::commentOnPullRequest,
                    model.pullRequestBusy, model.pullRequestErrors, model::dismissPullRequestError,
                )
            }
            val agentNotifications by model.agentNotifications.collectAsStateWithLifecycle()
            val openedThread by model.openedThread.collectAsStateWithLifecycle()
            val portBrowser by model.portForwards.browser.collectAsStateWithLifecycle()
            val stoppedForwards by model.portForwards.stoppedNotices.collectAsStateWithLifecycle()
            val refreshing by model.isRefreshing.collectAsStateWithLifecycle()
            // New-thread records and first-line reply previews change only on visible edits, not on every reply keystroke.
            val newThreadDrafts by model.newThreadComposerDrafts.collectAsStateWithLifecycle()
            val replyPreviews by model.replyDraftPreviews.collectAsStateWithLifecycle()
            val draftsLoaded by model.composerDraftsLoaded.collectAsStateWithLifecycle()
            val composerDraftUi = remember(newThreadDrafts, replyPreviews, draftsLoaded) {
                ComposerDraftUi(newThreadDrafts + replyPreviews, model::composerDraft, model::updateComposerDraft, model::clearComposerDraft,
                    model::discardComposerDraft, model::restoreComposerDraft, model::moveComposerDraft, model::updateComposerDraftText, draftsLoaded)
            }
            val dismissedSuggestions by model.dismissedPromptSuggestions.collectAsStateWithLifecycle()
            val sideChatState by model.sideChat.collectAsStateWithLifecycle()
            val sideChatUi = remember(sideChatState) { SideChatUi(sideChatState.inFlight, sideChatState.result, model::startSideChat, model::consumeSideChatResult) }
            val promptSuggestionUi = remember(dismissedSuggestions) { PromptSuggestionUi(dismissedSuggestions, model::dismissPromptSuggestion) }
            ProvideWindowFold { TrefoilApp(
                onThreadShown = model::setThreadShown,
                usageOpenRequest = usageRequest,
                pairLinkRequest = pairLinkRequest,
                openedThread = openedThread,
                onOpenedThreadShown = model::consumeOpenedThread,
                usage = usage,
                issues = issuesUi,
                onOpenUsage = model.usageController::open,
                onUsageRange = model.usageController::selectRange,
                onRefreshUsage = model.usageController::refresh,
                onRetryUsage = model.usageController::retry,
                state = state.copy(error = notice ?: state.error, busy = working || state.busy),
                savedEnvironments = environments,
                onPair = model::pair,
                onConnect = model::selectMachine,
                onSelectThread = model::selectThread,
                onSend = model::send,
                onSubmitMessage = model::submitMessage,
                onConsumeSendOutcome = model::consumeSendOutcome,
                stopPendingKeys = stopPending,
                queueOperationKeys = queueOperations,
                sendOutcomes = sendOutcomes,
                sendsInFlight = sendsInFlight,
                onStop = model::stopThread,
                onCancelQueued = model::cancelQueued,
                onSteerQueued = model::steerQueued,
                onResumeQueue = model::resumeQueue,
                onStopSubagent = model::stopSubagent,
                onOpenInspector = model::openInspector,
                onCloseInspector = model::closeInspector,
                onThreadAction = model::threadAction,
                isRefreshing = refreshing,
                onRefresh = model::refresh,
                composerDrafts = composerDraftUi,
                promptSuggestions = promptSuggestionUi,
                sideChat = sideChatUi,
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
                    { openDownloaded(it) }, { openDownloaded(it, share = true) }, { openDownloaded(it, install = true) },
                    imageLoader = model.fileDownloads.images, htmlLoader = model.fileDownloads.htmlRenders),
                portBrowser = portBrowser,
                onOpenPortInApp = model.portForwards::openInApp,
                onOpenPortExternal = model.portForwards::openExternal,
                onStopPortForward = model.portForwards::stop,
                onForwardPort = model.portForwards::forwardManual,
                onClosePortBrowser = model.portForwards::closeBrowser,
                stoppedForwards = stoppedForwards,
                onDismissStoppedForwards = model::dismissStoppedForwards,
                onRespond = model::respond,
                draft = draft,
                sendFailed = model.sendFailed,
                onStartDraft = model::startDraft,
                onCancelDraft = model::cancelDraft,
                onSetRuntimeMode = model::setRuntimeMode,
                onSetModel = model::setModel,
                onSetModelOption = model::setModelOption,
                onSetModelFavorite = model::setModelFavorite,
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
                onAllowLocalNetworkHttp = model::allowLocalNetworkHttp,
                keepConnected = keepConnected,
                onKeepConnectedChange = ::onKeepConnectedChange,
                agentNotifications = agentNotifications,
                onAgentNotificationsChange = ::onAgentNotificationsChange,
                changes = changesUi,
                pullRequests = pullRequestUi,
            ) }
        }
    }
}

private const val MAX_PAIR_LINK_LENGTH = 4096

/** `SharedPreferences("trefoil.ui")` key: the one-time agent-notification permission request was made. */
internal const val AGENT_NOTIFICATION_PERMISSION_ASKED = "agentNotificationPermissionAsked"

/**
 * At most one permission dialog at a time: a request while one is in flight (the toggle's and the automatic first-connect request
 * of the same action) is dropped. [finished] runs when the dialog's result arrives.
 */
internal class SinglePermissionRequest(private val launch: () -> Unit) {
    private var inFlight = false
    /** Launches the dialog unless one is showing; true when it launched. */
    fun request(): Boolean {
        if (inFlight) return false
        inFlight = true
        try { launch() } catch (e: RuntimeException) { inFlight = false; throw e }
        return true
    }
    fun finished() { inFlight = false }
}

/** Ask for `POST_NOTIFICATIONS` without a toggle: Android 13+, connected, setting on, not granted, and never asked before. */
internal fun shouldAskAgentNotificationPermission(sdk: Int, connected: Boolean, settingOn: Boolean, granted: Boolean, asked: Boolean): Boolean =
    sdk >= Build.VERSION_CODES.TIRAMISU && connected && settingOn && !granted && !asked

/**
 * The `t3code://pair…` link of a browser/app VIEW intent, or null. Untrusted: only the data string is read, and it is
 * returned unparsed so the dialog applies the same validation as a pasted link. A link re-delivered from recents is ignored.
 */
internal fun parsePairLinkIntent(intent: Intent?): String? {
    if (intent?.action != Intent.ACTION_VIEW || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
    val data = intent.data ?: return null
    if (!data.scheme.equals("t3code", ignoreCase = true) || data.host != "pair") return null
    return data.toString().takeIf { it.length <= MAX_PAIR_LINK_LENGTH }
}

private const val MAX_OPEN_THREAD_ID_LENGTH = 512

/**
 * The thread a tap on an agent notification asks to open, or null. MainActivity is exported, so any app can send this action: only
 * an intent carrying [tapToken] (the installation's secret, see [AgentAlertNotifier.tapToken], which only this app's own
 * notification PendingIntents hold) is accepted. Even then the ids are only used to look up a saved machine and one of its listed
 * threads, never to reach a thread by id alone.
 */
internal fun parseOpenThreadIntent(intent: Intent?, tapToken: String): OpenThreadRequest? {
    if (intent?.action != AgentAlertNotifier.ACTION_OPEN_THREAD || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
    val presented = intent.getStringExtra(AgentAlertNotifier.EXTRA_TAP_TOKEN) ?: return null
    if (tapToken.isEmpty() || !java.security.MessageDigest.isEqual(presented.toByteArray(), tapToken.toByteArray())) return null
    val environmentId = intent.getStringExtra(AgentAlertNotifier.EXTRA_ENVIRONMENT_ID)?.takeIf { it.isNotBlank() && it.length <= MAX_OPEN_THREAD_ID_LENGTH } ?: return null
    val threadId = intent.getStringExtra(AgentAlertNotifier.EXTRA_THREAD_ID)?.takeIf { it.isNotBlank() && it.length <= MAX_OPEN_THREAD_ID_LENGTH } ?: return null
    return OpenThreadRequest(environmentId, threadId)
}

internal fun parseUsageWidgetIntent(intent: Intent?): UsageWidgetTab? {
    if (intent?.action != ACTION_OPEN_USAGE) return null
    return UsageWidgetTab.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_OPEN_USAGE_TAB) } ?: UsageWidgetTab.Limits
}

/** Cellular with no transport that could reach a private network (Wi-Fi, Ethernet, Bluetooth, USB or a VPN). */
internal fun NetworkCapabilities.isPlainCellular(): Boolean =
    hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
        listOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_BLUETOOTH,
            NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI_AWARE, NetworkCapabilities.TRANSPORT_LOWPAN).none(::hasTransport) &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !hasTransport(NetworkCapabilities.TRANSPORT_USB))
