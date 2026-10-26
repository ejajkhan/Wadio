package com.wadio.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.geometry.Offset
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
    val isGif = record.relativePath.lowercase().endsWith(".gif")

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(File(baseDir, record.relativePath))
                .memoryCacheKey(record.sha256).diskCacheKey(record.sha256).size(4096, 4096).build(),
            contentDescription = record.fileName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer(
                scaleX = zoom, scaleY = zoom, translationX = panX, translationY = panY,
            ).pointerInput(record.id) {
                awaitEachGesture {
                    var swipeX = 0f
                    var swipeY = 0f
                    var hadMultiTouch = false
                    var keepGoing = true
                    while (keepGoing) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            hadMultiTouch = true
                            val first = pressed[0].position
                            val second = pressed[1].position
                            val prevFirst = first - pressed[0].positionChange()
                            val prevSecond = second - pressed[1].positionChange()
                            val oldDistance = (prevFirst - prevSecond).getDistance().coerceAtLeast(1f)
                            val newDistance = (first - second).getDistance().coerceAtLeast(1f)
                            zoom = (zoom * (newDistance / oldDistance)).coerceIn(1f, 5f)
                            if (zoom > 1f) {
                                val pan = (pressed[0].positionChange() + pressed[1].positionChange()) / 2f
                                panX += pan.x
                                panY += pan.y
                            } else {
                                panX = 0f
                                panY = 0f
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (pressed.size == 1) {
                            val change = pressed.first()
                            val delta = change.positionChange()
                            if (zoom > 1.05f) {
                                panX += delta.x
                                panY += delta.y
                            } else if (!hadMultiTouch) {
                                swipeX += delta.x
                                swipeY += delta.y
                            }
                            if (delta != Offset.Zero) change.consume()
                        }
                        keepGoing = event.changes.any { it.pressed }
                    }
                    if (!hadMultiTouch && zoom <= 1.05f && abs(swipeX) > 70f && abs(swipeX) > abs(swipeY) * 1.2f) {
                        controller.viewerPrevNext(if (swipeX < 0f) -1 else 1)
                    }
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

    }
}
