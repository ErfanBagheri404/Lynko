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
    private lateinit var permScreen: LinearLayout
    private lateinit var permScreenIcon: TextView
    private lateinit var permScreenText: TextView
    private lateinit var permA11y: LinearLayout
    private lateinit var permA11yIcon: TextView
    private lateinit var permA11yText: TextView
    private lateinit var permBattery: LinearLayout
    private lateinit var permBatteryIcon: TextView
    private lateinit var permBatteryText: TextView
    private lateinit var permNotif: LinearLayout
    private lateinit var permNotifIcon: TextView
    private lateinit var permNotifText: TextView

    private var sharedUris: List<android.net.Uri>? = null
    private var pendingMirror = false
    private var currentTab = 0  // 0=home, 1=screen
    private var feedback: com.google.android.material.snackbar.Snackbar? = null

    private fun showFeedback(message: String) {
        feedback?.dismiss()
        val bar = com.google.android.material.snackbar.Snackbar.make(
            findViewById(android.R.id.content), message,
            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
        ).setAnchorView(findViewById<View>(R.id.bottomNav))
            .setBackgroundTint(0xFF1A1B1E.toInt())
            .setTextColor(0xFFE8E6E1.toInt())
        bar.view.elevation = 0f
        bar.view.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF1A1B1E.toInt())
            setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1), 0xFF555658.toInt())
            cornerRadius = 0f
        }
        bar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).maxLines = 4
        feedback = bar
        bar.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Loc.init(this)

        // Request battery optimization exemption on first launch — without
        // this, MIUI kills the foreground service when VPN toggles.
        if (Build.VERSION.SDK_INT >= 23) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$packageName")))
                } catch (_: Exception) { }
            }
        }

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

        permScreen = findViewById(R.id.permScreen)
        permScreenIcon = findViewById(R.id.permScreenIcon)
        permScreenText = findViewById(R.id.permScreenText)
        permA11y = findViewById(R.id.permA11y)
        permA11yIcon = findViewById(R.id.permA11yIcon)
        permA11yText = findViewById(R.id.permA11yText)
        permBattery = findViewById(R.id.permBattery)
        permBatteryIcon = findViewById(R.id.permBatteryIcon)
        permBatteryText = findViewById(R.id.permBatteryText)
        permNotif = findViewById(R.id.permNotif)
        permNotifIcon = findViewById(R.id.permNotifIcon)
        permNotifText = findViewById(R.id.permNotifText)

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
        renderPerms()
        findViewById<Button>(R.id.audioPermission).setOnClickListener {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                showFeedback(getString(R.string.audio_permission_granted))
            } else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 102)
        }
        findViewById<Button>(R.id.privacyPolicy).setOnClickListener {
            // setMessage() does not scroll: the long policy got silently cut
            // off mid-sentence, and Myket requires the privacy text to be
            // readable in-app. Use a scrollable body instead.
            val pad = (20 * resources.displayMetrics.density).toInt()
            val body = TextView(this).apply {
                text = getString(R.string.privacy_policy_body)
                setTextColor(0xFFE8E6E1.toInt())
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

        // Each checklist row is a shortcut to the exact system screen needed.
        permScreen.setOnClickListener { v ->
            Haptics.tap(v)
            if (!ScreenPermission.isGranted) ScreenPermission.request(this)
        }
        permA11y.setOnClickListener { v -> Haptics.tap(v); openAccessibilitySettings() }
        permBattery.setOnClickListener { v -> Haptics.tap(v); openBatterySettings() }
        permNotif.setOnClickListener { v ->
            Haptics.tap(v)
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
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
            icons[i].setColorFilter(if (active) 0xFFFFB454.toInt() else 0xFF8B8D90.toInt())
            labels[i].setTextColor(if (active) 0xFFFFB454.toInt() else 0xFF8B8D90.toInt())
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
        LynkoAccessibilityService.onChange = { renderPerms() }
        screenTab.render()
        readShareIntent()
        if (intent.getBooleanExtra("request_mirror_consent", false)) {
            intent.removeExtra("request_mirror_consent")
            pendingMirror = true
            ScreenPermission.request(this)
        }
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
        renderPerms()
    }

    /** Checklist rows flip from • (todo) to ✓ (granted) as the user grants them. */
    private fun renderPerms() {
        // Check the PERMISSION (Settings string), not the live binding: MIUI
        // unbinds the service on backgrounding, so instance==null on every
        // relaunch while the grant is still intact — checking instance told
        // every user to toggle the service off→on on each app open. The
        // checklist re-renders via LynkoAccessibilityService.onChange when the
        // system actually (re)binds.
        val a11yOk = LynkoAccessibilityService.enabled(this)
        val notifOk = if (Build.VERSION.SDK_INT >= 24) {
            androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this)
                .contains(packageName)
        } else true

        fun bind(row: LinearLayout, icon: TextView, text: TextView, ok: Boolean, label: String) {
            // Jackfield world: lanes never change their plate. State is drawn
            // on the lane's own mark — • pending (amber), ╬ granted (muted,
            // struck). No background swap, no rounding.
            row.setBackgroundResource(0)
            // Granted rows keep FULL alpha: 0.55 over #8B8D90 composites to
            // ~2.6:1 and the strike-through line was the only thing reading as
            // "done". Contrast must stay AA (5.9:1); state is carried by the
            // mark (•/✓) and the strike, not by fading the text out.
            row.alpha = 1f
            icon.text = if (ok) "✓" else "•"
            icon.setTextColor(if (ok) 0xFF8B8D90.toInt() else 0xFFFFB454.toInt())
            text.paint.isStrikeThruText = ok
            text.setTextColor(if (ok) 0xFF8B8D90.toInt() else 0xFFE8E6E1.toInt())
            text.text = label
        }
        bind(permScreen, permScreenIcon, permScreenText, ScreenPermission.isGranted, Loc.t("phone", "perm_screen"))
        bind(permA11y, permA11yIcon, permA11yText, a11yOk, Loc.t("phone", "perm_a11y"))
        // MIUI kills the process at lock-screen unless battery optimizations
        // are ignored — and that kill is what resets the a11y grant.
        val battOk = if (Build.VERSION.SDK_INT >= 23)
            (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .isIgnoringBatteryOptimizations(packageName) else true
        bind(permBattery, permBatteryIcon, permBatteryText, battOk, Loc.t("phone", "perm_battery"))
        bind(permNotif, permNotifIcon, permNotifText, notifOk, Loc.t("phone", "perm_notif"))
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
