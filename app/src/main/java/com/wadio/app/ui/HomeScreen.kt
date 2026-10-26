package com.wadio.app.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wadio.app.ActiveTab
import com.wadio.app.WadioController
import com.wadio.app.audio.AudioVolumeController
import com.wadio.app.audio.StreamState
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

@Composable
fun HomeScreen(controller: WadioController, onPickFiles: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Wadio",
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        val released = withTimeoutOrNull(1000L) { tryAwaitRelease() }
                        if (released == null) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            controller.openAuthPrompt()
                        }
                    }
                )
            }
        )

        Spacer(Modifier.height(24.dp))
        Text("System Sound", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        state.audioStates.forEach { channel ->
            VolumeCard(channel) { value -> controller.setVolume(channel.streamType, value) }
            Spacer(Modifier.height(8.dp))
        }

        if (state.unlocked) {
            Spacer(Modifier.height(24.dp))
            Divider()
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { controller.setTab(ActiveTab.Pictures) }) { Text("Pictures") }
                Button(onClick = { controller.setTab(ActiveTab.Videos) }) { Text("Videos") }
            }
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun VolumeCard(channel: StreamState, onChange: (Int) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon(channel.streamType), null)
                Spacer(Modifier.width(8.dp))
                Text(AudioVolumeController.label(channel.streamType))
                Spacer(Modifier.weight(1f))
                Text("${channel.current}/${channel.max}")
            }
            val fraction = if (channel.max - channel.min <= 0) 0f
                else (channel.current - channel.min).toFloat() / (channel.max - channel.min)
            Slider(
                value = fraction,
                onValueChange = { f ->
                    val idx = channel.min + (f * (channel.max - channel.min)).roundToInt()
                    onChange(idx)
                },
                steps = (channel.max - channel.min - 1).coerceAtLeast(0),
            )
        }
    }
}

private fun icon(stream: Int) = when (stream) {
    AudioVolumeController.STREAM_MUSIC -> Icons.Rounded.MusicNote
    AudioVolumeController.STREAM_SYSTEM -> Icons.Rounded.Notifications
    else -> Icons.Rounded.Alarm
}
