package com.abybijo.openflow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * The floating Flow bubble: a TYPE_APPLICATION_OVERLAY window that sits over
 * every app. FLAG_NOT_FOCUSABLE so tapping it never steals the text-field
 * cursor in the app underneath. Tap to record, tap again to stop and
 * transcribe with Groq, then insert at the cursor (or copy to clipboard).
 */
class BubbleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var windowManager: WindowManager? = null
    private var bubbleView: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var recorder: WavRecorder? = null
    private var recordingFile: File? = null
    private var state: BubbleState = BubbleState.IDLE
    private var prefs: Prefs = Prefs()
    private var waveAnimators: List<ObjectAnimator> = emptyList()
    private var micIcon: ImageView? = null
    private var waveRow: LinearLayout? = null
    private var bubbleBg: GradientDrawable? = null
    private var lastX: Int? = null
    private var lastY: Int? = null
    private var pendingRebuild = false

    private enum class BubbleState { IDLE, RECORDING, TRANSCRIBING }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        // Follow preference changes live: size, colour, shape and opacity
        // from Settings must reach the bubble without restarting the app.
        // Only appearance changes rebuild the overlay — usage, history,
        // theme and onboarding changes must not remove and re-add the
        // bubble (visible flicker) after every dictation. While recording
        // or transcribing, defer the rebuild until idle.
        scope.launch {
            Store.prefsFlow(this@BubbleService).collect { newPrefs ->
                val firstBuild = bubbleView == null
                val appearanceChanged = firstBuild ||
                    newPrefs.bubbleSize != prefs.bubbleSize ||
                    newPrefs.bubbleOpacity != prefs.bubbleOpacity ||
                    newPrefs.bubbleColor != prefs.bubbleColor ||
                    newPrefs.bubbleShape != prefs.bubbleShape ||
                    newPrefs.snapToEdge != prefs.snapToEdge ||
                    newPrefs.showWaveform != prefs.showWaveform ||
                    newPrefs.haptic != prefs.haptic ||
                    newPrefs.bubbleEnabled != prefs.bubbleEnabled
                prefs = newPrefs
                when {
                    firstBuild -> buildBubble()
                    appearanceChanged && state == BubbleState.IDLE -> rebuildBubble()
                    appearanceChanged -> pendingRebuild = true
                    else -> { /* Non-appearance change: keep the bubble as it is. */ }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        try { recorder?.let { } } catch (_: Exception) { }
        try {
            bubbleView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) { }
        bubbleView = null
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        val channelId = "openflow_bubble"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Flow bubble", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("OpenFlow")
            .setContentText("Flow bubble is on. Tap it in any app to dictate.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildBubble() {
        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Microphone permission is needed for the bubble.", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val sizePx = dp(prefs.bubbleSize)
        val colors = BUBBLE_COLORS[prefs.bubbleColor]
        val bg = GradientDrawable()
        bg.orientation = GradientDrawable.Orientation.TL_BR
        bg.colors = intArrayOf(colors.first.toInt(), colors.second.toInt())
        bg.cornerRadius = if (prefs.bubbleShape == "pill") sizePx / 2f else sizePx / 2f
        bg.alpha = (prefs.bubbleOpacity * 255 / 100).coerceIn(0, 255)
        bubbleBg = bg

        val root = FrameLayout(this)
        root.background = bg
        micIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(Color.WHITE)
        }
        val iconSize = (sizePx * 0.45).toInt()
        val iconParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
        root.addView(micIcon, iconParams)

        waveRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        repeat(7) {
            val bar = View(this).apply {
                // Rounded caps, like the Compose preview.
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.WHITE)
                    cornerRadius = dp(2).toFloat()
                }
                alpha = 0.9f
            }
            val barParams = LinearLayout.LayoutParams(dp(4), dp(10))
            barParams.setMargins(dp(2), 0, dp(2), 0)
            waveRow?.addView(bar, barParams)
        }
        root.addView(waveRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))

        val metrics = resources.displayMetrics
        val widthPx = metrics.widthPixels
        val heightPx = metrics.heightPixels
        val bubbleWidth = if (prefs.bubbleShape == "pill") (sizePx * 1.9).toInt() else sizePx
        params = WindowManager.LayoutParams(
            bubbleWidth,
            sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lastX ?: (widthPx - bubbleWidth - dp(14))
            y = lastY ?: (heightPx * 0.58).toInt()
        }
        bubbleView = root
        root.setOnTouchListener(DragTouchListener())
        try {
            windowManager?.addView(root, params)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not show the bubble: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var downTime = 0L
        private var dragged = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params?.x ?: 0
                    startY = params?.y ?: 0
                    downTime = System.currentTimeMillis()
                    dragged = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(8)) dragged = true
                    if (dragged) {
                        params?.x = startX + dx
                        params?.y = startY + dy
                        try { windowManager?.updateViewLayout(bubbleView, params) } catch (_: Exception) { }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        lastX = params?.x
                        lastY = params?.y
                        if (prefs.snapToEdge) snapToEdge()
                    } else if (System.currentTimeMillis() - downTime < 500) {
                        onBubbleTap()
                    }
                    return true
                }
            }
            return false
        }
    }

    /** Remove the current bubble and build it again with the latest prefs,
     *  keeping the position the user dragged it to. */
    private fun rebuildBubble() {
        lastX = params?.x
        lastY = params?.y
        stopWaveAnimation()
        try {
            bubbleView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) { }
        bubbleView = null
        micIcon = null
        waveRow = null
        bubbleBg = null
        buildBubble()
    }

    private fun snapToEdge() {
        val p = params ?: return
        val widthPx = resources.displayMetrics.widthPixels
        val bubbleWidth = p.width
        p.x = if (p.x + bubbleWidth / 2 < widthPx / 2) dp(10) else widthPx - bubbleWidth - dp(10)
        lastX = p.x
        lastY = p.y
        try { windowManager?.updateViewLayout(bubbleView, p) } catch (_: Exception) { }
        haptic()
    }

    private fun haptic() {
        if (!prefs.haptic) return
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(12)
            }
        } catch (_: Exception) { }
    }

    private fun setBubbleAppearance(recording: Boolean, transcribing: Boolean) {
        val bg = bubbleBg ?: return
        val sizePx = dp(prefs.bubbleSize)
        val colors = BUBBLE_COLORS[prefs.bubbleColor]
        val stateColors = when {
            recording -> intArrayOf(Color.parseColor("#EF4444"), Color.parseColor("#EF4444"))
            else -> intArrayOf(colors.first.toInt(), colors.second.toInt())
        }
        bg.colors = stateColors
        val width = when {
            recording || transcribing -> (sizePx * 1.9).toInt()
            prefs.bubbleShape == "pill" -> (sizePx * 1.9).toInt()
            else -> sizePx
        }
        params?.width = width
        try { windowManager?.updateViewLayout(bubbleView, params) } catch (_: Exception) { }
        micIcon?.visibility = if (recording || transcribing) View.GONE else View.VISIBLE
        if (prefs.showWaveform && (recording || transcribing)) {
            waveRow?.visibility = View.VISIBLE
            startWaveAnimation()
        } else {
            waveRow?.visibility = View.GONE
            stopWaveAnimation()
            if (!prefs.showWaveform && recording) micIcon?.visibility = View.VISIBLE
        }
        bubbleView?.invalidate()
    }

    private fun startWaveAnimation() {
        stopWaveAnimation()
        val row = waveRow ?: return
        // Seven bars on one shared ease-in-out curve, each starting a
        // fraction later — a smooth wave, not a mechanical tick. Runs
        // while recording and while transcribing.
        waveAnimators = (0 until row.childCount).map { i ->
            val bar = row.getChildAt(i)
            ObjectAnimator.ofFloat(bar, "scaleY", 0.35f, 1.7f).apply {
                duration = 650L
                startDelay = i * 110L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    private fun stopWaveAnimation() {
        waveAnimators.forEach { try { it.cancel() } catch (_: Exception) { } }
        waveAnimators = emptyList()
    }

    private fun onBubbleTap() {
        haptic()
        when (state) {
            BubbleState.IDLE -> startRecording()
            BubbleState.RECORDING -> stopAndTranscribe()
            BubbleState.TRANSCRIBING -> { /* ignore taps while transcribing */ }
        }
    }

    private fun startRecording() {
        val key = KeyStore.getGroqKey(this)
        if (key.isNullOrBlank()) {
            Toast.makeText(this, "Add your Groq API key in OpenFlow Settings first.", Toast.LENGTH_LONG).show()
            return
        }
        val file = File(cacheDir, "bubble_recording.wav")
        val rec = WavRecorder()
        if (!rec.start(file)) {
            Toast.makeText(this, "Could not start the microphone.", Toast.LENGTH_LONG).show()
            return
        }
        recorder = rec
        recordingFile = file
        state = BubbleState.RECORDING
        setBubbleAppearance(recording = true, transcribing = false)
    }

    private fun stopAndTranscribe() {
        val rec = recorder ?: return
        val file = recordingFile ?: return
        state = BubbleState.TRANSCRIBING
        setBubbleAppearance(recording = false, transcribing = true)
        val seconds = rec.stop(file)
        recorder = null
        scope.launch(Dispatchers.IO) {
            val key = KeyStore.getGroqKey(this@BubbleService)
            if (key.isNullOrBlank()) {
                finishTranscription(null, "No Groq API key saved.")
                return@launch
            }
            when (val result = GroqClient.transcribe(key, file)) {
                is TranscribeResult.Ok -> {
                    var text = result.text.trim()
                    if (prefs.autoPunctuation && text.isNotEmpty() && text.last() !in listOf('.', '!', '?')) {
                        text += "."
                    }
                    Store.addDictation(this@BubbleService, text, seconds.toInt())
                    finishTranscription(text, null)
                }
                is TranscribeResult.InvalidKey ->
                    finishTranscription(null, "Groq key invalid — check it in Settings.")
                is TranscribeResult.RateLimited ->
                    finishTranscription(null, "Groq daily limit reached — try tomorrow.")
                is TranscribeResult.Error ->
                    finishTranscription(null, result.message)
            }
        }
    }

    private fun finishTranscription(text: String?, error: String?) {
        scope.launch(Dispatchers.Main) {
            state = BubbleState.IDLE
            setBubbleAppearance(recording = false, transcribing = false)
            if (pendingRebuild) {
                pendingRebuild = false
                rebuildBubble()
            }
            if (error != null) {
                Toast.makeText(this@BubbleService, error, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (text.isNullOrBlank()) {
                Toast.makeText(this@BubbleService, "No text was transcribed.", Toast.LENGTH_LONG).show()
                return@launch
            }
            val inserted = OpenFlowAccessibilityService.insertText(this@BubbleService, text)
            if (inserted) {
                Toast.makeText(this@BubbleService, "Inserted", Toast.LENGTH_SHORT).show()
            } else {
                OpenFlowAccessibilityService.copyToClipboard(this@BubbleService, text)
                Toast.makeText(this@BubbleService, "Copied — long-press the field and tap Paste", Toast.LENGTH_LONG).show()
            }
        }
    }
}
