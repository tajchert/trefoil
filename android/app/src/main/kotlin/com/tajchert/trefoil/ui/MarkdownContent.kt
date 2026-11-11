package com.tajchert.trefoil.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

internal enum class MdAlign { Start, Center, End }
internal data class MdListItem(val depth: Int, val marker: String, val checked: Boolean?, val text: String)

internal sealed interface MdNode {
    data class Code(val text: String, val language: String) : MdNode
    data class Heading(val level: Int, val text: String) : MdNode
    data class Paragraph(val text: String) : MdNode
    data class ListBlock(val items: List<MdListItem>) : MdNode
    data class Quote(val text: String) : MdNode
    data object Rule : MdNode
    data class Table(val header: List<String>, val align: List<MdAlign>, val rows: List<List<String>>) : MdNode
}

private val ruleRegex = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
private val headingRegex = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)(\\s+#+)?\\s*$")
private val listRegex = Regex("^(\\s*)([-*+]|\\d{1,9}[.)])\\s+(.*)$")
private val quoteRegex = Regex("^\\s{0,3}>\\s?(.*)$")
private val taskRegex = Regex("^\\[([ xX])]\\s+(.*)$")
private val separatorCell = Regex("^:?-+:?$")

/** Splits a table row on unescaped pipes; `\|` becomes a literal pipe inside the cell. */
internal fun splitTableRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        if (ch == '\\' && i + 1 < s.length && s[i + 1] == '|') { cell.append('|'); i += 2; continue }
        if (ch == '|') { cells += cell.toString().trim(); cell.clear() } else cell.append(ch)
        i++
    }
    cells += cell.toString().trim()
    return cells
}

private fun isTableStart(lines: List<String>, i: Int): Boolean {
    if (i + 1 >= lines.size || !lines[i].contains('|') || !lines[i + 1].contains('-')) return false
    val sep = splitTableRow(lines[i + 1])
    return sep.all { separatorCell.matches(it) } && sep.size == splitTableRow(lines[i]).size
}

private fun startsBlock(lines: List<String>, i: Int): Boolean {
    val line = lines[i]
    return ruleRegex.matches(line) || headingRegex.matches(line) || listRegex.matches(line) || quoteRegex.matches(line) || isTableStart(lines, i)
}

internal fun parseProse(lines: List<String>): List<MdNode> {
    val out = mutableListOf<MdNode>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.isBlank()) { i++; continue }
        val heading = headingRegex.matchEntire(line)
        when {
            ruleRegex.matches(line) -> { out += MdNode.Rule; i++ }
            heading != null -> { out += MdNode.Heading(heading.groupValues[1].length, heading.groupValues[2]); i++ }
            isTableStart(lines, i) -> {
                val header = splitTableRow(line)
                val align = splitTableRow(lines[i + 1]).map { c ->
                    when { c.startsWith(":") && c.endsWith(":") -> MdAlign.Center; c.endsWith(":") -> MdAlign.End; else -> MdAlign.Start }
                }
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                    val cells = splitTableRow(lines[i])
                    rows += List(header.size) { cells.getOrElse(it) { "" } }
                    i++
                }
                out += MdNode.Table(header, align, rows)
            }
            quoteRegex.matches(line) -> {
                val quoted = mutableListOf<String>()
                while (i < lines.size) { val m = quoteRegex.matchEntire(lines[i]) ?: break; quoted += m.groupValues[1]; i++ }
                out += MdNode.Quote(quoted.joinToString("\n"))
            }
            listRegex.matches(line) -> {
                val items = mutableListOf<MdListItem>()
                val indents = mutableListOf<Int>()
                while (i < lines.size) {
                    val current = lines[i]
                    val m = listRegex.matchEntire(current)
                    if (m != null && !ruleRegex.matches(current)) {
                        val indent = m.groupValues[1].replace("\t", "    ").length
                        while (indents.isNotEmpty() && indent < indents.last()) indents.removeAt(indents.lastIndex)
                        if (indents.isEmpty() || indent > indents.last()) indents += indent
                        val marker = m.groupValues[2]
                        val task = taskRegex.matchEntire(m.groupValues[3])
                        items += MdListItem((indents.size - 1).coerceAtMost(3), if (marker.first().isDigit()) marker else "•",
                            task?.let { it.groupValues[1] != " " }, task?.groupValues?.get(2) ?: m.groupValues[3])
                        i++
                    } else if (current.isNotBlank() && current.first().isWhitespace() && !startsBlock(lines, i) && items.isNotEmpty()) {
                        items[items.lastIndex] = items.last().let { it.copy(text = it.text + "\n" + current.trim()) }
                        i++
                    } else break
                }
                out += MdNode.ListBlock(items)
            }
            else -> {
                val para = mutableListOf(line.trimEnd())
                i++
                while (i < lines.size && lines[i].isNotBlank() && !startsBlock(lines, i)) { para += lines[i].trimEnd(); i++ }
                out += MdNode.Paragraph(para.joinToString("\n"))
            }
        }
    }
    return out
}

