package com.wadio.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File
import java.io.FileOutputStream

/** Small persistent thumbnails for vault grids. Full-resolution media is never decoded for a grid cell. */
object ThumbnailStore {
    private const val THUMB_SIDE = 256

    fun fileFor(baseDir: File, id: String): File = File(File(baseDir, ".thumbnails"), "$id.jpg")

    /** Returns an existing thumbnail or creates one off the main thread. Returns null on unsupported/corrupt media. */
    fun getOrCreate(baseDir: File, source: File, id: String, mediaType: String): File? {
        if (!source.isFile || id.isBlank() || id.contains('/') || id.contains('\\')) return null
        val directory = File(baseDir, ".thumbnails")
        if (!directory.exists() && !directory.mkdirs()) return null
        val target = fileFor(baseDir, id)
        if (target.isFile && target.length() > 0L) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            // Upgrade thumbnails made by older Wadio builds (96x96) on demand.
            if (maxOf(bounds.outWidth, bounds.outHeight) >= 192) return target
            runCatching { target.delete() }
        }

        val bitmap = if (mediaType.equals("Video", ignoreCase = true)) {
            createVideoFrame(source)
        } else {
            createImageThumbnail(source)
        } ?: return null

        val scaled = try {
            val width = bitmap.width.coerceAtLeast(1)
            val height = bitmap.height.coerceAtLeast(1)
            val ratio = minOf(THUMB_SIDE.toFloat() / width, THUMB_SIDE.toFloat() / height, 1f)
            if (ratio < 1f) Bitmap.createScaledBitmap(
                bitmap,
                (width * ratio).toInt().coerceAtLeast(1),
                (height * ratio).toInt().coerceAtLeast(1),
                true,
            ) else bitmap
        } catch (_: Throwable) {
            bitmap
        }

        val temp = File(directory, "$id.jpg.tmp")
        return try {
            FileOutputStream(temp).use { output ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 82, output)) return null
                output.flush()
                runCatching { output.fd.sync() }
            }
            if (target.exists()) target.delete()
            if (temp.renameTo(target)) target else null
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { temp.delete() }
            if (scaled !== bitmap) runCatching { scaled.recycle() }
            runCatching { bitmap.recycle() }
        }
    }

    private fun createImageThumbnail(source: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB_SIDE * 2 ||
            bounds.outHeight / (sample * 2) >= THUMB_SIDE * 2
        ) sample *= 2
        return BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            },
        )
    }

    private fun createVideoFrame(source: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source.absolutePath)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    THUMB_SIDE,
                    THUMB_SIDE,
                )
            } else {
                // API 26 fallback; still handled off the main thread.
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
