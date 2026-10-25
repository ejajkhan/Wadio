package com.wadio.app

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner

class WadioApplication : Application() {

    lateinit var controller: WadioController
        private set

    @Volatile
    var startupFailure: Throwable? = null
        private set

    override fun onCreate() {
        super.onCreate()
        controller = WadioController(this)
        val prefs = getSharedPreferences("wadio_startup", MODE_PRIVATE)
        val previousCrash = prefs.getString("last_crash", null)
        if (!previousCrash.isNullOrBlank()) {
            prefs.edit().remove("last_crash").apply()
            startupFailure = RuntimeException("The previous run crashed. Share the startup log below.")
            controller.startupLogger.log("Previous-run crash detected: $previousCrash")
            return
        }
        try {
            controller.initialize()
        } catch (t: Throwable) {
            // Keep the process alive long enough for MainActivity to show diagnostics.
            startupFailure = t
            controller.startupLogger.log("Application startup failed; showing diagnostic screen.", t)
            return
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP && !controller.biometricPromptActive &&
                    !controller.externalPickerActive && !controller.importInProgress) {
                    controller.requestLock()
                }
            }
        )
    }
}
