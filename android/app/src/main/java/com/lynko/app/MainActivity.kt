package com.lynko.app

import android.Manifest
import android.content.Context
import android.provider.Settings
import android.widget.LinearLayout
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/**
 * Lynko phone UI: brand, live status card, required-permissions checklist,
 * start/stop, pairing PIN.
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val PIN = "1234"
    }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Loc.init(this)

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

        renderState()

        startBtn.setOnClickListener {
            if (LinkService.running) {
                stopLink()
            } else {
                beginStart()
            }
        }

        // Each checklist row is a shortcut to the exact system screen needed.
        permScreen.setOnClickListener {
            if (!ScreenPermission.isGranted) ScreenPermission.request(this)
        }
        permA11y.setOnClickListener { openAccessibilitySettings() }
        permBattery.setOnClickListener { openBatterySettings() }
        permNotif.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    /** Deep-link into Accessibility settings with Lynko pre-highlighted when possible. */
    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "Open Settings → Accessibility → Lynko", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        renderState()
    }

    private fun beginStart() {
        // Accessibility is REQUIRED for taps/swipes/typing. Gate the start
        // flow on it so nobody runs a link that can't be controlled.
        if (!LynkoAccessibilityService.enabled(this)) {
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
        val svcIntent = Intent(this, LinkService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svcIntent)
        else startService(svcIntent)
        renderState()
    }

    private fun stopLink() {
        stopService(Intent(this, LinkService::class.java))
        renderState()
        Toast.makeText(this, "Lynko stopped", Toast.LENGTH_SHORT).show()
    }

    private fun renderState() {
        if (LinkService.running) {
            statusDot.setBackgroundResource(R.drawable.dot_live)
            statusTitle.text = Loc.t("phone", "status_waiting")
            statusSub.text = Loc.t("phone", "status_advertising").replace("{model}", android.os.Build.MODEL)
            startBtn.text = Loc.t("phone", "stop")
            pinText.visibility = View.VISIBLE
            pinText.text = Loc.t("phone", "pin").replace("{pin}", PIN)
        } else {
            statusDot.setBackgroundResource(R.drawable.dot_idle)
            statusTitle.text = Loc.t("phone", "status_not_running")
            statusSub.text = Loc.t("phone", "status_subtitle_idle")
            startBtn.text = Loc.t("phone", "start")
            startBtn.isEnabled = true
            pinText.visibility = View.GONE
        }
        renderPerms()
    }

    /** Checklist rows flip from • (todo) to ✓ (granted) as the user grants them. */
    private fun renderPerms() {
        val a11yOk = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains("com.lynko.app") == true
        val notifOk = if (Build.VERSION.SDK_INT >= 24) {
            androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this)
                .contains(packageName)
        } else true

        fun bind(row: LinearLayout, icon: TextView, text: TextView, ok: Boolean, label: String) {
            row.background = if (ok) drawableOk else null
            row.alpha = if (ok) 0.72f else 1f
            icon.text = if (ok) "✓" else "•"
            icon.setTextColor(if (ok) 0xFF7BC47F.toInt() else 0xFFFFB454.toInt())
            text.paint.isStrikeThruText = ok
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
                Toast.makeText(this, "Apps → Lynko → Battery saver → No restrictions", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val drawableOk: android.graphics.drawable.Drawable? by lazy {
        getDrawable(R.drawable.perm_row_done)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        ScreenPermission.onResult(requestCode, resultCode, data)
        if (requestCode == 9001) {
            if (ScreenPermission.isGranted) startLinkService()
            else {
                statusTitle.text = Loc.t("phone", "perm_denied")
                statusSub.text = Loc.t("phone", "perm_denied_sub")
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 &&
            (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED)
        ) {
            Toast.makeText(this, "Notifications blocked — link continues without them", Toast.LENGTH_LONG).show()
        }
    }
}