internal fun parseMarkdown(text: String): List<MdNode> = markdownBlocks(text).flatMap { block ->
    if (block.code) listOf(MdNode.Code(block.text, block.language)) else parseProse(block.text.lines())
}

private val inlineRegex = Regex(
    "\\\\([\\\\`*_{}\\[\\]()#+\\-.!|~>])" +                      // 1 escape
        "|`([^`]+)`" +                                            // 2 code
        "|\\[((?:[^\\[\\]]|\\[[^\\]]*])+)]\\(\\s*([^)\\s]+)(?:\\s+\"[^\"]*\")?\\s*\\)" + // 3,4 link
        "|\\*\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*\\*" +                // 5 bold italic
        "|\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*|(?<!\\w)__(?=\\S)(.+?)(?<=\\S)__(?!\\w)" + // 6,7 bold
        "|~~(?=\\S)(.+?)(?<=\\S)~~" +                              // 8 strike
        "|\\*(?=\\S)([^*]+?)(?<=\\S)\\*|(?<!\\w)_(?=\\S)([^_]+?)(?<=\\S)_(?!\\w)") // 9,10 italic

internal fun isSafeLink(url: String) = url.startsWith("http://", true) || url.startsWith("https://", true)

private fun AnnotatedString.Builder.appendInline(text: String, c: TrefoilColors, codeSize: TextUnit, codeBg: Color, onLink: (String) -> Unit) {
    var cursor = 0
    inlineRegex.findAll(text).forEach { m ->
        append(text.substring(cursor, m.range.first))
        val g = m.groups
        fun inner(s: String) = appendInline(s, c, codeSize, codeBg, onLink)
        when {
            g[1] != null -> append(g[1]!!.value)
            g[2] != null -> withStyle(SpanStyle(fontFamily = MonoFamily, fontSize = codeSize, background = codeBg)) { append(g[2]!!.value) }
            g[3] != null -> {
                val url = g[4]!!.value
                if (isSafeLink(url)) {
                    withLink(LinkAnnotation.Clickable(url, TextLinkStyles(SpanStyle(color = c.accent)), { onLink(url) })) { inner(g[3]!!.value) }
                } else inner(g[3]!!.value)
            }
            g[5] != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic)) { inner(g[5]!!.value) }
            g[6] != null || g[7] != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { inner((g[6] ?: g[7])!!.value) }
            g[8] != null -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { inner(g[8]!!.value) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inner((g[9] ?: g[10])!!.value) }
        }
        cursor = m.range.last + 1
    }
    append(text.substring(cursor))
}

@Composable
private fun rememberInline(text: String, codeSize: TextUnit = 13.sp, codeBg: Color = Tf.colors.s2): AnnotatedString {
    val c = Tf.colors
    val uri = LocalUriHandler.current
    return remember(text, c, codeSize, codeBg) { buildAnnotatedString { appendInline(text, c, codeSize, codeBg) { runCatching { uri.openUri(it) } } } }
}

@Composable
internal fun MarkdownContent(text: String, modifier: Modifier = Modifier) {
    val nodes = remember(text) { parseMarkdown(text) }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { nodes.forEach { MdNodeView(it) } }
    }
}

@Composable
private fun MdNodeView(node: MdNode) {
    val c = Tf.colors
    val clipboard = LocalClipboardManager.current
    when (node) {
        is MdNode.Code -> Surface(shape = RoundedCornerShape(14.dp), color = c.s1, contentColor = c.text, border = BorderStroke(1.dp, c.line)) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(node.language.ifBlank { "Code" }, Modifier.weight(1f), style = TrefoilType.mono.copy(fontSize = 11.sp), color = c.muted)
                    IconButton(onClick = { clipboard.setText(AnnotatedString(node.text)) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.ContentCopy, "Copy code", Modifier.size(15.dp), tint = c.muted) }
                }
                Text(node.text, Modifier.horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 12.dp), style = TrefoilType.code, color = c.text)
            }
        }
        is MdNode.Heading -> {
            val size = when (node.level) { 1 -> 21; 2 -> 19; 3 -> 17; else -> 15 }
            Text(rememberInline(node.text, (size - 2).sp), style = TrefoilType.message.copy(fontSize = size.sp, lineHeight = (size + 7).sp, fontWeight = FontWeight.SemiBold), color = c.text)
        }
        is MdNode.Paragraph -> Text(rememberInline(node.text), style = TrefoilType.message, color = c.text)
        MdNode.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(c.line))
        is MdNode.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(c.line, RoundedCornerShape(2.dp)))
            Text(rememberInline(node.text), Modifier.padding(start = 12.dp), style = TrefoilType.message, color = c.muted)
        }
        is MdNode.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            node.items.forEach { item ->
                Row(Modifier.padding(start = (item.depth * 20).dp), verticalAlignment = Alignment.Top) {
                    if (item.checked != null) {
                        Box(Modifier.padding(top = 4.dp, end = 8.dp).size(16.dp).clip(RoundedCornerShape(4.dp))
                            .background(if (item.checked) c.accent else Color.Transparent)
                            .border(1.5.dp, if (item.checked) c.accent else c.muted, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                            if (item.checked) Icon(Icons.Default.Check, null, Modifier.size(12.dp), tint = c.onAccent)
                        }
                    } else Text(item.marker, Modifier.widthIn(min = 20.dp).padding(end = 4.dp), style = TrefoilType.message, color = c.muted)
                    Text(rememberInline(item.text), Modifier.weight(1f), style = TrefoilType.message, color = if (item.checked == true) c.muted else c.text)
                }
            }
        }
        is MdNode.Table -> MdTable(node)
    }
}

