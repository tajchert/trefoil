package com.tajchert.trefoil.core.model

import java.net.InetAddress
import java.net.URI
import kotlinx.serialization.Serializable

/** Declaration order is preference order (T3 routes.ts ROUTE_KIND_RANK). Ssh is never persisted. */
@Serializable enum class RouteKind { Lan, Tailnet, Public, Ssh, Tunnel }
@Serializable enum class RouteSource { Paired, Learned }
/** One way to reach an environment. [baseUrl] is a normalized `scheme://host[:port]/` and never carries credentials. */
@Serializable data class EnvironmentRoute(val kind: RouteKind, val baseUrl: String, val source: RouteSource)

/** Host hints. directEndpoints == null: the server did not say (keep what we have); empty: it has none. */
data class DirectEndpointHint(val kind: String, val httpBaseUrl: String)
sealed interface TunnelHint {
    /** Old server, link-state failed/404/401, or tunnel not up yet. */
    data object Unknown : TunnelHint
    /** linked == false or linkRevoked == true. */
    data object NotLinked : TunnelHint
    data class Active(val hostname: String) : TunnelHint
}
/**
 * [additive]: [directEndpoints] may still be incomplete (T3 sends its first config snapshot before network discovery
 * finishes), so they add learned routes but never remove one; a later complete list replaces them.
 */
data class RouteHints(val environmentId: String, val directEndpoints: List<DirectEndpointHint>?, val tunnel: TunnelHint, val additive: Boolean = false)

const val MAX_LEARNED_ROUTES = 8

private fun parse(url: String): URI? = try { URI(url.trim()) } catch (_: Exception) { null }

/** Lowercase host without IPv6 brackets, or null. */
private fun URI.plainHost(): String? = host?.lowercase()?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotBlank() }

private fun ipv4Octets(host: String): List<Int>? {
    val parts = host.split('.')
    if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } }) return null
    return parts.map { it.toInt() }.takeIf { octets -> octets.all { it in 0..255 } }
}

/** Loopback or unspecified: never a durable route to another machine. Literal IPv6 is parsed without DNS. */
private fun isLoopbackOrUnspecified(host: String): Boolean {
    if (host == "localhost" || host.endsWith(".localhost")) return true
    ipv4Octets(host)?.let { return it[0] == 127 || it.all { octet -> octet == 0 } }
    if (':' !in host) return false
    return try { InetAddress.getByName(host).let { it.isLoopbackAddress || it.isAnyLocalAddress } } catch (_: Exception) { true }
}

/**
 * Returns `scheme://host[:port]/` with lowercase scheme and host and the default port dropped, or null for
 * non-http(s), userinfo, a blank host, a path other than `/`, a query, a fragment, loopback or `0.0.0.0`.
 */
fun normalizedRouteBase(url: String): String? {
    val uri = parse(url) ?: return null
    val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    if (uri.rawPath.orEmpty() !in setOf("", "/")) return null
    val host = uri.plainHost() ?: return null
    if (isLoopbackOrUnspecified(host) || uri.port == 0 || uri.port > 65535) return null
    val port = if ((scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)) -1 else uri.port
    return try { URI(scheme, null, host, port, "/", null, null).toASCIIString() } catch (_: Exception) { null }
}

/** HTTPS with a host, or plain HTTP only to a literal RFC1918 IPv4. Consent is checked at connect time. */
fun routeUsable(url: String): Boolean {
    val uri = parse(url) ?: return false
    val host = uri.plainHost() ?: return false
    return when (uri.scheme?.lowercase()) {
        "https" -> true
        "http" -> isPrivateLanAddress(host)
        else -> false
    }
}

/** Kind of the paired `baseUrl`; [tunnelHost] is the learned tunnel hostname, if any. */
fun classifyRoute(url: String, tunnelHost: String?): RouteKind {
    val host = parse(url)?.plainHost() ?: return RouteKind.Public
    val octets = ipv4Octets(host)
    return when {
        isPrivateLanAddress(host) || host == "10.0.2.2" || isLoopbackOrUnspecified(host) -> RouteKind.Lan
        host.endsWith(".ts.net") || (octets != null && octets[0] == 100 && octets[1] in 64..127) -> RouteKind.Tailnet
        tunnelHost != null && host.equals(tunnelHost, ignoreCase = true) -> RouteKind.Tunnel
        else -> RouteKind.Public
    }
}

/** Host of the learned [RouteKind.Tunnel] route, if any. */
fun SavedEnvironment.tunnelHost(): String? =
    routes.firstOrNull { it.kind == RouteKind.Tunnel }?.let { parse(it.baseUrl)?.plainHost() }

