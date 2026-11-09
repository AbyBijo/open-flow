package com.abybijo.openflow

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The floating Flow bubble: a TYPE_APPLICATION_OVERLAY window that sits over
 * every app. FLAG_NOT_FOCUSABLE so tapping it never steals the text-field
 * cursor in the app underneath. Tap to record, tap again to stop and
 * transcribe with Groq, then insert at the cursor (or copy to clipboard).
 *
 * The bubble is a classic View overlay, not Compose, so there is no
 * recomposition anywhere here: the waveform bars move by setting scaleY
 * (a draw-phase property) from a 55 ms poll, and dragging only updates
 * the window position. Nothing else runs on the main thread while
 * dragging or recording.
 */
class BubbleService : Service() {

    companion object {
        /** Sent by ReviewActivity when the user taps Insert on a reviewed dictation. */
        const val ACTION_INSERT_REVIEWED = "com.abybijo.openflow.INSERT_REVIEWED"
        /** Bars in the recording waveform — long enough to read as a voice trace. */
        private const val WAVE_BAR_COUNT = 11
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var windowManager: WindowManager? = null
    private var bubbleView: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var recorder: WavRecorder? = null
    private var recordingFile: File? = null
    /** The app the current dictation started in — the insert never goes anywhere else. */
    private var recordingHostPackage: String? = null
    private var state: BubbleState = BubbleState.IDLE
    private var prefs: Prefs = Prefs()
    private var wavePollJob: Job? = null
    private var resultResetJob: Job? = null
    private var snapAnimator: ValueAnimator? = null
    private var micIcon: ImageView? = null
    private var checkIcon: ImageView? = null
    private var copiedText: TextView? = null
    private var waveRow: LinearLayout? = null
    private var waveBars: List<View> = emptyList()
    private var liveDot: View? = null
    /** Three dots for the transcribing state — deliberately not bars. */
    private var thinkingRow: LinearLayout? = null
    private var thinkingDots: List<View> = emptyList()
    private var thinkingAnimators: List<Animator> = emptyList()
    /** Drawable alpha from the user's opacity preset; the breathing glow animates around it. */
    private var bubbleBaseAlpha: Int = 255
    /** Scrolling history of smoothed mic levels, newest on the right. */
    private var waveLevels = FloatArray(WAVE_BAR_COUNT) { 0.2f }
    private var smoothLevel = 0.2f
    private var bubbleBg: GradientDrawable? = null
    private var lastX: Int? = null
    private var lastY: Int? = null
    private var pendingRebuild = false

    private enum class BubbleState { IDLE, RECORDING, TRANSCRIBING, INSERTED, COPIED }

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
                    firstBuild -> {
                        buildBubble()
                        // On-device engine: warm the model now, so the
                        // first dictation after a reboot is fast.
                        if (newPrefs.engine == "local") {
                            scope.launch { warmLocalEngine(this@BubbleService, newPrefs.localModelId) }
                        }
                    }
                    appearanceChanged && state == BubbleState.IDLE -> rebuildBubble()
                    appearanceChanged -> pendingRebuild = true
                    else -> { /* Non-appearance change: keep the bubble as it is. */ }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_INSERT_REVIEWED) {
            val text = intent.getStringExtra(ReviewActivity.EXTRA_TEXT).orEmpty()
            if (text.isNotBlank()) {
                // The review screen just finished; give the host app a beat
                // to come back to the front, then insert. The event-driven
                // pending path covers it if the app is still settling.
                scope.launch {
                    delay(350)
                    insertTranscribedText(text)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseLocalEngine()
        stopWaveAnimation()
        snapAnimator?.cancel()
        snapAnimator = null
        OpenFlowAccessibilityService.clearPending()
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
        bubbleBaseAlpha = bg.alpha

        val root = FrameLayout(this)
        root.background = bg
        val iconSize = (sizePx * 0.45).toInt()
        val iconParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
        micIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(Color.WHITE)
        }
        root.addView(micIcon, iconParams)
        // Green check for the Inserted state. It morphs in over 250 ms.
        checkIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_check)
            setColorFilter(Color.WHITE)
            visibility = View.GONE
        }
        root.addView(checkIcon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
        // "Copied" pill for the clipboard-fallback state.
        copiedText = TextView(this).apply {
            text = "Copied"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(copiedText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))

        waveRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val bars = mutableListOf<View>()
        repeat(WAVE_BAR_COUNT) {
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
            bars.add(bar)
        }
        waveBars = bars
        // The live dot rides at the leading edge of the waveform. Its
        // brightness follows the real mic level — bright while speaking,
        // dim in pauses — never a fixed timer.
        liveDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            alpha = 0.3f
            visibility = View.GONE
        }
        val dotParams = LinearLayout.LayoutParams(dp(6), dp(6))
        dotParams.setMargins(dp(4), 0, 0, 0)
        waveRow?.addView(liveDot, dotParams)
        root.addView(waveRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        // Thinking dots for the transcribing state. Three dots that bob
        // and breathe while the words are being worked out — deliberately
        // not bars, so the two states can never be confused: the bars
        // mean "I can hear you", the dots mean "I'm thinking".
        thinkingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val dots = mutableListOf<View>()
        repeat(3) {
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
                alpha = 0.6f
            }
            val dotParams = LinearLayout.LayoutParams(dp(8), dp(8))
            dotParams.setMargins(dp(3), 0, dp(3), 0)
            thinkingRow?.addView(dot, dotParams)
            dots.add(dot)
        }
        thinkingDots = dots
        root.addView(thinkingRow, FrameLayout.LayoutParams(
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
        root.setOnTouchListener(DragTouchListener(root))
        try {
            windowManager?.addView(root, params)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not show the bubble: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        applyState()
    }

    /**
     * Drag to move, tap to dictate, long-press to hold-to-talk.
     * Dragging does nothing but move the window — no other work runs on
     * the main thread while the finger is down. A long-press (~500 ms,
     * without dragging past the slop) starts recording; releasing stops
     * it and sends. A normal tap keeps tap-to-start / tap-to-stop.
     */
    private inner class DragTouchListener(private val rootView: View) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var downTime = 0L
        private var dragged = false
        private var holdingToTalk = false
        private val holdRunnable = Runnable {
            // The bubble may have been rebuilt under the finger; only the
            // current bubble may start a hold-to-talk recording.
            if (bubbleView === rootView && !dragged && state == BubbleState.IDLE) {
                holdingToTalk = true
                haptic()
                startRecording()
            }
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params?.x ?: 0
                    startY = params?.y ?: 0
                    downTime = System.currentTimeMillis()
                    dragged = false
                    holdingToTalk = false
                    snapAnimator?.cancel()
                    v.postDelayed(holdRunnable, 500)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (!dragged && kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(8)) {
                        // Past the drag slop: this is a drag, not a hold.
                        v.removeCallbacks(holdRunnable)
                        dragged = true
                    }
                    if (dragged) {
                        params?.x = startX + dx
                        params?.y = startY + dy
                        try { windowManager?.updateViewLayout(bubbleView, params) } catch (_: Exception) { }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(holdRunnable)
                    if (holdingToTalk) {
                        holdingToTalk = false
                        if (dragged) {
                            lastX = params?.x
                            lastY = params?.y
                            if (prefs.snapToEdge) snapToEdge()
                        }
                        stopAndTranscribe()
                    } else if (dragged) {
                        lastX = params?.x
                        lastY = params?.y
                        if (prefs.snapToEdge) snapToEdge()
                    } else if (System.currentTimeMillis() - downTime < 500) {
                        onBubbleTap()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(holdRunnable)
                    if (holdingToTalk) {
                        holdingToTalk = false
                        stopAndTranscribe()
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
        snapAnimator?.cancel()
        snapAnimator = null
        try {
            bubbleView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) { }
        bubbleView = null
        micIcon = null
        checkIcon = null
        copiedText = null
        waveRow = null
        waveBars = emptyList()
        liveDot = null
        thinkingRow = null
        thinkingDots = emptyList()
        bubbleBg = null
        buildBubble()
    }

    /** Settle against the nearest screen edge with a short spring overshoot. */
    private fun snapToEdge() {
        val p = params ?: return
        val widthPx = resources.displayMetrics.widthPixels
        val bubbleWidth = p.width
        val targetX = if (p.x + bubbleWidth / 2 < widthPx / 2) dp(10) else widthPx - bubbleWidth - dp(10)
        val startX = p.x
        snapAnimator?.cancel()
        if (startX == targetX) {
            lastX = p.x
            lastY = p.y
            haptic()
            return
        }
        snapAnimator = ValueAnimator.ofInt(startX, targetX).apply {
            duration = 300
            interpolator = OvershootInterpolator(0.8f)
            addUpdateListener { anim ->
                params?.x = anim.animatedValue as Int
                lastX = params?.x
                lastY = params?.y
                try { windowManager?.updateViewLayout(bubbleView, params) } catch (_: Exception) { }
            }
            start()
        }
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

    /** Two quick ticks for the Copied state. */
    private fun hapticDouble() {
        if (!prefs.haptic) return
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 12, 80, 12), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(12)
            }
        } catch (_: Exception) { }
    }

    /**
     * The single place state changes. Inserted shows the green check
     * morphing in over 250 ms, then returns to Idle; Copied shows the
     * pill for 1.5 s, then returns to Idle. A deferred bubble rebuild
     * runs when the state settles back to Idle.
     */
    private fun setState(newState: BubbleState) {
        resultResetJob?.cancel()
        resultResetJob = null
        val changed = state != newState
        state = newState
        if (changed) applyState()
        when (newState) {
            BubbleState.INSERTED -> {
                haptic()
                resultResetJob = scope.launch {
                    delay(500)
                    setState(BubbleState.IDLE)
                }
            }
            BubbleState.COPIED -> {
                hapticDouble()
                resultResetJob = scope.launch {
                    delay(1500)
                    setState(BubbleState.IDLE)
                }
            }
            BubbleState.IDLE -> {
                if (pendingRebuild) {
                    pendingRebuild = false
                    rebuildBubble()
                }
            }
            else -> { /* Recording / Transcribing have no timed reset. */ }
        }
    }

    /** Draw the current state: colours, width, icon, waveform, check, pill. */
    private fun applyState() {
        val bg = bubbleBg ?: return
        val sizePx = dp(prefs.bubbleSize)
        val colors = BUBBLE_COLORS[prefs.bubbleColor]
        val stateColors = when (state) {
            BubbleState.RECORDING -> intArrayOf(Color.parseColor("#EF4444"), Color.parseColor("#EF4444"))
            BubbleState.INSERTED -> intArrayOf(Color.parseColor("#16A34A"), Color.parseColor("#16A34A"))
            else -> intArrayOf(colors.first.toInt(), colors.second.toInt())
        }
        bg.colors = stateColors
        // The recording and transcribing pills are longer (2.6x) so the
        // eleven-bar waveform and the thinking dots have room to breathe.
        val width = when (state) {
            BubbleState.RECORDING, BubbleState.TRANSCRIBING -> (sizePx * 2.6).toInt()
            BubbleState.COPIED -> (sizePx * 1.9).toInt()
            BubbleState.INSERTED -> sizePx
            BubbleState.IDLE -> if (prefs.bubbleShape == "pill") (sizePx * 1.9).toInt() else sizePx
        }
        params?.width = width
        try { windowManager?.updateViewLayout(bubbleView, params) } catch (_: Exception) { }
        // Mic: idle, or recording with the waveform switched off.
        micIcon?.visibility =
            if (state == BubbleState.IDLE || (state == BubbleState.RECORDING && !prefs.showWaveform)) View.VISIBLE
            else View.GONE
        // Check: only in the Inserted state, morphing in over 250 ms.
        if (state == BubbleState.INSERTED) {
            checkIcon?.visibility = View.VISIBLE
            checkIcon?.scaleX = 0.5f
            checkIcon?.scaleY = 0.5f
            ObjectAnimator.ofFloat(checkIcon, "scaleX", 0.5f, 1f).apply { duration = 250; start() }
            ObjectAnimator.ofFloat(checkIcon, "scaleY", 0.5f, 1f).apply { duration = 250; start() }
        } else {
            checkIcon?.visibility = View.GONE
        }
        copiedText?.visibility = if (state == BubbleState.COPIED) View.VISIBLE else View.GONE
        // Waveform while recording, thinking dots while transcribing.
        // The bars mean "I can hear you", the dots mean "I'm thinking".
        when (state) {
            BubbleState.RECORDING -> {
                if (prefs.showWaveform) {
                    waveRow?.visibility = View.VISIBLE
                    liveDot?.visibility = View.VISIBLE
                    thinkingRow?.visibility = View.GONE
                    startMicWavePoll()
                } else {
                    waveRow?.visibility = View.GONE
                    thinkingRow?.visibility = View.GONE
                    stopWaveAnimation()
                }
            }
            BubbleState.TRANSCRIBING -> {
                waveRow?.visibility = View.GONE
                thinkingRow?.visibility = View.VISIBLE
                startThinkingAnimation()
            }
            else -> {
                waveRow?.visibility = View.GONE
                thinkingRow?.visibility = View.GONE
                stopWaveAnimation()
            }
        }
        bubbleView?.invalidate()
    }

    /**
     * Transcribing, made unmistakable: three dots bobbing up and down
     * on staggered beats, over a slow breathing glow (~2.4 s cycle)
     * and a gentle pill pulse in step with it. Nothing here moves like
     * a bar, so it can never be mistaken for listening.
     */
    private fun startThinkingAnimation() {
        stopWaveAnimation()
        val bg = bubbleBg ?: return
        val view = bubbleView ?: return
        val dotAnimators = thinkingDots.mapIndexed { i, dot ->
            ObjectAnimator.ofFloat(dot, "translationY", 0f, -dp(5).toFloat(), 0f).apply {
                duration = 900L
                startDelay = i * 180L
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        } + thinkingDots.mapIndexed { i, dot ->
            ObjectAnimator.ofFloat(dot, "alpha", 0.45f, 1f).apply {
                duration = 900L
                startDelay = i * 180L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
        val glowAnimator = ObjectAnimator.ofInt(
            bg, "alpha", (bubbleBaseAlpha * 0.8f).toInt(), bubbleBaseAlpha
        ).apply {
            duration = 1200L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
        val pulseX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.04f).apply {
            duration = 1200L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        val pulseY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.04f).apply {
            duration = 1200L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        thinkingAnimators = dotAnimators + glowAnimator + pulseX + pulseY
    }

    /**
     * Mic-driven waveform: every 55 ms the live level is read and the
     * bars shift left, newest on the right. The level is smoothed with
     * a fast attack (jump straight up, ~one poll) and a slow release
     * (~200 ms decay), so the bars move fluidly and never jitter.
     * Bar heights and the dot's brightness are set directly on the
     * Views — draw-phase properties, no layout, no recomposition.
     * The level array is a primitive FloatArray: nothing is allocated
     * in this loop.
     */
    /**
     * Mic-driven waveform: every 55 ms the live level is read and the
     * bars shift left, newest on the right — a scrolling voice trace,
     * not a static row. The level is smoothed with a fast attack (jump
     * straight up, ~one poll) and a slow release (~200 ms decay), so
     * the bars move fluidly and never jitter. Older bars fade with
     * age, newest brightest. Bar heights and the dot's brightness are
     * set directly on the Views — draw-phase properties, no layout,
     * no recomposition. The level array is a primitive FloatArray:
     * nothing is allocated in this loop.
     */
    private fun startMicWavePoll() {
        stopWaveAnimation()
        waveLevels = FloatArray(WAVE_BAR_COUNT) { 0.2f }
        smoothLevel = 0.2f
        wavePollJob = scope.launch {
            while (state == BubbleState.RECORDING) {
                val raw = recorder?.amplitude() ?: 0.2f
                smoothLevel = if (raw >= smoothLevel) raw
                else smoothLevel + (raw - smoothLevel) * 0.24f
                for (i in 0 until WAVE_BAR_COUNT - 1) waveLevels[i] = waveLevels[i + 1]
                waveLevels[WAVE_BAR_COUNT - 1] = smoothLevel
                for (i in waveBars.indices) {
                    waveBars[i].scaleY = 0.35f + waveLevels[i] * 1.35f
                    waveBars[i].alpha = 0.35f + 0.65f * (i.toFloat() / (WAVE_BAR_COUNT - 1))
                }
                liveDot?.alpha = 0.3f + smoothLevel * 0.7f
                delay(55)
            }
        }
    }

    private fun stopWaveAnimation() {
        wavePollJob?.cancel()
        wavePollJob = null
        thinkingAnimators.forEach { try { it.cancel() } catch (_: Exception) { } }
        thinkingAnimators = emptyList()
        bubbleBg?.alpha = bubbleBaseAlpha
        bubbleView?.scaleX = 1f
        bubbleView?.scaleY = 1f
    }

    private fun onBubbleTap() {
        haptic()
        when (state) {
            BubbleState.IDLE, BubbleState.INSERTED, BubbleState.COPIED -> startRecording()
            BubbleState.RECORDING -> stopAndTranscribe()
            BubbleState.TRANSCRIBING -> { /* ignore taps while transcribing */ }
        }
    }

    private fun startRecording() {
        if (prefs.engine == "local") {
            // On-device needs no key — but it needs a model. Recording
            // is still allowed without one: the transcribe step falls
            // back to Groq when a key exists. Only block when there is
            // no way to transcribe at all.
            val modelReady = ModelDownloader.isDownloaded(
                this, ModelCatalog.byId(prefs.localModelId)
            )
            if (!modelReady && KeyStore.getGroqKey(this).isNullOrBlank()) {
                Toast.makeText(
                    this,
                    "Download an on-device model in Settings, or add your Groq key.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        } else {
            val key = KeyStore.getGroqKey(this)
            if (key.isNullOrBlank()) {
                Toast.makeText(this, "Add your Groq API key in OpenFlow Settings first.", Toast.LENGTH_LONG).show()
                return
            }
        }
        val file = File(cacheDir, "bubble_recording.wav")
        val rec = WavRecorder()
        if (!rec.start(file)) {
            Toast.makeText(this, "Could not start the microphone.", Toast.LENGTH_LONG).show()
            return
        }
        recorder = rec
        recordingFile = file
        recordingHostPackage = OpenFlowAccessibilityService.currentHostPackage()
        setState(BubbleState.RECORDING)
    }

    private fun stopAndTranscribe() {
        val rec = recorder ?: return
        val file = recordingFile ?: return
        recorder = null
        recordingFile = null
        setState(BubbleState.TRANSCRIBING)
        scope.launch(Dispatchers.IO) {
            // WAV finalisation (thread join + header write) happens off
            // the main thread: tap-stop must return instantly.
            val seconds = rec.stop(file)
            // The selected engine transcribes; when On-device is picked
            // but the model is missing or local transcription fails,
            // Groq is used for this utterance instead — the dictation
            // is never lost. Only Groq seconds count toward the usage
            // ring; on-device dictations are free.
            val (result, usedGroq) = transcribeWithFallback(
                this@BubbleService,
                prefs.engine,
                prefs.localModelId,
                file,
                onFallback = { message ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@BubbleService, message, Toast.LENGTH_LONG).show()
                    }
                }
            )
            when (result) {
                is TranscribeResult.Ok -> {
                    var text = result.text.trim()
                    if (prefs.cleanTranscript) text = cleanTranscript(text)
                    if (prefs.autoPunctuation && text.isNotEmpty() && text.last() !in listOf('.', '!', '?')) {
                        text += "."
                    }
                    Store.addDictation(this@BubbleService, text, seconds.toInt(), countUsage = usedGroq)
                    val finalText = text
                    withContext(Dispatchers.Main) { handleTranscribedText(finalText) }
                }
                is TranscribeResult.InvalidKey -> withContext(Dispatchers.Main) {
                    setState(BubbleState.IDLE)
                    Toast.makeText(this@BubbleService, "Groq key invalid — check it in Settings.", Toast.LENGTH_LONG).show()
                }
                is TranscribeResult.RateLimited -> withContext(Dispatchers.Main) {
                    setState(BubbleState.IDLE)
                    Toast.makeText(this@BubbleService, "Groq daily limit reached — try tomorrow.", Toast.LENGTH_LONG).show()
                }
                is TranscribeResult.LocalModelMissing -> withContext(Dispatchers.Main) {
                    // Unreachable through transcribeWithFallback (it falls
                    // back to Groq first); kept so the sealed `when` stays
                    // exhaustive if the routing ever changes.
                    setState(BubbleState.IDLE)
                    Toast.makeText(this@BubbleService, "On-device model not downloaded. Download one in Settings.", Toast.LENGTH_LONG).show()
                }
                is TranscribeResult.Error -> withContext(Dispatchers.Main) {
                    setState(BubbleState.IDLE)
                    Toast.makeText(this@BubbleService, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Route a finished transcription: review screen when enabled, insert otherwise. */
    private fun handleTranscribedText(text: String) {
        if (text.isBlank()) {
            setState(BubbleState.IDLE)
            Toast.makeText(this, "No text was transcribed.", Toast.LENGTH_LONG).show()
            return
        }
        if (prefs.reviewBeforeInsert) {
            setState(BubbleState.IDLE)
            val intent = Intent(this, ReviewActivity::class.java).apply {
                putExtra(ReviewActivity.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(intent)
            } catch (_: Exception) {
                // Review screen could not open — fall back to auto-insert.
                insertTranscribedText(text)
            }
            return
        }
        insertTranscribedText(text)
    }

    private fun insertTranscribedText(text: String) {
        OpenFlowAccessibilityService.requestInsert(this, text, recordingHostPackage) { inserted ->
            deliverInsertResult(text, inserted)
        }
    }

    private fun deliverInsertResult(text: String, inserted: Boolean) {
        recordingHostPackage = null
        if (inserted) {
            setState(BubbleState.INSERTED)
            Toast.makeText(this, "Inserted", Toast.LENGTH_SHORT).show()
        } else {
            OpenFlowAccessibilityService.copyToClipboard(this, text)
            setState(BubbleState.COPIED)
            Toast.makeText(this, "Copied — long-press the field and tap Paste", Toast.LENGTH_LONG).show()
        }
    }
}
