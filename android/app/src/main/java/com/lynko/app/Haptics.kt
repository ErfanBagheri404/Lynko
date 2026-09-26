package com.lynko.app

import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Local UI haptics: a light tick on every tappable surface.
 * Uses View.performHapticFeedback — no VIBRATE permission needed, the
 * system owns amplitude and the user's touch-feedback setting is honored.
 */
object Haptics {
    fun tap(v: View) {
        try {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        } catch (_: Exception) {
        }
    }
}
