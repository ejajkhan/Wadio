package com.wadio.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wadio.app.WadioController
import java.io.File
import kotlin.math.abs

@Composable
fun ImageViewerScreen(controller: WadioController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val record = state.cachedMedia.firstOrNull { it.id == state.viewerMediaId } ?: run {
        controller.closeViewer(); return
    }
    val baseDir = File(context.filesDir, "vault")
    var zoom by remember(record.id) { mutableStateOf(1f) }
    var panX by remember(record.id) { mutableStateOf(0f) }
    var panY by remember(record.id) { mutableStateOf(0f) }
    var menuExpanded by remember(record.id) { mutableStateOf(false) }
    var swipeDistance by remember(record.id) { mutableFloatStateOf(0f) }
    val isGif = record.relativePath.lowercase().endsWith(".gif")

    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(record.id) {
            detectHorizontalDragGestures(
                onHorizontalDrag = { _, amount -> swipeDistance += amount },
                onDragEnd = {
                    if (abs(swipeDistance) > 80f) controller.viewerPrevNext(if (swipeDistance < 0) 1 else -1)
                    swipeDistance = 0f
                },
                onDragCancel = { swipeDistance = 0f },
            )
        }
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(File(baseDir, record.relativePath))
                .memoryCacheKey(record.sha256).diskCacheKey(record.sha256).size(4096, 4096).build(),
            contentDescription = record.fileName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer(
                scaleX = zoom, scaleY = zoom, translationX = panX, translationY = panY,
            ).pointerInput(record.id) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    zoom = (zoom * gestureZoom).coerceIn(1f, 5f)
                    panX = if (zoom > 1f) panX + pan.x else 0f
                    panY = if (zoom > 1f) panY + pan.y else 0f
                }
            },
        )

        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { controller.closeViewer() }) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Image options", tint = Color.White)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Rotate left 90°") }, enabled = !isGif, onClick = {
                        menuExpanded = false; controller.rotateImagePermanently(record, -90)
                    })
                    DropdownMenuItem(text = { Text("Rotate right 90°") }, enabled = !isGif, onClick = {
                        menuExpanded = false; controller.rotateImagePermanently(record, 90)
                    })
                    DropdownMenuItem(text = { Text("Reset zoom") }, onClick = {
                        zoom = 1f; panX = 0f; panY = 0f; menuExpanded = false
                    })
                }
            }
        }

        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(record.fileName, color = Color.White, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            val dims = if (record.width != null && record.height != null) "${record.width} × ${record.height}" else ""
            val mb = record.byteSize.toDouble() / 1_048_576.0
            Text("$dims • ${"%.2f".format(mb)} MB", color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
    }
}
