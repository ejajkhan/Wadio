package com.wadio.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.sqrt

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
    val slideOffset = remember { Animatable(0f) }
    var pendingSlideDirection by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    LaunchedEffect(record.id) {
        if (pendingSlideDirection != 0) {
            slideOffset.snapTo(with(density) { (pendingSlideDirection * 48).dp.toPx() })
            slideOffset.animateTo(0f, animationSpec = tween(190))
            pendingSlideDirection = 0
        }
    }
    val isGif = record.relativePath.lowercase().endsWith(".gif")

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(File(baseDir, record.relativePath))
                .memoryCacheKey(record.sha256).diskCacheKey(record.sha256).size(4096, 4096)
                .crossfade(240).build(),
            contentDescription = record.fileName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer(
                scaleX = zoom, scaleY = zoom, translationX = panX + slideOffset.value, translationY = panY,
            ).pointerInput(record.id) {
                awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var totalX = 0f
                        var totalY = 0f
                        var hadMultiTouch = false
                        var event: PointerEvent
                        do {
                            event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2) {
                                hadMultiTouch = true
                                val active = event.changes.filter { it.pressed }
                                if (active.size >= 2) {
                                    val previousCenterX = active.map { it.previousPosition.x }.average().toFloat()
                                    val previousCenterY = active.map { it.previousPosition.y }.average().toFloat()
                                    val currentCenterX = active.map { it.position.x }.average().toFloat()
                                    val currentCenterY = active.map { it.position.y }.average().toFloat()
                                    val panXDelta = currentCenterX - previousCenterX
                                    val panYDelta = currentCenterY - previousCenterY

                                    var previousDistance = 0f
                                    var currentDistance = 0f
                                    active.forEach { change ->
                                        val previousDx = change.previousPosition.x - previousCenterX
                                        val previousDy = change.previousPosition.y - previousCenterY
                                        val currentDx = change.position.x - currentCenterX
                                        val currentDy = change.position.y - currentCenterY
                                        previousDistance += sqrt(previousDx * previousDx + previousDy * previousDy)
                                        currentDistance += sqrt(currentDx * currentDx + currentDy * currentDy)
                                    }
                                    val gestureZoom = if (previousDistance > 0.001f) currentDistance / previousDistance else 1f
                                    zoom = (zoom * gestureZoom).coerceIn(1f, 5f)
                                    if (zoom > 1f) {
                                        panX += panXDelta
                                        panY += panYDelta
                                    } else {
                                        panX = 0f; panY = 0f
                                    }
                                }
                                event.changes.forEach { if (it.pressed) it.consume() }
                            } else if (pressed == 1) {
                                val change = event.changes.first()
                                val dx = change.position.x - change.previousPosition.x
                                val dy = change.position.y - change.previousPosition.y
                                totalX += dx; totalY += dy
                                if (zoom > 1f) {
                                    panX += dx; panY += dy
                                    change.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        if (!hadMultiTouch && zoom <= 1.01f && abs(totalX) > 80.dp.toPx() && abs(totalX) > abs(totalY) * 1.25f) {
                            val direction = if (totalX < 0f) 1 else -1
                            pendingSlideDirection = direction
                            controller.viewerPrevNext(direction)
                        }
                }
            },
        )

        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.35f))
                .statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
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
