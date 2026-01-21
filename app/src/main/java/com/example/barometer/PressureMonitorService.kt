package com.example.barometer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.audiofx.AcousticEchoCanceler
import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

class PressureMonitorService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var pressureSensor: Sensor? = null
    private lateinit var notificationManager: NotificationManager
    private var latestPressure: Float = 0f
    private var latestAltitude: Float = 0f
    private var latestDecibel: Double = 0.0
    private var audioRecord: AudioRecord? = null
    @Volatile private var isAudioRecording: Boolean = false

    companion object {
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "pressure_monitor_channel"
        private const val SEA_LEVEL_PRESSURE = 1013.25f
        private const val DB_OFFSET = 100.0 // shift dBFS to positive range
        private const val DB_ALPHA = 0.2 // EMA smoothing factor
        private const val SAMPLE_RATE_PRIMARY = 48000
        private const val SAMPLE_RATE_FALLBACK = 44100
        // For activity communication
        const val ACTION_DATA_UPDATE = "com.example.barometer.DATA_UPDATE"
        const val EXTRA_PRESSURE = "EXTRA_PRESSURE"
        const val EXTRA_ALTITUDE = "EXTRA_ALTITUDE"
        const val EXTRA_DECIBEL = "EXTRA_DECIBEL"
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
        startAudioCapture()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterSensor()
        stopAudioCapture()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_PRESSURE) {
                val pressure = it.values[0]
                val altitude = calculateAltitude(pressure)
                latestPressure = pressure
                latestAltitude = altitude
                updateNotification()
                sendDataToActivity(latestPressure, latestAltitude, latestDecibel)
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

    private fun updateNotification() {
        val altitudeText = String.format(Locale.getDefault(), "海拔: %.1f 米", latestAltitude)
        val decibelText = String.format(Locale.getDefault(), "分贝: %.1f dB", latestDecibel)
        val notification = buildNotification(altitudeText, decibelText)
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

    private fun sendDataToActivity(pressure: Float, altitude: Float, decibel: Double) {
        Intent(ACTION_DATA_UPDATE).also { intent ->
            intent.putExtra(EXTRA_PRESSURE, pressure)
            intent.putExtra(EXTRA_ALTITUDE, altitude)
            intent.putExtra(EXTRA_DECIBEL, decibel)
            sendBroadcast(intent)
        }
    }

    private fun startAudioCapture() {
        if (isAudioRecording) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        // Try UNPROCESSED@48k, then MIC@48k, then MIC@44.1k
        val record =
            buildAudioRecord(preferUnprocessed = true, sampleRate = SAMPLE_RATE_PRIMARY)
                ?: buildAudioRecord(preferUnprocessed = false, sampleRate = SAMPLE_RATE_PRIMARY)
                ?: buildAudioRecord(preferUnprocessed = false, sampleRate = SAMPLE_RATE_FALLBACK)
                ?: return

        // Disable input effects when possible to reduce coloration
        disableInputEffects(record)

        val sr = record.sampleRate
        val minBuffer = AudioRecord.getMinBufferSize(
            sr,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).let { size -> if (size <= 0) sr else size }

        audioRecord = record
        record.startRecording()
        isAudioRecording = true
        Thread {
            val buffer = ShortArray(minBuffer)
            var smoothed = latestDecibel
            val epsilon = 1e-9
            while (isAudioRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    var sum = 0.0
                    for (i in 0 until read) {
                        val sample = buffer[i] / 32768.0
                        sum += sample * sample
                    }
                    val rms = sqrt(sum / read)
                    val dbFs = 20 * log10(rms + epsilon)
                    val estimatedDb = (dbFs + DB_OFFSET).coerceIn(0.0, 120.0)
                    smoothed = DB_ALPHA * estimatedDb + (1 - DB_ALPHA) * smoothed
                    latestDecibel = smoothed
                    updateNotification()
                    sendDataToActivity(
                        pressure = latestPressure,
                        altitude = latestAltitude,
                        decibel = latestDecibel
                    )
                }
            }
        }.start()
    }

    private fun buildAudioRecord(preferUnprocessed: Boolean, sampleRate: Int): AudioRecord? {
        val source = if (preferUnprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0 || minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) return null
        val rec = AudioRecord(
            source,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf
        )
        return if (rec.state == AudioRecord.STATE_INITIALIZED) rec else {
            rec.release(); null
        }
    }

    private fun disableInputEffects(record: AudioRecord) {
        val sessionId = record.audioSessionId
        try {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(sessionId)?.setEnabled(false)
            }
        } catch (_: Throwable) {}
        try {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.setEnabled(false)
            }
        } catch (_: Throwable) {}
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.setEnabled(false)
            }
        } catch (_: Throwable) {}
    }

    private fun stopAudioCapture() {
        isAudioRecording = false
        audioRecord?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {}
            it.release()
        }
        audioRecord = null
        latestDecibel = 0.0
    }
}
