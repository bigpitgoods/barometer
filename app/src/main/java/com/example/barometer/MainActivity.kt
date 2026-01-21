package com.example.barometer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity() {

    private lateinit var pressureValue: TextView
    private lateinit var altitudeValue: TextView
    private lateinit var statusText: TextView
    private lateinit var stopServiceButton: Button
    private lateinit var decibelValue: TextView
    private val latestDecibel = AtomicReference(0.0)
    private val requestMicPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (!isGranted) {
                Toast.makeText(this, "麦克风权限被拒绝，分贝将无法采集", Toast.LENGTH_LONG).show()
            }
        }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startPressureService()
            } else {
                Toast.makeText(this, "通知权限被拒绝，后台监测功能可能受限", Toast.LENGTH_LONG).show()
            }
        }

    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PressureMonitorService.ACTION_DATA_UPDATE) {
                val pressure = intent.getFloatExtra(PressureMonitorService.EXTRA_PRESSURE, 0f)
                val altitude = intent.getFloatExtra(PressureMonitorService.EXTRA_ALTITUDE, 0f)
                val decibel = intent.getDoubleExtra(PressureMonitorService.EXTRA_DECIBEL, latestDecibel.get())
                updateUI(pressure, altitude, decibel)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Go Edge-to-Edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        // Handle insets for Edge-to-Edge
        val rootView = findViewById<LinearLayout>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        initializeViews()
        askNotificationPermission()

        stopServiceButton.setOnClickListener {
            stopPressureService()
        }

        // 分贝默认在后台服务中采集，无需按钮
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(PressureMonitorService.ACTION_DATA_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dataReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(dataReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(dataReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun initializeViews() {
        pressureValue = findViewById(R.id.pressureValue)
        altitudeValue = findViewById(R.id.altitudeValue)
        statusText = findViewById(R.id.statusText)
        stopServiceButton = findViewById(R.id.stopServiceButton)
        decibelValue = findViewById(R.id.decibelValue)
        statusText.text = "服务已启动，正在后台监测..."
        decibelValue.text = "分贝: -- dB"
    }

    private fun updateUI(pressure: Float, altitude: Float, decibel: Double) {
        pressureValue.text = String.format(Locale.getDefault(), "气压: %.1f hPa", pressure)
        altitudeValue.text = String.format(Locale.getDefault(), "海拔: %.1f 米", altitude)
        latestDecibel.set(decibel)
        decibelValue.text = String.format(Locale.getDefault(), "分贝: %.1f dB", decibel)
    }

    private fun startPressureService() {
        val serviceIntent = Intent(this, PressureMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        statusText.text = "服务已启动，正在后台监测..."
    }

    private fun stopPressureService() {
        val serviceIntent = Intent(this, PressureMonitorService::class.java)
        serviceIntent.action = PressureMonitorService.ACTION_STOP_SERVICE
        startService(serviceIntent) // Send command to stop
        stopService(Intent(this, PressureMonitorService::class.java)) // Ensure service stops
        statusText.text = "监测已停止"
        pressureValue.text = "气压: -- hPa"
        altitudeValue.text = "海拔: -- 米"
        decibelValue.text = "分贝: -- dB"
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                startPressureService()
            } else if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            startPressureService()
        }

        // Request microphone runtime permission for SPL capture
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
