package com.wadio.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
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
            val current = controller.state.value
            val secure = !current.allowScreenshots || current.showPinPrompt ||
                current.viewerMediaId != null
            activity?.let { com.wadio.app.security.ScreenGuard.setSecure(it, secure) }
            delay(100)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AnimatedContent(
            targetState = when {
                state.showPinPrompt -> "auth"
                state.unlocked && state.viewerMediaId != null -> "viewer"
                state.unlocked && state.showSettings -> "settings"
                state.unlocked && state.showMediaChooser -> "chooser"
                state.unlocked && state.tab != ActiveTab.Audio -> "vault-${state.tab}-${state.showAlbumList}"
                else -> "home"
            },
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(120)) },
            label = "wadio-screen-transition",
        ) { screen ->
            when {
                screen == "auth" -> AuthSheet(controller = controller, onBiometric = onBiometricRequest)
                screen == "viewer" -> ImageViewerScreen(controller)
                screen == "settings" -> SettingsScreen(controller)
                screen == "chooser" -> MediaChooserScreen(controller)
                screen.startsWith("vault-") -> VaultScreen(controller = controller, onPickFiles = onPickFiles)
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
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsSection(title = "Security", subtitle = "Choose how the vault unlocks") {
                listOf(
                    "both" to ("PIN + fingerprint" to "Recommended: use either available method."),
                    "pin" to ("PIN only" to "Require your PIN to unlock the vault."),
                    "biometric" to ("Fingerprint only" to "Use biometrics first; PIN fallback after three failures."),
                ).forEach { (mode, details) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { controller.setAuthMode(mode) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(details.first, style = MaterialTheme.typography.bodyLarge)
                            Text(details.second, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        RadioButton(selected = state.authMode == mode, onClick = { controller.setAuthMode(mode) })
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Allow screenshots", style = MaterialTheme.typography.bodyLarge)
                        Text("Allows screenshots while the vault is open.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = state.allowScreenshots, onCheckedChange = controller::setAllowScreenshots)
                }
            }

            SettingsSection(title = "Automatic lock", subtitle = "Lock the vault after inactivity") {
                listOf(15 to "15 seconds", 30 to "30 seconds", 60 to "1 minute", 120 to "2 minutes", 300 to "5 minutes").forEach { (seconds, label) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .clickable { controller.setAutoLockSeconds(seconds) }.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        RadioButton(selected = state.autoLockSeconds == seconds, onClick = { controller.setAutoLockSeconds(seconds) })
                    }
                }
            }

            SettingsSection(title = "Deletion", subtitle = "Protect against accidental removal") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Confirm permanent deletion", style = MaterialTheme.typography.bodyLarge)
                        Text("Ask before permanently deleting selected media.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = state.confirmDeletes, onCheckedChange = controller::setConfirmDeletes)
                }
            }

            SettingsSection(title = "Storage", subtitle = "Vault media is kept in app-private internal storage") {
                Text("Photos: files/vault/media/photos/[album ID]", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text("Videos: files/vault/media/videos/[album ID]", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            content()
        }
    }
}
