package com.tajchert.trefoil.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class SshAuthMethod { Password, PrivateKey }

/** Only persisted inside the encrypted credential vault. */
@Serializable
data class SshProfile(
    val host: String,
    val port: Int = 22,
    val username: String,
    val fingerprint: String,
    val keyType: String,
    val t3Home: String = "~/.t3",
    val executable: String = "",
    val authMethod: SshAuthMethod = SshAuthMethod.Password,
    val password: String? = null,
    val privateKey: String? = null,
    val passphrase: String? = null,
) {
    override fun toString() = "SshProfile(credentials=***)"
}

/** Ephemeral UI input: never save to Android instance state. */
data class SshConnectionInput(
    val name: String,
    val host: String,
    val username: String,
    val port: Int = 22,
    val authMethod: SshAuthMethod = SshAuthMethod.Password,
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val rememberCredentials: Boolean = false,
    val t3Home: String = "~/.t3",
    val executable: String = "",
    val savedEnvironmentId: String? = null,
) {
    override fun toString() = "SshConnectionInput(credentials=***)"
}

@Serializable
data class SshHostKey(val host: String, val port: Int, val keyType: String, val fingerprint: String)

/** Ephemeral forwarding addresses never become durable connection endpoints. */
fun SavedEnvironment.forStorage(): SavedEnvironment = if (ssh == null) this else copy(baseUrl = "")

fun SshConnectionInput.savedProfile(key: SshHostKey): SshProfile = SshProfile(
    host = host.trim(), port = port, username = username.trim(), fingerprint = key.fingerprint,
    keyType = key.keyType, t3Home = t3Home, executable = executable, authMethod = authMethod,
    password = password.takeIf { rememberCredentials && authMethod == SshAuthMethod.Password },
    privateKey = privateKey.takeIf { rememberCredentials && authMethod == SshAuthMethod.PrivateKey },
    passphrase = passphrase.takeIf { rememberCredentials && authMethod == SshAuthMethod.PrivateKey },
)

fun SavedEnvironment.sshInput(): SshConnectionInput {
    val profile = requireNotNull(ssh)
    return SshConnectionInput(name, profile.host, profile.username, profile.port, profile.authMethod,
        profile.password.orEmpty(), profile.privateKey.orEmpty(), profile.passphrase.orEmpty(),
        profile.password != null || profile.privateKey != null, profile.t3Home, profile.executable, id)
}
