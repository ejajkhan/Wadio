package com.wadio.app

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner

class WadioApplication : Application() {

    lateinit var controller: WadioController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = WadioController(this)
        controller.initialize()

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP && !controller.biometricPromptActive) {
                    controller.requestLock()
                }
            }
        )
    }
}
