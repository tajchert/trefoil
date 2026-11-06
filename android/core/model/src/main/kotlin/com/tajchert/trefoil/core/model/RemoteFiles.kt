/* File-link position and candidate rules adapted from T3 Code shared/markdownLinks.ts (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.core.model

/** Host paths are resolved only by the authenticated environment's asset service. */
data class RemoteFileReference(
    val path: String? = null,
    val attachmentId: String? = null,
    val fileName: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
) {
    val identity: String get() = attachmentId?.let { "attachment:$it" } ?: "path:$path"
    override fun toString() = "RemoteFileReference(content=***)"
}
data class FileDownloadProgress(val bytesDownloaded: Long, val totalBytes: Long?)
data class DownloadedFile(val fileName: String, val mimeType: String, val sizeBytes: Long)

/** Conservative file-shaped absolute paths: directories and shortened or executable expressions fail. */
private fun absoluteFile(value: String): String? {
    val path = value.trim().replace(Regex(":\\d+(?::\\d+)?$"), "")
    if (path.length !in 2..1024 || !path.startsWith('/') || path.startsWith("//") || path.endsWith('/')) return null
    if (path.any { it.isISOControl() || it in "`$*?<>|;{}\\\"" }) return null
    if (path.split('/').any { it in setOf("..", ".", "...", "…") }) return null
    val name = path.substringAfterLast('/')
    if (!Regex(".+\\.[\\p{L}\\p{N}_-]+").matches(name) && name !in extensionlessFiles) return null
    return path
}
private val extensionlessFiles = setOf("Makefile", "Dockerfile", "Containerfile", "Justfile", "Rakefile", "Gemfile", "Procfile", "Brewfile", "Caddyfile", "Vagrantfile", "Jenkinsfile", "Podfile", "Fastfile", "BUILD", "WORKSPACE", "LICENSE", "LICENCE", "COPYING", "NOTICE", "AUTHORS", "CONTRIBUTORS", "CHANGELOG", "README", "CODEOWNERS")
private val commandLine = Regex("^(?:[$>]\\s*|(?:adb|cat|ls|cp|mv|rm|chmod|chown|curl|wget|sh|bash|zsh|sudo|find|stat|open|install|echo|printf|cd|export|python\\d*|node|java|git|gradle|\\./gradlew)\\s)")

/** Only assistant prose supplies inferred references. Structured attachments retain their metadata. */
fun ConversationMessage.remoteFiles(): List<RemoteFileReference> {
    if (role != "assistant") return attachments.distinctBy { it.identity }
    val candidates = mutableListOf<Pair<Int, String>>()
    // Keep offsets while masking code fences, inline code and destinations from the plain-text pass.
    val visible = text.take(256_000).toCharArray()
    fun mask(start: Int, end: Int) { for (i in start until end) if (visible[i] != '\n') visible[i] = ' ' }
    var fence: String? = null
    var offset = 0
    text.take(256_000).split('\n').forEach { line ->
        val trimmed = line.trimStart()
        val marker = Regex("^(`{3,}|~{3,})").find(trimmed)?.value
        if (fence != null || marker != null) {
            mask(offset, offset + line.length)
            if (fence == null) fence = marker else if (marker != null && marker.first() == fence.first() && marker.length >= fence.length) fence = null
        } else if (line.startsWith("    ") || line.startsWith('\t') || commandLine.containsMatchIn(trimmed)) mask(offset, offset + line.length)
        offset += line.length + 1
    }
    val outsideFences = String(visible)
    Regex("\\[[^\\]\\n]*]\\(\\s*(?:<([^>\\n]+)>|([^\\s)]+))(?:\\s+[\"'][^\\n]*?[\"'])?\\s*\\)").findAll(outsideFences).forEach { match ->
        val raw = match.groups[1]?.value ?: match.groups[2]?.value.orEmpty()
        val decoded = try { java.net.URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8) } catch (_: Exception) { "" }
        absoluteFile(decoded.substringBefore('#'))?.let { candidates += match.range.first to it }
        mask(match.range.first, match.range.last + 1)
    }
    Regex("`+[^`\\n]*`+").findAll(String(visible)).forEach { match ->
        absoluteFile(match.value.trim('`'))?.let { candidates += match.range.first to it }
        mask(match.range.first, match.range.last + 1)
    }
    Regex("(?<![^\\s(\\[])\\/[^\\s`<>\\\"']+").findAll(String(visible)).forEach { match ->
        absoluteFile(match.value.trimEnd('.', ',', '!', ')', ']'))?.let { candidates += match.range.first to it }
    }
    return (attachments + candidates.sortedBy { it.first }.map { (_, path) -> RemoteFileReference(path = path, fileName = path.substringAfterLast('/')) }).distinctBy { it.identity }
}
