package com.wadio.app.util

import android.graphics.BitmapFactory
import android.media.ExifInterface
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgcodecs.Imgcodecs.IMREAD_UNCHANGED
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.IOException

/**
 * Lightweight, on-device enhancement for the vault photo viewer.
 *
 * Keeps the full source resolution, but refuses very large images before pixel decoding
 * to avoid exhausting the app's native heap on lower-memory phones.
 */
object ImageEnhancer {
    private const val MAX_PIXELS = 12_000_000L
    private val supportedExtensions = setOf("jpg", "jpeg", "png", "webp")

    fun isSupported(file: File): Boolean =
        file.extension.lowercase() in supportedExtensions

    data class Result(val width: Int, val height: Int)

    @Throws(IOException::class)
    fun enhance(
        source: File,
        output: File,
        denoiseStrength: Float = 0.10f,
        sharpenStrength: Float = 0.12f,
        contrast: Float = 1.00f,
        brightness: Float = 0.0f,
    ): Result {
        if (!source.isFile || source.length() <= 0L) throw IOException("The original photo is missing or empty")
        if (!isSupported(source)) throw IOException("This image format is not supported for enhancement")
        if (!OpenCVLoader.initLocal()) throw IOException("OpenCV could not be initialized")

        // Read dimensions without allocating the image pixel buffer.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Wadio could not read this photo's dimensions")
        }
        val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        if (pixels > MAX_PIXELS) {
            throw IOException("This photo is too large for safe enhancement on this device (maximum 12 megapixels)")
        }

        var image: Mat? = null
        var work: Mat? = null
        try {
            image = Imgcodecs.imread(source.absolutePath, IMREAD_UNCHANGED)
            if (image.empty()) throw IOException("OpenCV could not decode this photo")
            if (image.channels() !in 1..4) throw IOException("This photo uses an unsupported color format")
            work = Mat()

            // Reuse one temporary Mat to keep native-memory usage bounded.
            // Defaults preserve Wadio's previous light denoise and subtle sharpening.
            val denoise = denoiseStrength.coerceIn(0f, 0.30f).toDouble()
            val sharpen = sharpenStrength.coerceIn(0f, 0.80f).toDouble()
            val safeContrast = contrast.coerceIn(0.80f, 1.30f).toDouble()
            val safeBrightness = brightness.coerceIn(-20f, 20f).toDouble()

            if (denoise > 0.001) {
                Imgproc.GaussianBlur(image, work, Size(3.0, 3.0), 0.45)
                org.opencv.core.Core.addWeighted(image, 1.0 - denoise, work, denoise, 0.0, image)
            }

            if (sharpen > 0.001) {
                Imgproc.GaussianBlur(image, work, Size(0.0, 0.0), 0.8)
                org.opencv.core.Core.addWeighted(image, 1.0 + sharpen, work, -sharpen, 0.0, image)
            }

            if (safeContrast != 1.0 || safeBrightness != 0.0) {
                image.convertTo(image, image.type(), safeContrast, safeBrightness)
            }

            val params = when (source.extension.lowercase()) {
                "jpg", "jpeg" -> org.opencv.core.MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 95)
                "png" -> org.opencv.core.MatOfInt(Imgcodecs.IMWRITE_PNG_COMPRESSION, 3)
                "webp" -> org.opencv.core.MatOfInt(Imgcodecs.IMWRITE_WEBP_QUALITY, 95)
                else -> org.opencv.core.MatOfInt()
            }
            try {
                output.parentFile?.mkdirs()
                if (!Imgcodecs.imwrite(output.absolutePath, image, params)) {
                    throw IOException("OpenCV could not save the enhanced photo")
                }
            } finally {
                params.release()
            }

            if (!output.isFile || output.length() <= 0L) {
                throw IOException("The enhanced photo was not written correctly")
            }

            // IMREAD_UNCHANGED intentionally preserves alpha, but ignores EXIF orientation.
            // Restore that tag so portrait photos continue to display in the correct direction.
            val orientation = runCatching {
                ExifInterface(source.absolutePath).getAttribute(ExifInterface.TAG_ORIENTATION)
            }.getOrNull()
            if (orientation != null) {
                runCatching {
                    ExifInterface(output.absolutePath).apply {
                        setAttribute(ExifInterface.TAG_ORIENTATION, orientation)
                        saveAttributes()
                    }
                }
            }
            return Result(image.cols(), image.rows())
        } finally {
            work?.release()
            image?.release()
        }
    }
}
