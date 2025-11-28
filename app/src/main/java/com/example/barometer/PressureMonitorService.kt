package com.example.barometer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.pow

class PressureMonitorService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var pressureSensor: Sensor? = null
    private lateinit var notificationManager: NotificationManager

    companion object {
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "pressure_monitor_channel"
        private const val SEA_LEVEL_PRESSURE = 1013.25f

        // For activity communication
        const val ACTION_DATA_UPDATE = "com.example.barometer.DATA_UPDATE"
        const val EXTRA_PRESSURE = "EXTRA_PRESSURE"
        const val EXTRA_ALTITUDE = "EXTRA_ALTITUDE"
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createInitialNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }
        registerSensor()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterSensor()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_PRESSURE) {
                val pressure = it.values[0]
                val altitude = calculateAltitude(pressure)
                updateNotification(pressure, altitude)
                sendDataToActivity(pressure, altitude)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }

    private fun registerSensor() {
        pressureSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun unregisterSensor() {
        sensorManager.unregisterListener(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "气压监测 (后台)",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "在后台持续监测气压和海拔数据"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createInitialNotification(): Notification {
        return buildNotification("正在初始化传感器...", "")
    }

    private fun updateNotification(pressure: Float, altitude: Float) {
        val pressureText = String.format(Locale.getDefault(), "%.2f hPa", pressure)
        val altitudeText = String.format(Locale.getDefault(), "%.1f 米", altitude)
        val notification = buildNotification("气压: $pressureText", "海拔: $altitudeText")
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_sensor_icon)
            .setColor(ContextCompat.getColor(this, com.google.android.material.R.color.design_default_color_primary))
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun calculateAltitude(pressure: Float): Float {
        return 44330.0f * (1.0f - (pressure / SEA_LEVEL_PRESSURE).toDouble().pow(1.0 / 5.255)).toFloat()
    }

    private fun sendDataToActivity(pressure: Float, altitude: Float) {
        Intent(ACTION_DATA_UPDATE).also { intent ->
            intent.putExtra(EXTRA_PRESSURE, pressure)
            intent.putExtra(EXTRA_ALTITUDE, altitude)
            sendBroadcast(intent)
        }
    }
}