private val TableMinColumn = 64.dp
private val TableMaxColumn = 280.dp

@Composable
private fun MdTable(table: MdNode.Table) {
    val c = Tf.colors
    val cols = table.header.size
    val shape = RoundedCornerShape(10.dp)
    val cellStyle = TrefoilType.message.copy(fontSize = 13.5.sp, lineHeight = 19.sp)
    val rowCount = table.rows.size + 1
    BoxWithConstraints {
    val available = constraints.maxWidth
    Box(Modifier.clip(shape).border(1.dp, c.line, shape).horizontalScroll(rememberScrollState())) {
        Layout(content = {
            for (r in 0 until rowCount) for (col in 0 until cols) {
                val header = r == 0
                val raw = if (header) table.header[col] else table.rows[r - 1][col]
                val align = table.align.getOrElse(col) { MdAlign.Start }
                val lastCol = col == cols - 1
                val lastRow = r == rowCount - 1
                Box(Modifier.fillMaxSize().background(if (header) c.s2 else Color.Transparent).drawBehind {
                    val t = 1.dp.toPx()
                    if (!lastCol) drawRect(c.line, Offset(size.width - t, 0f), Size(t, size.height))
                    if (!lastRow) drawRect(c.line, Offset(0f, size.height - t), Size(size.width, t))
                }.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(rememberInline(raw, 12.5.sp, if (header) c.s3 else c.s2), Modifier.fillMaxWidth(),
                        style = cellStyle.copy(fontWeight = if (header) FontWeight.SemiBold else cellStyle.fontWeight,
                            textAlign = when (align) { MdAlign.Start -> TextAlign.Start; MdAlign.Center -> TextAlign.Center; MdAlign.End -> TextAlign.End }),
                        color = c.text)
                }
            }
        }) { measurables, _ ->
            val minW = TableMinColumn.roundToPx(); val maxW = TableMaxColumn.roundToPx()
            val natural = IntArray(cols) { col ->
                (0 until rowCount).maxOf { r -> measurables[r * cols + col].maxIntrinsicWidth(Int.MAX_VALUE) }.coerceIn(minW, maxW)
            }
            // Longest unbreakable word per column: below this a cell can't wrap any narrower.
            val tightest = IntArray(cols) { col ->
                (0 until rowCount).maxOf { r -> measurables[r * cols + col].minIntrinsicWidth(Int.MAX_VALUE) }.coerceIn(minW, natural[col])
            }
            val widths = tableColumnWidths(natural, tightest, available)
            val heights = IntArray(rowCount) { r -> (0 until cols).maxOf { col -> measurables[r * cols + col].maxIntrinsicHeight(widths[col]) } }
            val placeables = measurables.mapIndexed { index, m -> m.measure(Constraints.fixed(widths[index % cols], heights[index / cols])) }
            layout(widths.sum(), heights.sum()) {
                var y = 0
                for (r in 0 until rowCount) {
                    var x = 0
                    for (col in 0 until cols) { placeables[r * cols + col].placeRelative(x, y); x += widths[col] }
                    y += heights[r]
                }
            }
        }
    }
    }
}

/**
 * Auto table layout: natural widths when they fit; otherwise columns give up their slack in proportion,
 * wrapping text, down to their longest word. Only a table that can't fit even then scrolls sideways.
 */
internal fun tableColumnWidths(natural: IntArray, tightest: IntArray, available: Int): IntArray {
    val naturalSum = natural.sum(); val tightSum = tightest.sum()
    if (available == Constraints.Infinity || naturalSum <= available) return natural
    if (tightSum >= available) return tightest
    val ratio = (available - tightSum).toDouble() / (naturalSum - tightSum)
    val widths = IntArray(natural.size) { tightest[it] + ((natural[it] - tightest[it]) * ratio).toInt() }
    widths[widths.indices.maxBy { natural[it] - tightest[it] }] += available - widths.sum()
    return widths
}
