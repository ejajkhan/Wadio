package com.wadio.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
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

@Composable
fun ImageViewerScreen(controller: WadioController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val record = state.cachedMedia.firstOrNull { it.id == state.viewerMediaId } ?: run {
        controller.closeViewer(); return
    }
    val baseDir = File(context.filesDir, "vault")
    val index = state.cachedMedia.indexOfFirst { it.id == record.id }
    val canPrev = index > 0
    val canNext = index >= 0 && index + 1 < state.cachedMedia.size

    var rotation by remember(record.id) { mutableStateOf(0f) }
    var zoom by remember(record.id) { mutableStateOf(1f) }
    var panX by remember(record.id) { mutableStateOf(0f) }
    var panY by remember(record.id) { mutableStateOf(0f) }
    var fit by remember(record.id) { mutableStateOf(true) }
    val isGif = record.relativePath.lowercase().endsWith(".gif")

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { controller.closeViewer() }) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            TextButton(onClick = { controller.viewerPrevNext(-1) }, enabled = canPrev) { Text("‹ Prev", color = Color.White) }
            TextButton(onClick = { controller.viewerPrevNext(1) }, enabled = canNext) { Text("Next ›", color = Color.White) }
            Spacer(Modifier.weight(1f))
            if (!isGif) {
                TextButton(onClick = { rotation = (rotation - 90f) % 360f }) { Text("⟲", color = Color.White) }
                TextButton(onClick = { rotation = (rotation + 90f) % 360f }) { Text("⟳", color = Color.White) }
            }
            TextButton(onClick = { zoom = 1f; panX = 0f; panY = 0f; fit = true }) { Text("Fit", color = Color.White) }
            TextButton(onClick = { zoom = 1f; panX = 0f; panY = 0f; fit = false }) { Text("100%", color = Color.White) }
        }

        // Media
        Box(Modifier.fillMaxSize().padding(top = 48.dp, bottom = 32.dp)) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(File(baseDir, record.relativePath))
                    .size(2048, 2048)
                    .build(),
                contentDescription = record.fileName,
                contentScale = if (fit) ContentScale.Fit else ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = panX, translationY = panY, rotationZ = rotation)
                    .pointerInput(record.id) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            zoom = (zoom * gestureZoom).coerceIn(0.1f, 5f)
                            panX += pan.x
                            panY += pan.y
                            fit = false
                        }
                    },
            )
        }

        // Info
        Column(
            Modifier.align(Alignment.BottomStart).padding(12.dp)
        ) {
            Text(record.fileName, color = Color.White, style = MaterialTheme.typography.bodySmall)
            val dims = if (record.width != null && record.height != null) "${record.width} × ${record.height}" else ""
            val mb = record.byteSize.toDouble() / 1_048_576.0
            Text("$dims • ${"%.2f".format(mb)} MB", color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
    }
}
