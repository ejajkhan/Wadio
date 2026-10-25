package com.wadio.app.audio

import android.content.Context
import android.media.AudioManager
import android.os.Build

data class StreamState(
    val streamType: Int,
    val current: Int,
    val min: Int,
    val max: Int,
)

class AudioVolumeController(context: Context) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val streams = listOf(STREAM_MUSIC, STREAM_SYSTEM, STREAM_ALARM)

    fun getStates(): List<StreamState> = streams.map { type ->
        StreamState(
            streamType = type,
            current = audio.getStreamVolume(type),
            min = minVolume(type),
            max = audio.getStreamMaxVolume(type),
        )
    }

    fun setVolume(streamType: Int, value: Int) {
        try {
            val min = minVolume(streamType)
            val max = audio.getStreamMaxVolume(streamType)
            audio.setStreamVolume(streamType, value.coerceIn(min, max), 0)
        } catch (_: SecurityException) {
            // DND-restricted stream — silently ignore, matching Rust behaviour.
        }
    }

    private fun minVolume(streamType: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            audio.getStreamMinVolume(streamType)
        else 0

    companion object {
        const val STREAM_VOICE_CALL = 0
        const val STREAM_SYSTEM = 1
        const val STREAM_RING = 2
        const val STREAM_MUSIC = 3
        const val STREAM_ALARM = 4

        fun label(streamType: Int): String = when (streamType) {
            STREAM_MUSIC -> "Media Volume"
            STREAM_SYSTEM -> "System Volume"
            STREAM_ALARM -> "Alarm Volume"
            else -> "Unknown Stream"
        }
    }
}
