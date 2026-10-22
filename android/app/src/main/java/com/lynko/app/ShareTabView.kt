package com.lynko.app

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

/**
 * LocalSend parity for phone file sharing, one scrolling tab:
 *
 *   RECEIVE  alias + visual id + enable toggle
 *   SELECTION card (Files / Size) — shared by the picker rows below
 *   INFO     Device name / IP address / Port
 *   SEND     picker row (File / Media / Text / Paste) + target line
 *   PROGRESS live transfer panel (LocalSend progressPage rows)
 *   HISTORY  transfer history list
 *
 * Flat design law: hairline #303034 separators, 2px corners, amber #FFB454
 * active only, no shadows, every TextView on R.font.lynko_ui.
 */
class ShareTabView(context: Context) : LinearLayout(context) {

    // --- host callbacks (MainActivity owns SAF intents, dialogs, cancel) ---
    var onChoose: (() -> Unit)? = null
    var onReceive: (() -> Unit)? = null
    var onPickFile: (() -> Unit)? = null
    var onPickMedia: (() -> Unit)? = null
    var onPickText: (() -> Unit)? = null
    var onPaste: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onSend: (() -> Unit)? = null
    var onOpenFolder: (() -> Unit)? = null

    /** Files staged for send, not yet handed to ShareSender. */
    private val staged = mutableListOf<Pair<Uri, String>>()
    val stagedUris: List<Uri> get() = staged.map { it.first }
    fun hasStaged() = staged.isNotEmpty()
    /** MainActivity (SAF result / Text / Paste) drops picks here. */
    fun stage(uri: Uri, name: String) { staged.add(uri to name); render() }
    fun clearStaged() { staged.clear(); render() }

    /** Share/Settings screens are built in Kotlin (unlike the XML tabs), so
     *  every TextView needs the family applied explicitly — otherwise they
     *  silently fall back to Roboto and the app shows two different typefaces.
     *  R.font.lynko_ui is a family alias: it resolves to Space Grotesk normally
     *  and to YekanBakh under values-fa, so the Farsi tab gets its own glyphs. */
    private val ui: Typeface? = ResourcesCompat.getFont(context, R.font.lynko_ui)
    // The family carries a real 700 face (YekanBakh Bold under font-fa), so this
    // picks it instead of relying on framework fake-bold.
    private val uiBold: Typeface? = ui?.let { Typeface.create(it, Typeface.BOLD) }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    /** Isolate an LTR run (device/computer names, addresses) so an RTL
     *  paragraph can't reorder its digits/underscores around. */
    private fun ltr(s: String) = if (Loc.isRtl()) "\u202A$s\u202C" else s

    private val sepColor = 0xFF303034.toInt()
    private val fgColor = 0xFFE8E6E1.toInt()
    private val dimColor = 0xFF8B8D90.toInt()
    private val amber = 0xFFFFB454.toInt()

    // --- built views, refilled by render() ---
    private val visualId = TextView(context)
    private val receiveBtn = Button(context)
    private val selectionCard = LinearLayout(context)
    private val infoBox = LinearLayout(context)
    private val pickerRow = LinearLayout(context)
    private val targetCard = LinearLayout(context)
    private val targetAlias = TextView(context)
    private val targetState = TextView(context)
    private val targetChips = LinearLayout(context)
    private val targetHint = TextView(context)
    private val progressCard = LinearLayout(context)
    private val historyCard = LinearLayout(context)

    init {
        orientation = VERTICAL
        setPadding(dp(24), dp(20), dp(24), dp(24))

        buildHeader()
        // LocalSend-style target device card: who you're sending to + state.
        targetCard.orientation = VERTICAL
        add(this, targetCard, top = dp(8), bottom = dp(8))
        selectionCard.orientation = VERTICAL
        add(this, selectionCard)
        infoBox.orientation = VERTICAL
        add(this, infoBox, top = dp(8))
        buildSend()
        // progressCard / historyCard below; single hairline separates them.
        progressCard.orientation = VERTICAL
        add(this, progressCard)
        hairline(dp(12), dp(12))
        historyCard.orientation = VERTICAL
        add(this, historyCard)

        Transfers.onAnyChange = { render() }
        ShareSender.onChange = { render() }
        render()
    }

