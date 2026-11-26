package com.tajchert.trefoil.core.model

import com.tajchert.trefoil.core.model.RouteKind.*
import com.tajchert.trefoil.core.model.RouteSource.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val direct = SavedEnvironment("env", "Mac", "https://mac.example/", "token")
    private val ssh = SavedEnvironment("env", "Mac", "", "token", SshProfile("mac.local", 22, "user", "SHA256:x", "ssh-ed25519"))
    private fun learned(kind: RouteKind, url: String) = EnvironmentRoute(kind, url, Learned)
    private fun hints(endpoints: List<DirectEndpointHint>? = null, tunnel: TunnelHint = TunnelHint.Unknown, id: String = "env", additive: Boolean = false) = RouteHints(id, endpoints, tunnel, additive)
    private fun lan(url: String) = DirectEndpointHint("lan", url)
    private fun tailnet(url: String) = DirectEndpointHint("tailnet", url)

    @Test fun `normalizedRouteBase canonicalizes scheme host and port`() {
        assertEquals("https://mac.example/", normalizedRouteBase("HTTPS://Mac.Example"))
        assertEquals("https://mac.example/", normalizedRouteBase(" https://mac.example/ "))
        assertEquals("https://mac.example/", normalizedRouteBase("https://mac.example:443/"))
        assertEquals("http://192.168.1.20/", normalizedRouteBase("http://192.168.1.20:80"))
        assertEquals("http://192.168.1.20:3773/", normalizedRouteBase("http://192.168.1.20:3773/"))
        assertEquals("https://mac.tail1234.ts.net:8443/", normalizedRouteBase("https://MAC.tail1234.ts.net:8443"))
        assertEquals("https://[fd00::1]/", normalizedRouteBase("https://[fd00::1]/"))
    }

    @Test fun `normalizedRouteBase rejects loopback paths userinfo queries and other schemes`() {
        listOf(
            "http://localhost:3773/", "https://LOCALHOST/", "https://app.localhost/", "http://127.0.0.1:3773/", "http://127.8.9.10/",
            "http://127.000.000.001/", "http://[::1]:3773/", "http://[0:0:0:0:0:0:0:1]/", "http://0.0.0.0:3773/", "http://[::]/",
            "https://mac.example/api", "https://mac.example/pair", "https://mac.example//", "https://user@mac.example/",
            "https://user:pw@mac.example/", "https://mac.example/?token=x", "https://mac.example/#token=x", "https://mac.example?x",
            "ftp://mac.example/", "ws://mac.example/", "mac.example", "", "   ", "https:///", "https://:443/", "https://mac.example:0/",
            "https://bad host/", "https://under_score.example/", "not a url",
        ).forEach { assertNull(it, normalizedRouteBase(it)) }
    }

    @Test fun `routeUsable allows HTTPS and plain HTTP only to RFC1918 literals`() {
        assertTrue(routeUsable("https://mac.example/"))
        assertTrue(routeUsable("https://8.8.8.8/"))
        assertTrue(routeUsable("http://10.0.0.5:3773/"))
        assertTrue(routeUsable("http://172.16.0.1/"))
        assertTrue(routeUsable("http://172.31.255.255/"))
        assertTrue(routeUsable("http://192.168.1.20:3773/"))
        assertFalse(routeUsable("http://172.32.0.1/"))
        assertFalse(routeUsable("http://100.64.1.2/"))
        assertFalse(routeUsable("http://8.8.8.8/"))
        assertFalse(routeUsable("http://mac.example/"))
        assertFalse(routeUsable("http://mac.local/"))
        assertFalse(routeUsable("http://192.168.001.020/"))
        assertFalse(routeUsable("ftp://192.168.1.20/"))
        assertFalse(routeUsable("https:///"))
        assertFalse(routeUsable("not a url"))
    }

    @Test fun `classifyRoute covers every branch`() {
        assertEquals(Lan, classifyRoute("http://192.168.1.20:3773/", null))
        assertEquals(Lan, classifyRoute("http://10.1.2.3/", null))
        assertEquals(Lan, classifyRoute("https://172.20.0.1/", null))
        assertEquals(Lan, classifyRoute("http://10.0.2.2:3773/", null))
        assertEquals(Lan, classifyRoute("http://127.0.0.1:4567/", null))
        assertEquals(Lan, classifyRoute("http://localhost:4567/", null))
        assertEquals(Lan, classifyRoute("http://[::1]:4567/", null))
        assertEquals(Tailnet, classifyRoute("https://mac.tail1234.ts.net/", null))
        assertEquals(Tailnet, classifyRoute("https://MAC.TAIL1234.TS.NET/", null))
        assertEquals(Tailnet, classifyRoute("http://100.64.0.1:3773/", null))
        assertEquals(Tailnet, classifyRoute("http://100.127.255.254/", null))
        assertEquals(Public, classifyRoute("http://100.63.0.1/", null))
        assertEquals(Public, classifyRoute("http://100.128.0.1/", null))
        assertEquals(Tunnel, classifyRoute("https://prod-abc.t3coderelay.com/", "prod-abc.t3coderelay.com"))
        assertEquals(Tunnel, classifyRoute("https://PROD-ABC.t3coderelay.com/", "prod-abc.T3CODERELAY.com"))
        assertEquals(Public, classifyRoute("https://prod-abc.t3coderelay.com/", null))
        assertEquals(Public, classifyRoute("https://prod-abc.t3coderelay.com/", "prod-other.t3coderelay.com"))
        assertEquals(Public, classifyRoute("https://mac.example/", "prod-abc.t3coderelay.com"))
        assertEquals(Public, classifyRoute("not a url", null))
        assertEquals(Public, classifyRoute("", null))
    }

    @Test fun `tunnelHost reads the learned tunnel route`() {
        assertNull(direct.tunnelHost())
        assertEquals("prod-abc.t3coderelay.com", direct.copy(routes = listOf(learned(Lan, "http://192.168.1.20/"), learned(Tunnel, "https://prod-abc.t3coderelay.com/"))).tunnelHost())
    }

    @Test fun `direct endpoints replace learned Lan and Tailnet routes`() {
        val start = direct.copy(routes = listOf(learned(Lan, "http://192.168.1.9/"), learned(Tailnet, "https://old.ts.net/"), learned(Tunnel, "https://prod-abc.t3coderelay.com/")))
        val next = start.withRouteHints(hints(listOf(lan("http://192.168.1.20:3773"), tailnet("https://mac.tail1234.ts.net/"), DirectEndpointHint("vpn", "https://10.8.0.2/"))))
        assertEquals(listOf(learned(Lan, "http://192.168.1.20:3773/"), learned(Lan, "https://10.8.0.2/"), learned(Tailnet, "https://mac.tail1234.ts.net/"), learned(Tunnel, "https://prod-abc.t3coderelay.com/")), next.routes)
    }

    @Test fun `additive direct endpoints add routes but never remove a saved one`() {
        val start = direct.copy(routes = listOf(learned(Lan, "http://192.168.1.9/"), learned(Tailnet, "https://mac.tail1234.ts.net/")))
        // T3's first config snapshot, before its Tailscale probe finished: LAN only.
        val partial = start.withRouteHints(hints(listOf(lan("http://192.168.1.20:3773")), additive = true))
        assertEquals(listOf(learned(Lan, "http://192.168.1.9/"), learned(Lan, "http://192.168.1.20:3773/"), learned(Tailnet, "https://mac.tail1234.ts.net/")), partial.routes)
        assertSame(start, start.withRouteHints(hints(emptyList(), additive = true)))
        val complete = partial.withRouteHints(hints(listOf(lan("http://192.168.1.20:3773"))))
        assertEquals("The complete list then replaces them", listOf(learned(Lan, "http://192.168.1.20:3773/")), complete.routes)
    }

    @Test fun `empty direct endpoints clear learned direct routes but null keeps them`() {
        val start = direct.copy(routes = listOf(learned(Lan, "http://192.168.1.9/"), learned(Tailnet, "https://old.ts.net/"), learned(Tunnel, "https://prod-abc.t3coderelay.com/")))
        assertSame(start, start.withRouteHints(hints(null)))
        assertEquals(listOf(learned(Tunnel, "https://prod-abc.t3coderelay.com/")), start.withRouteHints(hints(emptyList())).routes)
    }

    @Test fun `unusable or malformed direct endpoints are dropped`() {
        val next = direct.withRouteHints(hints(listOf(
            lan("http://127.0.0.1:3773/"), lan("http://localhost:3773/"), lan("http://8.8.8.8/"), lan("http://mac.local:3773/"),
            lan("https://192.168.1.20/api"), lan("https://user@192.168.1.20/"), lan("https://192.168.1.20/?q"), tailnet("http://100.64.0.1/"),
            lan("ws://192.168.1.20/"), lan(""), lan("http://192.168.1.20:3773/"),
        )))
        assertEquals(listOf(learned(Lan, "http://192.168.1.20:3773/")), next.routes)
    }

    @Test fun `active tunnel sets a single learned tunnel route`() {
        val first = direct.withRouteHints(hints(tunnel = TunnelHint.Active("Prod-ABC.t3coderelay.com")))
        assertEquals(listOf(learned(Tunnel, "https://prod-abc.t3coderelay.com/")), first.routes)
        val moved = first.withRouteHints(hints(tunnel = TunnelHint.Active("prod-def.t3coderelay.com")))
        assertEquals(listOf(learned(Tunnel, "https://prod-def.t3coderelay.com/")), moved.routes)
    }

    @Test fun `NotLinked removes the tunnel and Unknown keeps it`() {
        val start = direct.copy(routes = listOf(learned(Lan, "http://192.168.1.20/"), learned(Tunnel, "https://prod-abc.t3coderelay.com/")))
        assertEquals(listOf(learned(Lan, "http://192.168.1.20/")), start.withRouteHints(hints(tunnel = TunnelHint.NotLinked)).routes)
        assertSame(start, start.withRouteHints(hints(tunnel = TunnelHint.Unknown)))
    }

    @Test fun `invalid tunnel hostnames are ignored`() {
        val start = direct.copy(routes = listOf(learned(Tunnel, "https://prod-abc.t3coderelay.com/")))
        listOf("", " ", "localhost", "prod-abc.t3coderelay.com:443", "https://prod-abc.t3coderelay.com", "prod-abc.t3coderelay.com/x",
            "under_score.example", "-bad.example", "bad-.example", "a..b", "1.2.3.4", "192.168.1.20", "host name.example", "user@host.example",
            "x".repeat(64) + ".example", "prod.123").forEach { value ->
            assertSame(value, start, start.withRouteHints(hints(tunnel = TunnelHint.Active(value))))
            assertNull(value, direct.withRouteHints(hints(tunnel = TunnelHint.Active(value))).tunnelHost())
        }
    }

    @Test fun `learned routes never duplicate the paired baseUrl or each other`() {
        val paired = SavedEnvironment("env", "Mac", "http://192.168.1.20:3773/", "token", allowInsecureHttp = true)
        val next = paired.withRouteHints(hints(listOf(lan("http://192.168.1.20:3773"), lan("HTTP://192.168.1.21:3773/"), lan("http://192.168.1.21:3773"), tailnet("http://192.168.1.21:3773/"))))
        assertEquals(listOf(learned(Lan, "http://192.168.1.21:3773/")), next.routes)
    }

    @Test fun `learned routes are capped at eight and keep the tunnel`() {
        val endpoints = (1..12).map { lan("http://192.168.1.$it:3773/") }
        val next = direct.withRouteHints(hints(endpoints, TunnelHint.Active("prod-abc.t3coderelay.com")))
        assertEquals(MAX_LEARNED_ROUTES, next.routes.size)
        assertEquals((1..7).map { learned(Lan, "http://192.168.1.$it:3773/") } + learned(Tunnel, "https://prod-abc.t3coderelay.com/"), next.routes)
        assertEquals((1..8).map { learned(Lan, "http://192.168.1.$it:3773/") }, direct.withRouteHints(hints(endpoints)).routes)
    }

    @Test fun `hints for another environment are ignored`() {
        val start = direct.copy(routes = listOf(learned(Tunnel, "https://prod-abc.t3coderelay.com/")))
        assertSame(start, start.withRouteHints(hints(listOf(lan("http://192.168.1.20/")), TunnelHint.NotLinked, id = "other")))
    }

    @Test fun `same hints give an equal result`() {
        val h = hints(listOf(tailnet("https://mac.tail1234.ts.net"), lan("http://192.168.1.20:3773")), TunnelHint.Active("prod-abc.t3coderelay.com"))
        val once = direct.withRouteHints(h)
        val twice = once.withRouteHints(h)
        assertNotEquals(direct, once)
        assertEquals(once, twice)
        assertSame(once, twice)
        assertEquals(once, direct.withRouteHints(h))
    }

    @Test fun `stale persisted entries are sanitized`() {
        val start = direct.copy(routes = listOf(EnvironmentRoute(Ssh, "", Paired), learned(Tunnel, "HTTPS://Prod-ABC.t3coderelay.com:443"), learned(Public, "http://8.8.8.8/")))
        assertEquals(listOf(learned(Tunnel, "https://prod-abc.t3coderelay.com/")), start.withRouteHints(hints()).routes)
    }

    @Test fun `pairing through the tunnel keeps the tunnel marker so the paired route is classified Tunnel`() {
        val viaTunnel = SavedEnvironment("env", "Mac", "https://prod-abc.t3coderelay.com/", "token")
        val next = viaTunnel.withRouteHints(hints(emptyList(), TunnelHint.Active("prod-abc.t3coderelay.com")))
        assertEquals("prod-abc.t3coderelay.com", next.tunnelHost())
        assertEquals(listOf(EnvironmentRoute(Tunnel, "https://prod-abc.t3coderelay.com/", Paired)), next.candidateRoutes())
        assertSame(next, next.withRouteHints(hints(emptyList(), TunnelHint.Active("prod-abc.t3coderelay.com"))))
    }

    @Test fun `candidateRoutes orders by kind with paired first within a kind`() {
        val env = SavedEnvironment("env", "Mac", "https://mac.example/", "token", allowInsecureHttp = true, routes = listOf(
            learned(Tunnel, "https://prod-abc.t3coderelay.com/"), learned(Public, "https://other.example/"), learned(Tailnet, "https://mac.tail1234.ts.net/"),
            learned(Lan, "http://192.168.1.20:3773/"),
        ))
        assertEquals(listOf(
            learned(Lan, "http://192.168.1.20:3773/"), learned(Tailnet, "https://mac.tail1234.ts.net/"),
            EnvironmentRoute(Public, "https://mac.example/", Paired), learned(Public, "https://other.example/"), learned(Tunnel, "https://prod-abc.t3coderelay.com/"),
        ), env.candidateRoutes())
    }

    @Test fun `candidateRoutes classifies the paired route against the learned tunnel`() {
        val env = SavedEnvironment("env", "Mac", "https://prod-abc.t3coderelay.com/", "token", routes = listOf(learned(Tunnel, "https://prod-abc.t3coderelay.com/")))
        assertEquals(listOf(EnvironmentRoute(Tunnel, "https://prod-abc.t3coderelay.com/", Paired)), env.candidateRoutes())
        val lanPaired = SavedEnvironment("env", "Mac", "http://192.168.1.20:3773/", "token", allowInsecureHttp = true, routes = listOf(learned(Tunnel, "https://prod-abc.t3coderelay.com/")))
        assertEquals(listOf(EnvironmentRoute(Lan, "http://192.168.1.20:3773/", Paired), learned(Tunnel, "https://prod-abc.t3coderelay.com/")), lanPaired.candidateRoutes())
    }

    @Test fun `candidateRoutes adds an Ssh placeholder and never the SSH loopback address`() {
        val routes = listOf(learned(Lan, "https://192.168.1.20/"), learned(Tunnel, "https://prod-abc.t3coderelay.com/"))
        val expected = listOf(learned(Lan, "https://192.168.1.20/"), EnvironmentRoute(Ssh, "", Paired), learned(Tunnel, "https://prod-abc.t3coderelay.com/"))
        assertEquals(expected, ssh.copy(routes = routes).candidateRoutes())
        assertEquals(expected, ssh.copy(baseUrl = "http://127.0.0.1:4567/", routes = routes).candidateRoutes())
        assertEquals(listOf(EnvironmentRoute(Ssh, "", Paired)), ssh.candidateRoutes())
    }

    @Test fun `candidateRoutes hides learned HTTP LAN routes without consent`() {
        val routes = listOf(learned(Lan, "http://192.168.1.20:3773/"), learned(Lan, "https://192.168.1.21/"), learned(Tunnel, "https://prod-abc.t3coderelay.com/"))
        assertEquals(listOf(learned(Lan, "https://192.168.1.21/"), EnvironmentRoute(Public, "https://mac.example/", Paired), learned(Tunnel, "https://prod-abc.t3coderelay.com/")),
            direct.copy(routes = routes).candidateRoutes())
        assertEquals(learned(Lan, "http://192.168.1.20:3773/"), direct.copy(routes = routes, allowInsecureHttp = true).candidateRoutes().first())
        // The paired route keeps its existing behaviour: URL policy decides at connect time.
        val pairedHttp = SavedEnvironment("env", "Mac", "http://192.168.1.20:3773/", "token")
        assertEquals(listOf(EnvironmentRoute(Lan, "http://192.168.1.20:3773/", Paired)), pairedHttp.candidateRoutes())
    }

    @Test fun `candidateRoutes skips a blank baseUrl and ignores persisted Ssh or paired entries`() {
        val env = SavedEnvironment("env", "Mac", " ", "token", routes = listOf(EnvironmentRoute(Ssh, "", Learned), EnvironmentRoute(Lan, "https://192.168.1.5/", Paired), learned(Tunnel, "https://prod-abc.t3coderelay.com/")))
        assertEquals(listOf(learned(Tunnel, "https://prod-abc.t3coderelay.com/")), env.candidateRoutes())
    }

    @Test fun `old vault JSON without routes still decodes`() {
        val old = json.decodeFromString<SavedEnvironment>("""{"id":"env","name":"Mac","baseUrl":"https://mac.example/","accessToken":"token","allowInsecureHttp":false}""")
        assertEquals(emptyList<EnvironmentRoute>(), old.routes)
        assertEquals(direct, old)
        val oldSsh = json.decodeFromString<SavedEnvironment>("""{"id":"env","name":"Mac","baseUrl":"","accessToken":"token","ssh":{"host":"mac.local","port":22,"username":"user","fingerprint":"SHA256:x","keyType":"ssh-ed25519"}}""")
        assertEquals(emptyList<EnvironmentRoute>(), oldSsh.routes)
        assertEquals("mac.local", oldSsh.ssh?.host)
    }

    @Test fun `routes round trip through vault JSON and stay redacted`() {
        val env = direct.withRouteHints(hints(listOf(lan("http://192.168.1.20:3773")), TunnelHint.Active("prod-abc.t3coderelay.com")))
        val encoded = json.encodeToString(env)
        assertEquals(env, json.decodeFromString<SavedEnvironment>(encoded))
        assertEquals(env, env.forStorage())
        assertEquals(env.routes, ssh.copy(baseUrl = "http://127.0.0.1:4567/", routes = env.routes).forStorage().routes)
        assertFalse(env.toString().contains("token"))
        assertFalse(env.toString().contains("t3coderelay"))
    }
}
