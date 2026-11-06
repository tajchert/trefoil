package com.tajchert.trefoil.core.model

enum class ConnectionKind { Ssh, Pairing, Saved }
enum class ConnectionPhase { Idle, Testing, AwaitingTrust, Succeeded, Failed }
data class ConnectionAttempt(
    val id: Long = 0,
    val kind: ConnectionKind? = null,
    val phase: ConnectionPhase = ConnectionPhase.Idle,
    val message: String = "",
)
