package com.lynko.app

import android.Manifest
import android.content.Context
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.app.ActivityCompat

/**
 * Home for connection and capture, Share for files, Settings for preferences.
 */
class MainActivity : AppCompatActivity() {

    companion object {

        /** Live share tab, so a `hello` from the desktop can repaint it. */
        @JvmStatic var shareTab: ShareTabView? = null

        /** Repaint the share tab (desktop name arrived or changed). Runs on
         *  the UI thread via View.post — this is a companion, not an Activity. */
        @JvmStatic fun refreshSharePeers() {
            val tab = shareTab ?: return
            tab.post { tab.render() }
        }
    }

    // --- tab plumbing ---
    private lateinit var settingsContainer: ScrollView
    private lateinit var navSettings: LinearLayout
    private lateinit var navSettingsIcon: android.widget.ImageView
    private lateinit var navSettingsLabel: TextView
    private lateinit var homeContainer: ScrollView
    private lateinit var screenContainer: FrameLayout
    private lateinit var screenTab: ShareTabView
    private lateinit var navHome: LinearLayout
    private lateinit var navScreen: LinearLayout
    private lateinit var navHomeIcon: android.widget.ImageView
    private lateinit var navScreenIcon: android.widget.ImageView
    private lateinit var navHomeLabel: TextView
    private lateinit var navScreenLabel: TextView

    // --- home tab ---
    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var startBtn: Button
    private lateinit var pinText: TextView
    private lateinit var permPrepare: LinearLayout

    private var sharedUris: List<android.net.Uri>? = null
    private var pendingMirror = false
    private var currentTab = 0  // 0=home, 1=screen
    private var feedback: com.google.android.material.snackbar.Snackbar? = null

    // --- first-run prep screen ("Four things, once.") ---
    private val pal by lazy { Pal(this) }
    private var prepView: PrepView? = null
    private var prepActive = false

