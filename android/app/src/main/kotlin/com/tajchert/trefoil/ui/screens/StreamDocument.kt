/* Adapted from T3 Code's mobile stream documents (MIT). See assets/t3-streams/LICENSE. */
package com.tajchert.trefoil.ui.screens

import java.net.URI
import kotlinx.serialization.json.*

internal fun escapeStreamConfiguration(value: String): String = value.replace("<", "\\u003c")

/** Defense in depth for the trusted pixel viewer; this is never a general-purpose browser. */
internal class StreamResourcePolicy(httpBase: String, wsBase: String) {
    private val root = URI(httpBase)
    private val socket = URI(wsBase)
    private val rootPath = root.rawPath.trimEnd('/')
    val httpOrigin = origin(root)
    val wsOrigin = origin(socket)
    val cleartext = root.scheme == "http"
    init {
        require(root.scheme in setOf("https", "http") && root.host != null && root.rawUserInfo == null)
        require(root.rawQuery == null && root.rawFragment == null && rootPath.isNotEmpty())
        require(socket.scheme == if (cleartext) "ws" else "wss")
        require(socket.host == root.host && socket.port == root.port && socket.rawPath == root.rawPath)
        require(socket.rawUserInfo == null && socket.rawQuery == null && socket.rawFragment == null)
    }
    // Origins, including ports, are exact; all remote scripts, frames, workers and forms are denied.
    val csp: String get() = "default-src 'none'; connect-src $httpOrigin $wsOrigin; " +
        "img-src $httpOrigin blob: data:; media-src $httpOrigin blob:; " +
        "style-src 'unsafe-inline'; frame-src 'none'; child-src 'none'; worker-src 'none'; " +
        "object-src 'none'; base-uri 'none'; form-action 'none';"
    fun allowsResource(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.rawUserInfo == null && uri.rawFragment == null && origin(uri) == httpOrigin &&
            uri.rawPath.startsWith("$rootPath/") && uri.rawPath == uri.normalize().rawPath &&
            !Regex("%2e|%2f|%5c|\\\\", RegexOption.IGNORE_CASE).containsMatchIn(uri.rawPath)
    }.getOrDefault(false)
    // loadDataWithBaseURL is the sole document load; no subsequent navigation is permitted.
    fun allowsNavigation(@Suppress("UNUSED_PARAMETER") value: String): Boolean = false
    companion object {
        private fun origin(uri: URI): String {
            require(uri.host != null)
            val host = if (uri.host.contains(':') && !uri.host.startsWith('[')) "[${uri.host}]" else uri.host
            val defaultPort = when (uri.scheme) { "https", "wss" -> 443; "http", "ws" -> 80; else -> -2 }
            val port = if (uri.port == -1 || uri.port == defaultPort) "" else ":${uri.port}"
            return "${uri.scheme}://$host$port"
        }
    }
}

enum class StreamPhase { Connecting, Streaming, Error, Unauthorized, Gone, Stopped }
data class StreamViewerStatus(
    val phase: StreamPhase = StreamPhase.Connecting,
    val inputConnected: Boolean = false,
    val canOperate: Boolean = false,
    val controller: String? = null,
    val detail: String? = null,
    val viewportWidth: Int? = null,
    val viewportHeight: Int? = null,
)

sealed interface StreamCommand {
    data object Home : StreamCommand
    data object Back : StreamCommand
    data object AppSwitcher : StreamCommand
    data object Rotate : StreamCommand
    data object BrowserBack : StreamCommand
    data object BrowserForward : StreamCommand
    data object Reload : StreamCommand
    data object TakeControl : StreamCommand
    data object ReleaseControl : StreamCommand
    data class Text(val value: String) : StreamCommand
    data class Navigate(val url: String) : StreamCommand
    data class BrowserKey(val key: String, val code: String, val keyCode: Int) : StreamCommand
    data class Dialog(val accept: Boolean, val promptText: String? = null) : StreamCommand
}

/** Immediate delivery only. A disconnected controller retains no input to replay. */
class StreamController {
    private var receiver: ((StreamCommand) -> Boolean)? = null
    private var lease: Any? = null
    @Synchronized fun send(command: StreamCommand): Boolean = receiver?.invoke(command) ?: false
    @Synchronized internal fun attach(receiver: (StreamCommand) -> Boolean): Any = Any().also {
        lease = it
        this.receiver = receiver
    }
    @Synchronized internal fun detach(token: Any) {
        if (lease === token) { receiver = null; lease = null }
    }
}

internal sealed interface StreamMessage {
    data class Status(val phase: StreamPhase) : StreamMessage
    data class Input(val connected: Boolean) : StreamMessage
    data class Control(val canOperate: Boolean, val controller: String) : StreamMessage
    data class Viewport(val width: Int, val height: Int) : StreamMessage
}

