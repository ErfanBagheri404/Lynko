package com.lynko.app

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.content.Intent
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import android.Manifest
import android.content.pm.PackageManager

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var startBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        startBtn = findViewById(R.id.startBtn)

        statusText.text = "Not running"

        startBtn.setOnClickListener {
            // Ask notification permission (Android 13+)
            if (Build.VERSION.SDK_INT >= 33) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }

            // MediaProjection consent FIRST — the link service needs the
            // projection app-op before it may start an FGS of that type (API 34+).
            ScreenPermission.request(this)
            statusText.text = "Waiting for screen consent…"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        ScreenPermission.onResult(requestCode, resultCode, data)
        if (requestCode == 9001) {
            if (ScreenPermission.isGranted) {
                startLinkService()
            } else {
                statusText.text = "Screen permission denied — link not started"
            }
        }
    }

    private fun startLinkService() {
        val svcIntent = Intent(this, LinkService::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(svcIntent)
        } else {
            startService(svcIntent)
        }
        statusText.text = "Running — waiting for your desktop…"
        startBtn.isEnabled = false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notifications enabled", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
