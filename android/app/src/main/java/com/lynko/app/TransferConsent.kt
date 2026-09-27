package com.lynko.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.ContextThemeWrapper

/**
 * Consent dialog for LocalSend-style transfer sessions. Shown from the
 * TransferServer's prepare-upload handler (HTTP thread); the dialog answer
 * releases the CountDownLatch so the sender proceeds or gets declined.
 */
object TransferConsent {

    /** Called from the HTTP thread. Must run dialog code on the main thread. */
    fun show(context: Context, session: TransferServer.TransferSession, answer: (Boolean) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.post {
            val ctx = if (context is Activity) context
            else ContextThemeWrapper(context, R.style.Theme_Lynko)
            val sizeKb = session.files.sumOf { it.size } / 1024
            val sizeLabel = if (sizeKb > 1024) "${sizeKb / 1024} MB" else "$sizeKb KB"
            AlertDialog.Builder(ctx)
                .setTitle(Loc.t("phone", "transfer_title"))
                .setMessage(Loc.t("phone", "transfer_body")
                    .replace("{from}", session.senderAlias)
                    .replace("{n}", session.files.size.toString())
                    .replace("{size}", sizeLabel))
                .setPositiveButton(Loc.t("phone", "accept")) { _, _ -> answer(true) }
    .setNegativeButton(Loc.t("phone", "decline")) { _, _ -> answer(false) }
                .setOnCancelListener { answer(false) }
                .show()
        }
    }
}
