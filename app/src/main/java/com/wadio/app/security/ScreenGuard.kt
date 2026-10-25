package com.wadio.app.security

import android.app.Activity
import android.view.WindowManager

object ScreenGuard {

    /** Called from MainActivity on resume/pause to keep the flag correct. */
    fun setSecure(activity: Activity, secure: Boolean) {
        if (secure) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /** Called from onPause/onStop — never clear FLAG_SECURE while paused. */
    fun protectOnBackground(activity: Activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
