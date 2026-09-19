package io.github.tetractius.battery2fenix

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var batteryText: TextView
    private lateinit var statusText: TextView
    private lateinit var statusLed: View
    private lateinit var dndButton: Button

    private val handler = Handler(Looper.getMainLooper())

    private val ledUpdater = object : Runnable {
        override fun run() {
            updateLed()
            handler.postDelayed(this, 1000)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            BatteryForwardService.start(this)
            statusText.text = getString(R.string.status_connecting)
            updateLed()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        batteryText = findViewById(R.id.batteryText)
        statusText = findViewById(R.id.statusText)
        statusLed = findViewById(R.id.statusLed)

        findViewById<Button>(R.id.startButton).setOnClickListener {
            BatteryForwardService.start(this)
            statusText.text = getString(R.string.status_connecting)
            updateLed()
        }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            BatteryForwardService.stop(this)
            statusText.text = getString(R.string.status_stopped)
            updateLed()
        }

        // Switching the phone to silent from the watch toggles Do Not Disturb,
        // which can only be granted on a system settings screen. Offer it here
        // so the user does not have to discover it through the service's
        // notification the first time they pick "Silent".
        dndButton = findViewById(R.id.dndButton)
        dndButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }

        val neededPermissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (neededPermissions.isNotEmpty()) {
            permissionLauncher.launch(neededPermissions.toTypedArray())
        } else {
            BatteryForwardService.start(this)
            statusText.text = getString(R.string.status_connecting)
            updateLed()
        }
    }

    override fun onResume() {
        super.onResume()
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) {
            batteryText.text = getString(R.string.battery_now, level * 100 / scale)
        }
        handler.post(ledUpdater)
        updateDndButton()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ledUpdater)
    }

    private fun updateDndButton() {
        val granted = getSystemService(NotificationManager::class.java)
            .isNotificationPolicyAccessGranted
        dndButton.isEnabled = !granted
        dndButton.text = getString(if (granted) R.string.dnd_granted else R.string.grant_dnd)
    }

    private fun updateLed() {
        val (color, text) = when (BatteryForwardService.state) {
            BatteryForwardService.State.STOPPED    -> Color.RED    to getString(R.string.status_stopped)
            BatteryForwardService.State.CONNECTING -> Color.YELLOW to getString(R.string.status_connecting)
            BatteryForwardService.State.RUNNING    -> Color.GREEN  to getString(R.string.status_forwarding)
        }
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.OVAL
        drawable.setColor(color)
        statusLed.background = drawable
        statusText.text = text
    }
}
