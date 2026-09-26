package com.lynko.app

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * Mirror-only audio routing: while a desktop mirror is active, the phone
 * speaker is muted so playback happens ONLY on the desktop. Unmute restores
 * the user's normal volume. Uses STREAM_MUSIC mute — the same stream the
 * AudioPlaybackCapture configuration silences on-device.
 */
object PhoneAudio {

    private var mutedHere = false

    /** Mute the phone speaker while the mirror streams. */
    fun muteForMirror(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (am.isStreamMute(AudioManager.STREAM_MUSIC)) return  // user muted already
            am.setStreamMute(AudioManager.STREAM_MUSIC, true)
            mutedHere = true
            Log.i("lynko", "phone speaker muted for mirror")
        } catch (t: Throwable) {
            Log.w("lynko", "mute failed: ${t.message}")
        }
    }

    /** Restore the speaker when the mirror stops. */
    fun unmute(ctx: Context) {
        if (!mutedHere) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            am.setStreamMute(AudioManager.STREAM_MUSIC, false)
            Log.i("lynko", "phone speaker restored")
        } catch (t: Throwable) {
            Log.w("lynko", "unmute failed: ${t.message}")
        }
        mutedHere = false
    }
}
