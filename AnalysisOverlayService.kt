package com.chesscheatpro.mobile

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** User-invoked movable training panel. It displays only the last analyzed position. */
class AnalysisOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var panel: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE) {
            removePanel()
            stopSelf()
            return START_NOT_STICKY
        }
        val move = intent?.getStringExtra(EXTRA_MOVE).orEmpty()
        val score = intent?.getStringExtra(EXTRA_SCORE).orEmpty()
        if (move.isBlank()) { stopSelf(); return START_NOT_STICKY }
        return try {
            showPanel(move, score)
            START_NOT_STICKY
        } catch (_: Exception) {
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun showPanel(move: String, score: String) {
        removePanel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            background = GradientDrawable().apply {
                setColor(Color.argb(238, 25, 27, 35))
                cornerRadius = 24f
                setStroke(2, Color.rgb(215, 173, 88))
            }
        }
        val header = TextView(this).apply {
            text = "CHESSCHEAT PRO  ·  TRAINING"
            setTextColor(Color.rgb(215, 173, 88))
            textSize = 10f
        }
        val bestMove = TextView(this).apply {
            text = "Лучший ход: $move"
            setTextColor(Color.WHITE)
            textSize = 19f
            setPadding(0, 8, 0, 4)
        }
        val eval = TextView(this).apply {
            text = score
            setTextColor(Color.LTGRAY)
            textSize = 12f
        }
        val close = Button(this).apply {
            text = "Закрыть"
            setOnClickListener { removePanel(); stopSelf() }
        }
        root.addView(header)
        root.addView(bestMove)
        root.addView(eval)
        root.addView(close)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 24; y = 180 }
        header.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x; startY = params.y
                        touchX = event.rawX; touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX - (event.rawX - touchX).toInt()
                        params.y = startY + (event.rawY - touchY).toInt()
                        windowManager?.updateViewLayout(root, params)
                        return true
                    }
                }
                return false
            }
        })
        windowManager?.addView(root, params)
        panel = root
    }

    private fun removePanel() {
        panel?.let { view -> runCatching { windowManager?.removeView(view) } }
        panel = null
    }

    override fun onDestroy() { removePanel(); super.onDestroy() }

    companion object {
        const val ACTION_CLOSE = "com.chesscheatpro.mobile.CLOSE_ANALYSIS_OVERLAY"
        const val EXTRA_MOVE = "best_move"
        const val EXTRA_SCORE = "score"
    }
}
