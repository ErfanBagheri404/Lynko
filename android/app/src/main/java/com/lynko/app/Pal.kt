package com.lynko.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat

/**
 * Semantic colors for views built in Kotlin. Values live in
 * res/values/colors.xml (light) and res/values-night/colors.xml (dark), so
 * system dark mode and the in-app switch both flow through the same palette.
 */
class Pal(context: Context) {
    private val ctx = context
    private fun c(id: Int) = ContextCompat.getColor(ctx, id)

    val paper = c(R.color.paper)
    val card = c(R.color.card)
    val panel = c(R.color.panel)
    val panelHi = c(R.color.panel_hi)
    val line = c(R.color.line)
    val lineSoft = c(R.color.line_soft)
    val lineStrong = c(R.color.line_strong)
    val overlay = c(R.color.overlay)
    val ink0 = c(R.color.ink0)
    val ink1 = c(R.color.ink1)
    val ink2 = c(R.color.ink2)
    val faint = c(R.color.faint)
    val faint2 = c(R.color.faint2)
    val muted = c(R.color.muted)
    val amber = c(R.color.amber)
    val amberDim = c(R.color.amber_dim)
    val amberPress = c(R.color.amber_press)
    val onAmber = c(R.color.on_amber)
    val green = c(R.color.green)
    val amberWash = c(R.color.amber_wash)
    val amberWashSoft = c(R.color.amber_wash_soft)
    val amberStroke = c(R.color.amber_stroke)
    val whiteStroke = c(R.color.white_stroke)
}

/** System is the default; the explicit choice survives restarts via SharedPreferences. */
object ThemeMode {
    private const val PREFS = "lynko_prefs"
    private const val KEY_MODE = "theme_mode"

    const val SYSTEM = "system"
    const val DARK = "dark"
    const val LIGHT = "light"

    fun mode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, SYSTEM)
            ?.takeIf { it == DARK || it == LIGHT } ?: SYSTEM

    fun isDark(context: Context): Boolean = when (mode(context)) {
        DARK -> true
        LIGHT -> false
        else -> (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode(context)) {
                DARK -> AppCompatDelegate.MODE_NIGHT_YES
                LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
    }

    /** Pin one of SYSTEM / DARK / LIGHT. */
    fun set(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, value).apply()
        apply(context)
    }
}
