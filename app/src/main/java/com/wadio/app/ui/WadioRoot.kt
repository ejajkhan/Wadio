package com.wadio.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wadio.app.ActiveTab
import com.wadio.app.WadioController
import com.wadio.app.util.findActivity
import kotlinx.coroutines.delay

@Composable
fun WadioRoot(
    controller: WadioController,
    onPickFiles: () -> Unit,
    onBiometricRequest: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    // Periodic tick (idle timer, audio poll) — 100ms cadence matches the Rust loop.
    LaunchedEffect(controller) {
        while (true) {
            controller.tick()
            // Keep FLAG_SECURE synced with state.
            val secure = state.showPinPrompt || state.unlocked || state.viewerMediaId != null
            activity?.let { com.wadio.app.security.ScreenGuard.setSecure(it, secure) }
            delay(100)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            if (state.viewerMediaId != null) {
                ImageViewerScreen(controller = controller)
            } else {
                HomeScreen(controller = controller, onPickFiles = onPickFiles)
                if (state.showPinPrompt) {
                    AuthSheet(controller = controller, onBiometric = onBiometricRequest)
                }
                if (state.tab != ActiveTab.Audio && state.unlocked) {
                    VaultScreen(controller = controller, onPickFiles = onPickFiles)
                }
            }
        }
    }
}
