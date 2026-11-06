package com.tajchert.trefoil.ui.screens

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.net.Uri
import java.io.ByteArrayInputStream
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tajchert.trefoil.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StreamWebViewLifecycleTest {
    @get:Rule val compose = createComposeRule()
    private val target = ScreenTarget.Device("fixture", "host", "device", "android")
    private val environment = SavedEnvironment("fixture", "Fixture", "https://fixture.example/", "bearer-never-in-js")
    private fun access(ticket: String = "media") = ScreenAccess("fixture", 1, target,
        "https://fixture.example/api/device-hub", "wss://fixture.example/api/device-hub",
        mapOf("hostId" to "host", "wsTicket" to ticket), interactive = true)

    @Test fun pauseDestroysStreamAndOldTicketCannotResumeOrReplayInput() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        val granted = mutableStateOf(access())
        val controller = StreamController()
        var root: View? = null
        val statuses = mutableListOf<StreamViewerStatus>()
        compose.setContent {
            root = LocalView.current.rootView
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                StreamWebView(target, granted.value, environment, true, controller, statuses::add)
            }
        }
        lateinit var first: WebView
        compose.runOnIdle {
            first = findWebView(root!!)!!
            assertFalse(first.settings.allowFileAccess)
            assertFalse(first.settings.allowContentAccess)
            assertFalse(first.settings.domStorageEnabled)
            val html = bootstrapHtml(first)
            assertTrue(shadowOf(first).lastLoadedUrl.startsWith("https://localhost/trefoil-stream/"))
            assertFalse(html.contains("bearer-never-in-js"))
            assertTrue(html.contains("media"))
            assertFalse(controller.send(StreamCommand.Home))
            val name = Regex("TrefoilStream_[a-f0-9]+").find(html)!!.value
            val bridge = shadowOf(first).getJavascriptInterface(name)
            val post = bridge.javaClass.getDeclaredMethod("postMessage", String::class.java).apply { isAccessible = true }
            post.invoke(bridge, "{\"type\":\"input\",\"connected\":true}")
            post.invoke(bridge, "{\"type\":\"status\",\"status\":\"streaming\"}")
        }
        compose.runOnIdle {
            assertTrue(controller.send(StreamCommand.Home))
            owner.registry.currentState = Lifecycle.State.STARTED
            assertFalse(controller.send(StreamCommand.Home))
        }
        compose.runOnIdle {
            assertTrue(shadowOf(first).wasDestroyCalled())
            assertTrue(shadowOf(first).wasClearCacheCalled())
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.runOnIdle {
            assertNull(findWebView(root!!))
            assertEquals(StreamPhase.Stopped, statuses.last().phase)
            granted.value = access("fresh-media")
        }
        compose.runOnIdle {
            val next = findWebView(root!!)!!
            assertNotSame(first, next)
            assertFalse(controller.send(StreamCommand.Home))
        }
    }

    @Test fun readOnlyViewerCannotSendEvenIfBridgeClaimsInputConnected() {
        var root: View? = null
        val controller = StreamController()
        val statuses = mutableListOf<StreamViewerStatus>()
        compose.setContent {
            root = LocalView.current.rootView
            StreamWebView(target, access().copy(interactive = false), environment, true, controller, statuses::add)
        }
        compose.runOnIdle {
            val view = findWebView(root!!)!!
            val html = bootstrapHtml(view)
            val bridge = shadowOf(view).getJavascriptInterface(Regex("TrefoilStream_[a-f0-9]+").find(html)!!.value)
            val post = bridge.javaClass.getDeclaredMethod("postMessage", String::class.java).apply { isAccessible = true }
            post.invoke(bridge, "{\"type\":\"input\",\"connected\":true}")
            post.invoke(bridge, "{\"type\":\"status\",\"status\":\"streaming\"}")
        }
        compose.runOnIdle {
            assertEquals(StreamPhase.Streaming, statuses.last().phase)
            assertFalse(statuses.last().inputConnected)
            assertFalse(statuses.last().canOperate)
            assertFalse(controller.send(StreamCommand.Home))
        }
    }

    @Test fun invalidMediaRootIsRefusedBeforeAWebViewReceivesTheTicket() {
        var root: View? = null
        val statuses = mutableListOf<StreamViewerStatus>()
        compose.setContent {
            root = LocalView.current.rootView
            StreamWebView(target, access().copy(httpBase = "https://evil.example/api/device-hub"), environment,
                true, remember { StreamController() }, statuses::add)
        }
        compose.runOnIdle {
            assertNull(findWebView(root!!))
            assertEquals(StreamPhase.Stopped, statuses.last().phase)
        }
    }
    @Test fun onlyExactBootstrapMainFrameIsServedLocallyAndHttpErrorsHalt() {
        var root: View? = null
        val statuses = mutableListOf<StreamViewerStatus>()
        compose.setContent {
            root = LocalView.current.rootView
            StreamWebView(target, access(), environment, true, remember { StreamController() }, statuses::add)
        }
        compose.runOnIdle {
            val view = findWebView(root!!)!!
            val url = shadowOf(view).lastLoadedUrl
            assertNotNull("Bootstrap must use an exact local URL instead of a data document", url)
            assertTrue(url.matches(Regex("https://localhost/trefoil-stream/[a-f0-9]{32}")))
            val client = shadowOf(view).webViewClient
            val local = client.shouldInterceptRequest(view, request(url))!!
            assertEquals(200, local.statusCode)
            assertEquals("text/html", local.mimeType)
            assertEquals("no-store", local.responseHeaders["Cache-Control"])
            assertTrue(local.data.bufferedReader().readText().startsWith("<!doctype html>"))
            listOf("https://localhost/", "$url?extra=1", "https://evil.example/", "about:blank", "file:///private", "data:text/html,hello",
                "https://fixture.example/api/device-hub/vendor/serve-emu/ws").forEach {
                assertEquals(it, 403, client.shouldInterceptRequest(view, request(it))!!.statusCode)
            }
            assertEquals(403, client.shouldInterceptRequest(view, request(url, false))!!.statusCode)
            assertNull(client.shouldInterceptRequest(view, request("https://fixture.example/api/device-hub/vendor/serve-emu/api", false)))
            client.onReceivedHttpError(view, request(url), WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf())))
            assertEquals(StreamPhase.Error, statuses.last().phase)
        }
    }
    private fun bootstrapHtml(view: WebView): String {
        val url = shadowOf(view).lastLoadedUrl
        assertNotNull("Viewer bootstrap must be routed through an exact local URL", url)
        return shadowOf(view).webViewClient.shouldInterceptRequest(view, request(url))!!.also { assertEquals(200, it.statusCode) }
            .data.bufferedReader().readText()
    }
    private fun request(value: String, mainFrame: Boolean = true): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(value)
        override fun isForMainFrame(): Boolean = mainFrame
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }
}
