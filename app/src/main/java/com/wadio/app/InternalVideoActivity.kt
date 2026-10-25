package com.wadio.app

import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

/**
 * Separate task, excluded from Recents, FLAG_SECURE. Mirrors
 * InternalVideoActivity.kt from the original project.
 */
class InternalVideoActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val relPath = intent.getStringExtra(EXTRA_REL_PATH) ?: run { finish(); return }
        val root = File(filesDir, "vault").canonicalFile
        val video = File(filesDir, "vault/$relPath").canonicalFile
        if (!video.path.startsWith(root.path + File.separator) || !video.isFile) {
            finish(); return
        }

        val exo = ExoPlayer.Builder(this).build().also { player = it }
        exo.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(video)))
        exo.prepare()
        exo.playWhenReady = true

        setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = true
                            controllerAutoShow = true
                            this.player = exo
                            playerView = this
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
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
    }
}
