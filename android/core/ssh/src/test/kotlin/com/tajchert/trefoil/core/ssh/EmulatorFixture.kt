package com.tajchert.trefoil.core.ssh

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*
import com.tajchert.trefoil.core.model.SshConnectionInput
import java.util.Collections
import java.util.concurrent.CountDownLatch

/** Disposable loopback-only development service. All credentials below are PUBLIC TEST DATA. */
internal fun emulatorT3Dispatcher(oauthRequests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())): Dispatcher {
    val descriptor = """{"environmentId":"fixture","serverVersion":"0.1.0","orchestrationProtocolVersion":2}"""
    val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = Json.parseToJsonElement(text).jsonObject
            if (frame["_tag"]?.jsonPrimitive?.content != "Request") return
            val id = frame["id"]!!.jsonPrimitive.content
            when (frame["tag"]?.jsonPrimitive?.content) {
                "server.getConfig" -> webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":{"providers":[]}}}""")
                "orchestration.subscribeThread" -> webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","projection":{"thread":{"id":"test-thread"},"messages":[],"runs":[],"turnItems":[],"runtimeRequests":[]}},{"kind":"synchronized"}]}""")
                "orchestration.subscribeShell" -> webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","snapshot":{"projects":[{"id":"test-project","title":"SSH fixture project","workspaceRoot":"/isolated-fixture"}],"threads":[{"id":"test-thread","projectId":"test-project","title":"SSH fixture conversation","status":"idle"}]}},{"kind":"synchronized"}]}""")
            }
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
    }
    return object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            oauthRequests.add(request)
            return when {
                request.path == "/.well-known/t3/environment" -> MockResponse().setBody(descriptor)
                request.path == "/oauth/token" -> MockResponse().setBody("""{"access_token":"fixture-bearer","token_type":"Bearer"}""")
                request.path == "/api/auth/session" -> MockResponse().setBody("""{"authenticated":true,"permissions":["orchestration:operate"]}""")
                request.path == "/api/auth/websocket-ticket/rpc" -> MockResponse().setBody("""{"ticket":"fixture-ticket"}""")
                request.path?.startsWith("/ws?") == true -> MockResponse().withWebSocketUpgrade(listener)
                else -> MockResponse().setResponseCode(404)
            }
        }
    }
}

object EmulatorFixture {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val fixture = TunnelIntegrationTest.Fixture()
        fixture.http.dispatcher = emulatorT3Dispatcher()
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { fixture.close() } })
        val key = try {
            SshTunnelConnector().open(SshConnectionInput("Fixture", "127.0.0.1", "user", fixture.ssh.port, password = "pw"), null)
            error("Expected trust challenge")
        } catch (e: SshHostKeyException) { e.presented }
        val metadata = buildJsonObject {
            put("host", "127.0.0.1"); put("emulatorHost", "10.0.2.2"); put("port", fixture.ssh.port)
            put("username", "user"); put("password", "pw"); put("fingerprint", key.fingerprint); put("keyType", key.keyType)
            put("testOnly", true)
        }.toString()
        args.firstOrNull()?.let { java.io.File(it).writeText(metadata + "\n") }
        println(metadata)
        CountDownLatch(1).await()
    }
}
