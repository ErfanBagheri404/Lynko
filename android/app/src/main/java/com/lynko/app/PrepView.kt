package com.lynko.app

import android.app.Activity
import android.content.Context
import androidx.core.content.res.ResourcesCompat
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * First-run prep screen — "Four things, once." Four hairline rows, one custom
 * pill switch each (taps / awake / notif), an informational chip row for the
 * screen-share (MediaProjection cannot be pre-granted), sticky amber CTA.
 *
 * There is no "I'm done" button: tapping a row opens the exact system screen,
 * MainActivity.onResume() calls onScreenResume(), and if the awaited item came
 * back on the next off item is opened automatically.
 *
 * Flat design law (same as ShareTabView): hairline #303034, amber #FFB454
 * active only, no shadows, every TextView on R.font.lynko_ui. Layout is
 * start/end only so fa (RTL) mirrors correctly.
 */
class PrepView(private val act: Activity, private val firstRun: Boolean) : LinearLayout(act) {

    /** MainActivity: user pressed the CTA's "Continue" — reveal the tabs. */
    var onContinue: (() -> Unit)? = null
    /** MainActivity: opened from Settings — back to the previous tab. */
    var onBack: (() -> Unit)? = null

    private val ui: Typeface? = ResourcesCompat.getFont(context, R.font.lynko_ui)
    private val uiBold: Typeface = Typeface.create(ui, Typeface.BOLD)
    private val pal by lazy { Pal(context) }
    private val amber get() = pal.amber
    private val fgColor get() = pal.ink0
    private val dimColor get() = pal.ink1
    private val faintColor get() = pal.faint
    private val inkOnAmber get() = pal.onAmber
    private val paper get() = pal.paper
    private val hairline get() = pal.line

    private val swTaps = PrepSwitch(context)
    private val swAwake = PrepSwitch(context)
    private val swNotif = PrepSwitch(context)
    private val themePicker = LinearLayout(context).apply {
        orientation = HORIZONTAL
        listOf(
            ThemeMode.SYSTEM to R.string.prep_theme_system,
            ThemeMode.DARK to R.string.prep_theme_dark,
            ThemeMode.LIGHT to R.string.prep_theme_light,
        ).forEachIndexed { i, (mode, label) ->
            addView(
                themeButton(mode, label),
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                    .apply { if (i > 0) marginStart = dp(6) },
            )
        }
    }

    /** Row key waiting on a system screen: "taps" | "awake" | "notif". */
    private var awaiting: String? = null

    private val cta = Button(context)
    private val note = TextView(context)

