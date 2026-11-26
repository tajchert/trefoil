package com.tajchert.trefoil.ui.diff

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/**
 * Longest line the viewer lays out. A minified bundle can put megabytes on one line; laying that out would exceed
 * Compose's layout constraints, so the rest is replaced by an ellipsis.
 */
internal const val DIFF_MAX_RENDER_CHARS = 2_000
internal const val DIFF_TAB_WIDTH = 4

/**
 * Builds the text of one diff line: tabs become spaces (the mono font draws a tab as a box), the line is cut at
 * [DIFF_MAX_RENDER_CHARS], and [wordRanges] (flat start/end-exclusive pairs into [text]) get a [highlight] background.
 * Called once per row (remembered by the row), never per frame. Never log the result: it is file content.
 */
internal fun buildDiffLineText(text: String, wordRanges: IntArray?, highlight: Color): AnnotatedString {
    var shown = text
    var ranges = wordRanges
    if (text.indexOf('\t') >= 0) {
        val expanded = StringBuilder(text.length + 8)
        // map[i] is where original index i lands in the expanded text; map[length] is its end.
        val map = IntArray(text.length + 1)
        for (i in text.indices) {
            map[i] = expanded.length
            if (text[i] == '\t') repeat(DIFF_TAB_WIDTH) { expanded.append(' ') } else expanded.append(text[i])
        }
        map[text.length] = expanded.length
        shown = expanded.toString()
        if (ranges != null) {
            val mapped = IntArray(ranges.size)
            for (i in ranges.indices) mapped[i] = map[ranges[i].coerceIn(0, text.length)]
            ranges = mapped
        }
    }
    val clipped = shown.length > DIFF_MAX_RENDER_CHARS
    val limit = if (clipped) DIFF_MAX_RENDER_CHARS else shown.length
    val display = if (clipped) shown.substring(0, limit) + "…" else shown
    if (ranges == null || ranges.size < 2) return AnnotatedString(display)
    return buildAnnotatedString {
        append(display)
        var i = 0
        while (i + 1 < ranges.size) {
            val start = ranges[i].coerceIn(0, limit)
            val end = ranges[i + 1].coerceIn(0, limit)
            if (end > start) addStyle(SpanStyle(background = highlight), start, end)
            i += 2
        }
    }
}
