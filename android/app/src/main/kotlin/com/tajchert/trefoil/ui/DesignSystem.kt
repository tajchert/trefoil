package com.tajchert.trefoil.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.tajchert.trefoil.ui.adaptive.handPointer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import com.tajchert.trefoil.ui.adaptive.focusRing

/* ---------- Icons: the design's 24px stroke set (1.8 stroke, round caps/joins) ---------- */

private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"
private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
    "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

private fun strokeIcon(name: String, vararg paths: String, filled: List<String> = emptyList()): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { d ->
            addPath(PathParser().parsePathString(d).toNodes(), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null)
        }
        filled.forEach { d -> addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black)) }
    }.build()

private const val EFFORT_BARS = 4
private const val STAR = "M12 3l2.9 5.9 6.5.9-4.7 4.6 1.1 6.5L12 17.8 6.2 20.9l1.1-6.5L2.6 9.8l6.5-.9z"
private const val SHIELD = "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"

private fun effortBarsIcon(filled: Int): ImageVector = ImageVector.Builder("effortBars$filled", 24.dp, 24.dp, 24f, 24f).apply {
    for (i in 0 until EFFORT_BARS) {
        val x = 4f + i * 5f
        val h = 5f + i * 4f
        addPath(PathParser().parsePathString("M$x ${20f - h}h3v${h - 1f}a1.5 1.5 0 0 1 -1.5 1.5a1.5 1.5 0 0 1 -1.5 -1.5z").toNodes(),
            fill = SolidColor(Color.Black), fillAlpha = if (i < filled) 1f else .3f)
    }
}.build()

