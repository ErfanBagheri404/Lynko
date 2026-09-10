package com.lynko.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Consent screen for LocalSend-style transfer sessions. TransferServer's
 * prepare-upload handler (HTTP thread) launches this with the session extras;
 * the user's answer finishes the activity and releases the latch.
 *
 * Launched from a Service context — a dialog would need a window token an
 * Activity owns, so this must be a full Activity (NEW_TASK flag required).
 */
class TransferConsentActivity : Activity() {

    companion object {
        const val EXTRA_ALIAS = "alias"
        const val EXTRA_COUNT = "count"
        const val EXTRA_SIZE = "size" // human label, e.g. "1.2 MB"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val alias = intent.getStringExtra(EXTRA_ALIAS) ?: "PC"
        val count = intent.getIntExtra(EXTRA_COUNT, 1)
        val size = intent.getStringExtra(EXTRA_SIZE) ?: ""
        // sessionId for callback lookup
        val sid = intent.getStringExtra("sessionId")

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF16181A.toInt())
            setPadding(pad * 2, pad * 2, pad * 2, pad * 2)
        }
        val title = TextView(this).apply {
            text = "Incoming files"
            textSize = 22f
            setTextColor(0xFFFFB454.toInt())
        }
        val body = TextView(this).apply {
            text = "$alias wants to send $count file(s) $size"
            textSize = 16f
            setTextColor(0xFFECEDEE.toInt())
            setPadding(0, pad, 0, pad * 2)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val accept = Button(this).apply {
            text = "Accept"
            setOnClickListener { answer(true) }
        }
        val decline = Button(this).apply {
            text = "Decline"
            setOnClickListener { answer(false) }
        }
        row.addView(accept, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(decline, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(title)
        root.addView(body)
        root.addView(row)
        setContentView(root)
    }

    private fun answer(ok: Boolean) {
        intent.getStringExtra("sessionId")?.let { sid ->
            TransferConsentHub.pending.remove(sid)?.invoke(ok)
        }
        setResult(if (ok) RESULT_OK else RESULT_CANCELED)
        finish()
    }
}
