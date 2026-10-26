package com.wadio.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.SurfaceView
import android.graphics.Bitmap
import android.os.Build
import android.view.PixelCopy
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import com.wadio.app.data.MediaRecord
import com.wadio.app.data.WadioDatabase
import java.security.MessageDigest
import java.io.FileOutputStream
import java.util.UUID
import java.io.File
import java.util.Locale

@androidx.annotation.OptIn(UnstableApi::class)
class InternalVideoActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val requested = intent.getStringArrayListExtra(EXTRA_PATHS)
            ?: arrayListOf(intent.getStringExtra(EXTRA_REL_PATH) ?: "")
        val paths = requested.filter { rel ->
            val root = File(filesDir, "vault").canonicalFile
            val file = File(filesDir, "vault/$rel").canonicalFile
            file.path.startsWith(root.path + File.separator) && file.isFile
        }
        if (paths.isEmpty()) { finish(); return }
        val initialIndex = intent.getIntExtra(EXTRA_INITIAL_INDEX, 0).coerceIn(0, paths.lastIndex)
        val exo = ExoPlayer.Builder(this).build().also { player = it }

        setContent {
            var index by remember { mutableIntStateOf(initialIndex) }
            var playing by remember { mutableStateOf(true) }
            var muted by remember { mutableStateOf(false) }
            var duration by remember { mutableLongStateOf(0L) }
            var position by remember { mutableLongStateOf(0L) }
            var seekPosition by remember { mutableFloatStateOf(0f) }
            var seeking by remember { mutableStateOf(false) }

            LaunchedEffect(index) {
                val file = File(filesDir, "vault/${paths[index]}").canonicalFile
                exo.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
                exo.prepare()
                exo.playWhenReady = true
            }
            LaunchedEffect(exo) {
                while (true) {
                    duration = exo.duration.takeIf { it > 0L } ?: 0L
                    position = exo.currentPosition.coerceAtLeast(0L)
                    playing = exo.isPlaying
                    if (!seeking) seekPosition = position.toFloat()
                    delay(200)
                }
            }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            this.player = exo
                            playerView = this
                        }
                    },
                    update = { it.player = exo; it.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT },
                    modifier = Modifier.fillMaxSize(),
                )
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.72f)).padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatTime(if (seeking) seekPosition.toLong() else position), color = Color.White)
                        Slider(
                            value = seekPosition.coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
                            onValueChange = { seeking = true; seekPosition = it },
                            onValueChangeFinished = { exo.seekTo(seekPosition.toLong()); seeking = false },
                            valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                            modifier = Modifier.weight(1f).height(34.dp),
                        )
                        Text(formatTime(duration), color = Color.White)
                    }
                    Row(
                        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { if (index > 0) index-- }, enabled = index > 0) {
                            Icon(Icons.Rounded.SkipPrevious, "Previous video", tint = if (index > 0) Color.White else Color.Gray)
                        }
                        IconButton(onClick = { if (exo.isPlaying) exo.pause() else exo.play(); playing = exo.isPlaying }) {
                            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                if (playing) "Pause" else "Play", tint = Color.White)
                        }
                        IconButton(onClick = { if (index < paths.lastIndex) index++ }, enabled = index < paths.lastIndex) {
                            Icon(Icons.Rounded.SkipNext, "Next video", tint = if (index < paths.lastIndex) Color.White else Color.Gray)
                        }
                        Spacer(Modifier.width(18.dp))
                        IconButton(onClick = { captureCurrentFrame() }) {
                            Icon(Icons.Rounded.PhotoCamera, "Capture frame to Photos", tint = Color.White)
                        }
                        IconButton(onClick = { muted = !muted; exo.volume = if (muted) 0f else 1f }) {
                            Icon(if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                                if (muted) "Sound off" else "Sound on", tint = Color.White)
                        }
                    }
                }
            }
        }
    }

    private fun captureCurrentFrame() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Toast.makeText(this, "Frame capture requires Android 7 or later", Toast.LENGTH_SHORT).show()
            return
        }
        val surface = playerView?.videoSurfaceView as? SurfaceView
        if (surface == null || surface.width <= 0 || surface.height <= 0) {
            Toast.makeText(this, "Video frame is not ready yet", Toast.LENGTH_SHORT).show()
            return
        }
        val bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(surface, bitmap, { result ->
            if (result != PixelCopy.SUCCESS) {
                bitmap.recycle()
                Toast.makeText(this, "Could not capture this video frame", Toast.LENGTH_SHORT).show()
                return@request
            }
            Thread {
                var saved: File? = null
                try {
                    val root = File(filesDir, "vault")
                    val albumDir = File(root, "media/photos/default")
                    if (!albumDir.exists() && !albumDir.mkdirs()) throw java.io.IOException("Could not open Default album")
                    val name = "video_frame_${System.currentTimeMillis()}.jpg"
                    val outputFile = File(albumDir, name)
                    saved = outputFile
                    FileOutputStream(outputFile).use { out ->
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw java.io.IOException("JPEG encoding failed")
                        out.fd.sync()
                    }
                    bitmap.recycle()
                    val digest = MessageDigest.getInstance("SHA-256").digest(outputFile.readBytes()).joinToString("") { "%02x".format(it) }
                    val db = WadioDatabase.open(this, File(root, "wadio_vault.db"), root)
                    val rec = MediaRecord(
                        id = UUID.randomUUID().toString(), albumId = "default", mediaType = "Image",
                        fileName = name, byteSize = outputFile.length(), sha256 = digest,
                        width = surface.width, height = surface.height, durationMs = null,
                        importedAt = System.currentTimeMillis() / 1000L,
                        relativePath = "media/photos/default/$name",
                    )
                    val inserted = db.insertMedia(rec) == WadioDatabase.InsertResult.Ok
                    if (!inserted) throw java.io.IOException("Could not register the captured image")
                    runOnUiThread { Toast.makeText(this, "Frame saved to Photos / Default", Toast.LENGTH_SHORT).show() }
                } catch (t: Throwable) {
                    saved?.delete()
                    bitmap.recycle()
                    runOnUiThread { Toast.makeText(this, "Capture failed: ${t.message ?: "unknown error"}", Toast.LENGTH_LONG).show() }
                }
            }.start()
        }, Handler(Looper.getMainLooper()))
    }

    private fun formatTime(ms: Long): String {
        val seconds = (ms.coerceAtLeast(0L) / 1000L)
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        playerView?.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_REL_PATH = "com.wadio.app.extra.REL_PATH"
        const val EXTRA_PATHS = "com.wadio.app.extra.PATHS"
        const val EXTRA_INITIAL_INDEX = "com.wadio.app.extra.INITIAL_INDEX"
    }
}
