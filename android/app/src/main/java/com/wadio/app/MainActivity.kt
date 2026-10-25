package com.wadio.app

import android.app.NativeActivity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.WindowManager
import androidx.biometric.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.content.DialogInterface
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.sqrt

class MainActivity : NativeActivity() {

    // Written before super.onCreate(), because NativeActivity loads the native
    // library and enters android_main during activity initialization.
    private val startupLogFile: File by lazy { File(File(filesDir, "wadio"), "logreport.txt") }

    @Synchronized
    private fun startupLog(message: String, error: Throwable? = null) {
        try {
            startupLogFile.parentFile?.mkdirs()
            FileWriter(startupLogFile, true).use { writer ->
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                writer.append("[$timestamp] [Android] ").append(message).append('\n')
                if (error != null) {
                    writer.append(Log.getStackTraceString(error)).append('\n')
                }
                writer.flush()
            }
        } catch (logError: Exception) {
            // Logging must never prevent the app from starting.
            Log.e("WadioStartup", "Could not write startup log", logError)
        }
    }

    private lateinit var audioManager: AudioManager
    @Volatile private var pendingAlbumId: String = "default"
    @Volatile private var biometricPromptActive: Boolean = false
    @Volatile private var activityResumedForUi: Boolean = false
    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            // Android's device-credential sheet may temporarily background this activity.
            // It is only launched from the locked audio screen, so do not treat that sheet as app abandonment.
            if (biometricPromptActive) return
            runOnUiThread { window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            nativeRequestLock()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Start a fresh report before calling NativeActivity: super.onCreate()
        // may load the native library and begin the Rust startup sequence.
        try {
            startupLogFile.parentFile?.mkdirs()
            startupLogFile.writeText("")
        } catch (error: Exception) {
            Log.e("WadioStartup", "Could not initialize startup log", error)
        }
        startupLog("MainActivity.onCreate entered")
        try {
            // NativeActivity can start the Rust render loop inside super.onCreate().
            // Initialize this before entering native code so its first JNI volume poll is safe.
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            startupLog("AudioManager initialized")
            pendingAlbumId = savedInstanceState?.getString("pendingAlbumId") ?: "default"
        // Remove HEIF conversion leftovers from a previous process crash.
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.startsWith("wadio-heif-") && file.name.endsWith(".jpg")) {
                    if (!file.delete()) Log.w("WadioImport", "Could not remove stale HEIF cache file")
                }
            }
            startupLog("Pre-native setup complete; calling NativeActivity.super.onCreate")
            super.onCreate(savedInstanceState)
            startupLog("NativeActivity.super.onCreate returned")
            // Process lifecycle only reports backgrounding when all Wadio activities are stopped;
            // opening the in-app video activity therefore does not prematurely lock the vault.
            ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
            startupLog("Process lifecycle observer registered; onCreate complete")
        } catch (error: Throwable) {
            startupLog("FATAL: exception during MainActivity.onCreate", error)
            throw error
        }
    }

    override fun onPause() {
        // Android may capture the task preview during pause. Prevent the vault/audio UI
        // from appearing in Recents; native auth state restores the appropriate flag on resume.
        activityResumedForUi = false
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onPause()
    }

    override fun onStop() {
        // Keep the preview protected even if the native render loop processes a lock while paused.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onStop()
    }

    override fun onResume() {
        activityResumedForUi = true
        super.onResume()
        // Time spent watching the separate in-app video task must not count as vault idle time.
        // Rust reconciles FLAG_SECURE to the current auth state after this signal is consumed.
        nativeResetIdle()
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingAlbumId", pendingAlbumId)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null) return

        val uris = mutableListOf<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { uris.add(it) }
            }
        } else {
            data.data?.let { uris.add(it) }
        }
        if (uris.isNotEmpty()) {
            val albumId = pendingAlbumId
            Thread {
                for (uri in uris) {
                    processIncomingStream(uri, albumId)
                }
            }.start()
        }
    }

    fun getStreamVolume(streamType: Int): Int = audioManager.getStreamVolume(streamType)
    fun getStreamMaxVolume(streamType: Int): Int = audioManager.getStreamMaxVolume(streamType)
    fun getStreamMinVolume(streamType: Int): Int {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            audioManager.getStreamMinVolume(streamType)
        } else {
            0
        }
    }

    fun setStreamVolume(streamType: Int, index: Int) {
        try {
            audioManager.setStreamVolume(streamType, index, 0)
        } catch (se: SecurityException) {
            Log.w("WadioAudio", "DND restriction encountered on stream $streamType: ${se.message}")
        }
    }

    fun launchBiometricPrompt() {
        runOnUiThread { launchBiometricPromptOnUiThread() }
    }

    private fun launchBiometricPromptOnUiThread() {
        if (biometricPromptActive) return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
            Log.w("WadioAuth", "Platform biometric prompt requires Android 9 or newer")
            return
        }

        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val manager = BiometricManager.from(this)
            val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            if (manager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
                Log.w("WadioAuth", "No supported biometric or device credential is configured")
                return
            }
        }

        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                biometricPromptActive = false
                nativeBiometricSuccess()
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Log.w("WadioAuth", "Biometric authentication attempt failed")
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                biometricPromptActive = false
                Log.i("WadioAuth", "Biometric prompt closed: $errorCode")
            }
        }

        val builder = BiometricPrompt.Builder(this)
            .setTitle("Unlock Wadio vault")
            .setSubtitle("Use your fingerprint or device screen lock")

        when {
            android.os.Build.VERSION.SDK_INT >= 30 -> {
                builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
            }
            android.os.Build.VERSION.SDK_INT >= 29 -> {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }
            else -> {
                builder.setNegativeButton("Cancel", executor, DialogInterface.OnClickListener { _, _ ->
                    biometricPromptActive = false
                })
            }
        }

        biometricPromptActive = true
        try {
            builder.build().authenticate(null, CancellationSignal(), executor, callback)
        } catch (e: Exception) {
            biometricPromptActive = false
            Log.e("WadioAuth", "Could not start biometric prompt", e)
        }
    }

    private external fun nativeBiometricSuccess()

    fun setWindowSecurity(enableSecure: Boolean) {
        runOnUiThread {
            if (enableSecure) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else if (activityResumedForUi) {
                // Never clear Recents protection while the activity is paused/stopped.
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    @Suppress("DEPRECATION")
    fun launchSystemPicker(albumId: String) {
        pendingAlbumId = albumId
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, REQ_IMPORT)
    }

    fun playVideoInternally(relPath: String) {
        val root = File(filesDir, "vault/media").canonicalFile
        val video = File(filesDir, "vault/$relPath").canonicalFile
        if (!video.path.startsWith(root.path + File.separator) || !video.isFile) return
        startActivity(Intent(this, InternalVideoActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(InternalVideoActivity.EXTRA_REL_PATH, relPath)
        })
    }

    private fun processIncomingStream(uri: Uri, albumId: String) {
        var declaredSize = 0L
        var displayName = "import_${System.currentTimeMillis()}"

        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) displayName = cursor.getString(nameIndex)
                if (sizeIndex != -1) declaredSize = cursor.getLong(sizeIndex)
            }
        }

        val mime = contentResolver.getType(uri)?.lowercase().orEmpty()
        val extension = displayName.substringAfterLast('.', "").lowercase()
        val isHeif = mime in setOf("image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence") || extension in setOf("heic", "heif")
        if (isHeif) {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
                Log.w("WadioImport", "HEIC/HEIF conversion requires Android 9 or newer")
                return
            }
            val converted = File(cacheDir, "wadio-heif-${UUID.randomUUID()}.jpg")
            var decoded: Bitmap? = null
            try {
                val source = ImageDecoder.createSource(contentResolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val width = info.size.width.coerceAtLeast(1)
                    val height = info.size.height.coerceAtLeast(1)
                    val scale = minOf(1.0, sqrt(50_000_000.0 / (width.toDouble() * height.toDouble())))
                    decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                decoded = bitmap
                FileOutputStream(converted).use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) throw java.io.IOException("HEIC/HEIF conversion failed")
                    output.fd.sync()
                }
                val jpegName = displayName.substringBeforeLast('.', displayName) + ".jpg"
                FileInputStream(converted).use { stream -> feedStreamToNative(stream, converted.length(), jpegName, albumId) }
            } catch (e: Exception) {
                Log.e("WadioImport", "HEIC/HEIF conversion failed", e)
            } finally {
                decoded?.recycle()
                converted.delete()
            }
            return
        }

        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                feedStreamToNative(stream, declaredSize, displayName, albumId)
            }
        } catch (e: Exception) {
            Log.e("WadioImport", "Failed reading incoming SAF uri: ${e.message}")
        }
    }

    private fun feedStreamToNative(stream: InputStream, declaredSize: Long, filename: String, albumId: String) {
        val buffer = ByteArray(64 * 1024)
        val streamHandle = nativeBeginImport(declaredSize.coerceAtLeast(0L), filename, albumId)
        if (streamHandle == 0L) return

        var completed = false
        var zeroReads = 0
        try {
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) {
                    completed = true
                    break
                }
                if (read == 0) {
                    zeroReads++
                    if (zeroReads >= 10) throw java.io.IOException("Input stream repeatedly returned zero bytes")
                    Thread.sleep(1)
                    continue
                }
                zeroReads = 0
                if (!nativeFeedChunk(streamHandle, buffer, read)) {
                    throw java.io.IOException("Native import write failed")
                }
            }
        } catch (e: Exception) {
            Log.e("WadioImport", "Import stream failed; aborting partial import", e)
        } finally {
            if (completed) nativeFinishImport(streamHandle) else nativeAbortImport(streamHandle)
        }
    }

    private external fun nativeRequestLock()
    private external fun nativeResetIdle()
    private external fun nativeBeginImport(declaredSize: Long, filename: String, albumId: String): Long
    private external fun nativeFeedChunk(handle: Long, chunk: ByteArray, size: Int): Boolean
    private external fun nativeFinishImport(handle: Long)
    private external fun nativeAbortImport(handle: Long)

    companion object {
        const val REQ_IMPORT = 4107

        init {
            System.loadLibrary("wadio")
        }
    }
}
