package com.lynko.app

import android.content.Context

/**
 * The 6-digit transfer PIN (LocalSend spec: `?pin=` query parameter on
 * prepare-upload / prepare-download; 401 when required or wrong).
 *
 * Empty string = no PIN required, which is LocalSend's default.
 */
object PinStore {
    private const val PREFS = "lynko"
    private const val KEY = "transfer_pin"

    @Volatile private var cached: String? = null

    fun current(ctx: Context? = null): String? {
        cached?.let { return it.ifBlank { null } }
        val v = ctx?.let {
            runCatching {
                it.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
            }.getOrNull() ?: ""
        } ?: ""
        cached = v
        return v.ifBlank { null }
    }

    fun set(ctx: Context, value: String) {
        val clean = value.filter { it.isDigit() }.take(6)
        cached = clean
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, clean).apply()
        }
    }

    fun isSet(): Boolean = current() != null
}
