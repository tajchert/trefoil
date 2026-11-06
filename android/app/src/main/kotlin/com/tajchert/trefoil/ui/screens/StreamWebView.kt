/* Native adapter for T3 Code's MIT-licensed mobile pixel viewers. */
package com.tajchert.trefoil.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Looper
import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tajchert.trefoil.core.model.SavedEnvironment
import com.tajchert.trefoil.core.model.ScreenAccess
import com.tajchert.trefoil.core.model.ScreenTarget
import com.tajchert.trefoil.core.session.validateScreenAccess
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.UUID
import kotlinx.serialization.json.*

/**
 * Keep this composable at one stable composition location when entering fullscreen. Access is
 * fresh for one attempt; resuming a paused viewer requires freshly minted access from the parent.
 * [connected] must come from TrefoilClient.isScreenAccessCurrent(access), not network reachability.
 */
@Composable
fun StreamWebView(
    target: ScreenTarget,
    access: ScreenAccess,
    environment: SavedEnvironment,
    connected: Boolean,
    controller: StreamController,
    onStatus: (StreamViewerStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember(lifecycleOwner) { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val statusCallback by rememberUpdatedState(onStatus)
    val holder = remember { ViewerHolder() }
    // A paused ticket is consumed. Returning to the app never silently reconnects a previous view.
    var consumedAccess by remember { mutableStateOf<ScreenAccess?>(null) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumed = true
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                holder.viewer?.halt(StreamPhase.Stopped)
                consumedAccess = holder.access
                resumed = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            holder.viewer?.halt(StreamPhase.Stopped)
        }
    }
    val allowed = connected && resumed && access !== consumedAccess && target == access.target && validateScreenAccess(access, environment)
    if (!allowed) {
        SideEffect { holder.viewer?.halt(StreamPhase.Stopped) }
        LaunchedEffect(allowed, access, resumed) { statusCallback(StreamViewerStatus(StreamPhase.Stopped)) }
        return
    }
    key(access, target, environment.id) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                val viewer = NativeStreamViewer(context, target, access, controller) { statusCallback(it) }
                holder.viewer = viewer
                holder.access = access
                viewer.webView
            },
            onRelease = { view ->
                (view.tag as? NativeStreamViewer)?.release()
                if (holder.viewer?.webView === view) holder.viewer = null
            },
            update = { view -> if (!connected) (view.tag as? NativeStreamViewer)?.halt(StreamPhase.Stopped) },
        )
    }
}

