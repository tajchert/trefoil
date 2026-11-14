package com.tajchert.trefoil.widget.limits

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.WindowManager
import com.tajchert.trefoil.widget.UsageWidgetSnapshot
import kotlin.math.sqrt

internal data class LimitRingMasks(val track: Bitmap, val arcs: Map<Int, Bitmap>)

/** Conservative target: fixed 96px masks, also bounded by the smallest 48dp gauge and cap/count. */
internal fun createRingMasks(context: Context, snapshot: UsageWidgetSnapshot?): LimitRingMasks {
    val percents =
        snapshot
            ?.providers
            .orEmpty()
            .flatMap { it.limits?.windows.orEmpty() }
            .filter { it.usedPercent.isFinite() }
            .map { it.remainingPercent }
            .distinct()
    val count = percents.size + 1
    val perImageCap = calculateWidgetMemoryCapBytes(context) / count
    // ALPHA_8 scanlines are aligned to four bytes. Leave half the estimated cap unused.
    val pixels =
        minOf(
                96,
                (48 * context.resources.displayMetrics.density).toInt(),
                sqrt(perImageCap / 2.0).toInt(),
            )
            .coerceAtLeast(4)
            .let { it - it % 4 }
    return LimitRingMasks(
        drawRingMask(pixels, 100),
        percents.associateWith { drawRingMask(pixels, it) },
    )
}

internal fun calculateWidgetMemoryCapBytes(context: Context): Long {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds =
            context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds
        if (bounds != null && bounds.width() > 0 && bounds.height() > 0)
            return 6L * bounds.width() * bounds.height()
    }
    val metrics = context.resources.displayMetrics
    return 6L * metrics.widthPixels.coerceAtLeast(1) * metrics.heightPixels.coerceAtLeast(1)
}

private fun drawRingMask(pixels: Int, percent: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ALPHA_8)
    val stroke = pixels * 0.085f
    val inset = stroke / 2 + 1
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
    if (percent > 0)
        Canvas(bitmap)
            .drawArc(
                RectF(inset, inset, pixels - inset, pixels - inset),
                -90f,
                percent * 3.6f,
                false,
                paint,
            )
    return bitmap
}
