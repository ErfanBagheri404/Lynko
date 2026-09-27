package com.lynko.app

import android.Manifest
import android.content.Context
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.content.Intent
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
        val PIN: String get() = LinkService.PIN

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
        screenTab.onChoose = {
            if (!PhoneState.link) showFeedback(Loc.t("phone", "share_connect"))
            else if (!sharedUris.isNullOrEmpty()) chooseSharePeer(sharedUris!!)
            else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, 9100)
        }
        screenTab.onReceive = { startLinkService() }
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
        // Accessibility is REQUIRED for taps/swipes/typing. Check both the
        // Settings string AND live binding: MIUI can leave the string intact
        // while the service is dead (malfunctioning) — operational() covers
        // that case so the user is nudged to toggle off→on.
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
                statusDot.setBackgroundResource(R.drawable.dot_idle)
                statusTitle.text = Loc.t("phone", "status_waiting")
                statusSub.text = Loc.t("phone", "status_advertising").replace("{model}", android.os.Build.MODEL).replace("{pin}", PIN)
                startBtn.text = Loc.t("phone", "stop")
                startBtn.isEnabled = true
                pinText.visibility = View.VISIBLE
                pinText.text = Loc.t("phone", "pin").replace("{pin}", PIN)
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
        // Check the LIVE binding, not the stored setting. MIUI's settings
        // screen happily shows Lynko "On" while enabled_accessibility_services
        // is null and nothing is bound — the exact stale state that made taps
        // silently dead. instance != null means the service is actually
        // running, which is the only thing that matters for dispatchGesture.
        val a11yOk = LynkoAccessibilityService.instance != null
        val notifOk = if (Build.VERSION.SDK_INT >= 24) {
            androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this)
                .contains(packageName)
        } else true

        fun bind(row: LinearLayout, icon: TextView, text: TextView, ok: Boolean, label: String) {
            // Jackfield world: lanes never change their plate. State is drawn
            // on the lane's own mark — • pending (amber), ╬ granted (muted,
            // struck). No background swap, no rounding.
            row.setBackgroundResource(0)
            row.alpha = if (ok) 0.55f else 1f
            icon.text = if (ok) "✓" else "•"
            icon.setTextColor(if (ok) 0xFF555658.toInt() else 0xFFFFB454.toInt())
            text.paint.isStrikeThruText = ok
            text.setTextColor(if (ok) 0xFF555658.toInt() else 0xFF8B8D90.toInt())
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

    private fun chooseSharePeer(uris: List<android.net.Uri>) {
        val peers = LinkService.instance?.sharePeers().orEmpty()
        if (peers.isEmpty()) showFeedback(Loc.t("phone", "share_connect"))
        else androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(Loc.t("phone", "share_destination"))
            // The desktop's computer name when it announced itself; the raw
            // socket IP only as a fallback (older desktop, or before hello).
            .setItems(peers.map { PeerInfo.alias.ifBlank { it.remoteSocketAddress?.address?.hostAddress ?: "Desktop" } }.toTypedArray()) { _, i ->
                val host = peers[i].remoteSocketAddress?.address?.hostAddress ?: return@setItems
                ShareSender.send(applicationContext, uris, host, 53317, PinStore.current(this))
                sharedUris = null
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
            data?.data?.let { uri ->
                chooseSharePeer(listOf(uri))
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