private class ViewerHolder {
    var viewer: NativeStreamViewer? = null
    var access: ScreenAccess? = null
}

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
private class NativeStreamViewer(
    context: android.content.Context,
    private val target: ScreenTarget,
    private val access: ScreenAccess,
    private val controller: StreamController,
    private val report: (StreamViewerStatus) -> Unit,
) {
    val webView = WebView(context)
    private val browser = target is ScreenTarget.Browser
    private val interactive = access.interactive && (target !is ScreenTarget.Browser || target.interactive)
    private val policy = StreamResourcePolicy(access.httpBase, access.wsBase)
    private val bridgeName = "TrefoilStream_${UUID.randomUUID().toString().replace("-", "") }"
    private val documentOrigin = if (target is ScreenTarget.Device && target.platform == "ios") policy.httpOrigin else "https://localhost"
    private val documentUrl = "$documentOrigin/trefoil-stream/${UUID.randomUUID().toString().replace("-", "") }"
    @Volatile private var documentBytes = byteArrayOf()
    @Volatile private var active = true
    private var destroyed = false
    private var state = StreamViewerStatus()
    private val lease: Any
    private val bootstrapTimeout = Runnable { halt(StreamPhase.Error) }
    init {
        WebView.setWebContentsDebuggingEnabled(false)
        webView.tag = this
        webView.isSaveEnabled = false
        webView.setBackgroundColor(android.graphics.Color.rgb(16, 16, 20))
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = android.view.View.OVER_SCROLL_NEVER
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = false
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = false
            // Only reached after shared policy explicitly permits this exact saved environment.
            mixedContentMode = if (policy.cleartext) WebSettings.MIXED_CONTENT_ALWAYS_ALLOW else WebSettings.MIXED_CONTENT_NEVER_ALLOW
            saveFormData = false
            setGeolocationEnabled(false)
        }
        // This app has no web login surface. Pixel viewers authenticate solely with media tickets.
        CookieManager.getInstance().setAcceptCookie(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                callback?.invoke(origin, false, false)
            }
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false
            override fun onShowFileChooser(webView: WebView?, filePathCallback: android.webkit.ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
                filePathCallback?.onReceiveValue(null)
                return true
            }
        }
        webView.setDownloadListener { _, _, _, _, _ -> /* No implicit downloads or external intents. */ }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
            @Deprecated("Legacy callback remains blocked")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request == null || !active) return deniedResource()
                val url = request.url.toString()
                if (request.isForMainFrame) {
                    // loadUrl's one unpredictable bootstrap route is fulfilled entirely in memory.
                    // No network document, data URL, file URL, redirect or alternate frame is admitted.
                    return if (url == documentUrl && request.method == "GET" && !request.isRedirect) {
                        WebResourceResponse("text/html", "UTF-8", 200, "OK", mapOf(
                            "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff",
                        ), ByteArrayInputStream(documentBytes))
                    } else deniedResource()
                }
                return if (policy.allowsResource(url)) null else deniedResource()
            }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                handler?.cancel()
                webView.post { halt(StreamPhase.Error) }
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) halt(StreamPhase.Error)
            }
            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true) halt(StreamPhase.Error)
            }
            override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) { handler?.cancel() }
            override fun onReceivedClientCertRequest(view: WebView?, request: ClientCertRequest?) { request?.cancel() }
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                halt(StreamPhase.Error)
                return true
            }
        }
        webView.addJavascriptInterface(NativeMessageBridge { value ->
            if (value.length <= 8192) webView.post { if (active) receive(value) }
        }, bridgeName)
        lease = controller.attach(::send)
        val configuration = streamConfiguration(target, access)
        val asset = if (browser) "preview" else "device"
        val script = context.assets.open("t3-streams/$asset-stream.js").bufferedReader().use { it.readText() }
        val html = streamDocument(configuration.toString(), script, browser, bridgeName,
            UUID.randomUUID().toString().replace("-", ""), policy, interactive)
        documentBytes = html.toByteArray(Charsets.UTF_8)
        webView.loadUrl(documentUrl)
        webView.postDelayed(bootstrapTimeout, 15_000)
        report(state)
    }
    private fun receive(value: String) {
        when (val message = parseStreamMessage(value)) {
            is StreamMessage.Status -> {
                if (!browser && message.phase == StreamPhase.Connecting && state.phase == StreamPhase.Streaming) { halt(StreamPhase.Error); return }
                if (message.phase in setOf(StreamPhase.Error, StreamPhase.Unauthorized, StreamPhase.Gone)) { halt(message.phase); return }
                state = if (message.phase == StreamPhase.Connecting) state.copy(phase = message.phase, inputConnected = false, canOperate = false, controller = null)
                else state.copy(phase = message.phase)
                if (message.phase == StreamPhase.Streaming) webView.removeCallbacks(bootstrapTimeout)
            }
            is StreamMessage.Input -> {
                if (!message.connected && state.inputConnected) { halt(StreamPhase.Error); return }
                state = state.copy(inputConnected = interactive && message.connected, canOperate = interactive && message.connected)
            }
            is StreamMessage.Control -> state = state.copy(canOperate = interactive && message.canOperate,
                inputConnected = interactive && message.canOperate && message.controller == "you", controller = message.controller)
            is StreamMessage.Viewport -> state = state.copy(viewportWidth = message.width, viewportHeight = message.height)
            null -> return
        }
        report(state)
    }
    private fun send(command: StreamCommand): Boolean {
        // Never post commands to the UI looper for later delivery after a state transition.
        if (Looper.myLooper() != Looper.getMainLooper() || !active || !interactive || state.phase != StreamPhase.Streaming) return false
        val takeControl = browser && command == StreamCommand.TakeControl && state.canOperate
        if (!takeControl && !state.inputConnected) return false
        val script = streamCommandScript(target, command) ?: return false
        webView.evaluateJavascript(script, null)
        return true
    }
    fun halt(phase: StreamPhase) {
        if (!active) return
        active = false
        documentBytes = byteArrayOf()
        controller.detach(lease)
        webView.removeCallbacks(bootstrapTimeout)
        webView.evaluateJavascript("window.__trefoilStop?.(); true;", null)
        webView.stopLoading()
        webView.removeJavascriptInterface(bridgeName)
        webView.loadUrl("about:blank")
        state = state.copy(phase = phase, inputConnected = false, canOperate = false, controller = null,
            detail = if (phase == StreamPhase.Error) {
                if (browser) "Screen viewer stopped. Reconnect to try again."
                else "Device stream unavailable. Update Android System WebView and use an HTTP/WebSocket stream; WebRTC-only hosts are not supported."
            } else null)
        report(state)
    }
    fun release() {
        halt(StreamPhase.Stopped)
        if (destroyed) return
        destroyed = true
        webView.clearHistory()
        webView.clearCache(true)
        webView.clearFormData()
        webView.removeAllViews()
        webView.destroy()
    }
}

