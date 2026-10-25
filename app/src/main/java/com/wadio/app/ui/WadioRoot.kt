package com.wadio.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

    LaunchedEffect(controller) {
        while (true) {
            controller.tick()
            val secure = controller.state.value.showPinPrompt || controller.state.value.unlocked ||
                controller.state.value.viewerMediaId != null
            activity?.let { com.wadio.app.security.ScreenGuard.setSecure(it, secure) }
            delay(100)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            when {
                state.showPinPrompt -> AuthSheet(controller = controller, onBiometric = onBiometricRequest)
                state.unlocked && state.showMediaChooser -> MediaChooserScreen(controller)
                state.unlocked && state.tab != ActiveTab.Audio -> VaultScreen(controller = controller, onPickFiles = onPickFiles)
                else -> HomeScreen(controller = controller, onPickFiles = onPickFiles)
            }
        }
    }
}

@Composable
private fun MediaChooserScreen(controller: WadioController) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Wadio Vault", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            Text("Choose what you want to open", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
            Button(onClick = { controller.setTab(ActiveTab.Pictures) }, modifier = Modifier.fillMaxWidth()) {
                Text("Photos", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { controller.setTab(ActiveTab.Videos) }, modifier = Modifier.fillMaxWidth()) {
                Text("Videos", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(24.dp))
            androidx.compose.material3.OutlinedButton(onClick = { controller.lockVault() }) { Text("Lock vault") }
        }
    }
}