/** Treat the JS/native boundary as untrusted. Never forward arbitrary errors or page content. */
internal fun parseStreamMessage(value: String): StreamMessage? {
    if (value.length > 8192) return null
    return runCatching {
        val obj = Json.parseToJsonElement(value) as? JsonObject ?: return null
        fun string(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun bool(key: String) = (obj[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        when (string("type")) {
            "status" -> when (string("status")) {
                "connecting" -> StreamMessage.Status(StreamPhase.Connecting)
                "streaming" -> StreamMessage.Status(StreamPhase.Streaming)
                "error" -> StreamMessage.Status(StreamPhase.Error)
                else -> null
            }
            "input" -> bool("connected")?.let(StreamMessage::Input)
            "control" -> {
                val controller = string("controller") ?: return null
                val canOperate = bool("canOperate") ?: return null
                if (controller !in setOf("agent", "you", "another-viewer", "unclaimed")) return null
                StreamMessage.Control(canOperate, controller)
            }
            "viewport" -> {
                val width = (obj["width"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return null
                val height = (obj["height"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return null
                if (width !in 1..32768 || height !in 1..32768) null else StreamMessage.Viewport(width, height)
            }
            "unauthorized" -> StreamMessage.Status(StreamPhase.Unauthorized)
            "gone" -> StreamMessage.Status(StreamPhase.Gone)
            "hostSetup" -> StreamMessage.Status(StreamPhase.Error)
            else -> null
        }
    }.getOrNull()
}

internal fun streamDocument(configuration: String, script: String, browser: Boolean, bridgeName: String,
                            nonce: String, policy: StreamResourcePolicy, interactive: Boolean): String {
    require(bridgeName.matches(Regex("[a-zA-Z0-9_]+")) && nonce.matches(Regex("[a-zA-Z0-9]+")))
    val viewer = if (browser) "T3PreviewStream" else "T3DeviceStream"
    val safeScript = script.replace(Regex("</script", RegexOption.IGNORE_CASE)) { "<\\/script" }
    val csp = "${policy.csp} script-src 'nonce-$nonce';".replace("\"", "&quot;")
    // Gate synchronously before the original viewer's handlers run. Its connection callbacks clear
    // this flag before native status delivery, so stale events can never become deferred inputs.
    val prelude = """
      (() => {
        let streaming=false, input=false, control=false;
        const interactive=$interactive;
        window.__trefoilCanInput=()=>interactive&&streaming&&${if (browser) "control" else "input"};
        window.ReactNativeWebView=Object.freeze({postMessage(value){
          try { const m=JSON.parse(value);
            if(m.type==='status'){streaming=m.status==='streaming';if(!streaming){input=false;control=false;}}
            if(m.type==='input')input=m.connected===true;
            if(m.type==='control')control=m.canOperate===true&&m.controller==='you';
          } catch(_) {return;}
          window.$bridgeName.postMessage(value);
        }});
        for(const name of ['pointerdown','pointermove','pointerup','pointercancel','keydown','keyup','beforeinput','input','compositionend','wheel']){
          window.addEventListener(name,event=>{if(!window.__trefoilCanInput()){event.preventDefault();event.stopImmediatePropagation();}},true);
        }
        // ICE may contain arbitrary STUN/TURN addresses. Only the pinned HTTP/WS media transports
        // are admitted by Trefoil's shared endpoint policy; do not open an ICE side channel.
        Object.defineProperty(window,'RTCPeerConnection',{value:undefined,configurable:false});
        const failed=()=>window.ReactNativeWebView.postMessage(JSON.stringify({type:'status',status:'error'}));
        window.addEventListener('error',failed);window.addEventListener('unhandledrejection',failed);
        window.__trefoilStop=()=>{streaming=input=control=false;window.$viewer?.stop();};
      })();
    """.trimIndent()
    val css = """html,body{height:100%;overflow:hidden;margin:0;background:#101014}body>div{position:fixed;inset:0;overflow:hidden}canvas,video{position:absolute;inset:0;width:100%;height:100%;object-fit:contain}canvas{touch-action:none;user-select:none;-webkit-user-select:none}video{pointer-events:none}textarea{position:fixed;left:0;top:0;width:1px;height:1px;padding:0;margin:-1px;border:0;overflow:hidden;clip:rect(0,0,0,0);font-size:16px}.agent-cursor{position:absolute;left:0;top:0;width:20px;height:20px;pointer-events:none;opacity:0}.agent-cursor svg{position:relative;display:block}"""
    return """<!doctype html><html><head><meta http-equiv="Content-Security-Policy" content="$csp"><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"><style>$css</style></head><body><script nonce="$nonce">$prelude
$safeScript
try{$viewer.start(${escapeStreamConfiguration(configuration)});}catch(_){window.ReactNativeWebView.postMessage('{"type":"status","status":"error"}');}</script></body></html>"""
}
