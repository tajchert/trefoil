package com.tajchert.trefoil.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tajchert.trefoil.core.model.RemoteFileReference
import com.tajchert.trefoil.downloads.DownloadKey
import com.tajchert.trefoil.downloads.DownloadStatus
import com.tajchert.trefoil.downloads.isInlineImage

private sealed interface ImageLoad {
    data object Loading : ImageLoad
    data object Failed : ImageLoad
    data class Ready(val bitmap: Bitmap) : ImageLoad
}

/** Shows image references inline when a loader is available; every other case is the plain download card. */
@Composable
internal fun InlineOrCard(reference: RemoteFileReference, modifier: Modifier = Modifier) {
    val ui = LocalFileDownloadUi.current
    val loader = ui.imageLoader
    val environment = ui.environmentId
    val thread = ui.threadId
    if (loader == null || environment == null || thread == null || !reference.isInlineImage()) {
        DownloadFileCard(reference, modifier); return
    }
    var load by remember(reference.identity, environment, thread) {
        mutableStateOf<ImageLoad>(loader.cached(reference, environment, thread)?.let { ImageLoad.Ready(it) } ?: ImageLoad.Loading)
    }
    // Fetches only while connected; leaving the thread disposes the effect, which cancels the transfer.
    LaunchedEffect(reference.identity, environment, thread, ui.canDownload) {
        if (load is ImageLoad.Ready || !ui.canDownload) return@LaunchedEffect
        val bitmap = loader.load(reference, environment, thread)
        load = if (bitmap != null) ImageLoad.Ready(bitmap) else ImageLoad.Failed
    }
    when (val current = load) {
        ImageLoad.Failed -> DownloadFileCard(reference, modifier)
        ImageLoad.Loading -> Box(
            modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(14.dp)).background(Tf.colors.s1).border(1.dp, Tf.colors.line, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) { if (ui.canDownload) CircularProgressIndicator(Modifier.size(22.dp), color = Tf.colors.muted, strokeWidth = 2.dp) }
        is ImageLoad.Ready -> LoadedImage(reference, current.bitmap, modifier)
    }
}

@Composable
private fun LoadedImage(reference: RemoteFileReference, bitmap: Bitmap, modifier: Modifier) {
    val ui = LocalFileDownloadUi.current
    val shape = RoundedCornerShape(14.dp)
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val ratio = (bitmap.width.toFloat() / bitmap.height).coerceIn(0.5f, 3f)
    var fullScreen by remember { mutableStateOf(false) }
    val key = ui.environmentId?.let { e -> ui.threadId?.let { DownloadKey(e, it, reference.identity) } }
    val state = ui.downloads[key]
    val saved = state?.status == DownloadStatus.Complete
    val saving = state?.status == DownloadStatus.Downloading
    Box(modifier.fillMaxWidth().aspectRatio(ratio).clip(shape).background(Tf.colors.s1).border(1.dp, Tf.colors.line, shape)
        .clickable(onClickLabel = "Open image full screen") { fullScreen = true }) {
        Image(image, reference.fileName, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = .55f)), contentAlignment = Alignment.Center) {
            when {
                saving -> CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                saved -> Icon(Icons.Default.Check, "Saved ${reference.fileName} to Downloads", Modifier.size(18.dp), tint = Color.White)
                else -> IconButton(
                    onClick = { ui.environmentId?.let { e -> ui.threadId?.let { t -> ui.onDownload(e, t, reference) } } },
                    enabled = ui.canDownload && key != null,
                ) { Icon(Icons.Default.Download, "Download ${reference.fileName}", Modifier.size(18.dp), tint = Color.White) }
            }
        }
    }
    if (fullScreen) FullScreenImage(reference.fileName, image) { fullScreen = false }
}

@Composable
private fun FullScreenImage(name: String, image: androidx.compose.ui.graphics.ImageBitmap, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(image, name, Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        if (scale == 1f) { offsetX = 0f; offsetY = 0f } else { offsetX += pan.x; offsetY += pan.y }
                    }
                }
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
                contentScale = ContentScale.Fit)
            IconButton(onClose, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Default.Close, "Close image", tint = Color.White)
            }
        }
    }
}
