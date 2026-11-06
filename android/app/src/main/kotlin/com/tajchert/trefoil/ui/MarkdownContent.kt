package com.tajchert.trefoil.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

internal data class MarkdownBlock(val text: String, val code: Boolean = false, val language: String = "")
internal fun markdownBlocks(text: String): List<MarkdownBlock> {
    val result = mutableListOf<MarkdownBlock>()
    val lines = mutableListOf<String>()
    var code = false
    var language = ""
    var fenceCharacter = '`'
    var fenceLength = 0
    fun flush() { if (lines.isNotEmpty()) { result += MarkdownBlock(lines.joinToString("\n"), code, language); lines.clear() } }
    text.lines().forEach { line ->
        val trimmed = line.trimStart()
        val fence = Regex("^(`{3,}|~{3,})(.*)$").matchEntire(trimmed)
        val marker = fence?.groupValues?.get(1)
        if (!code && marker != null) {
            flush()
            code = true
            fenceCharacter = marker.first()
            fenceLength = marker.length
            language = fence.groupValues[2].trim()
        } else if (code && marker != null && marker.first() == fenceCharacter && marker.length >= fenceLength && fence.groupValues[2].isBlank()) {
            flush()
            code = false
            language = ""
        } else if (!code && line.isBlank()) flush() else lines += line
    }
    flush()
    return result
}

@Composable
internal fun MarkdownContent(text: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val blocks = remember(text) { markdownBlocks(text) }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            blocks.forEach { block ->
                if (block.code) {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(block.language.ifBlank { "Code" }, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                IconButton(onClick = { clipboard.setText(AnnotatedString(block.text)) }) { Icon(Icons.Default.ContentCopy, "Copy code", Modifier.size(16.dp)) }
                            }
                            Text(block.text, Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        block.text.lines().forEach { line ->
                            val heading = Regex("^(#{1,6})\\s+(.*)$").matchEntire(line)
                            val bullet = Regex("^\\s*[-*+]\\s+(.*)$").matchEntire(line)
                            val quote = line.startsWith("> ")
                            val value = heading?.groupValues?.get(2) ?: bullet?.let { "•  ${it.groupValues[1]}" } ?: if (quote) line.removePrefix("> ") else line
                            val style = when (heading?.groupValues?.get(1)?.length) { 1 -> MaterialTheme.typography.headlineSmall; 2 -> MaterialTheme.typography.titleLarge; 3, 4, 5, 6 -> MaterialTheme.typography.titleMedium; else -> MaterialTheme.typography.bodyLarge }
                            Text(inlineMarkdown(value), style = style, fontWeight = if (heading != null) FontWeight.SemiBold else null, color = if (quote) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                modifier = if (quote) Modifier.background(MaterialTheme.colorScheme.surfaceContainer).padding(10.dp) else Modifier)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun inlineMarkdown(text: String) = buildAnnotatedString {
    val tokens = Regex("(`[^`]+`|\\*\\*[^*]+\\*\\*|\\*[^*]+\\*|\\[[^]]+]\\([^)]+\\))")
    var cursor = 0
    tokens.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        val token = match.value
        when {
            token.startsWith("`") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = MaterialTheme.colorScheme.surfaceContainerHigh)) { append(token.removeSurrounding("`")) }
            token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.removeSurrounding("**")) }
            token.startsWith("*") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(token.removeSurrounding("*")) }
            else -> withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(token.substringAfter('[').substringBefore(']')); append(" (${token.substringAfter("](").dropLast(1)})") }
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}
