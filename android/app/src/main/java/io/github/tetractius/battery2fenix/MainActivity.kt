package io.github.tetractius.battery2fenix

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var batteryText: TextView
    private lateinit var statusText: TextView

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Once permissions are resolved (granted or denied), try starting the service.
            BatteryForwardService.start(this)
            statusText.text = getString(R.string.status_forwarding)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        batteryText = findViewById(R.id.batteryText)
        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.startButton).setOnClickListener {
            BatteryForwardService.start(this)
            statusText.text = getString(R.string.status_forwarding)
        }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            BatteryForwardService.stop(this)
            statusText.text = getString(R.string.status_stopped)
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
            // All permissions already granted — start right away.
            BatteryForwardService.start(this)
            statusText.text = getString(R.string.status_forwarding)
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
    }
}
