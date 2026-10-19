package com.zev3nz.flowcenter
import com.zev3nz.flowcenter.R


import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView

    private fun setEnabled(on: Boolean) {
        getSharedPreferences("cc", Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", on).apply()
    }

    private fun isEnabled() =
        getSharedPreferences("cc", Context.MODE_PRIVATE).getBoolean("enabled", false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.statusText)

        findViewById<Button>(R.id.overlayButton).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName)
                )
            )
        }
        findViewById<Button>(R.id.writeButton).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + packageName)
                )
            )
        }
        findViewById<Button>(R.id.accessButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.notifButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.startButton).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow overlay permission first", Toast.LENGTH_SHORT).show()
            } else {
                setEnabled(true)
                startForegroundService(Intent(this, OverlayService::class.java))
                Toast.makeText(this, "Flow Center started", Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
        }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            setEnabled(false)
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "Flow Center stopped", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val overlay = if (Settings.canDrawOverlays(this)) "✓" else "✗"
        val write = if (Settings.System.canWrite(this)) "✓" else "✗"
        val access = if (TriggerService.instance != null) "✓" else "✗"
        val notif = if (NotificationListener.instance != null) "✓" else "✗"
        val running = if (isEnabled()) "ON" else "OFF"
        status.text = "Overlay permission: " + overlay + "\n" +
            "System settings permission: " + write + "\n" +
            "Accessibility: " + access + "\n" +
            "Notification access: " + notif + "\n" +
            "Flow Center: " + running
    }
}
