package com.wadio.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
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

    BackHandler(enabled = state.showPinPrompt || state.viewerMediaId != null || state.showSettings || state.showMediaChooser || (state.unlocked && state.tab != ActiveTab.Audio)) {
        controller.goBack()
    }

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
                state.unlocked && state.viewerMediaId != null -> ImageViewerScreen(controller)
                state.unlocked && state.showSettings -> SettingsScreen(controller)
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
        Box(Modifier.fillMaxSize()) {
            IconButton(
                onClick = { controller.openSettings() },
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
            ) {
                Text("⚙", style = MaterialTheme.typography.titleLarge)
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 56.dp),
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
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(controller: WadioController) {
    val state by controller.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { controller.goBack() }) {
                        Text("←", style = MaterialTheme.typography.headlineSmall)
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Privacy and behavior", style = MaterialTheme.typography.titleMedium)
            Text("Automatic vault lock", style = MaterialTheme.typography.titleSmall)
            Text("Lock the vault after inactivity.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(15 to "15 seconds", 30 to "30 seconds", 60 to "1 minute", 120 to "2 minutes", 300 to "5 minutes").forEach { (seconds, label) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, modifier = Modifier.weight(1f))
                    RadioButton(selected = state.autoLockSeconds == seconds, onClick = { controller.setAutoLockSeconds(seconds) })
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Confirm permanent deletion", style = MaterialTheme.typography.titleSmall)
                    Text("Ask before deleting selected media.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.confirmDeletes, onCheckedChange = controller::setConfirmDeletes)
            }
        }
    }
}
