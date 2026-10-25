package com.wadio.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.wadio.app.ActiveTab
import com.wadio.app.security.ScreenGuard
import com.wadio.app.ui.WadioRoot
import com.wadio.app.ui.theme.WadioTheme
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private val controller: WadioController
        get() = (application as WadioApplication).controller

    private var pickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        controller.externalPickerActive = false
        controller.noteUserActivity()
        if (uris.isNotEmpty()) controller.startImportFromUris(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Protect the Recent-tasks preview even when the vault is closed.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val app = application as WadioApplication
        val startupFailure = app.startupFailure
        if (startupFailure != null) {
            val logText = runCatching {
                java.io.File(filesDir, "wadio/logreport.txt").readText()
            }.getOrDefault("Startup log is not available.")
            setContent { StartupFailureScreen(startupFailure, logText) }
            return
        }

        setContent {
            WadioTheme {
                WadioRoot(
                    controller = controller,
                    onPickFiles = { launchPicker() },
                    onBiometricRequest = { launchBiometricPrompt() },
                )
            }
        }

        lifecycleScope.launch {
            controller.biometricRequest.collect { launchBiometricPrompt() }
        }
    }


    private fun launchPicker() {
        controller.externalPickerActive = true
        try {
            val mimeTypes = when (controller.state.value.tab) {
                ActiveTab.Pictures -> arrayOf("image/*")
                ActiveTab.Videos -> arrayOf("video/*")
                ActiveTab.Audio -> arrayOf("image/*", "video/*")
            }
            pickerLauncher.launch(mimeTypes)
        } catch (t: Throwable) {
            controller.externalPickerActive = false
            throw t
        }
    }

    override fun onResume() {
        super.onResume()
        if ((application as WadioApplication).startupFailure != null) return
        controller.onActivityResumed()
        applyWindowSecurityForState()
    }

    override fun onPause() {
        super.onPause()
        // Never clear FLAG_SECURE while paused; Recent preview must stay blocked.
        ScreenGuard.protectOnBackground(this)
    }

    override fun onStop() {
        super.onStop()
        ScreenGuard.protectOnBackground(this)
    }

    private fun applyWindowSecurityForState() {
        val s = controller.state.value
        val secure = s.showPinPrompt || s.unlocked || s.viewerMediaId != null
        ScreenGuard.setSecure(this, secure)
    }

    private fun launchBiometricPrompt() {
        if (controller.biometricPromptActive) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (!controller.auth.hasPin) return

        val manager = BiometricManager.from(this)
        val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (manager.canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) return

        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                controller.biometricPromptActive = false
                controller.notifyBiometricSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                controller.biometricPromptActive = false
            }
            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
            }
        }

        val promptBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Wadio vault")
            .setSubtitle("Use your fingerprint or device screen lock")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            promptBuilder.setAllowedAuthenticators(allowed)
        } else {
            @Suppress("DEPRECATION")
            promptBuilder.setDeviceCredentialAllowed(true)
        }

        controller.biometricPromptActive = true
        try {
            val prompt = BiometricPrompt(this, executor, callback)
            prompt.authenticate(promptBuilder.build())
        } catch (t: Throwable) {
            controller.biometricPromptActive = false
        }
    }

    companion object {
        fun launchVideo(context: Context, relPath: String) {
            val intent = Intent(context, InternalVideoActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(InternalVideoActivity.EXTRA_REL_PATH, relPath)
            }
            context.startActivity(intent)
        }
    }
}


@androidx.compose.runtime.Composable
private fun StartupFailureScreen(error: Throwable, logText: String) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0B0F14),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Wadio could not finish starting", style = MaterialTheme.typography.headlineSmall)
            Text("${error.javaClass.simpleName}: ${error.message ?: "No error message"}", color = Color(0xFFFFB4AB))
            Text("The app saved startup details. Tap below to share them; no separate crash report is needed.")
            Text(logText.takeLast(7000), style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Wadio startup diagnostic")
                    putExtra(Intent.EXTRA_TEXT, "Startup error: ${error.javaClass.name}: ${error.message}\n\n$logText")
                }
                context.startActivity(Intent.createChooser(send, "Share Wadio diagnostic"))
            }) { Text("Share diagnostic details") }
        }
    }
}