private class NativeMessageBridge(private val receiver: (String) -> Unit) {
    @JavascriptInterface fun postMessage(value: String) = receiver(value)
}

private fun deniedResource() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))

internal fun streamConfiguration(target: ScreenTarget, access: ScreenAccess): JsonObject = buildJsonObject {
    put("access", buildJsonObject {
        put("httpBase", access.httpBase); put("wsBase", access.wsBase)
        put("query", buildJsonObject { access.query.forEach { (key, value) -> put(key, value) } })
        put("credentials", false)
    })
    when (target) {
        is ScreenTarget.Browser -> {
            put("threadId", target.threadId); put("tabId", target.tabId)
            put("interactive", access.interactive && target.interactive); put("background", "#101014")
        }
        is ScreenTarget.Device -> {
            put("platform", target.platform); put("deviceId", target.deviceId); put("serveSimOnly", target.serveSimOnly)
            put("videoOnly", !access.interactive)
            put("colors", buildJsonObject {
                put("background", "#101014"); put("foreground", "#f5f5fa"); put("muted", "#aaaab8")
                put("buttonBackground", "#292932"); put("buttonForeground", "#f5f5fa"); put("buttonBorder", "#454552")
            })
        }
    }
}

internal fun streamCommandScript(target: ScreenTarget, command: StreamCommand): String? {
    if (target is ScreenTarget.Device) {
        val button = when (command) {
            StreamCommand.Home -> "home"
            StreamCommand.Back -> if (target.platform == "android") "back" else return null
            StreamCommand.AppSwitcher -> "appSwitcher"
            StreamCommand.Rotate -> if (target.platform == "ios") "rotate" else return null
            else -> null
        }
        if (button != null) return "if(window.__trefoilCanInput?.())window.T3DeviceStream?.command('$button'); true;"
        // A recorded wrapper-only export calls the original shared Unicode text implementation.
        // serve-sim and iOS use HID key events and do not implement text paste.
        if (command is StreamCommand.Text && target.platform == "android" && !target.serveSimOnly &&
            command.value.isNotEmpty() && command.value.toByteArray(Charsets.UTF_8).size <= 18000) {
            val text = JsonPrimitive(command.value).toString()
            return "if(window.__trefoilCanInput?.())window.T3DeviceStream?.text($text); true;"
        }
        return null
    }
    val input = buildJsonObject {
        when (command) {
            StreamCommand.BrowserBack -> { put("type", "history"); put("delta", -1) }
            StreamCommand.BrowserForward -> { put("type", "history"); put("delta", 1) }
            StreamCommand.Reload -> put("type", "reload")
            StreamCommand.TakeControl -> put("type", "takeControl")
            StreamCommand.ReleaseControl -> put("type", "releaseControl")
            is StreamCommand.Text -> { if (command.value.length !in 1..65536) return null; put("type", "text"); put("text", command.value) }
            is StreamCommand.Navigate -> {
                val uri = runCatching { URI(command.url) }.getOrNull() ?: return null
                if (uri.scheme !in setOf("https", "http") || uri.host == null || uri.rawUserInfo != null) return null
                put("type", "navigate"); put("url", command.url)
            }
            is StreamCommand.BrowserKey -> {
                if (command.key.length > 64 || command.code.length > 64 || command.keyCode !in 0..255) return null
                // Both phases execute synchronously in the same JS task, never buffered natively.
                val common = buildJsonObject { put("type", "key"); put("key", command.key); put("code", command.code); put("keyCode", command.keyCode); put("modifiers", 0) }
                val down = JsonObject(common + ("action" to JsonPrimitive("down")))
                val up = JsonObject(common + ("action" to JsonPrimitive("up")))
                return "if(window.__trefoilCanInput?.()){window.T3PreviewStream?.command($down);window.T3PreviewStream?.command($up);} true;"
            }
            is StreamCommand.Dialog -> {
                if ((command.promptText?.length ?: 0) > 65536) return null
                put("type", "dialog"); put("accept", command.accept); command.promptText?.let { put("promptText", it) }
            }
            else -> return null
        }
    }
    val guard = if (command == StreamCommand.TakeControl) "true" else "window.__trefoilCanInput?.()"
    return "if($guard)window.T3PreviewStream?.command($input); true;"
}