    private fun showFeedback(message: String) {
        feedback?.dismiss()
        val bar = com.google.android.material.snackbar.Snackbar.make(
            findViewById(android.R.id.content), message,
            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
        ).setAnchorView(findViewById<View>(R.id.bottomNav))
            .setBackgroundTint(pal.panel)
            .setTextColor(pal.ink0)
        bar.view.elevation = 0f
        bar.view.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(pal.panel)
            setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1), pal.faint)
            cornerRadius = 0f
        }
        bar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).maxLines = 4
        feedback = bar
        bar.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeMode.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Loc.init(this)

        // Battery-optimization exemption is not requested here: the prep screen
        // owns that gate (one row, one dialog). Asking twice = two prompts.

        settingsContainer = findViewById(R.id.settingsContainer)
        navSettings = findViewById(R.id.navSettings)
        navSettingsIcon = findViewById(R.id.navSettingsIcon)
        navSettingsLabel = findViewById(R.id.navSettingsLabel)
        navSettings.setOnClickListener { Haptics.tap(it); switchTab(2) }
        homeContainer = findViewById(R.id.homeContainer)
        screenContainer = findViewById(R.id.screenContainer)
        navHome = findViewById(R.id.navHome)
        navScreen = findViewById(R.id.navScreen)
        navHomeIcon = findViewById(R.id.navHomeIcon)
        navScreenIcon = findViewById(R.id.navScreenIcon)
        navHomeLabel = findViewById(R.id.navHomeLabel)
        navScreenLabel = findViewById(R.id.navScreenLabel)

        statusDot = findViewById(R.id.statusDot)
        statusTitle = findViewById(R.id.statusTitle)
        statusSub = findViewById(R.id.statusSub)
        startBtn = findViewById(R.id.startBtn)
        pinText = findViewById(R.id.pinText)
        DevPermHeal.heal(this)

        // Share tab lives in the same container the old Screen tab used.
        screenTab = ShareTabView(this)
        screenContainer.addView(
            screenTab,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        shareTab = screenTab
        screenTab.onChoose = { pickShareFile(false) }
        screenTab.onReceive = { startLinkService() }
        screenTab.onPickFile = { pickShareFile(false) }
        screenTab.onPickMedia = { pickShareFile(true) }
        screenTab.onPickText = { showTextDialog() }
        screenTab.onPaste = { sendClipboardText() }
        screenTab.onSend = { sendStaged() }
        screenTab.onCancel = { ShareSender.cancel() }
        screenTab.onOpenFolder = {
            try {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(Environment.DIRECTORY_DOWNLOADS),
                        "resource/folder")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) { showFeedback(Loc.t("phone", "general_offline")) }
        }
        ShareSender.onChange = { screenTab.render() }

        // Bottom nav: switch tabs.
        navHome.setOnClickListener { v ->
            Haptics.tap(v)
            switchTab(0)
        }
        navScreen.setOnClickListener { v ->
            Haptics.tap(v)
            switchTab(1)
        }

        // The card is driven by PhoneState — the same source the desktop
        // reads — so the two surfaces can never disagree.
        PhoneState.onChange = {
            runOnUiThread {
                renderState()
                screenTab.render()
            }
        }
        renderState()
        findViewById<TextView>(R.id.settingsVersion).text =
            "LYNKO  " + (packageManager.getPackageInfo(packageName, 0).versionName ?: "")
        findViewById<View>(R.id.audioPermission).setOnClickListener {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                showFeedback(getString(R.string.audio_permission_granted))
            } else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 102)
        }
        findViewById<View>(R.id.updateRow).setOnClickListener { v ->
            Haptics.tap(v)
            checkUpdates(manual = true)
        }
        Update.onChange = { runOnUiThread { renderUpdateRow() } }
        renderUpdateRow()
        // Launch check stays silent unless GitHub has a version this build has
        // not already shown: an update row is enough for the rest. A check that
        // finished before a recreation replays from Update instead of refetching.
        if (Update.checked && !Update.failed) promptUpdate(Update.latest)
        else Update.check(this) { r -> promptUpdate(r) }
        findViewById<View>(R.id.privacyPolicy).setOnClickListener {
            // setMessage() does not scroll: the long policy got silently cut
            // off mid-sentence, and Myket requires the privacy text to be
            // readable in-app. Use a scrollable body instead.
            val pad = (20 * resources.displayMetrics.density).toInt()
            val body = TextView(this).apply {
                text = getString(R.string.privacy_policy_body)
                setTextColor(pal.ink0)
                textSize = 14f
                setLineSpacing(0f, 1.15f)
                setPadding(pad, pad / 2, pad, pad / 2)
            }
            val scroller = ScrollView(this).apply { addView(body) }
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.privacy_policy_title)
                .setView(scroller)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        val langButtons = mapOf(
            "en" to findViewById<TextView>(R.id.langEn),
            "fa" to findViewById<TextView>(R.id.langFa),
            "zh-Hans" to findViewById<TextView>(R.id.langZh),
        )
        // No stored override means follow the phone; AppCompat keeps the choice.
        val activeTag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            .ifEmpty { resources.configuration.locales[0].toLanguageTag() }
        val activeLang = when {
            activeTag.startsWith("fa") -> "fa"
            activeTag.startsWith("zh") -> "zh-Hans"
            else -> "en"
        }
        langButtons.forEach { (tag, btn) ->
            btn.setTextColor(if (tag == activeLang) pal.amber else pal.ink1)
            btn.setOnClickListener {
                Haptics.tap(it)
                if (tag != activeLang) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                }
            }
        }
        val motionToggle = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.motionToggle)
        motionToggle.isChecked = getPreferences(Context.MODE_PRIVATE).getBoolean("motion", true)
        motionToggle.setOnCheckedChangeListener { _, enabled ->
            getPreferences(Context.MODE_PRIVATE).edit().putBoolean("motion", enabled).apply()
            showFeedback(getString(if (enabled) R.string.motion_on else R.string.motion_off))
            if (!enabled) listOf(homeContainer, screenContainer, settingsContainer).forEach {
                it.animate().cancel(); it.alpha = 1f; it.translationY = 0f
            }
        }
        switchTab(savedInstanceState?.getInt("tab", 0) ?: 0, false)
        val root = findViewById<View>(R.id.appRoot)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(root)

        startBtn.setOnClickListener { v ->
            Haptics.tap(v)
            if (LinkService.running) {
                stopLink()
            } else {
                beginStart()
            }
        }

        permPrepare = findViewById(R.id.permPrepare)
        permPrepare.setOnClickListener { v -> Haptics.tap(v); showPrep(fromSettings = true) }

        // First run: the prep screen replaces the tabs until "Continue".
        // Re-show it after a theme change recreates the activity mid-prep.
        if (!getPreferences(Context.MODE_PRIVATE).getBoolean("lynko_prep_done", false)) {
            showPrep(fromSettings = false)
        } else if (savedInstanceState?.getBoolean("prep_open") == true) {
            showPrep(fromSettings = true)
        }
    }

    /** Overlay the "Four things, once." screen on appRoot (covers the nav). */
    private fun showPrep(fromSettings: Boolean) {
        if (prepActive) return
        prepActive = true
        val v = PrepView(this, !fromSettings)
        prepView = v
        v.onContinue = {
            getPreferences(Context.MODE_PRIVATE).edit().putBoolean("lynko_prep_done", true).apply()
            dismissPrep(if (fromSettings) 2 else currentTab)
        }
        v.onBack = { dismissPrep(2) }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(v) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<android.view.ViewGroup>(android.R.id.content).addView(
            v, android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun dismissPrep(tab: Int) {
        prepActive = false
        prepView?.let { findViewById<android.view.ViewGroup>(android.R.id.content).removeView(it) }
        prepView = null
        switchTab(tab, false)
        renderState()
    }

    private fun switchTab(tab: Int, animate: Boolean = true) {
        val selected = tab.coerceIn(0, 2)
        val changed = currentTab != selected
        currentTab = selected
        val pages = listOf(homeContainer, screenContainer, settingsContainer)
        val navs = listOf(navHome, navScreen, navSettings)
        val icons = listOf(navHomeIcon, navScreenIcon, navSettingsIcon)
        val labels = listOf(navHomeLabel, navScreenLabel, navSettingsLabel)
        pages.forEachIndexed { i, page ->
            page.animate().cancel(); page.alpha = 1f; page.translationY = 0f
            val active = i == selected
            page.visibility = if (active) View.VISIBLE else View.GONE
            navs[i].isSelected = active
            icons[i].setColorFilter(if (active) pal.amber else pal.ink1)
            labels[i].setTextColor(if (active) pal.amber else pal.ink1)
            labels[i].paint.isFakeBoldText = active
        }
        if (changed && animate && motionEnabled()) {
            val incoming: View = pages[selected]
            incoming.alpha = 0f
            incoming.translationY = 8f * resources.displayMetrics.density
            incoming.animate().alpha(1f).translationY(0f).setDuration(180L)
                .setInterpolator(android.view.animation.PathInterpolator(0.23f, 1f, 0.32f, 1f)).start()
        }
    }

    /** Deep-link into Accessibility settings with Lynko pre-highlighted when possible. */
    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, Loc.t("phone", "a11y_toast"), Toast.LENGTH_LONG).show()
        }
    }

    private fun motionEnabled() = android.animation.ValueAnimator.areAnimatorsEnabled() &&
        getPreferences(Context.MODE_PRIVATE).getBoolean("motion", true)

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", currentTab)
        outState.putBoolean("prep_open", prepActive)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        super.onPause()
        ShareSender.onChange = null
        LynkoAccessibilityService.onChange = null
    }

    override fun onResume() {
        super.onResume()
        ShareSender.onChange = { screenTab.render() }
        screenTab.render()
        readShareIntent()
        if (intent.getBooleanExtra("request_mirror_consent", false)) {
            intent.removeExtra("request_mirror_consent")
            pendingMirror = true
            ScreenPermission.request(this)
        }
        prepView?.onScreenResume()
        switchTab(currentTab, false)
        renderState()
        screenTab.render()
    }

    override fun onDestroy() {
        PhoneState.onChange = null
        feedback?.dismiss()
        feedback = null
        super.onDestroy()
    }

    private fun beginStart() {
        // Accessibility is REQUIRED for taps/swipes/typing. Check the Settings
        // string only — MIUI unbinds the service whenever the app is
        // backgrounded, so the live binding is null on every relaunch while
        // the grant is still intact. Gating on it sent the user into settings
        // to toggle off→on on every single launch; the system rebinds on its
        // own within seconds of the process starting.
        if (!LynkoAccessibilityService.operational(this)) {
            Toast.makeText(this, Loc.t("phone", "perm_a11y_needed"), Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }
        // Notification permission first (Android 13+)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }
        // MediaProjection consent must precede the FGS of that type (API 34+)
        statusTitle.text = Loc.t("phone", "consent_wait")
        statusSub.text = Loc.t("phone", "consent_wait_sub")
        ScreenPermission.request(this)
    }

    private fun startLinkService() {
        val svcIntent = Intent(this, LinkService::class.java).putExtra("start_mirror", pendingMirror)
        pendingMirror = false
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svcIntent)
        else startService(svcIntent)
        renderState()
        // The service pokes PhoneState.onChange the instant running=true and
        // the ports are listening — that repaint is the authoritative one.
        // This one repaints as "waiting to start" via the service-alive path
        // once onCreate completes, covering the sub-second gap.
        startBtn.postDelayed({ renderState() }, 1200)
    }

    private fun stopLink() {
        // Clear BEFORE stopService: teardown lands on the main looper a beat
        // later, and renderState() here would still see running=true (stale
        // "Waiting" + a Stop button that's already been tapped).
        LinkService.running = false
        stopService(Intent(this, LinkService::class.java))
        renderState()
        showFeedback(Loc.t("phone", "stopped_toast"))
    }

    private fun renderState() {
        when {
            // Connected desktop: mirroring, or linked and ready.
            PhoneState.link && PhoneState.mirror -> {
                statusDot.setBackgroundResource(R.drawable.dot_live)
                statusTitle.text = if (PhoneState.locked)
                    Loc.t("phone", "status_locked") else Loc.t("phone", "status_mirroring")
                statusSub.text = Loc.t("phone", "status_mirroring_sub")
                startBtn.text = Loc.t("phone", "stop")
                startBtn.isEnabled = true
                pinText.visibility = View.GONE
            }
            PhoneState.link -> {
                statusDot.setBackgroundResource(R.drawable.dot_live)
                statusTitle.text = Loc.t("phone", "status_connected")
                statusSub.text = Loc.t("phone", "status_connected_sub")
                startBtn.text = Loc.t("phone", "stop")
                startBtn.isEnabled = true
                pinText.visibility = View.GONE
            }
            // Service up, nobody attached yet: advertise + show the PIN.
            LinkService.running -> {
                val pin = LinkService.ensurePin(this) // lazy-safe: also fills it if onCreate hasn't run yet
                statusDot.setBackgroundResource(R.drawable.dot_idle)
                statusTitle.text = Loc.t("phone", "status_waiting")
                statusSub.text = Loc.t("phone", "status_advertising").replace("{model}", android.os.Build.MODEL).replace("{pin}", pin)
                startBtn.text = Loc.t("phone", "stop")
                startBtn.isEnabled = true
                pinText.visibility = View.VISIBLE
                pinText.text = Loc.t("phone", "pin").replace("{pin}", pin)
            }
            else -> {
                statusDot.setBackgroundResource(R.drawable.dot_idle)
                statusTitle.text = Loc.t("phone", "status_not_running")
                statusSub.text = Loc.t("phone", "status_subtitle_idle")
                startBtn.text = Loc.t("phone", "start")
                startBtn.isEnabled = true
                pinText.visibility = View.GONE
            }
        }
    }


    /** Deep-link to MIUI's battery saver screen for this app. */
    private fun openBatterySettings() {
        try {
            startActivity(Intent("miui.intent.action.OP_APP_DETAIL").apply {
                putExtra("miui.intent.extra.APP_PKG", packageName)
            })
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, Loc.t("phone", "battery_toast"), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun pickShareFile(media: Boolean) {
        // Media restricts the SAF sheet to images+video; File stays */*.
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            if (media) putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "image/*", "video/*"
            ))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, 9100)
    }

    /** Hand the tab's staged selection to the peer chooser, then send. */
    private fun sendStaged() {
        val tab = shareTab ?: return
        if (!tab.hasStaged()) return
        if (!PhoneState.link) { showFeedback(Loc.t("phone", "share_connect")); return }
        val uris = tab.stagedUris
        chooseSharePeer(uris, tab)
    }

    /** Settings row: same wording as the desktop card. */
    private fun renderUpdateRow() {
        val sub = findViewById<TextView>(R.id.updateSub)
        val cur = Update.current(this)
        sub.text = when {
            Update.busy -> getString(R.string.update_checking)
            Update.failed -> getString(R.string.update_failed)
            Update.latest != null -> getString(R.string.update_available) + "  ·  v" + Update.latest!!.version
            Update.checked -> getString(R.string.update_uptodate) + "  ·  v" + cur
            else -> getString(R.string.update_desc)
        }
    }

    private fun checkUpdates(manual: Boolean) {
        if (Update.busy) return
        Update.check(this) { r ->
            if (r != null) {
                showUpdateDialog(r)
            } else if (manual) {
                showFeedback(getString(if (Update.failed) R.string.update_failed else R.string.update_uptodate))
            }
        }
    }

    private fun seen(v: String) {
        getPreferences(Context.MODE_PRIVATE).edit().putString("lynko_seen_version", v).apply()
    }

    /** A failed download must not be remembered as "already seen". */
    private fun clearSeen() {
        getPreferences(Context.MODE_PRIVATE).edit().putString("lynko_seen_version", "").apply()
    }

    private fun promptUpdate(r: Update.Release?) {
        val seen = getPreferences(Context.MODE_PRIVATE).getString("lynko_seen_version", "")
        if (r != null && r.version != seen) showUpdateDialog(r)
    }

    private fun showUpdateDialog(r: Update.Release) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val body = TextView(this).apply {
            text = r.notes.replace("**", "").replace("`", "").ifBlank {
                if (r.published.isNotEmpty()) getString(R.string.update_published) + " " + r.published else ""
            }
            setTextColor(pal.ink0)
            textSize = 13.5f
            setLineSpacing(0f, 1.2f)
            setPadding(pad, pad / 2, pad, pad / 2)
            android.text.util.Linkify.addLinks(this, android.text.util.Linkify.WEB_URLS)
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            setLinkTextColor(pal.amber)
        }
        val head = TextView(this).apply {
            text = getString(R.string.update_whatsnew).uppercase()
            setTextColor(pal.ink2)
            textSize = 10f
            letterSpacing = 0.2f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(pad, pad, pad, 0)
        }
        val status = TextView(this).apply {
            visibility = View.GONE
            setTextColor(pal.ink1)
            textSize = 12f
            setPadding(pad, 0, pad, pad / 2)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(head)
            addView(ScrollView(this@MainActivity).apply { addView(body) })
            addView(status)
        }
        // Keep the dialog open across the download so the user sees progress and
        // a failure instead of a silent no-op after the button dismisses it.
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Lynko_Dialog)
            .setTitle(getString(R.string.update_available) + "  ·  v" + r.version)
            .setView(box)
            .setPositiveButton(R.string.update_download, null)
            .setNegativeButton(R.string.update_later) { _, _ -> seen(r.version) }
            .setOnCancelListener { seen(r.version) }
            .create()
        dialog.show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            seen(r.version)
            if (r.apk.isEmpty()) {
                dialog.dismiss()
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.page)))
                return@setOnClickListener
            }
            if (!Update.download(
                    this, r,
                    progress = { pct ->
                        status.visibility = View.VISIBLE
                        status.text = getString(R.string.update_downloading) + " $pct%"
                    },
                    fail = { e ->
                        clearSeen()
                        dialog.dismiss()
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Lynko_Dialog)
                            .setTitle(R.string.update_install_failed)
                            .setMessage(e)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    },
                )) {
                showFeedback(getString(R.string.update_downloading))
                return@setOnClickListener
            }
            status.visibility = View.VISIBLE
            status.text = getString(R.string.update_downloading)
        }
    }

    /** Text picker dialog — the typed text lands as a cache .txt file. */
    private fun showTextDialog() {
        val input = android.widget.EditText(this)
        input.hint = Loc.t("phone", "text_share_hint")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(Loc.t("phone", "text_share_title"))
            .setView(input)
            .setPositiveButton(Loc.t("phone", "text_share_send")) { _, _ ->
                val text = input.text.toString()
                if (text.isBlank()) return@setPositiveButton
                sharePlainText(text, "lynko-text-${System.currentTimeMillis() / 1000}.txt")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Paste picker: clipboard text goes out as a .txt file. */
    private fun sendClipboardText() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) showFeedback(Loc.t("phone", "history_empty"))
        else sharePlainText(text, "lynko-clipboard-${System.currentTimeMillis() / 1000}.txt")
    }

    private fun sharePlainText(text: String, name: String) {
        // file:// URI: ShareSender.stage() opens it via ContentResolver, which
        // handles the scheme directly — no FileProvider round-trip needed.
        val f = java.io.File(cacheDir, name)
        f.writeText(text)
        shareTab?.stage(android.net.Uri.fromFile(f), name)
        sendStaged()
    }

    private fun android.content.Intent?.clipList(): List<android.net.Uri> {
        this ?: return emptyList()
        val out = mutableListOf<android.net.Uri>()
        clipData?.let { cd ->
            for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let { out.add(it) }
        }
        data?.let { if (!out.contains(it)) out.add(it) }
        return out
    }

    private fun queryName(uri: android.net.Uri): String? = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) { null }

    /** Peers with pinned (favorite) destinations first, so the picker's index
     *  order matches LocalSend's. Returns the peer and the label to show. */
    private fun orderedPeers(peers: List<org.java_websocket.WebSocket>): List<Pair<org.java_websocket.WebSocket, String>> {
        val labeled = peers.map { p ->
            p to (PeerInfo.alias.ifBlank { p.remoteSocketAddress?.address?.hostAddress ?: Loc.t("phone", "peer_desktop") })
        }
        return labeled.sortedByDescending { Favorites.isFavorite(this, it.second) }
    }

    private fun chooseSharePeer(uris: List<android.net.Uri>, tab: ShareTabView? = null) {
        val peers = LinkService.instance?.sharePeers().orEmpty()
        if (peers.isEmpty()) showFeedback(Loc.t("phone", "share_connect"))
        else androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(Loc.t("phone", "share_destination"))
            // The desktop's computer name when it announced itself; the raw
            // socket IP only as a fallback (older desktop, or before hello).
            // Favorites first — LocalSend's ♡ pin, so a recurring PC isn't
            // buried behind however mDNS ordered the list.
            .setItems(orderedPeers(peers).map { it.second }.toTypedArray()) { _, i ->
                val host = orderedPeers(peers)[i].first.remoteSocketAddress?.address?.hostAddress ?: return@setItems
                val names = tab?.let { t ->
                    t.stagedUris.map { u -> u.toString() to (queryName(u) ?: "file.bin") }.toMap()
                } ?: emptyMap()
                ShareSender.send(applicationContext, uris, host, 53317, PinStore.current(this), names)
                sharedUris = null
                tab?.clearStaged()
                tab?.render()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    @Suppress("DEPRECATION")
    private fun readShareIntent() {
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        // SEND carries one URI, SEND_MULTIPLE a list. Accept both, and keep the
        // order the picker gave us so the desktop sees photos in the order the
        // user selected them.
        val uris: List<android.net.Uri> = if (action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM).orEmpty()
        } else {
            listOfNotNull(intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
        }.filter { it.scheme == "content" }
        intent.action = null
        intent.removeExtra(Intent.EXTRA_STREAM)
        if (uris.isEmpty()) return
        sharedUris = uris
        switchTab(1, false)
        if (PhoneState.link) chooseSharePeer(uris)
        else showFeedback(Loc.t("phone", "share_connect"))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readShareIntent()
        if (intent.getBooleanExtra("request_mirror_consent", false)) {
            intent.removeExtra("request_mirror_consent")
            pendingMirror = true
            ScreenPermission.request(this)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 9100 && resultCode == RESULT_OK) {
            val tab = shareTab
            if (tab != null) {
                // Stage into the Selection card; the Add button opens it again.
                val uris = data.clipList()
                if (uris.isNotEmpty()) {
                    // Read display names so File/Media pickers can list them.
                    uris.forEach { u ->
                        val n = queryName(u) ?: "file.bin"
                        tab.stage(u, n)
                    }
                    tab.render()
                    if (PhoneState.link) sendStaged()
                    else showFeedback(Loc.t("phone", "share_connect"))
                }
            }
            return
        }
        ScreenPermission.onResult(requestCode, resultCode, data)
        if (requestCode == 9001) {
            if (ScreenPermission.isGranted) startLinkService()
            else {
                pendingMirror = false
                showFeedback(Loc.t("phone", "perm_denied_sub"))
                statusTitle.text = Loc.t("phone", "perm_denied")
                statusSub.text = Loc.t("phone", "perm_denied_sub")
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 102) showFeedback(getString(
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) R.string.audio_permission_granted else R.string.audio_permission_denied))
        if (requestCode == 101 &&
            (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED)
        ) {
            Toast.makeText(this, Loc.t("phone", "notif_denied_toast"), Toast.LENGTH_LONG).show()
        }
    }
}
