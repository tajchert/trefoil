package com.tajchert.trefoil.core.model

/** Existing remote sessions; viewing never installs tools, boots devices, or opens tabs. */
sealed interface ScreenTarget {
    val threadId: String
    data class Browser(
        override val threadId: String, val tabId: String, val title: String = "Browser",
        val url: String = "", val interactive: Boolean = false,
    ) : ScreenTarget
    data class Device(
        override val threadId: String, val hostId: String, val deviceId: String,
        val platform: String, val name: String = "Device", val serveSimOnly: Boolean = false,
    ) : ScreenTarget
}

data class ScreenCatalog(
    val browsers: List<ScreenTarget.Browser> = emptyList(),
    val devices: List<ScreenTarget.Device> = emptyList(),
    val loaded: Boolean = false, val loading: Boolean = false, val error: String? = null,
) {
    val targets: List<ScreenTarget> get() = browsers + devices
}

/** Memory-only access for one viewer attempt. Never persist or reuse access across viewer attempts. */
data class ScreenAccess(
    val environmentId: String, val connectionGeneration: Long, val target: ScreenTarget,
    val httpBase: String, val wsBase: String, val query: Map<String, String>,
    val credentials: Boolean = false, val interactive: Boolean = false,
    val selectionGeneration: Long = 0,
) {
    override fun toString() = "ScreenAccess(credentials=***)"
}