    // ------------------------------------------------------------------ utils

    private fun tv(
        text: CharSequence = "", size: Float = 14f, color: Int = fgColor,
        bold: Boolean = false, gravity: Int = Gravity.START,
    ) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = if (bold) uiBold else ui
        this.gravity = gravity
    }

    private fun amp(label: String, primary: Boolean, height: Int = 46, onClick: () -> Unit) =
        Button(context).apply {
            text = label
            typeface = uiBold
            textSize = 14f
            setTextColor(if (primary) 0xFF1A1006.toInt() else amber)
            background = ResourcesCompat.getDrawable(
                resources, if (primary) R.drawable.btn_amber else R.drawable.btn_outline, null
            )
            stateListAnimator = null
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(height))
            setOnClickListener { Haptics.tap(it); onClick() }
        }

    private fun add(parent: LinearLayout, v: View, top: Int = 0, bottom: Int = 0) {
        parent.addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = top; bottomMargin = bottom
        })
    }

    private fun hairline(top: Int = 0, bottom: Int = 0) {
        addView(
            View(context).apply { setBackgroundColor(sepColor) },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = top; bottomMargin = bottom
            }
        )
    }

    /** Hairline-separated flat row: label + trailing value. */
    private fun infoRow(parent: LinearLayout, label: String, value: String) {
        add(parent, tv("$label ${ltr(value)}", 13f, dimColor).apply {
            textDirection = View.TEXT_DIRECTION_LOCALE
        }, top = dp(6), bottom = dp(6))
    }

    // --------------------------------------------------------------- RECEIVE

    private fun buildHeader() {
        // Big alias — this device, so peers can identify it.
        add(this, tv(ltr(android.os.Build.MODEL.ifBlank { "Android" }), 28f, fgColor, bold = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            textDirection = View.TEXT_DIRECTION_LOCALE
        }, bottom = dp(2))
        // LocalSend visual id: last IPv4 octet, or Offline when the server is down.
        visualId.typeface = ui
        visualId.textSize = 13f
        visualId.setTextColor(dimColor)
        add(this, visualId, bottom = dp(8))
        // Amber accent underline under the alias block.
        addView(View(context).apply { setBackgroundColor(amber) }, LayoutParams(dp(36), dp(2)))

        receiveBtn.typeface = uiBold
        receiveBtn.textSize = 15f
        receiveBtn.setTextColor(amber)
        receiveBtn.background = ResourcesCompat.getDrawable(resources, R.drawable.btn_outline, null)
        receiveBtn.stateListAnimator = null
        receiveBtn.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(48))
        receiveBtn.setOnClickListener { Haptics.tap(it); onReceive?.invoke() }
        add(this, receiveBtn, top = dp(20), bottom = dp(6))
    }

    // ------------------------------------------------------------------ SEND

    private fun buildSend() {
        add(this, tv(Loc.t("phone", "send_title"), 18f, fgColor, bold = true), top = dp(4), bottom = dp(12))
        pickerRow.orientation = HORIZONTAL
        pickerRow.gravity = Gravity.CENTER_VERTICAL
        val pickers = listOf(
            "picker_file" to { onPickFile?.invoke() ?: onChoose?.invoke() },
            "picker_media" to { onPickMedia?.invoke() ?: onChoose?.invoke() },
            "picker_text" to { onPickText?.invoke() ?: onChoose?.invoke() },
            "picker_paste" to { onPaste?.invoke() ?: onChoose?.invoke() },
        )
        pickers.forEachIndexed { i, (key, cb) ->
            // None of the four is "selected" — they are four equal ways to
            // stage content. Filling the first amber read as a chosen tab.
            pickerRow.addView(
                amp(Loc.t("phone", key), primary = false, height = 46) { cb() },
                LayoutParams(0, dp(46), 1f).apply { if (i > 0) marginStart = dp(8) }
            )
        }
        add(this, pickerRow, bottom = dp(16))
    }

    // ----------------------------------------------------------------- render

    fun render() {
        val ip = localIp()
        visualId.text = if (LinkService.running && ip.isNotEmpty())
            "#" + ip.split(".").last() else Loc.t("phone", "general_offline")
        receiveBtn.text = Loc.t("phone", if (LinkService.running) "general_on" else "share_enable")
        receiveBtn.isEnabled = !LinkService.running
        receiveBtn.alpha = if (LinkService.running) 0.6f else 1f

        infoBox.removeAllViews()
        // No alias row here: the big header above already IS the device name,
        // and repeating it two lines later read as duplicated content.
        infoRow(infoBox, Loc.t("phone", "info_ip"), ip)
        infoRow(infoBox, Loc.t("phone", "info_port"), TransferServer.DEFAULT_PORT.toString())

        renderSelection()
        renderProgress()
        renderHistory()
    }

    private fun renderSelection() {
        selectionCard.removeAllViews()
        val picked = staged.isNotEmpty()
        pickerRow.visibility = if (picked) View.GONE else View.VISIBLE
        selectionCard.visibility = if (picked) View.VISIBLE else View.GONE
        renderTarget()
        if (!picked) return

        val totalSize = staged.sumOf { sizeOf(it) }
        add(selectionCard, tv(Loc.t("phone", "selection_title"), 15f, fgColor, bold = true), bottom = dp(4))
        add(selectionCard, tv(Loc.t("phone", "sel_files").replace("{files}", staged.size.toString()), 13f, dimColor))
        add(selectionCard, tv(Loc.t("phone", "sel_size").replace("{size}", readableSize(totalSize)), 13f, dimColor), bottom = dp(6))
        staged.take(4).forEach { add(selectionCard, tv(it.second, 13f, dimColor), bottom = dp(1)) }
        if (staged.size > 4)
            add(selectionCard, tv(more(staged.size - 4), 12f, dimColor), bottom = dp(4))

        // LocalSend's Selection card actions: Add more, Send now, clear.
        val acts = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        acts.addView(amp(Loc.t("phone", "general_add"), false, 44) { onPickFile?.invoke() ?: onChoose?.invoke() },
            LayoutParams(0, dp(44), 1f))
        acts.addView(amp(Loc.t("phone", "text_share_send"), true, 44) { onSend?.invoke() }.apply {
            isEnabled = PhoneState.link
            alpha = if (PhoneState.link) 1f else 0.4f
        }, LayoutParams(0, dp(44), 1.2f).apply { marginStart = dp(8) })
        acts.addView(amp("✕", false, 44) { staged.clear(); render() },
            LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) })
        add(selectionCard, acts, top = dp(8))
    }

    private fun more(n: Int) = Loc.t("phone", "more_files").replace("{n}", n.toString())

    /**
     * LocalSend's target device card (send screen): the alias you'd send to,
     * the #id/OS chips a peer sees, and a state line. When the link is down it
     * carries LocalSend's footer micro-copy — name the likely cause BEFORE the
     * user has to ask — plus a Troubleshooting dialog with real diagnostics
     * (LocalSend's Troubleshoot page, but showing the facts we actually have).
     */
    private fun renderTarget() {
        targetCard.removeAllViews()
        val linked = PhoneState.link
        val alias = PeerInfo.alias.ifBlank { Loc.t("phone", "peer_desktop") }
        add(targetCard, tv(Loc.t("phone", "share_target_label"), 12f, dimColor), bottom = dp(4))
        add(targetCard, tv(if (linked) ltr(alias) else Loc.t("phone", "share_target_none"), 17f, fgColor, bold = true).apply {
            textDirection = View.TEXT_DIRECTION_LOCALE
        }, bottom = dp(6))

        val stateKey = when {
            linked && UsbState.connected -> "share_state_usb"
            linked -> "share_state_wifi"
            else -> null
        }
        if (stateKey != null)
            add(targetCard, tv(Loc.t("phone", stateKey), 13f, 0xFF4ADE80.toInt()), bottom = dp(6))
        if (linked) {
            // Favorites (LocalSend ♡): keeps a recurring desktop at the top of
            // the picker. Outline chip, amber only while pinned.
            val fav = Favorites.isFavorite(context, alias)
            val favChip = chip(if (fav) "\u2665 " + Loc.t("phone", "favorite") else "\u2661 " + Loc.t("phone", "favorite")).apply {
                setTextColor(if (fav) amber else dimColor)
                isClickable = true; isFocusable = true
                setOnClickListener { v ->
                    Haptics.tap(v)
                    Favorites.toggle(context, alias)
                    render()
                }
            }
            targetCard.addView(favChip, LayoutParams(LayoutParams.WRAP_CONTENT, dp(28)).apply { bottomMargin = dp(8) })
        }

        if (!linked) {
            add(targetCard, tv(Loc.t("phone", "send_help"), 13f, dimColor), bottom = dp(8))
            add(targetCard, tv(Loc.t("phone", "share_troubleshoot"), 13f, amber).apply {
                setPadding(0, dp(4), 0, dp(4))
                isClickable = true; isFocusable = true
                setOnClickListener { Haptics.tap(it); showDiagnostics() }
            })
        }
    }

    /** Flat outline chip: 2px corner, hairline stroke, no fill (design law). */
    private fun chip(label: String) = TextView(context).apply {
        text = label; textSize = 12f; setTextColor(dimColor); typeface = ui
        setPadding(dp(10), 0, dp(10), 0)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(2).toFloat(); setColor(0x00000000); setStroke(dp(1), sepColor)
        }
    }

    /** Real troubleshooting facts: link, IP, port, service, USB. */
    private fun showDiagnostics() {
        val ip = localIp()
        fun yn(b: Boolean) = Loc.t("phone", if (b) "general_on" else "general_off")
        val msg = listOf(
            Loc.t("phone", "share_diag_link") + "  " + yn(PhoneState.link),
            Loc.t("phone", "info_ip") + "  " + ip.ifEmpty { "—" },
            Loc.t("phone", "info_port") + "  " + TransferServer.DEFAULT_PORT,
            Loc.t("phone", "share_diag_service") + "  " + if (LinkService.running)
                Loc.t("phone", "general_on") else Loc.t("phone", "not_running"),
            Loc.t("phone", "share_diag_usb") + "  " + yn(UsbState.connected),
        ).joinToString("\n")
        android.app.AlertDialog.Builder(context)
            .setTitle(Loc.t("phone", "share_troubleshoot"))
            .setMessage(msg)
            .setPositiveButton(Loc.t("phone", "general_close"), null)
            .show()
    }

    /** Staged row size: real content size when the Uri is file://, else the
     *  SAF row size (0 only if the provider hides it). */
    private fun sizeOf(p: Pair<Uri, String>): Long = try {
        if (p.first.scheme == "file") p.first.path?.let { java.io.File(it).length() } ?: 0L
        else context.contentResolver.query(p.first,
            arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        } ?: 0L
    } catch (_: Exception) { 0L }

    // --------------------------------------------------------------- PROGRESS

    private fun renderProgress() {
        val s = Transfers.current
        progressCard.removeAllViews()
        progressCard.visibility = if (s.visible) View.VISIBLE else View.GONE
        if (!s.visible) return

        val sending = s.direction == "send"
        add(progressCard, tv(Loc.t("phone", if (sending) "sending_files" else "receiving_files"), 18f, fgColor, bold = true))
        if (s.status == SessionStatus.waiting)
            add(progressCard, tv(Loc.t("phone", "waiting_response"), 13f, dimColor), top = dp(4))

        val total = s.sessionTotal
        val pct = if (total > 0) s.sessionWritten * 100 / total else 0
        val eta = formatEta(s.etaSeconds)
        add(progressCard,
            tv(Loc.t("phone", "total_progress").replace("{time}", if (eta.isEmpty()) "$pct%" else eta), 14f, fgColor, bold = true),
            top = dp(10), bottom = dp(4))
        add(progressCard, tv(
            Loc.t("phone", "files_count").replace("{curr}", s.finishedCount.toString()).replace("{n}", s.files.size.toString()),
            13f, dimColor))
        add(progressCard, tv(
            Loc.t("phone", "size_count").replace("{curr}", readableSize(s.sessionWritten)).replace("{n}", readableSize(total)),
            13f, dimColor))
        add(progressCard, tv(Loc.t("phone", "speed").replace("{speed}", readableSize(s.speedBps)), 13f, dimColor), bottom = dp(6))

        // Per-file rows: name + Queue / Sending / Done / Error.
        s.files.forEach { f ->
            val label = when (f.status) {
                FileStatus.queue -> Loc.t("phone", "st_queue")
                FileStatus.skipped -> Loc.t("phone", "st_skipped")
                FileStatus.failed -> Loc.t("phone", "st_error")
                FileStatus.finished -> Loc.t("phone", "st_done")
                FileStatus.sending -> Loc.t("phone", "st_sending") +
                    " ${readableSize(f.written)} / ${readableSize(f.size)}"
            }
            progressCard.addView(
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(tv(f.name, 14f, fgColor).apply {
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
                        layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                    })
                    // LocalSend's per-file skip: only while the file can still
                    // stop (queued or mid-flight). Rows past that just show state.
                    if (sending && (f.status == FileStatus.queue || f.status == FileStatus.sending)) {
                        addView(tv("✕", 13f, amber).apply {
                            setPadding(dp(10), dp(6), dp(2), dp(6))
                            isClickable = true; isFocusable = true
                            contentDescription = Loc.t("phone", "general_cancel")
                            setOnClickListener { v -> Haptics.tap(v); ShareSender.skipFile(f.id) }
                        })
                    }
                    addView(tv(label, 13f, when (f.status) {
                        FileStatus.failed -> amber
                        FileStatus.skipped -> amber
                        else -> dimColor
                    }))
                },
                LayoutParams(LayoutParams.MATCH_PARENT, dp(36))
            )
        }

        val endKey = when (s.status) {
            SessionStatus.finished -> "general_finished"
            SessionStatus.finishedWithErrors -> "finished_error"
            SessionStatus.declined -> "rejected"
            SessionStatus.recipientBusy -> "recipient_busy"
            SessionStatus.tooManyAttempts -> "too_many_attempts"
            SessionStatus.canceledBySender -> "canceled_sender"
            SessionStatus.canceledByReceiver -> "canceled_receiver"
            else -> null
        }
        if (endKey != null) {
            add(progressCard, tv(Loc.t("phone", endKey), 15f, fgColor, bold = true), top = dp(10), bottom = dp(8))
            add(progressCard, amp(Loc.t("phone", "general_close"), true) { Transfers.dismiss() })
        } else {
            add(progressCard, amp(Loc.t("phone", "general_cancel"), false) {
                if (sending) onCancel?.invoke() else Transfers.dismiss()
            }, top = dp(12))
        }
    }

    // ---------------------------------------------------------------- HISTORY

    private fun renderHistory() {
        val ctx = context
        historyCard.removeAllViews()
        val arr = HistoryStore.list(ctx)
        add(historyCard, tv(Loc.t("phone", "history"), 15f, fgColor, bold = true), bottom = dp(4))
        if (arr.length() == 0) {
            add(historyCard, tv(Loc.t("phone", "history_empty"), 13f, dimColor), top = dp(4), bottom = dp(8))
        } else {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                historyCard.addView(
                    View(context).apply { setBackgroundColor(sepColor) },
                    LayoutParams(LayoutParams.MATCH_PARENT, dp(1))
                )
                val t = o.optLong("time", 0L)
                val row = LinearLayout(ctx).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        tv("${o.optString("name")} · ${readableSize(o.optLong("size", 0L))}", 14f, fgColor).apply {
                            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
                            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                        }
                    )
                    addView(tv(
                        android.text.format.DateFormat.getDateFormat(ctx).format(java.util.Date(t)),
                        12f, dimColor))
                }
                historyCard.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)))
            }
            add(historyCard, amp(Loc.t("phone", "open_folder"), false, 44) { onOpenFolder?.invoke() }, top = dp(10))
            add(historyCard, amp(Loc.t("phone", "delete_history"), false, 44) {
                HistoryStore.clear(ctx); render()
            }, top = dp(8), bottom = dp(4))
        }
    }

    /** This device's LAN IPv4 (first non-loopback site-local). */
    private fun localIp(): String = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.address.size == 4 }
            ?.hostAddress ?: ""
    } catch (_: Exception) { "" }
}