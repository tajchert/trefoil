package com.tajchert.trefoil.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrefoilApp(
    state: ClientState, savedEnvironments: List<SavedEnvironment>,
    onPair: (String, String, Boolean) -> Unit, onConnect: (SavedEnvironment) -> Unit,
    onSelectThread: (String) -> Unit, onSend: (String) -> Unit, onInterrupt: () -> Unit,
    onRespond: (String, String, Map<String, List<String>>) -> Unit,
    onCreateThread: (String, String, ModelOption) -> Unit,
    onReconnect: () -> Unit, onDisconnect: () -> Unit, onForget: (SavedEnvironment) -> Unit,
    onSshConnect: (SshConnectionInput) -> Unit = {},
    sshHostKey: SshHostKey? = null,
    onTrustSsh: () -> Unit = {},
    onCancelSsh: () -> Unit = {},
    sshLogin: SshConnectionInput? = null,
    onSshKeyPickerActive: (Boolean) -> Unit = {},
    connectionAttempt: ConnectionAttempt = ConnectionAttempt(),
) {
    TrefoilTheme {
        val conversationState = rememberSaveableStateHolder()
        var adding by remember { mutableStateOf(false) }
        var sshConnecting by remember { mutableStateOf(false) }
        var pairing by rememberSaveable { mutableStateOf(false) }
        var choosingEnvironment by rememberSaveable { mutableStateOf(false) }
        var creating by rememberSaveable { mutableStateOf(false) }
        var showThread by rememberSaveable { mutableStateOf(false) }
        var submittedSsh by remember { mutableStateOf(false) }
        var submittedPair by remember { mutableStateOf(false) }
        LaunchedEffect(connectionAttempt) {
            if (connectionAttempt.phase == ConnectionPhase.Succeeded) {
                if (connectionAttempt.kind == ConnectionKind.Ssh && submittedSsh) { sshConnecting = false; submittedSsh = false; choosingEnvironment = false }
                if (connectionAttempt.kind == ConnectionKind.Pairing && submittedPair) { pairing = false; submittedPair = false; choosingEnvironment = false }
            }
        }
        val select: (String) -> Unit = { onSelectThread(it); showThread = true }
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints {
                val wide = maxWidth >= 840.dp
                BackHandler((showThread || choosingEnvironment) && !wide) { showThread = false; choosingEnvironment = false }
                Scaffold(topBar = {
                    TopAppBar(title = {
                        Column {
                            Text("Trefoil", fontWeight = FontWeight.Bold)
                            Text(state.environment?.let { "${it.name} · ${state.connection.name}" } ?: "Your work, within reach", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }, navigationIcon = {
                        if (showThread && !wide) IconButton(onClick = { showThread = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to threads") }
                        else Icon(Icons.Default.Spa, null, Modifier.padding(start = 20.dp, end = 8.dp), tint = MaterialTheme.colorScheme.primary)
                    }, actions = {
                        if (savedEnvironments.isNotEmpty()) IconButton(onClick = { choosingEnvironment = true; showThread = false }) { Icon(Icons.Default.Dns, "Saved environments") }
                        IconButton(onClick = { adding = true }, enabled = !state.busy) { Icon(Icons.Default.AddLink, "Add environment") }
                        if (state.environment != null) IconButton(onClick = { onDisconnect(); choosingEnvironment = true; showThread = false }) { Icon(Icons.Default.LinkOff, "Disconnect environment") }
                    })
                }) { padding ->
                    Column(Modifier.padding(padding).imePadding()) {
                        ConnectionBanner(state, onReconnect)
                        if (state.environment == null || choosingEnvironment) {
                            EnvironmentScreen(savedEnvironments, state.busy, { choosingEnvironment = false; onConnect(it) }, onForget, { pairing = true }, { sshConnecting = true })
                        } else Row(Modifier.weight(1f)) {
                            if (wide || !showThread) {
                                Box(if (wide) Modifier.width(320.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                                    ThreadList(state, select, { creating = true })
                                }
                            }
                            if (wide) VerticalDivider()
                            if (wide || showThread) Box(Modifier.weight(1f).fillMaxHeight()) {
                                conversationState.SaveableStateProvider("${state.environment?.id}:${state.selectedThreadId}") {
                                    Conversation(state, onSend, onInterrupt, onRespond)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("Add environment") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { adding = false; sshConnecting = true }, modifier = Modifier.fillMaxWidth()) { Text("Connect over SSH") }
                OutlinedButton(onClick = { adding = false; pairing = true }, modifier = Modifier.fillMaxWidth()) { Text("Pair with link") }
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } })
        if (sshConnecting || sshLogin != null) key(sshLogin?.savedEnvironmentId) {
            SshConnectionDialog(state.busy, sshLogin, { sshConnecting = false; submittedSsh = false; onCancelSsh() }, onSshKeyPickerActive, connectionAttempt.takeIf { it.kind == ConnectionKind.Ssh } ?: ConnectionAttempt()) {
                submittedSsh = true
                choosingEnvironment = false
                showThread = false
                onSshConnect(it)
            }
        }
        if (sshHostKey != null) SshHostKeyDialog(sshHostKey, onTrustSsh, onCancelSsh)
        if (pairing) PairDialog(state.busy, { pairing = false; submittedPair = false; onCancelSsh() }, connectionAttempt.takeIf { it.kind == ConnectionKind.Pairing } ?: ConnectionAttempt()) { url, name, insecure -> submittedPair = true; onPair(url, name, insecure) }
        if (creating) NewThreadDialog(state, { creating = false }) { project, title, model ->
            onCreateThread(project, title, model); creating = false; showThread = true
        }
    }
}

@Composable
private fun ConnectionBanner(state: ClientState, reconnect: () -> Unit) {
    if (state.connection == ConnectionStatus.Connected && state.error == null && !state.busy) return
    Surface(color = if (state.error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.busy || state.connection == ConnectionStatus.Connecting || state.connection == ConnectionStatus.Reconnecting)
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(if (state.busy) "Connecting…" else state.connection.name, style = MaterialTheme.typography.labelLarge)
                Text(state.error ?: if (state.environment == null) "Connect a T3 environment to get started." else "Drafts stay here. Reconnect to continue.", style = MaterialTheme.typography.bodySmall)
            }
            if (state.environment != null && !state.busy) TextButton(onClick = reconnect) { Text("Reconnect") }
        }
    }
}

@Composable
private fun EnvironmentScreen(saved: List<SavedEnvironment>, busy: Boolean, connect: (SavedEnvironment) -> Unit, forget: (SavedEnvironment) -> Unit, add: () -> Unit, ssh: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Spacer(Modifier.height(32.dp))
            Icon(Icons.Default.Spa, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
            Text("A little closer\nto your next idea.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("Connect directly to T3 Code. Follow your conversations and keep remote work moving.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Button(onClick = add, enabled = !busy) { Icon(Icons.Default.AddLink, null); Spacer(Modifier.width(8.dp)); Text("Pair with link") }
            OutlinedButton(onClick = ssh, enabled = !busy) { Text("Connect over SSH") }
        }
        if (saved.isNotEmpty()) item { Text("SAVED ENVIRONMENTS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        items(saved, key = { it.id }) { environment ->
            ElevatedCard(onClick = { connect(environment) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Dns, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) { Text(environment.name, fontWeight = FontWeight.SemiBold); Text(environment.ssh?.let { "${it.username}@${it.host}:${it.port}" } ?: environment.baseUrl, style = MaterialTheme.typography.bodySmall) }
                    IconButton(onClick = { forget(environment) }, enabled = !busy) { Icon(Icons.Default.DeleteOutline, "Forget ${environment.name}") }
                }
            }
        }
    }
}

@Composable
private fun ThreadList(state: ClientState, select: (String) -> Unit, create: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Conversations", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            IconButton(onClick = create, enabled = state.canWrite && state.projects.isNotEmpty() && state.models.isNotEmpty()) { Icon(Icons.Default.Add, "New conversation") }
        } }
        if (state.busy && state.projects.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (!state.busy && state.projects.isEmpty()) item { Text("No projects available in this environment.", Modifier.padding(vertical = 24.dp)) }
        state.projects.forEach { project ->
            item(key = "project:${project.id}") {
                Column(Modifier.padding(top = 16.dp, bottom = 4.dp)) {
                    Text(project.title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                    if (project.path.isNotBlank()) Text(project.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val threads = state.threads.filter { it.projectId == project.id }
            if (threads.isEmpty()) item { Text("No conversations yet", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp)) }
            items(threads, key = { it.id }) { thread ->
                Card(onClick = { select(thread.id) }, colors = CardDefaults.cardColors(containerColor = if (state.selectedThreadId == thread.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(thread.title.ifBlank { "Untitled conversation" }, fontWeight = FontWeight.Medium)
                        Text(thread.status.replace('_', ' '), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        item { Text("Projects and conversations reported by this environment", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp)) }
    }
}

@Composable
private fun Conversation(state: ClientState, send: (String) -> Unit, interrupt: () -> Unit, respond: (String, String, Map<String, List<String>>) -> Unit) {
    if (state.selectedThreadId == null) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Forum, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Room for your next thought", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp))
                Text("Choose a conversation or start a new one.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }; return
    }
    var draft by rememberSaveable(state.environment?.id, state.selectedThreadId) { mutableStateOf("") }
    var submitted by rememberSaveable(state.environment?.id, state.selectedThreadId) { mutableStateOf<String?>(null) }
    var messageIdsAtSend by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    LaunchedEffect(state.messages) {
        val pending = submitted
        if (pending != null && state.messages.any { it.role == "user" && it.text == pending && it.id !in messageIdsAtSend }) {
            if (draft == pending) draft = ""
            submitted = null
        }
    }
    LaunchedEffect(state.error) { if (state.error != null) submitted = null }
    val list = rememberLazyListState()
    val nearBottom by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 2 } ?: true } }
    LaunchedEffect(state.messages.lastOrNull()?.text, state.activities.size, state.requests.size) {
        if (nearBottom && list.layoutInfo.totalItemsCount > 0) list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1)
    }
    Column(Modifier.fillMaxSize()) {
        Text(state.threads.find { it.id == state.selectedThreadId }?.title ?: "Conversation", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(20.dp))
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (state.busy && state.messages.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!state.busy && state.messages.isEmpty()) item { Text("This conversation is ready for your first message.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.messages, key = { "message:${it.id}" }) { message ->
                Surface(color = if (message.role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(if (message.role == "user") "YOU" else message.role.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        SelectionContainer { Text(message.text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge) }
                        if (message.streaming) Text("Writing…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            items(state.activities, key = { "tool:${it.id}" }) { activity ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Build, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text(activity.title, style = MaterialTheme.typography.labelLarge) }
                        if (activity.detail.isNotBlank()) SelectionContainer { Text(activity.detail, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.padding(top = 8.dp)) }
                    }
                }
            }
            items(state.requests, key = { "request:${it.id}" }) { RequestCard(it, state.canWrite, respond) }
        }
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (state.activeRunId != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Run active · next message will be queued", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = interrupt, enabled = state.canWrite && !state.busy) { Text("Interrupt run") }
                }
                if (!state.canWrite) Text("Read only while disconnected or awaiting authorization", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 8.dp))
                if (submitted != null) Text("Waiting for the server to confirm your message…", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(draft, { draft = it }, modifier = Modifier.weight(1f), placeholder = { Text("Message T3…") }, maxLines = 6, shape = MaterialTheme.shapes.large)
                    IconButton(onClick = { if (draft.isNotBlank()) { messageIdsAtSend = ArrayList(state.messages.map { it.id }); submitted = draft; send(draft) } }, enabled = state.canWrite && !state.busy && draft.isNotBlank() && submitted == null, modifier = Modifier.padding(start = 8.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Send, if (state.activeRunId != null) "Queue message" else "Send message", tint = if (state.canWrite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestCard(request: RuntimeRequest, enabled: Boolean, respond: (String, String, Map<String, List<String>>) -> Unit) {
    var answers by remember(request.id) { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    val valid = request.questions.all { !it.required || answers[it.id]?.any(String::isNotBlank) == true }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("NEEDS YOUR ATTENTION", style = MaterialTheme.typography.labelSmall)
            Text(request.title, style = MaterialTheme.typography.titleMedium)
            if (request.detail.isNotBlank()) SelectionContainer { Text(request.detail, style = MaterialTheme.typography.bodyMedium) }
            if (!request.canApprove) {
                Text("This request is not supported here. Open another T3 client to respond.")
            } else {
                request.questions.forEach { question ->
                    Text(question.title, fontWeight = FontWeight.Medium)
                    if (question.options.isEmpty()) OutlinedTextField(
                        value = answers[question.id]?.firstOrNull().orEmpty(),
                        onValueChange = { answers = answers + (question.id to listOf(it)) },
                        enabled = enabled, modifier = Modifier.fillMaxWidth(), label = { Text("Your answer") })
                    else question.options.forEach { option ->
                        val checked = option in answers[question.id].orEmpty()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked, onCheckedChange = { selected ->
                                answers = answers + (question.id to if (question.multiple) {
                                    if (selected) answers[question.id].orEmpty() + option else answers[question.id].orEmpty() - option
                                } else if (selected) listOf(option) else emptyList())
                            }, enabled = enabled)
                            Text(option, Modifier.weight(1f))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (request.questions.isNotEmpty()) Button(onClick = { respond(request.id, "accept", answers) }, enabled = enabled && valid) { Text("Submit answers") }
                    else {
                        Button(onClick = { respond(request.id, "accept", emptyMap()) }, enabled = enabled) { Text("Approve") }
                        OutlinedButton(onClick = { respond(request.id, "decline", emptyMap()) }, enabled = enabled) { Text("Deny") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PairDialog(busy: Boolean, dismiss: () -> Unit, attempt: ConnectionAttempt = ConnectionAttempt(), pair: (String, String, Boolean) -> Unit) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var insecure by remember(url) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val target = remember(url) { runCatching { parsePairingLink(url) }.getOrNull() }
    val uri = target?.let { runCatching { java.net.URI(it.baseUrl) }.getOrNull() }
    val privateHttp = uri?.scheme == "http" && isPrivateLanAddress(uri.host.orEmpty())
    val shownError = error ?: attempt.message.takeIf { attempt.phase == ConnectionPhase.Failed }
    val reveal = remember { BringIntoViewRequester() }
    LaunchedEffect(shownError) { if (shownError != null) reveal.bringIntoView() }
    AlertDialog(onDismissRequest = dismiss, icon = { Icon(Icons.Default.AddLink, null) }, title = { Text("Pair an environment") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text("Paste the pairing link from your T3 server. Trefoil verifies the environment before connecting.")
            OutlinedTextField(name, { name = it; error = null }, enabled = !busy, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it; error = null }, enabled = !busy, label = { Text("Pairing link") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), maxLines = 3)
            Text("Pairing links contain credentials. Keep them private.", style = MaterialTheme.typography.bodySmall)
            if (privateHttp) {
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(insecure, { insecure = it; error = null }, enabled = !busy); Text("Allow unencrypted HTTP on this private network", Modifier.weight(1f)) }
                Text("HTTP exposes pairing credentials and conversations to others on the network. Use only a network you trust.", style = MaterialTheme.typography.bodySmall)
            }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(attempt.message.ifBlank { "Testing connection…" }) }
            shownError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.bringIntoViewRequester(reveal)) }
        }
    }, confirmButton = { Button(onClick = {
        error = when { name.isBlank() -> "Enter a display name."; target == null -> "Enter a valid pairing link."; privateHttp && !insecure -> "Choose whether to allow HTTP on this private network."; else -> null }
        if (error == null) pair(url.trim(), name.trim(), privateHttp && insecure)
    }, enabled = !busy) {
        if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Text(if (busy) "Testing connection…" else "Pair")
    } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun NewThreadDialog(state: ClientState, dismiss: () -> Unit, create: (String, String, ModelOption) -> Unit) {
    var projectId by rememberSaveable { mutableStateOf(state.projects.firstOrNull()?.id.orEmpty()) }
    var modelIndex by rememberSaveable { mutableIntStateOf(0) }
    var title by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("New conversation") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Conversation title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("PROJECT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            state.projects.forEach { project ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(projectId == project.id, onClick = { projectId = project.id })
                    Text(project.title, Modifier.weight(1f))
                }
            }
            Text("PROVIDER / MODEL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            if (state.models.isEmpty()) Text("No models reported by this environment. Reconnect to refresh configuration.")
            state.models.forEachIndexed { index, model ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(modelIndex == index, onClick = { modelIndex = index })
                    Column(Modifier.weight(1f)) { Text(model.label); Text(model.instanceId, style = MaterialTheme.typography.labelSmall) }
                }
            }
            Text("Starts with approvals required. Workspace uses the server default.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        Button(onClick = { state.models.getOrNull(modelIndex)?.let { create(projectId, title.trim(), it) } },
            enabled = state.canWrite && !state.busy && title.isNotBlank() && projectId.isNotBlank() && state.models.getOrNull(modelIndex) != null) { Text("Create") }
    }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