/** Icons from the Trefoil design canvas. Use with [Icon]; they tint with the content color. */
object TfIcons {
    private val effortBars by lazy { List(EFFORT_BARS + 1) { effortBarsIcon(it) } }
    /** Signal-style bars with [filled] of 4 bars solid (reasoning effort level). */
    fun effort(filled: Int): ImageVector = effortBars[filled.coerceIn(0, EFFORT_BARS)]
    val Back by lazy { strokeIcon("back", "M19 12H5M11 18l-6-6 6-6") }
    val Search by lazy { strokeIcon("search", circle(11f, 11f, 7f), "M20 20l-3.5-3.5") }
    /** Sliders: the design's settings/filters affordance. */
    val Settings by lazy { strokeIcon("settings", "M4 7h10M18 7h2M4 17h4M12 17h8", circle(16f, 7f, 2f), circle(10f, 17f, 2f)) }
    val ChevronDown by lazy { strokeIcon("chevronDown", "M6 9l6 6 6-6") }
    val ChevronRight by lazy { strokeIcon("chevronRight", "M9 6l6 6-6 6") }
    val Branch by lazy { strokeIcon("branch", circle(6f, 6f, 2.2f), circle(6f, 18f, 2.2f), circle(18f, 8f, 2.2f), "M6 8.2v7.6M18 10.2c0 4-4 4.5-10.4 6.6") }
    val Terminal by lazy { strokeIcon("terminal", "M5 8l4 4-4 4M12 16h7") }
    val Plus by lazy { strokeIcon("plus", "M12 5v14M5 12h14") }
    val More by lazy { strokeIcon("more", filled = listOf(circle(12f, 5f, 1.6f), circle(12f, 12f, 1.6f), circle(12f, 19f, 1.6f))) }
    val Attach by lazy { strokeIcon("attach", "M20 11l-8.2 8.2a4.6 4.6 0 0 1-6.5-6.5l8.2-8.2a3 3 0 0 1 4.3 4.3L9.6 17a1.5 1.5 0 0 1-2.1-2.1l7.6-7.6") }
    val Mic by lazy { strokeIcon("mic", rect(9f, 3f, 6f, 11f, 3f), "M18 11a6 6 0 0 1-12 0M12 17v4") }
    /** Arrow up: send / start. */
    val Send by lazy { strokeIcon("send", "M12 19V5M6 11l6-6 6 6") }
    /** Arrow pointing up and left: put a suggestion into the field. */
    val Insert by lazy { strokeIcon("insert", "M17 17L7 7M7 15V7h8") }
    /** Corner arrow: steer the running turn with a message. */
    val Steer by lazy { strokeIcon("steer", "M4 20v-7a5 5 0 0 1 5-5h11M15 3l5 5-5 5") }
    /** List with plus: queue a message behind the running turn. */
    val Queue by lazy { strokeIcon("queue", "M4 7h12M4 12h8M4 17h6M18 14v6M15 17h6") }
    /** Two people: the thread's agents. */
    val Agents by lazy { strokeIcon("agents", circle(9f, 8f, 3f), "M3 20a6 6 0 0 1 12 0", circle(17f, 9f, 2.4f), "M16.5 14.2A5 5 0 0 1 21 19") }
    val Stop by lazy { strokeIcon("stop", filled = listOf(rect(5f, 5f, 14f, 14f, 3f))) }
    val Close by lazy { strokeIcon("close", "M6 6l12 12M18 6L6 18") }
    val Check by lazy { strokeIcon("check", "M5 12l5 5 9-10") }
    /** Access / runtime mode. */
    val Shield by lazy { strokeIcon("shield", "M12 3L5 6v5c0 4.5 3 8.5 7 10 4-1.5 7-5.5 7-10V6z") }
    /** Runtime modes, matching the desktop app: supervised (lock), auto-accept edits (pen), auto (shield check), full access (shield off). */
    val Lock by lazy { strokeIcon("lock", rect(5f, 11f, 14f, 10f, 2f), "M8 11V7a4 4 0 0 1 8 0v4") }
    val PenLine by lazy { strokeIcon("penLine", "M13 21h8", "M16.4 3.6a2.1 2.1 0 1 1 3 3L7 19l-4 1 1-4z") }
    val ShieldCheck by lazy { strokeIcon("shieldCheck", SHIELD, "M9 12l2 2 4-4") }
    val ShieldOff by lazy {
        strokeIcon("shieldOff", "M2 2l20 20", "M5 5a1 1 0 0 0-1 1v7c0 5 3.5 7.5 7.67 8.94a1 1 0 0 0 .67.01c2.35-.82 4.48-1.97 5.9-3.71",
            "M9.31 3.65a12.25 12.25 0 0 0 1.93-1.37a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1v7a9.8 9.8 0 0 1-.08 1.26")
    }
    /** Favourite toggle: outline and filled star. */
    val Star by lazy { strokeIcon("star", STAR) }
    val StarFilled by lazy { strokeIcon("starFilled", STAR, filled = listOf(STAR)) }
    /** Fast mode. */
    val Bolt by lazy { strokeIcon("bolt", "M13 2L4 14h7l-1 8 9-12h-7z") }
    /** Model picker. */
    val Sparkle by lazy { strokeIcon("sparkle", "M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z") }
    val File by lazy { strokeIcon("file", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5") }
    /** File with plus: changes / diff. */
    val Changes by lazy { strokeIcon("changes", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5M12 11v6M9 14h6") }
    val Edit by lazy { strokeIcon("edit", "M4 20h4L19 9l-4-4L4 16z") }
    /** Quotation marks: an assistant citation. */
    val Quote by lazy { strokeIcon("quote", "M10 7H6a1 1 0 0 0-1 1v4h5zM10 12c0 2.5-1 4-3.5 4.5", "M19 7h-4a1 1 0 0 0-1 1v4h5zM19 12c0 2.5-1 4-3.5 4.5") }
    /** Stacked lines: settled / archive shelf. */
    val Settled by lazy { strokeIcon("settled", "M4 7h16M6 12h12M9 17h6") }
    val Link by lazy { strokeIcon("link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1") }
    val Scan by lazy { strokeIcon("scan", "M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3M8 12h8") }
    /** Small desktop box (Mac mini style). */
    val Desktop by lazy { strokeIcon("desktop", rect(4f, 7f, 16f, 10f, 3f), "M8 12h.01") }
    val Laptop by lazy { strokeIcon("laptop", rect(4f, 5f, 16f, 11f, 2f), "M2 19h20") }
    val Server by lazy { strokeIcon("server", rect(4f, 4f, 16f, 7f, 2f), rect(4f, 13f, 16f, 7f, 2f), "M8 7.5h.01M8 16.5h.01") }
    /** Screen sessions (camera-like frame). */
    val Screens by lazy { strokeIcon("screens", rect(3f, 5f, 18f, 12f, 2f), "M8 21h8M12 17v4") }
    /** Speedometer: limits and usage. */
    val Gauge by lazy { strokeIcon("gauge", "M5.6 18.4a9 9 0 1 1 12.8 0", "M12 12l4-4", circle(12f, 12f, 1.2f)) }
    val Trash by lazy { strokeIcon("trash", "M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3") }
    val Disconnect by lazy { strokeIcon("disconnect", "M9 15l-2 2a3 3 0 0 1-4.2-4.2l2-2M15 9l2-2a3 3 0 0 1 4.2 4.2l-2 2M4 4l16 16") }
    val Refresh by lazy { strokeIcon("refresh", "M20 11a8 8 0 1 0-2.3 5.7M20 5v6h-6") }
    /** Route kinds: local network, public HTTPS and the T3 Connect tunnel. */
    val Wifi by lazy { strokeIcon("wifi", "M3 9.5a14 14 0 0 1 18 0M6 13a9.5 9.5 0 0 1 12 0M9 16.5a5 5 0 0 1 6 0", "M12 20h.01") }
    val Globe by lazy { strokeIcon("globe", circle(12f, 12f, 9f), "M3 12h18M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9c-2.5-2.6-3.8-5.6-3.8-9S9.5 5.6 12 3z") }
    /** Rail destination for the thread list. */
    val Threads by lazy { strokeIcon("threads", "M5 5h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-7l-4 3.5V17H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M8 9.5h8M8 13h5") }
    /** Opens the navigation drawer on medium widths that have no rail. */
    val Menu by lazy { strokeIcon("menu", "M4 7h16M4 12h16M4 17h16") }
    /** Hide or show the thread list pane. */
    val PanelLeft by lazy { strokeIcon("panelLeft", rect(3f, 5f, 18f, 14f, 2.5f), "M9 5v14") }
    /** Toggle the side panel docked beside the conversation. */
    val PanelRight by lazy { strokeIcon("panelRight", rect(3f, 5f, 18f, 14f, 2.5f), "M15 5v14") }
    /** Work-log glyphs: reasoning (bulb), generic tool (wrench), mixed group (hammer), severe failure (alert). */
    val Reasoning by lazy { strokeIcon("reasoning", "M9 18h6M10 21h4M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2.1h5c0-.9.4-1.6 1-2.1A6 6 0 0 0 12 3z") }
    val Wrench by lazy { strokeIcon("wrench", "M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4l-2.6 2.6-2.4-.6-.6-2.4z") }
    val Hammer by lazy { strokeIcon("hammer", "M14 10L4.5 19.5a2.1 2.1 0 0 0 3 3L17 13M13 5l5-2 3 3-2 5-3-1-4-4z") }
    val Alert by lazy { strokeIcon("alert", "M12 4l9 16H3z", "M12 10v4M12 17h.01") }
    val Cloud by lazy { strokeIcon("cloud", "M7 18a4 4 0 0 1-.6-7.96A5.5 5.5 0 0 1 17 9.5a4.2 4.2 0 0 1 .5 8.5z") }
}

/* ---------- Brand mark ---------- */

private const val LEAF = "M0 -5C-15 -11 -21 -27 -13 -39C-7 -47 7 -47 13 -39C21 -27 15 -11 0 -5Z"

private fun markVector(c1: Color, c2: Color, c3: Color): ImageVector =
    ImageVector.Builder("trefoil", 24.dp, 24.dp, 100f, 100f).apply {
        val nodes = PathParser().parsePathString(LEAF).toNodes()
        group(translationX = 50f, translationY = 57f) {
            addPath(nodes, fill = SolidColor(c1))
            group(rotate = 240f) { addPath(nodes, fill = SolidColor(c2)) }
            group(rotate = 120f) { addPath(nodes, fill = SolidColor(c3)) }
        }
    }.build()

/** The three-leaf Trefoil mark. With [color] it is single-colour; otherwise the brand greens. */
@Composable
fun TrefoilMark(modifier: Modifier = Modifier.size(24.dp), color: Color? = null) {
    val vector = remember(color) {
        if (color != null) markVector(color, color, color) else markVector(Color(0xFF8EDB9B), Color(0xFF5FBF74), Color(0xFFBCEFC3))
    }
    Image(vector, contentDescription = null, modifier = modifier)
}

/* ---------- Project tiles ---------- */

private val tileDark = listOf(
    0xFF3A2D10 to 0xFFF3C35E, 0xFF3A1B2B to 0xFFF49CC6, 0xFF0F332E to 0xFF6ED8C7, 0xFF17264B to 0xFF94B6FF, 0xFF2A2342 to 0xFFBBA8FF,
    0xFF262B2B to 0xFFC9D0CE, 0xFF14311C to 0xFF8EDB9B, 0xFF33240F to 0xFFEDB57E, 0xFF1B3222 to 0xFFA6E4B0,
)
private val tileLight = listOf(
    0xFFFBEDCB to 0xFF7A5200, 0xFFFADFEC to 0xFF992A60, 0xFFD6F2EE to 0xFF0B6A5E, 0xFFDFE7FB to 0xFF2A4FA6, 0xFFEBE6FC to 0xFF5A3EB5,
    0xFFE8ECEA to 0xFF3E4745, 0xFFDCF0E0 to 0xFF1D6A33, 0xFFF6E6D3 to 0xFF7E4F1C, 0xFFDCF0E0 to 0xFF1D6A33,
)

/** Two-letter project initials: first letters of the first and last word, or the first two characters. */
internal fun projectInitials(title: String): String {
    val words = title.split(Regex("[\\s_\\-./]+")).filter { it.isNotBlank() }
    val raw = when {
        words.size >= 2 -> "${words.first().first()}${words.last().first()}"
        words.size == 1 -> words.first().filter { it.isLetterOrDigit() }.take(2)
        else -> "?"
    }
    return raw.ifBlank { "?" }.uppercase(Locale.ROOT)
}

/** Stable palette slot for a project key (id or title). */
internal fun projectPaletteIndex(key: String): Int = abs(key.hashCode() % tileDark.size)

/**
 * Rounded-square tile with project initials. 40dp in lists, 20–24dp inline. [capFontScale] stops the monogram growing past 14dp of text
 * (a fixed tile cannot widen with the font, so "WE" would clip to "W"); it changes nothing up to a font scale of about 1.08.
 */
@Composable
fun ProjectTile(title: String, modifier: Modifier = Modifier, size: Dp = 40.dp, key: String = title, capFontScale: Boolean = false) {
    val (bg, fg) = (if (Tf.colors.isDark) tileDark else tileLight)[projectPaletteIndex(key)]
    val radius = when { size >= 36.dp -> 12.dp; size >= 24.dp -> 7.dp; else -> 6.dp }
    val base = when { size >= 36.dp -> 13.sp; size >= 24.dp -> 10.sp; else -> 9.sp }
    val fontSize = if (capFontScale) with(LocalDensity.current) { minOf(base.toDp(), 14.dp).toSp() } else base
    Box(modifier.size(size).background(Color(bg), RoundedCornerShape(radius)), contentAlignment = Alignment.Center) {
        Text(projectInitials(title), color = Color(fg), fontSize = fontSize, fontWeight = FontWeight.Bold, fontFamily = InterFamily, letterSpacing = 0.02.sp, maxLines = 1, softWrap = !capFontScale)
    }
}

/* ---------- Status ---------- */

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).background(color, CircleShape))
}

/** The design's working spinner: a 216° arc rotating once per 1.1s, blue by default. */
@Composable
fun WorkingSpinner(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Tf.colors.blue, animated: Boolean = true) {
    val angle = if (animated) {
        val transition = rememberInfiniteTransition(label = "spinner")
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "angle").value
    } else -54f
    Canvas(modifier.size(size).rotate(angle)) {
        val stroke = this.size.minDimension * 2.6f / 24f
        val inset = this.size.minDimension * 4f / 24f
        drawArc(color, startAngle = 0f, sweepAngle = 216f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(this.size.width - 2 * inset, this.size.height - 2 * inset),
            style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/* ---------- Chips, pills, labels ---------- */

/** Filter / quick-reply chip: 36dp, outlined; selected = inverted (text colour fill). [dense] tightens the inner padding to 10dp and the gaps to 5dp. */
@Composable
fun TfChip(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, count: Int? = null,
    enabled: Boolean = true, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null, dense: Boolean = false,
) {
    val c = Tf.colors
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(18.dp),
        color = if (selected) c.text else Color.Transparent, contentColor = if (selected) c.bg else c.text,
        border = if (selected) null else BorderStroke(1.dp, c.line),
        modifier = modifier.focusRing(RoundedCornerShape(18.dp)).handPointer(enabled).semantics { this.selected = selected; role = Role.Tab }) {
        Row(Modifier.height(36.dp).padding(horizontal = if (dense) 10.dp else 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (dense) 5.dp else 8.dp)) {
            leading?.invoke()
            Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
            if (count != null) Text(count.toString(), fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                color = LocalContentColor.current.copy(alpha = .72f))
            trailing?.invoke()
        }
    }
}

/** Composer pill (model, access, branch): 32dp, filled s2. */
@Composable
fun TfPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, mono: Boolean = false, contentDescription: String? = null) {
    val c = Tf.colors
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(16.dp), color = c.s2, contentColor = c.text,
        modifier = modifier.then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)) {
        Row(Modifier.height(32.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) Icon(icon, null, Modifier.size(14.dp))
            Text(label, style = if (mono) TrefoilType.mono else TrefoilType.meta.copy(color = c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Icon-only [TfPill]; [contentDescription] names the current value. */
@Composable
fun TfIconPill(icon: ImageVector, onClick: () -> Unit, contentDescription: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Tf.colors
    com.tajchert.trefoil.ui.adaptive.TfTooltip(contentDescription) {
        Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(16.dp), color = c.s2, contentColor = c.text,
            modifier = modifier.focusRing(RoundedCornerShape(16.dp)).handPointer(enabled).semantics { this.contentDescription = contentDescription }) {
            Box(Modifier.size(40.dp, 32.dp), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(16.dp)) }
        }
    }
}

/** Uppercase section label (NEEDS YOU, WORKING, SAVED…) with an optional leading dot. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, dot: Color? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (dot != null) StatusDot(dot)
        Text(text.uppercase(Locale.ROOT), style = TrefoilType.section, color = Tf.colors.muted)
    }
}

/** Branch label: branch icon + mono name, muted. */
@Composable
fun BranchLabel(branch: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(TfIcons.Branch, null, Modifier.size(14.dp), tint = Tf.colors.muted)
        Text(branch, style = TrefoilType.mono, color = Tf.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
