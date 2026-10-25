package com.wadio.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.wadio.app.security.ScreenGuard
import com.wadio.app.ui.WadioRoot
import com.wadio.app.ui.theme.WadioTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val controller: WadioController
        get() = (application as WadioApplication).controller

    private var pickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) controller.startImportFromUris(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Protect the Recent-tasks preview even when the vault is closed.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            WadioTheme {
                WadioRoot(
                    controller = controller,
                    onPickFiles = { pickerLauncher.launch(arrayOf("image/*", "video/*")) },
                    onBiometricRequest = { launchBiometricPrompt() },
                )
            }
        }

        lifecycleScope.launch {
            controller.biometricRequest.collect { launchBiometricPrompt() }
        }
    }

    override fun onResume() {
        super.onResume()
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
