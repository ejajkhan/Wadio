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
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.wadio.app.WadioController
import com.wadio.app.util.ImageEnhancer
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
    var showSideBySide by remember(record.id) { mutableStateOf(false) }
    var compareSplit by remember(record.id) { mutableFloatStateOf(0.5f) }
    val slideOffset = remember { Animatable(0f) }
    val imageAlpha = remember { Animatable(1f) }
    val imageScale = remember { Animatable(1f) }
    val gestureOffsetX = remember { Animatable(0f) }
    var gestureDragX by remember(record.id) { mutableFloatStateOf(0f) }
    val pointerScope = rememberCoroutineScope()
    var swipeWidthPx by remember { mutableFloatStateOf(0f) }
    var pendingSlideDirection by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    // Keep only the immediate neighbors warm in Coil's bounded memory cache.
    // Requests use the same stable keys and decode size as the visible image,
    // so the viewer can reuse the decoded result after a swipe.
    LaunchedEffect(record.id, state.cachedMedia, baseDir) {
        val media = state.cachedMedia.filter { it.mediaType == "Image" }
        val currentIndex = media.indexOfFirst { it.id == record.id }
        if (currentIndex < 0 || media.size < 2) return@LaunchedEffect

        val previous = media[(currentIndex - 1 + media.size) % media.size]
        val next = media[(currentIndex + 1) % media.size]
        val neighbors = listOf(next, previous).distinctBy { it.id }
        val imageLoader = context.imageLoader
        val requests = neighbors.map { neighbor ->
            imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(File(baseDir, neighbor.relativePath))
                    .memoryCacheKey(neighbor.sha256)
                    .diskCacheKey(neighbor.sha256)
                    .size(2048, 2048)
                    .build(),
            )
        }
        try {
            awaitCancellation()
        } finally {
            requests.forEach { it.dispose() }
        }
    }

    LaunchedEffect(record.id, state.viewerAnimation) {
        gestureDragX = 0f
        when (state.viewerAnimation) {
            "horizontal_slide" -> {
                if (pendingSlideDirection != 0) {
                    slideOffset.snapTo(pendingSlideDirection * (swipeWidthPx.takeIf { it > 0f } ?: with(density) { 300.dp.toPx() }))
                    slideOffset.animateTo(0f, animationSpec = tween(210))
                }
            }
            "gesture_following" -> {
                slideOffset.snapTo(0f)
                gestureOffsetX.animateTo(0f, animationSpec = tween(180))
            }
            "crossfade" -> {
                imageAlpha.snapTo(0.12f)
                imageAlpha.animateTo(1f, animationSpec = tween(180))
            }
            "zoom_scale" -> {
                imageScale.snapTo(0.86f)
                imageAlpha.snapTo(0.75f)
                kotlinx.coroutines.coroutineScope {
                    launch { imageScale.animateTo(1f, animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f)) }
                    launch { imageAlpha.animateTo(1f, animationSpec = tween(180)) }
                }
            }
        }
        if (state.viewerAnimation != "gesture_following") gestureOffsetX.snapTo(0f)
        pendingSlideDirection = 0
    }
    val isGif = record.relativePath.lowercase().endsWith(".gif")
    val canEnhance = record.mediaType == "Image" &&
        ImageEnhancer.isSupported(File(baseDir, record.relativePath)) && !isGif
    val hasEnhancementPreview = state.enhancementPreviewMediaId == record.id &&
        !state.enhancementPreviewPath.isNullOrBlank() && File(state.enhancementPreviewPath!!).isFile
    val displayedImage = if (hasEnhancementPreview) File(state.enhancementPreviewPath!!) else File(baseDir, record.relativePath)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(displayedImage)
                .memoryCacheKey(if (hasEnhancementPreview) "${record.sha256}-preview-${displayedImage.absolutePath}-${displayedImage.lastModified()}" else record.sha256)
                .diskCacheKey(if (hasEnhancementPreview) "${record.sha256}-preview-${displayedImage.absolutePath}-${displayedImage.lastModified()}" else record.sha256)
                .size(2048, 2048).crossfade(500).build(),
            contentDescription = record.fileName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer(
                scaleX = zoom * imageScale.value, scaleY = zoom * imageScale.value,
                alpha = imageAlpha.value,
                translationX = panX + slideOffset.value + if (state.viewerAnimation == "gesture_following") gestureDragX + gestureOffsetX.value else 0f,
                translationY = panY,
            ).pointerInput(record.id) {
                awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        swipeWidthPx = size.width.toFloat()
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
                                } else if (state.viewerAnimation == "gesture_following" && abs(totalY) < 40.dp.toPx()) {
                                    gestureDragX = (gestureDragX + dx).coerceIn(-size.width.toFloat(), size.width.toFloat())
                                    change.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        if (!hadMultiTouch && zoom <= 1.01f && abs(totalX) > 80.dp.toPx() && abs(totalX) > abs(totalY) * 1.25f) {
                            val direction = if (totalX < 0f) 1 else -1
                            pendingSlideDirection = direction
                            gestureDragX = 0f
                            controller.viewerPrevNext(direction)
                        } else if (state.viewerAnimation == "gesture_following" && gestureDragX != 0f) {
                            // Animatable's suspend APIs must run in a regular coroutine,
                            // not inside awaitEachGesture's restricted pointer scope.
                            pointerScope.launch {
                                gestureOffsetX.snapTo(gestureDragX)
                                gestureDragX = 0f
                                gestureOffsetX.animateTo(0f, animationSpec = tween(150))
                            }
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
                    if (hasEnhancementPreview) {
                        DropdownMenuItem(
                            text = { Text("See side by side") },
                            onClick = { menuExpanded = false; showSideBySide = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Save the enhancement") },
                            enabled = !state.enhancementInProgress,
                            onClick = { menuExpanded = false; controller.saveEnhancementPreview(record) },
                        )
                        DropdownMenuItem(
                            text = { Text("Discard enhancement preview") },
                            enabled = !state.enhancementInProgress,
                            onClick = { menuExpanded = false; controller.discardEnhancementPreview(record) },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Enhance photo") },
                            enabled = canEnhance && !state.enhancementInProgress,
                            onClick = { menuExpanded = false; controller.createEnhancementPreview(record) },
                        )
                    }
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

        if (showSideBySide && hasEnhancementPreview) {
            Dialog(onDismissRequest = { showSideBySide = false }) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = Color(0xFF171717),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Original vs enhanced", color = Color.White, style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f))
                            IconButton(onClick = { showSideBySide = false }) {
                                Icon(Icons.Rounded.ArrowBack, contentDescription = "Close comparison", tint = Color.White)
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().height(320.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Column(Modifier.weight(compareSplit.coerceIn(0.25f, 0.75f))) {
                                Text("Original", color = Color.White, style = MaterialTheme.typography.labelMedium)
                                AsyncImage(
                                    model = ImageRequest.Builder(context).data(File(baseDir, record.relativePath))
                                        .memoryCacheKey("compare-original-${record.sha256}")
                                        .size(1024, 1024).crossfade(false).build(),
                                    contentDescription = "Original photo",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                )
                            }
                            Column(Modifier.weight((1f - compareSplit).coerceIn(0.25f, 0.75f))) {
                                Text("Enhanced", color = Color.White, style = MaterialTheme.typography.labelMedium)
                                AsyncImage(
                                    model = ImageRequest.Builder(context).data(File(state.enhancementPreviewPath!!))
                                        .memoryCacheKey("compare-enhanced-${File(state.enhancementPreviewPath!!).lastModified()}")
                                        .size(1024, 1024).crossfade(false).build(),
                                    contentDescription = "Enhanced photo",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                )
                            }
                        }
                        Text("Slide to adjust the space given to each image", color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodySmall)
                        Slider(value = compareSplit, onValueChange = { compareSplit = it }, valueRange = 0.25f..0.75f)
                        TextButton(onClick = { showSideBySide = false }, modifier = Modifier.align(Alignment.End)) { Text("Done") }
                    }
                }
            }
        }

        if (state.enhancementInProgress) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    CircularProgressIndicator(color = Color.White)
                    Text("Enhancing photo…", color = Color.White)
                    Text("Light noise reduction and sharpening", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (hasEnhancementPreview && !state.enhancementInProgress) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                color = Color.Black.copy(alpha = 0.72f),
                contentColor = Color.White,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text("Enhanced preview · Original unchanged", modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    state.enhancementMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { controller.dismissEnhancementMessage() },
            title = { Text(if (message.startsWith("Enhancement saved") || message.startsWith("Photo enhanced")) "Enhancement complete" else "Enhancement not completed") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { controller.dismissEnhancementMessage() }) { Text("OK") }
            },
        )
    }
}
