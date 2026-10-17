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
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
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
 *
 * The link is two-way: the watch can ask for a sound mode change, which is
 * applied here and answered immediately rather than on the next heartbeat.
 */
class BatteryForwardService : Service() {

    enum class State { STOPPED, CONNECTING, RUNNING }

    companion object {
        private const val TAG = "BatteryForward"

        const val WATCH_APP_ID = "a57999dd5a9942a5a9a35acb96ff49e1"

        private const val CHANNEL_ID = "battery_forward"
        private const val NOTIFICATION_ID = 1
        private const val HEARTBEAT_MS = 15 * 60 * 1000L

        /** Second channel so the permission request can actually alert the user. */
        private const val DND_CHANNEL_ID = "dnd_request"
        private const val DND_NOTIFICATION_ID = 2

        /** Command the watch sends to change the phone's sound mode. */
        private const val CMD_SET_SOUND_MODE = "setSoundMode"

        @Volatile var state: State = State.STOPPED

        /**
         * Battery-saving knobs: only send when the level crosses into a new
         * STEP_PCT band (the watch displays a range anyway), and never more
         * than once per MIN_SEND_INTERVAL_MS. A charging-state change always
         * sends immediately. Fewer sends = fewer background wakes on the
         * watch.
         */
        private const val STEP_PCT = 5
        private const val MIN_SEND_INTERVAL_MS = 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, BatteryForwardService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BatteryForwardService::class.java))
        }
    }

    private lateinit var audioManager: AudioManager
    private lateinit var connectIQ: ConnectIQ
    private val watchApp = IQApp(WATCH_APP_ID)
    private var sdkReady = false

    private var lastSentBucket = -1
    private var lastSentCharging = false
    private var lastSentAt = 0L

    private val handler = Handler(Looper.getMainLooper())

    /** Devices we currently hold an app-event registration for. */
    private val registeredDevices = mutableListOf<IQDevice>()

    /** Commands arriving from the watch widget. */
    private val appEventListener = object : ConnectIQ.IQApplicationEventListener {
        override fun onMessageReceived(
            device: IQDevice,
            app: IQApp,
            messages: MutableList<Any>?,
            status: ConnectIQ.IQMessageStatus
        ) {
            if (status != ConnectIQ.IQMessageStatus.SUCCESS) {
                Log.w(TAG, "Bad message from ${device.friendlyName}: $status")
                return
            }
            handler.post { handleWatchMessages(messages) }
        }
    }

    /** A watch coming back into range needs its app-event registration redone. */
    private val deviceEventListener = object : ConnectIQ.IQDeviceEventListener {
        override fun onDeviceStatusChanged(device: IQDevice, status: IQDevice.IQDeviceStatus) {
            Log.i(TAG, "${device.friendlyName}: $status")
            if (status == IQDevice.IQDeviceStatus.CONNECTED) {
                registerForWatchMessages()
                currentBatteryIntent()?.let { maybeSend(it, force = true) }
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            maybeSend(intent, force = false)
        }
    }

    private val ringerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            currentBatteryIntent()?.let { maybeSend(it, force = true) }
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
        state = State.CONNECTING
        audioManager = getSystemService(AudioManager::class.java)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.status_connecting)))

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerReceiver(ringerReceiver, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION))

        connectIQ = ConnectIQ.getInstance(this, IQConnectType.WIRELESS)
        connectIQ.initialize(this, false, object : ConnectIQ.ConnectIQListener {
            override fun onSdkReady() {
                sdkReady = true
                state = State.RUNNING
                Log.i(TAG, "Connect IQ SDK ready")
                updateNotification(getString(R.string.status_forwarding))
                registerForWatchMessages()
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
        state = State.STOPPED
        handler.removeCallbacks(heartbeat)
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching { unregisterReceiver(ringerReceiver) }
        for (device in registeredDevices) {
            runCatching { connectIQ.unregisterForApplicationEvents(device, watchApp) }
            runCatching { connectIQ.unregisterForDeviceEvents(device) }
        }
        registeredDevices.clear()
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

        if (!force && charging == lastSentCharging) {
            if (pct / STEP_PCT == lastSentBucket) return
            if (System.currentTimeMillis() - lastSentAt < MIN_SEND_INTERVAL_MS) return
        }
        sendToWatch(pct, charging)
    }

    /** Maps AudioManager ringer mode to the watch constant (0=Normal,1=Vibrate,2=Silent). */
    private fun currentSoundMode(): Int = when (audioManager.ringerMode) {
        AudioManager.RINGER_MODE_VIBRATE -> 1
        AudioManager.RINGER_MODE_SILENT  -> 2
        else                             -> 0
    }

    /** 77 -> "75-80%", 100 -> "100%" — mirrors what the watch displays. */
    private fun rangeLabel(pct: Int): String {
        if (pct >= 100) return "100%"
        val lower = pct / STEP_PCT * STEP_PCT
        return "$lower-${lower + STEP_PCT}%"
    }

    private fun sendToWatch(pct: Int, charging: Boolean) {
        val message = mapOf("level" to pct, "charging" to charging, "soundMode" to currentSoundMode())
        if (!sendMessageToWatch(message)) return

        lastSentBucket = pct / STEP_PCT
        lastSentCharging = charging
        lastSentAt = System.currentTimeMillis()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        updateNotification(getString(R.string.status_sent, rangeLabel(pct), time))
    }

    /** Returns false when there was nobody to send to. */
    private fun sendMessageToWatch(message: Map<String, Any>): Boolean {
        if (!sdkReady) return false

        val devices: List<IQDevice> = try {
            connectIQ.connectedDevices ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Could not list connected devices", e)
            emptyList()
        }

        if (devices.isEmpty()) {
            updateNotification(getString(R.string.status_no_device))
            return false
        }

        for (device in devices) {
            try {
                connectIQ.sendMessage(device, watchApp, message) { _, _, status ->
                    Log.d(TAG, "sendMessage to ${device.friendlyName}: $status")
                }
            } catch (e: Exception) {
                Log.w(TAG, "sendMessage failed", e)
            }
        }
        return true
    }

    // ---- Watch-initiated commands ------------------------------------------

    /**
     * Listens for messages from the widget. Device events are registered for
     * every known watch so we hear about reconnects; app events only work on
     * watches that are actually connected, so this is re-run on each connect.
     */
    private fun registerForWatchMessages() {
        if (!sdkReady) return

        val known: List<IQDevice> = try {
            connectIQ.knownDevices ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Could not list known devices", e)
            emptyList()
        }
        for (device in known) {
            try {
                connectIQ.registerForDeviceEvents(device, deviceEventListener)
            } catch (e: Exception) {
                Log.w(TAG, "registerForDeviceEvents failed for ${device.friendlyName}", e)
            }
        }

        val connected: List<IQDevice> = try {
            connectIQ.connectedDevices ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Could not list connected devices", e)
            emptyList()
        }
        for (device in connected) {
            try {
                connectIQ.registerForAppEvents(device, watchApp, appEventListener)
                if (registeredDevices.none { it.deviceIdentifier == device.deviceIdentifier }) {
                    registeredDevices.add(device)
                }
                Log.i(TAG, "Listening for commands from ${device.friendlyName}")
            } catch (e: Exception) {
                Log.w(TAG, "registerForAppEvents failed for ${device.friendlyName}", e)
            }
        }
    }

    private fun handleWatchMessages(messages: List<Any>?) {
        if (messages == null) return
        for (message in messages) {
            val map = message as? Map<*, *> ?: continue
            if (map["cmd"] != CMD_SET_SOUND_MODE) continue
            val mode = (map["soundMode"] as? Number)?.toInt() ?: continue
            if (mode < 0 || mode > 2) continue
            applySoundMode(mode)
        }
    }

    /**
     * Applies a sound mode asked for by the watch and answers straight away.
     * The watch is waiting on this reply, so it is sent whether the change
     * succeeded or not — if it was refused, the watch sees the unchanged mode
     * come back and drops its pending state.
     */
    private fun applySoundMode(mode: Int) {
        val target = when (mode) {
            1 -> AudioManager.RINGER_MODE_VIBRATE
            2 -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }

        var applied = false
        if (togglesDnd(target) && !hasDndAccess()) {
            Log.i(TAG, "Do Not Disturb access needed to reach ringer mode $target")
            promptForDndAccess()
        } else {
            try {
                audioManager.ringerMode = target
                applied = true
            } catch (e: SecurityException) {
                Log.w(TAG, "setRingerMode($target) denied", e)
                promptForDndAccess()
            }
        }

        pushSoundStateToWatch()

        // After the reply, so this is the status line the user is left with
        // rather than the "sent" text pushSoundStateToWatch() writes.
        if (applied) {
            updateNotification(getString(R.string.status_sound_set, soundModeLabel(mode)))
        }
    }

    /**
     * From Android N on, ringer changes that would toggle Do Not Disturb need
     * Notification Policy access — that means any move into silent, or out of
     * it. Normal <-> vibrate needs nothing.
     */
    private fun togglesDnd(target: Int): Boolean =
        target == AudioManager.RINGER_MODE_SILENT ||
            audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT

    private fun hasDndAccess(): Boolean =
        getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    /**
     * There is no runtime dialog for Do Not Disturb access, and a service
     * cannot start an activity in the background on Android 10+, so ask via a
     * notification that opens the settings screen.
     */
    private fun promptForDndAccess() {
        val settings = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            this, 1, settings,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, DND_CHANNEL_ID)
            .setContentTitle(getString(R.string.dnd_needed_title))
            .setContentText(getString(R.string.dnd_needed_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(DND_NOTIFICATION_ID, notification)
    }

    /**
     * Immediate answer to a watch command. Carries the battery too when we
     * have it, so the watch gets one consistent payload shape.
     */
    private fun pushSoundStateToWatch() {
        val battery = currentBatteryIntent()
        if (battery != null) {
            maybeSend(battery, force = true)
        } else {
            sendMessageToWatch(mapOf("soundMode" to currentSoundMode()))
        }
    }

    private fun soundModeLabel(mode: Int): String = getString(
        when (mode) {
            1 -> R.string.sound_vibrate
            2 -> R.string.sound_silent
            else -> R.string.sound_normal
        }
    )

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        // The permission request has to be noticeable, unlike the ongoing
        // status notification.
        manager.createNotificationChannel(
            NotificationChannel(
                DND_CHANNEL_ID,
                getString(R.string.dnd_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
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
