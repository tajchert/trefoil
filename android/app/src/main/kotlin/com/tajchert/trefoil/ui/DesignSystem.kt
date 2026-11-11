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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs

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

/** Icons from the Trefoil design canvas. Use with [Icon]; they tint with the content color. */
object TfIcons {
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
    val Stop by lazy { strokeIcon("stop", filled = listOf(rect(5f, 5f, 14f, 14f, 3f))) }
    val Close by lazy { strokeIcon("close", "M6 6l12 12M18 6L6 18") }
    val Check by lazy { strokeIcon("check", "M5 12l5 5 9-10") }
    /** Access / runtime mode. */
    val Shield by lazy { strokeIcon("shield", "M12 3L5 6v5c0 4.5 3 8.5 7 10 4-1.5 7-5.5 7-10V6z") }
    /** Model picker. */
    val Sparkle by lazy { strokeIcon("sparkle", "M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z") }
    val File by lazy { strokeIcon("file", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5") }
    /** File with plus: changes / diff. */
    val Changes by lazy { strokeIcon("changes", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5M12 11v6M9 14h6") }
    val Edit by lazy { strokeIcon("edit", "M4 20h4L19 9l-4-4L4 16z") }
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
    val Trash by lazy { strokeIcon("trash", "M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3") }
    val Disconnect by lazy { strokeIcon("disconnect", "M9 15l-2 2a3 3 0 0 1-4.2-4.2l2-2M15 9l2-2a3 3 0 0 1 4.2 4.2l-2 2M4 4l16 16") }
    val Refresh by lazy { strokeIcon("refresh", "M20 11a8 8 0 1 0-2.3 5.7M20 5v6h-6") }
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

/** Rounded-square tile with project initials. 40dp in lists, 20–24dp inline. */
@Composable
fun ProjectTile(title: String, modifier: Modifier = Modifier, size: Dp = 40.dp, key: String = title) {
    val (bg, fg) = (if (Tf.colors.isDark) tileDark else tileLight)[projectPaletteIndex(key)]
    val radius = when { size >= 36.dp -> 12.dp; size >= 24.dp -> 7.dp; else -> 6.dp }
    val fontSize = when { size >= 36.dp -> 13.sp; size >= 24.dp -> 10.sp; else -> 9.sp }
    Box(modifier.size(size).background(Color(bg), RoundedCornerShape(radius)), contentAlignment = Alignment.Center) {
        Text(projectInitials(title), color = Color(fg), fontSize = fontSize, fontWeight = FontWeight.Bold, fontFamily = InterFamily, letterSpacing = 0.02.sp, maxLines = 1)
    }
}

/* ---------- Status ---------- */

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).background(color, CircleShape))
}

/** The design's working spinner: a 216° arc rotating once per 1.1s, blue by default. */
@Composable
fun WorkingSpinner(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Tf.colors.blue) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "angle")
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

/** Filter / quick-reply chip: 36dp, outlined; selected = inverted (text colour fill). */
@Composable
fun TfChip(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, count: Int? = null,
    enabled: Boolean = true, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null,
) {
    val c = Tf.colors
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(18.dp),
        color = if (selected) c.text else Color.Transparent, contentColor = if (selected) c.bg else c.text,
        border = if (selected) null else BorderStroke(1.dp, c.line),
        modifier = modifier.semantics { this.selected = selected; role = Role.Tab }) {
        Row(Modifier.height(36.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