private val DNS_LABEL = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?")

/** A DNS hostname: lowercase labels of `[a-z0-9-]`, at least one dot, no port, no IP literal. */
private fun tunnelHostname(value: String): String? {
    val host = value.trim().lowercase().removeSuffix(".")
    val labels = host.split('.')
    if (host.length > 253 || labels.size < 2 || labels.any { it.length > 63 || !DNS_LABEL.matches(it) }) return null
    if (ipv4Octets(host) != null || labels.last().all { it in '0'..'9' }) return null
    return host
}

/**
 * Applies host hints to the learned routes. Hints for another environment are ignored. Non-null direct endpoints
 * replace all learned Lan/Tailnet routes (an [RouteHints.additive] hint only adds them); an active tunnel replaces the learned tunnel, NotLinked removes it,
 * Unknown keeps it. Learned routes are deduplicated by normalized base (also against the paired `baseUrl`),
 * sorted by kind and capped at [MAX_LEARNED_ROUTES], keeping the tunnel. Equal hints give an equal result.
 */
fun SavedEnvironment.withRouteHints(hints: RouteHints): SavedEnvironment {
    if (hints.environmentId != id) return this
    var learned = routes
    hints.directEndpoints?.let { endpoints ->
        val direct = endpoints.mapNotNull { hint ->
            normalizedRouteBase(hint.httpBaseUrl)?.takeIf(::routeUsable)
                ?.let { EnvironmentRoute(if (hint.kind == "tailnet") RouteKind.Tailnet else RouteKind.Lan, it, RouteSource.Learned) }
        }
        learned = if (hints.additive) learned + direct else learned.filterNot { it.kind == RouteKind.Lan || it.kind == RouteKind.Tailnet } + direct
    }
    when (val tunnel = hints.tunnel) {
        is TunnelHint.Active -> tunnelHostname(tunnel.hostname)?.let { host ->
            learned = learned.filterNot { it.kind == RouteKind.Tunnel } + EnvironmentRoute(RouteKind.Tunnel, "https://$host/", RouteSource.Learned)
        }
        TunnelHint.NotLinked -> learned = learned.filterNot { it.kind == RouteKind.Tunnel }
        TunnelHint.Unknown -> Unit
    }
    val paired = normalizedRouteBase(baseUrl)
    val seen = mutableSetOf<String>()
    val cleaned = learned.filter { it.kind != RouteKind.Ssh }.sortedBy { it.kind.ordinal }.mapNotNull { route ->
        val base = normalizedRouteBase(route.baseUrl)?.takeIf(::routeUsable) ?: return@mapNotNull null
        // A tunnel equal to the paired address is kept: it marks the paired route as the tunnel (candidateRoutes dedupes it).
        if ((base != paired || route.kind == RouteKind.Tunnel) && seen.add(base)) route.copy(baseUrl = base, source = RouteSource.Learned) else null
    }
    val tunnels = cleaned.filter { it.kind == RouteKind.Tunnel }.take(1)
    val capped = cleaned.filter { it.kind != RouteKind.Tunnel }.take(MAX_LEARNED_ROUTES - tunnels.size) + tunnels
    return if (capped == routes) this else copy(routes = capped)
}

/**
 * The connection plan, sorted by [RouteKind] and, within a kind, paired before learned: the paired `baseUrl`
 * (direct environments only), all learned routes, and an `Ssh` placeholder for SSH environments. Learned plain-HTTP
 * routes are excluded unless [SavedEnvironment.allowInsecureHttp]; the paired route keeps its existing behaviour.
 */
fun SavedEnvironment.candidateRoutes(): List<EnvironmentRoute> {
    val tunnel = tunnelHost()
    val paired = baseUrl.takeIf { ssh == null && it.isNotBlank() }?.let { EnvironmentRoute(classifyRoute(it, tunnel), it, RouteSource.Paired) }
    val learned = routes.filter { route ->
        route.source == RouteSource.Learned && route.kind != RouteKind.Ssh &&
            (allowInsecureHttp || parse(route.baseUrl)?.scheme?.lowercase() != "http")
    }
    val ssh = if (ssh != null) EnvironmentRoute(RouteKind.Ssh, "", RouteSource.Paired) else null
    return (listOfNotNull(paired) + learned + listOfNotNull(ssh))
        .distinctBy { if (it.kind == RouteKind.Ssh) "ssh" else normalizedRouteBase(it.baseUrl) ?: it.baseUrl }
        .sortedWith(compareBy({ it.kind.ordinal }, { it.source.ordinal }))
}
