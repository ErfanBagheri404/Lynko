package com.lynko.app

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

/** File sharing, independent of screen capture. */
class ShareTabView(context: Context) : LinearLayout(context) {
    var onChoose: (() -> Unit)? = null
    var onReceive: (() -> Unit)? = null
    private val status = TextView(context)
    private val choose = Button(context)
    private val receive = Button(context)

    // Share/Settings screens are built in Kotlin (unlike the XML tabs), so
    // every TextView needs the family applied explicitly — otherwise they
    // silently fall back to Roboto and the app shows two different typefaces.
    private val ui: Typeface? = ResourcesCompat.getFont(context, R.font.spacegrotesk_regular)
    private val uiBold: Typeface? = ResourcesCompat.getFont(context, R.font.spacegrotesk_bold)
    private val mono: Typeface? = Typeface.MONOSPACE

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        setPadding(dp(24), dp(20), dp(24), dp(20))

        fun body(key: String, size: Float, strong: Boolean = false) = TextView(context).apply {
            text = Loc.t("phone", key)
            textSize = size
            typeface = if (strong) uiBold else ui
            setTextColor(if (strong) 0xFFE8E6E1.toInt() else 0xFF8B8D90.toInt())
        }

        // title
        addView(body("share_title", 24f, true).apply { setPadding(0, 0, 0, dp(8)) })
        // description
        addView(body("share_description", 14f).apply { setPadding(0, 0, 0, dp(20)) })

        // send: primary amber button, matching btn_amber in the XML tabs
        choose.text = Loc.t("phone", "share_choose")
        choose.typeface = uiBold
        choose.textSize = 15f
        choose.background = ResourcesCompat.getDrawable(resources, R.drawable.btn_amber, null)
        choose.setTextColor(0xFF1A1006.toInt())
        choose.stateListAnimator = null
        choose.setOnClickListener { Haptics.tap(it); onChoose?.invoke() }
        addView(choose, LayoutParams(LayoutParams.MATCH_PARENT, dp(56)))

        status.typeface = mono
        status.textSize = 12f
        status.setTextColor(0xFF8B8D90.toInt())
        status.setPadding(0, dp(14), 0, dp(24))
        addView(status)

        addView(
            View(context).apply { setBackgroundColor(0xFF303236.toInt()) },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(1))
        )

        addView(body("share_receive", 18f, true).apply { setPadding(0, dp(20), 0, dp(8)) })
        addView(body("share_receive_hint", 14f).apply { setPadding(0, 0, 0, dp(16)) })

        receive.text = Loc.t("phone", "share_enable")
        receive.typeface = uiBold
        receive.textSize = 15f
        receive.background = ResourcesCompat.getDrawable(resources, R.drawable.btn_outline, null)
        receive.setTextColor(0xFFFFB454.toInt())
        receive.stateListAnimator = null
        receive.setOnClickListener { Haptics.tap(it); onReceive?.invoke() }
        addView(receive, LayoutParams(LayoutParams.MATCH_PARENT, dp(56)))

        render()
    }

    fun render() {
        choose.isEnabled = !ShareSender.busy
        status.text = ShareSender.status.ifEmpty {
            Loc.t("phone", if (PhoneState.link) "share_ready" else "share_connect")
        }
        receive.isEnabled = !LinkService.running
        receive.visibility = if (LinkService.running) View.GONE else View.VISIBLE
    }
}
