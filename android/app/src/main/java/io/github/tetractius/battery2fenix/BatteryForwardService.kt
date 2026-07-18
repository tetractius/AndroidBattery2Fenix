package io.github.tetractius.battery2fenix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.ConnectIQ.IQConnectType
import com.garmin.android.connectiq.ConnectIQ.IQSdkErrorStatus
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that watches the phone battery and forwards it to the
 * "Phone Battery" Connect IQ widget through Garmin Connect Mobile.
 *
 * An update is sent whenever the battery percentage or charging state
 * changes, plus a periodic heartbeat so the watch can tell the data is
 * fresh.
 */
class BatteryForwardService : Service() {

    companion object {
        private const val TAG = "BatteryForward"

        /**
         * Connect IQ application id of the watch widget.
         * Must match the `id` attribute in garmin/manifest.xml.
         */
        const val WATCH_APP_ID = "a57999dd5a9942a5a9a35acb96ff49e1"

        private const val CHANNEL_ID = "battery_forward"
        private const val NOTIFICATION_ID = 1
        private const val HEARTBEAT_MS = 15 * 60 * 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, BatteryForwardService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BatteryForwardService::class.java))
        }
    }

    private lateinit var connectIQ: ConnectIQ
    private val watchApp = IQApp(WATCH_APP_ID)
    private var sdkReady = false

    private var lastSentLevel = -1
    private var lastSentCharging = false

    private val handler = Handler(Looper.getMainLooper())

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            maybeSend(intent, force = false)
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            currentBatteryIntent()?.let { maybeSend(it, force = true) }
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.status_connecting)))

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        connectIQ = ConnectIQ.getInstance(this, IQConnectType.WIRELESS)
        connectIQ.initialize(this, false, object : ConnectIQ.ConnectIQListener {
            override fun onSdkReady() {
                sdkReady = true
                Log.i(TAG, "Connect IQ SDK ready")
                updateNotification(getString(R.string.status_forwarding))
                currentBatteryIntent()?.let { maybeSend(it, force = true) }
            }

            override fun onInitializeError(status: IQSdkErrorStatus) {
                Log.e(TAG, "Connect IQ init error: $status")
                updateNotification(getString(R.string.status_sdk_error, status.name))
            }

            override fun onSdkShutDown() {
                sdkReady = false
            }
        })

        handler.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching { connectIQ.shutdown(this) }
        super.onDestroy()
    }

    private fun currentBatteryIntent(): Intent? =
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun maybeSend(intent: Intent, force: Boolean) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        val pct = level * 100 / scale

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        if (!force && pct == lastSentLevel && charging == lastSentCharging) return
        sendToWatch(pct, charging)
    }

    private fun sendToWatch(pct: Int, charging: Boolean) {
        if (!sdkReady) return

        val devices: List<IQDevice> = try {
            connectIQ.connectedDevices ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Could not list connected devices", e)
            emptyList()
        }

        if (devices.isEmpty()) {
            updateNotification(getString(R.string.status_no_device))
            return
        }

        val message = mapOf("level" to pct, "charging" to charging)
        for (device in devices) {
            try {
                connectIQ.sendMessage(device, watchApp, message) { _, _, status ->
                    Log.d(TAG, "sendMessage to ${device.friendlyName}: $status")
                }
            } catch (e: Exception) {
                Log.w(TAG, "sendMessage failed", e)
            }
        }

        lastSentLevel = pct
        lastSentCharging = charging
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        updateNotification(getString(R.string.status_sent, pct, time))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
