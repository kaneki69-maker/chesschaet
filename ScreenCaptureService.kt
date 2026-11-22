package com.chesscheatpro.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** One-shot, user-consented screen capture. It never runs continuously. */
class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private val finished = AtomicBoolean(false)
    private var captureHandler: Handler? = null
    private var timeoutTask: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_CAPTURE) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                   else @Suppress("DEPRECATION") (intent.getParcelableExtra(EXTRA_DATA) as? Intent)
        if (resultCode == 0 || data == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            beginSingleFrameCapture()
        } catch (_: Exception) {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun beginSingleFrameCapture() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val density = metrics.densityDpi
        worker = HandlerThread("ChessScreenCapture").also { it.start() }
        val handler = Handler(worker!!.looper)
        captureHandler = handler
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        timeoutTask = Runnable {
            if (finished.compareAndSet(false, true)) {
                sendBroadcast(Intent(ACTION_FAILED).setPackage(packageName)
                    .putExtra(EXTRA_ERROR, "Android не предоставил кадр за 8 секунд. Повтори захват."))
                releaseCapture()
                stopSelf()
            }
        }.also { handler.postDelayed(it, CAPTURE_TIMEOUT_MS) }
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (finished.compareAndSet(false, true)) {
                    sendBroadcast(Intent(ACTION_FAILED).setPackage(packageName)
                        .putExtra(EXTRA_ERROR, "Системный захват экрана был прерван"))
                }
                releaseCapture()
                stopSelf()
            }
        }, handler)
        reader?.setOnImageAvailableListener({ imageReader ->
            if (!finished.compareAndSet(false, true)) return@setOnImageAvailableListener
            try {
                val captured = imageReader.acquireLatestImage()
                    ?: error("Системный захват не вернул изображение")
                captured.use { image ->
                    val plane = image.planes.first()
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * image.width
                    val padded = Bitmap.createBitmap(
                        image.width + rowPadding / pixelStride,
                        image.height,
                        Bitmap.Config.ARGB_8888
                    )
                    padded.copyPixelsFromBuffer(plane.buffer)
                    val bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                    padded.recycle()
                    val folder = File(filesDir, "captures").apply { mkdirs() }
                    val output = File(folder, "latest-screen.png")
                    FileOutputStream(output).use { stream ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Не удалось сохранить PNG" }
                    }
                    bitmap.recycle()
                    sendBroadcast(Intent(ACTION_CAPTURED).setPackage(packageName).putExtra(EXTRA_PATH, output.absolutePath))
                }
            } catch (e: Exception) {
                sendBroadcast(Intent(ACTION_FAILED).setPackage(packageName).putExtra(EXTRA_ERROR, e.message ?: "Ошибка захвата экрана"))
            } finally {
                releaseCapture()
                stopSelf()
            }
        }, handler)
        virtualDisplay = projection?.createVirtualDisplay(
            "ChessCheatOneShotCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface, null, handler
        )
    }

    private fun buildNotification(): Notification {
        val channelId = "chess_screen_capture"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Разовый захват экрана", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        return builder.setContentTitle("ChessCheat Pro")
            .setContentText("Выполняется один снимок экрана по вашему запросу")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true).build()
    }

    private fun releaseCapture() {
        timeoutTask?.let { task -> captureHandler?.removeCallbacks(task) }
        timeoutTask = null
        captureHandler = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { reader?.setOnImageAvailableListener(null, null); reader?.close() } catch (_: Exception) {}
        reader = null
        val activeProjection = projection
        projection = null
        try { activeProjection?.stop() } catch (_: Exception) {}
        try { worker?.quitSafely() } catch (_: Exception) {}
        worker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        releaseCapture()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CAPTURE = "com.chesscheatpro.mobile.CAPTURE_ONCE"
        const val ACTION_CAPTURED = "com.chesscheatpro.mobile.CAPTURED"
        const val ACTION_FAILED = "com.chesscheatpro.mobile.CAPTURE_FAILED"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "result_data"
        const val EXTRA_PATH = "image_path"
        const val EXTRA_ERROR = "error"
        private const val NOTIFICATION_ID = 5102
        private const val CAPTURE_TIMEOUT_MS = 8_000L
    }
}
