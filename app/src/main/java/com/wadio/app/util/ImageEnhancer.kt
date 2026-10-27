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
    fun enhance(source: File, output: File): Result {
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

            // Light denoising: blend a small Gaussian blur into the original.
            // Reuse the same temporary Mat for sharpening to limit native-memory use.
            Imgproc.GaussianBlur(image, work, Size(3.0, 3.0), 0.45)
            org.opencv.core.Core.addWeighted(image, 0.90, work, 0.10, 0.0, image)

            // Subtle unsharp mask: improve edge clarity without aggressive halos.
            Imgproc.GaussianBlur(image, work, Size(0.0, 0.0), 0.8)
            org.opencv.core.Core.addWeighted(image, 1.12, work, -0.12, 0.0, image)

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
