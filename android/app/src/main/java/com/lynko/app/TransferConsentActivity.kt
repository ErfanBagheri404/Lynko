package com.lynko.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

/**
 * Consent screen for LocalSend-style transfer sessions — LocalSend receivePage
 * parity. TransferServer's prepare-upload handler (HTTP thread) launches this
 * with the session extras; the user's answer finishes the activity and
 * releases the latch.
 *
 * Launched from a Service context — a dialog would need a window token an
 * Activity owns, so this must be a full Activity (NEW_TASK flag required).
 */
class TransferConsentActivity : Activity() {

    companion object {
        const val EXTRA_ALIAS = "alias"
        const val EXTRA_COUNT = "count"
        const val EXTRA_SIZE = "size" // human label, e.g. "1.2 MB"
        const val EXTRA_NAMES = "names" // ArrayList<String>, per-file rows
        const val EXTRA_SIZES = "sizes" // ArrayList<Long>, per-file sizes
        private const val MAX_ROWS = 6
    }

    private val sep get() = 0xFF303034.toInt()
    private val dim get() = 0xFF8B8D90.toInt()
    private val fg get() = 0xFFE8E6E1.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This activity can be the very first thing the process launches (consent
        // arrives after a desktop-initiated send), so the locale bundle may not be
        // loaded yet — without this every label renders as "phone.transfer_title".
        Loc.init(this)
        val alias = intent.getStringExtra(EXTRA_ALIAS) ?: "PC"
        val count = intent.getIntExtra(EXTRA_COUNT, 1)
        val names = intent.getStringArrayListExtra(EXTRA_NAMES).orEmpty()
        val sizes = intent.getLongArrayExtra(EXTRA_SIZES) ?: longArrayOf()

        // Built in Kotlin, so the family must be applied explicitly or these labels
        // fall back to Roboto while the rest of the app uses the lynko family.
        val ui = ResourcesCompat.getFont(this, R.font.lynko_ui)
        val uiBold = ui?.let { Typeface.create(it, Typeface.BOLD) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF16181A.toInt())
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }

        fun label(txt: String, size: Float, color: Int, bold: Boolean = false, gravity: Int = Gravity.CENTER) =
            TextView(this).apply {
                text = txt
                textSize = size
                setTextColor(color)
                typeface = if (bold) uiBold else ui
                this.gravity = gravity
            }

        // Glyph only — no drawable assets shipped in the build (LocalSend parity).
        add(root, label("⇩", 40f, 0xFF6E7176.toInt()), bottom = dp(14))
        // Alias amber + bold, as the LocalSend receive page header.
        add(root, label(alias, 26f, 0xFFFFB454.toInt(), bold = true), bottom = dp(6))
        // "wants to send you {n} files" — singular variant when one file.
        val subtitle = if (count == 1) Loc.t("phone", "wants_to_send_one")
        else Loc.t("phone", "wants_to_send_n").replace("{n}", count.toString())
        add(root, label(subtitle, 15f, fg), bottom = dp(20))

        // Per-file list: name + readable size, max 6 rows then "+N more…".
        val shown = minOf(count, MAX_ROWS)
        for (i in 0 until shown) {
            if (i > 0) hairline(root, top = dp(12), bottom = dp(12))
            val name = names.getOrNull(i) ?: Loc.t("phone", "picker_file")
            val size = sizes.getOrNull(i)?.let { sz -> readableSize(sz) }.orEmpty()
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@TransferConsentActivity).apply {
                    text = name; textSize = 14f; typeface = ui; setTextColor(fg)
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@TransferConsentActivity).apply {
                    text = size; textSize = 13f; typeface = ui; setTextColor(dim)
                })
            })
        }
        if (count > shown) {
            hairline(root, top = dp(12), bottom = dp(8))
            add(
                root,
                label(Loc.t("phone", "more_files").replace("{n}", (count - shown).toString()), 13f, dim)
            )
        }

        root.addView(View(this).apply { setBackgroundColor(sep) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(22)
            })

        // Decline (ghost/outline) then Accept (amber fill).
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val decline = Button(this).apply {
            text = Loc.t("phone", "decline")
            typeface = uiBold; textSize = 15f
            setTextColor(fg)
            background = ResourcesCompat.getDrawable(resources, R.drawable.btn_outline, null)
            stateListAnimator = null
            setOnClickListener { v -> Haptics.tap(v); answer(false) }
        }
        val accept = Button(this).apply {
            text = Loc.t("phone", "accept")
            typeface = uiBold; textSize = 15f
            setTextColor(0xFF1A1006.toInt())
            background = ResourcesCompat.getDrawable(resources, R.drawable.btn_amber, null)
            stateListAnimator = null
            setOnClickListener { v -> Haptics.tap(v); answer(true) }
        }
        row.addView(decline, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(12) })
        row.addView(accept, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(row)
        setContentView(root)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun add(parent: LinearLayout, v: View, top: Int = 0, bottom: Int = 0) {
        parent.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = top; bottomMargin = bottom
        })
    }

    private fun hairline(parent: LinearLayout, top: Int, bottom: Int) {
        parent.addView(
            View(this).apply { setBackgroundColor(sep) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = top; bottomMargin = bottom
            }
        )
    }

    private fun answer(ok: Boolean) {
        intent.getStringExtra("sessionId")?.let { sid ->
            TransferConsentHub.pending.remove(sid)?.invoke(ok)
        }
        setResult(if (ok) RESULT_OK else RESULT_CANCELED)
        finish()
    }
}