    init {
        orientation = VERTICAL
        setBackgroundColor(paper)

        if (!firstRun) addView(backBar())

        val body = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        body.addView(tv(getString(R.string.prep_title), 30f, fgColor, bold = true).apply {
            letterSpacing = -0.03f
            setPadding(0, dp(if (firstRun) 32 else 16), 0, 0)
        })
        body.addView(tv(getString(R.string.prep_lede), 14f, dimColor).apply {
            setPadding(0, dp(8), 0, 0)
            setLineSpacing(0f, 1.35f)
        })

        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, dp(20), 0, dp(8))
        }
        list.addView(divider())
        list.addView(row("taps", R.string.prep_taps, R.string.prep_taps_sub, swTaps))
        list.addView(divider())
        list.addView(row("mirror", R.string.prep_mirror, R.string.prep_mirror_sub, chip()))
        list.addView(divider())
        list.addView(row("awake", R.string.prep_awake, R.string.prep_awake_sub, swAwake))
        list.addView(divider())
        list.addView(row("notif", R.string.prep_notif, R.string.prep_notif_sub, swNotif))
        list.addView(divider())
        list.addView(row("theme", R.string.prep_theme, R.string.prep_theme_sub, themePicker))
        list.addView(divider())
        body.addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(
            ScrollView(context).apply {
                isFillViewport = true
                addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )

        // Sticky footer: CTA + note, outside the scroll area.
        val footer = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(22))
        }
        cta.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(44))
        cta.stateListAnimator = null
        cta.typeface = uiBold
        cta.textSize = 14f
        cta.setTextColor(inkOnAmber)
        cta.background = ResourcesCompat.getDrawable(resources, R.drawable.btn_amber, null)
        cta.setOnClickListener { v -> Haptics.tap(v); onCta() }
        footer.addView(cta)
        note.textSize = 11f
        note.setTextColor(dimColor)
        note.gravity = Gravity.CENTER
        note.typeface = ui
        note.setPadding(0, dp(10), 0, 0)
        footer.addView(note, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(footer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        render()
    }

    /**
     * MainActivity.onResume(): re-read the system state. If the awaited item
     * came back on, clear it and auto-open the next off item; otherwise clear
     * it and leave the row off. This replaces any "I'm done" button.
     */
    fun onScreenResume() {
        val a = awaiting
        if (a != null) {
            awaiting = null
            val st = Prep.state(act)
            val stillOff = when (a) {
                "taps" -> !st.taps
                "awake" -> !st.awake
                else -> !st.notif
            }
            if (!stillOff) {
                val next = offItems(st).firstOrNull()
                if (next != null) {
                    open(next)
                    return
                }
            }
        }
        render()
    }

    // ------------------------------------------------------------------ build

    private fun backBar() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        setPadding(dp(16), dp(8), dp(16), dp(0))
        // RTL flips the glyph so the arrow keeps pointing "back".
        val arrow = tv(if (Loc.isRtl()) "→" else "←", 20f, dimColor)
        arrow.contentDescription = getString(R.string.prep_back)
        arrow.setPadding(dp(8), 0, dp(8), 0)
        addView(arrow)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        setOnClickListener { v -> Haptics.tap(v); onBack?.invoke() }
    }

    private fun row(key: String, titleRes: Int, subRes: Int, control: View) =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(0, dp(12), 0, dp(12))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            if (key != "mirror" && key != "theme") {
                // Mirror is informational: MediaProjection cannot be pre-granted,
                // so there is no system screen to shortcut to. Theme has its own
                // three-button picker instead of a row-wide tap.
                val a = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                background = a.getDrawable(0)
                a.recycle()
                setOnClickListener { v ->
                    Haptics.tap(v)
                    open(key)
                }
            }
            val txt = LinearLayout(context).apply {
                orientation = VERTICAL
                addView(tv(getString(titleRes), 14f, fgColor))
                addView(tv(getString(subRes), 12f, dimColor).apply {
                    setPadding(0, dp(2), 0, 0)
                    setLineSpacing(0f, 1.3f)
                })
            }
            addView(txt, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(
                control,
                LayoutParams(if (control is PrepSwitch) dp(36) else LayoutParams.WRAP_CONTENT,
                    if (control is PrepSwitch) dp(20) else LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(12) },
            )
        }

    private fun chip() = TextView(context).apply {
        text = getString(R.string.prep_mirror_chip)
        textSize = 11f
        typeface = ui
        setTextColor(dimColor)
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 2f * resources.displayMetrics.density
            setColor(0x00000000)
            setStroke(dp(1), pal.lineStrong)
        }
    }

    private fun divider() = View(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(hairline)
    }

    // ------------------------------------------------------------------ state

    /** Off items the CTA can actually open (mirror is never openable here). */
    private fun offItems(st: Prep.State): List<String> {
        val out = mutableListOf<String>()
        if (!st.taps) out += "taps"
        if (!st.awake) out += "awake"
        if (!st.notif) out += "notif"
        return out
    }

    private fun open(key: String) {
        awaiting = key
        when (key) {
            "taps" -> Prep.openTaps(act)
            "awake" -> Prep.openBattery(act)
            else -> Prep.openNotifications(act)
        }
        render()
    }

    private fun themeButton(mode: String, labelRes: Int) = TextView(context).apply {
        tag = mode
        text = getString(labelRes)
        textSize = 11f
        typeface = uiBold
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setOnClickListener { v ->
            Haptics.tap(v)
            ThemeMode.set(act, mode)
            render()
        }
    }

    private fun paintTheme(selected: String) {
        for (i in 0 until themePicker.childCount) {
            val b = themePicker.getChildAt(i) as TextView
            val on = b.tag == selected
            b.setTextColor(if (on) inkOnAmber else dimColor)
            b.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 2f * resources.displayMetrics.density
                setColor(if (on) amber else 0x00000000)
                setStroke(dp(1), if (on) amber else pal.lineStrong)
            }
        }
    }

    private fun onCta() {
        val st = Prep.state(act)
        val first = offItems(st).firstOrNull()
        if (first != null) open(first) else onContinue?.invoke()
    }

    private fun render() {
        val st = Prep.state(act)
        swTaps.on = st.taps
        swAwake.on = st.awake
        swNotif.on = st.notif
        paintTheme(ThemeMode.mode(act))
        swTaps.contentDescription = desc(R.string.prep_taps, st.taps)
        swAwake.contentDescription = desc(R.string.prep_awake, st.awake)
        swNotif.contentDescription = desc(R.string.prep_notif, st.notif)
        // Count only the three switches, so the note agrees with the CTA.
        val total = listOf(st.taps, st.awake, st.notif).count { it }
        note.text = getString(R.string.prep_note, total)
        val off = offItems(st).size
        when {
            awaiting != null -> {
                cta.isEnabled = false
                cta.setTextColor(faintColor)
                cta.text = getString(R.string.prep_waiting)
            }
            off > 0 -> {
                cta.isEnabled = true
                cta.setTextColor(inkOnAmber)
                cta.text = if (off == 1) getString(R.string.prep_turn_on_1)
                else getString(R.string.prep_turn_on, off)
            }
            else -> {
                cta.isEnabled = true
                cta.setTextColor(inkOnAmber)
                cta.text = getString(R.string.prep_continue)
            }
        }
    }

    private fun desc(titleRes: Int, on: Boolean) =
        "${getString(titleRes)}, ${getString(if (on) R.string.prep_state_on else R.string.prep_state_off)}"

    private fun getString(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun tv(text: CharSequence, size: Float, color: Int, bold: Boolean = false) =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = if (bold) uiBold else ui
        }

    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)

    /** 36x20dp pill, 1dp border, 12dp knob — indicator, not a stock toggle. */
    private class PrepSwitch(ctx: Context) : View(ctx) {
        var on: Boolean = false
            set(v) {
                if (field != v) {
                    field = v
                    invalidate()
                }
            }

        private val d = resources.displayMetrics.density
        private val w = Math.round(36 * d)
        private val h = Math.round(20 * d)
        private val rad = 10 * d
        private val knobR = 6 * d
        private val gap = 4 * d
        private val pal = Pal(ctx)
        private val amber get() = pal.amber

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = d
        }
        private val ball = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(c: Canvas) {
            rect.set(0.5f * d, 0.5f * d, w - 0.5f * d, h - 0.5f * d)
            fill.color = if (on) pal.amberWash else 0x00000000
            c.drawRoundRect(rect, rad, rad, fill)
            stroke.color = if (on) pal.amberStroke else pal.whiteStroke
            c.drawRoundRect(rect, rad, rad, stroke)
            ball.color = if (on) amber else pal.ink1
            val cx = if (on) w - gap - knobR else gap + knobR
            c.drawCircle(cx, h / 2f, knobR, ball)
        }
    }
}
