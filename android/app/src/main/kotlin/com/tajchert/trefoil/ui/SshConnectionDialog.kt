package com.tajchert.trefoil.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_KEY_BYTES = 256 * 1024

@Composable
internal fun SshConnectionDialog(busy: Boolean, initial: SshConnectionInput?, dismiss: () -> Unit, pickerActive: (Boolean) -> Unit, attempt: ConnectionAttempt = ConnectionAttempt(), connect: (SshConnectionInput) -> Unit) {
    // Credentials deliberately never enter rememberSaveable / Android saved state.
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var host by remember { mutableStateOf(initial?.host.orEmpty()) }
    var username by remember { mutableStateOf(initial?.username.orEmpty()) }
    var port by remember { mutableStateOf((initial?.port ?: 22).toString()) }
    var method by remember { mutableStateOf(initial?.authMethod ?: SshAuthMethod.Password) }
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var saveLogin by remember { mutableStateOf(false) }
    var home by remember { mutableStateOf(initial?.t3Home ?: "~/.t3") }
    var executable by remember { mutableStateOf(initial?.executable.orEmpty()) }
    var advanced by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickerActive(false)
        if (uri != null) {
            importing = true
            error = null
            scope.launch {
                val imported = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = stream.read(buffer, 0, minOf(buffer.size, MAX_KEY_BYTES + 1 - output.size()))
                                if (count <= 0) break
                                output.write(buffer, 0, count)
                                if (output.size() > MAX_KEY_BYTES) break
                            }
                            val bytes = output.toByteArray()
                            require(bytes.size <= MAX_KEY_BYTES)
                            bytes.toString(Charsets.UTF_8)
                        } ?: kotlin.error("Cannot open file")
                    }
                }
                imported.onSuccess { privateKey = it; error = privateKeyValidationError(it) }.onFailure { error = "Could not read private key. Choose a readable file of at most 256 KiB, or paste it below." }
                importing = false
            }
        }
    }
    val enabled = !busy && !importing
    val errorReveal = remember { BringIntoViewRequester() }
    val shownError = error ?: attempt.message.takeIf { attempt.phase == ConnectionPhase.Failed }
    LaunchedEffect(shownError) { if (shownError != null) errorReveal.bringIntoView() }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (initial == null) "Connect over SSH" else "SSH login required") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (initial == null) "Connect to an installed, running T3 Code server through an encrypted SSH tunnel." else "Enter credentials to reconnect to ${initial.name}.")
            OutlinedTextField(name, { name = it; error = null }, label = { Text("Display name") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(host, { host = it; error = null }, label = { Text("IP address or hostname") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(username, { username = it; error = null }, label = { Text("SSH username") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(port, { port = it; error = null }, label = { Text("SSH port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(method == SshAuthMethod.Password, { method = SshAuthMethod.Password; error = null }, enabled = enabled)
                Text("Password")
                RadioButton(method == SshAuthMethod.PrivateKey, { method = SshAuthMethod.PrivateKey; error = null }, enabled = enabled)
                Text("Private key")
            }
            if (method == SshAuthMethod.Password) {
                OutlinedTextField(password, { password = it; error = null }, label = { Text("SSH password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
            } else {
                OutlinedButton(onClick = { pickerActive(true); runCatching { importer.launch(arrayOf("*/*")) }.onFailure { pickerActive(false); error = "No file picker is available. Paste the private key instead." } }, enabled = enabled) { Text(if (importing) "Reading key…" else "Import private key file") }
                OutlinedTextField(privateKey, { privateKey = it; error = privateKeyValidationError(it) }, label = { Text("Paste private key") }, visualTransformation = PasswordVisualTransformation(), minLines = 2, maxLines = 4, enabled = enabled, modifier = Modifier.fillMaxWidth())
                Text("OpenSSH or PEM private key, up to 256 KiB.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(passphrase, { passphrase = it; error = null }, label = { Text("Key passphrase (optional)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(saveLogin, { saveLogin = it }, enabled = enabled)
                Text("Save login credentials on this device", Modifier.weight(1f))
            }
            Text("Saved login credentials are encrypted. Otherwise, you will be asked again when reconnecting.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { advanced = !advanced }, enabled = enabled) { Text(if (advanced) "Hide advanced settings" else "Advanced settings") }
            if (advanced) {
                OutlinedTextField(home, { home = it; error = null }, label = { Text("T3 data directory") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(executable, { executable = it; error = null }, label = { Text("T3 executable (optional)") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
            }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(attempt.message.ifBlank { "Testing connection…" }) }
            shownError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.bringIntoViewRequester(errorReveal)) }
        }
    }, confirmButton = {
        Button(enabled = enabled, onClick = {
            val parsedPort = port.toIntOrNull()
            error = sshInputValidationError(SshConnectionInput(name, host, username, parsedPort ?: 0, method, password, privateKey, passphrase, t3Home = home))
            if (error == null && parsedPort != null) connect(SshConnectionInput(
                name = name.trim(), host = host.trim(), username = username.trim(), port = parsedPort,
                authMethod = method, password = if (method == SshAuthMethod.Password) password else "",
                privateKey = if (method == SshAuthMethod.PrivateKey) privateKey else "",
                passphrase = if (method == SshAuthMethod.PrivateKey) passphrase else "", rememberCredentials = saveLogin,
                t3Home = home.trim(), executable = executable.trim(), savedEnvironmentId = initial?.savedEnvironmentId,
            ))
        }) {
            if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text(if (busy) "Testing connection…" else "Connect")
        }
    }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
internal fun SshHostKeyDialog(key: SshHostKey, trust: () -> Unit, cancel: () -> Unit) {
    AlertDialog(onDismissRequest = cancel, title = { Text("Verify SSH host key") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Confirm this fingerprint with the server before sending your login credentials.")
            SelectionContainer { Text("${key.host}:${key.port}\n${key.keyType}\n${key.fingerprint}") }
            Text("On the server, compare with:")
            SelectionContainer { Text("for key in /etc/ssh/ssh_host_*_key.pub; do ssh-keygen -lf \"\$key\" -E sha256; done") }
            Text("Trust only if the key type and SHA256 fingerprint match.")
        }
    }, confirmButton = { Button(onClick = trust) { Text("Trust and connect") } }, dismissButton = { TextButton(onClick = cancel) { Text("Cancel") } })
}
