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
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.animation.PathInterpolatorCompat
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
        /** Bars in the recording waveform — seven tall bars, newest on the right. */
        private const val WAVE_BAR_COUNT = 7
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
    /** Long-pause auto-stop poll — runs during every recording, even
     * when the waveform is hidden (that poll only runs when bars are
     * shown, so this one is separate). */
    private var autoStopJob: Job? = null
    /** True when the current recording started from hold-to-talk.
     * Auto-stop only applies to tap-initiated recordings — releasing
     * already stops a hold, so a hold must never be auto-stopped. */
    private var recordingByHold = false
    private var resultResetJob: Job? = null
    private var snapAnimator: ValueAnimator? = null
    private var micIcon: ImageView? = null
    private var checkIcon: ImageView? = null
    private var copiedText: TextView? = null
    private var waveRow: LinearLayout? = null
    private var waveBars: List<View> = emptyList()
    private var liveDot: View? = null
    /** Cancel (X) at the right end of the recording waveform — taps
     * cancel the recording, discarding the audio. A clickable child
     * consumes its own touches before the root drag listener sees
     * them, so tapping it never starts a drag or a tap-to-stop. */
    private var cancelIcon: ImageView? = null
    /** Three dots for the transcribing state, in a row that is shown
     * only while thinking. Dots animate alpha only — no view is ever
     * scaled past 1.0, so nothing can be clipped by the window
     * bounds the way the old breathing round was. */
    private var dotsRow: LinearLayout? = null
    private var dotViews: List<View> = emptyList()
    /** Animators for the transcribing state (dots + glow) — all
     * cancelled by stopWaveAnimation on every state exit. */
    private var thinkingAnimators: List<Animator> = emptyList()
    /** Drag-to-dismiss target (bottom centre, shown only while dragging). */
    private var dismissLayout: LinearLayout? = null
    private var dismissCircle: FrameLayout? = null
    private var dismissCircleBg: GradientDrawable? = null
    /** Slide animator for the whole dismiss bar — the only animation
     * the target has. The X icon itself never animates: no scale,
     * pulse or fade on it, ever. */
    private var dismissSlideAnimator: ObjectAnimator? = null
    private var dismissParams: WindowManager.LayoutParams? = null
    private var overDismiss = false
    /** Width morph between the compact bubble and the recording capsule. */
    private var widthAnimator: ValueAnimator? = null
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
        widthAnimator?.cancel()
        widthAnimator = null
        OpenFlowAccessibilityService.clearPending()
        // The service can be killed mid-recording (swiped away, system
        // reclaim). The recorder must be stopped here, or its thread
        // keeps running and the microphone stays held after the
        // service is gone. The audio is discarded — nothing is
        // transcribed from a killed service.
        val rec = recorder
        val file = recordingFile
        recorder = null
        recordingFile = null
        if (rec != null && file != null) {
            try { rec.stop(file) } catch (_: Exception) { }
            try { file.delete() } catch (_: Exception) { }
        }
        try {
            bubbleView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) { }
        bubbleView = null
        try {
            dismissLayout?.let { windowManager?.removeView(it) }
        } catch (_: Exception) { }
        dismissLayout = null
        dismissCircle = null
        dismissCircleBg = null
        dismissParams = null
        dismissSlideAnimator?.cancel()
        dismissSlideAnimator = null
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
            val barParams = LinearLayout.LayoutParams(dp(4), dp(13))
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
        // Cancel (X) at the right end of the waveform, small like Wispr
        // Flow's: one tap cancels the recording — audio discarded,
        // nothing transcribed, no history entry. It is a clickable
        // child, so it consumes its own tap before the root drag
        // listener can see it: it can never start a drag or count as
        // a tap-to-stop. It rides inside the wave row so it is always
        // next to the bars, whatever the bubble size is.
        cancelIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(Color.WHITE)
            alpha = 0.85f
            isClickable = true
            setOnClickListener { cancelRecording() }
        }
        val cancelParams = LinearLayout.LayoutParams(dp(16), dp(16))
        cancelParams.setMargins(dp(6), 0, 0, 0)
        waveRow?.addView(cancelIcon, cancelParams)
        root.addView(waveRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        // Three dots for the transcribing state — the loading indicator.
        // A row of dots fits the small overlay; a loading screen cannot,
        // and opening a full-screen loading UI would cover the very app
        // the user is dictating into. The dots animate alpha only, so
        // nothing is ever scaled past the window bounds.
        dotsRow = LinearLayout(this).apply {
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
                alpha = 0.3f
            }
            val dotLayout = LinearLayout.LayoutParams(dp(8), dp(8))
            dotLayout.setMargins(dp(3), 0, dp(3), 0)
            dotsRow?.addView(dot, dotLayout)
            dots.add(dot)
        }
        dotViews = dots
        root.addView(dotsRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        // The root never clips its children — the window itself is the
        // only bound, and no child is ever scaled past 1.0, so no
        // animation can be cut off at the bubble's edges.
        root.clipChildren = false

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
        addDismissTarget()
        applyState()
    }

    /**
     * Drag-to-dismiss target: a red circle with an X and a "Turn off
     * bubble" label, pinned to the bottom centre of the screen. It is
     * a separate overlay window that never takes touches
     * (FLAG_NOT_TOUCHABLE), so it can never steal a tap from the
     * bubble itself. Hidden until a drag starts.
     */
    private fun addDismissTarget() {
        val wm = windowManager ?: return
        if (dismissLayout != null) return
        val circleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#EF4444"))
            alpha = 200
        }
        dismissCircleBg = circleBg
        val circle = FrameLayout(this).apply {
            background = circleBg
        }
        val xIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(Color.WHITE)
        }
        val iconSize = dp(36)
        circle.addView(xIcon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
        dismissCircle = circle
        val label = TextView(this).apply {
            text = "Turn off bubble"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#99000000"))
            }
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // INVISIBLE, not GONE: an invisible layout is still
            // measured, so the overlay window is sized to the full
            // bar when it is added. A GONE layout measures ~0 height,
            // the window is created that small, and showing it later
            // never resized the window — the top of the red circle was
            // clipped off by the window's edge. Starting translation
            // is below the screen; the slide-in brings it up.
            visibility = View.INVISIBLE
            translationY = dp(200).toFloat()
        }
        // 96 dp circle: the bubble (44–84 dp, user size) must fit
        // inside it with room to spare at every size, so the drop
        // target is the circle the user actually sees.
        val circleSize = dp(96)
        layout.addView(circle, LinearLayout.LayoutParams(circleSize, circleSize))
        val labelParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        labelParams.topMargin = dp(8)
        layout.addView(label, labelParams)
        dismissLayout = layout
        dismissParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(32)
        }
        try {
            wm.addView(layout, dismissParams)
        } catch (_: Exception) {
            dismissLayout = null
            dismissCircle = null
            dismissCircleBg = null
            dismissParams = null
        }
    }

    /**
     * Screen-space centre of the dismiss circle at its resting place.
     * Read live: getLocationOnScreen includes the slide translation
     * while the bar is moving in or out, so the layout's current
     * translationY is subtracted back out — the magnetise must aim
     * at where the circle rests, not where it is mid-slide. The
     * layout is INVISIBLE (measured) from the start, so the width
     * is valid from the first drag; a GONE layout used to read 0
     * here, which sent the bubble to the wrong spot.
     */
    private fun dismissCenter(): Pair<Float, Float> {
        val circle = dismissCircle ?: return 0f to 0f
        if (circle.width <= 0) return 0f to 0f
        val loc = IntArray(2)
        circle.getLocationOnScreen(loc)
        val slideOffset = dismissLayout?.translationY ?: 0f
        return (loc[0] + circle.width / 2f) to (loc[1] + circle.height / 2f - slideOffset)
    }

    /**
     * True when the bubble's centre is over the dismiss circle. The
     * hit-test matches the visual circle: its radius (48 dp) plus a
     * small tolerance — not the old 70 dp, which fired while the
     * bubble was still well outside the circle.
     */
    private fun isOverDismiss(): Boolean {
        val p = params ?: return false
        val bubbleCx = p.x + p.width / 2f
        val bubbleCy = p.y + p.height / 2f
        val (cx, cy) = dismissCenter()
        if (cx == 0f && cy == 0f) return false
        val dx = bubbleCx - cx
        val dy = bubbleCy - cy
        return kotlin.math.hypot(dx.toDouble(), dy.toDouble()) < dp(56)
    }

    /**
     * Show the target. The whole bar slides up from below the screen
     * into place (~250 ms) — that slide is the target's only
     * animation. The X icon itself never animates: no scale, pulse
     * or fade on it. While the bubble is over the target, the solid
     * red circle becomes a ring (instant state change, not an
     * animation), so the bubble sits visibly inside it instead of
     * sliding underneath a solid disc. The X stays exactly as it is.
     */
    private fun showDismiss(over: Boolean) {
        val layout = dismissLayout ?: return
        if (layout.visibility != View.VISIBLE) {
            layout.visibility = View.VISIBLE
            // Force the window to re-measure/re-position now that the
            // bar is shown — belt and braces next to the INVISIBLE
            // sizing fix, so the window can never stay at its old
            // ~0-height size with the circle's top clipped off.
            try { windowManager?.updateViewLayout(layout, dismissParams) } catch (_: Exception) { }
            dismissSlideAnimator?.cancel()
            dismissSlideAnimator = ObjectAnimator.ofFloat(
                layout, "translationY", layout.translationY, 0f
            ).apply {
                duration = 250
                interpolator = DecelerateInterpolator()
                start()
            }
        }
        val bg = dismissCircleBg
        if (over) {
            // Ring, not a disc: transparent fill, red stroke — the
            // bubble inside it stays fully visible.
            bg?.setColor(Color.TRANSPARENT)
            bg?.setStroke(dp(3), Color.parseColor("#EF4444"))
            bg?.alpha = 255
        } else {
            bg?.setColor(Color.parseColor("#EF4444"))
            bg?.setStroke(0, Color.TRANSPARENT)
            bg?.alpha = 200
        }
    }

    /**
     * Hide the target: the whole bar slides down out of view, then
     * goes INVISIBLE (not GONE — the window must keep its measured
     * size, or the next show clips the circle again). [animate]
     * is false when the service or the bubble is being torn down
     * and there is nothing left to animate for.
     */
    private fun hideDismiss(animate: Boolean = true) {
        val layout = dismissLayout ?: return
        if (layout.visibility != View.VISIBLE && !animate) {
            layout.translationY = dp(200).toFloat()
            return
        }
        dismissSlideAnimator?.cancel()
        if (!animate) {
            layout.visibility = View.INVISIBLE
            layout.translationY = dp(200).toFloat()
            dismissCircleBg?.setColor(Color.parseColor("#EF4444"))
            dismissCircleBg?.setStroke(0, Color.TRANSPARENT)
            dismissCircleBg?.alpha = 200
            overDismiss = false
            return
        }
        val animator = ObjectAnimator.ofFloat(
            layout, "translationY", layout.translationY, dp(200).toFloat()
        ).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
        }
        dismissSlideAnimator = animator
        animator.addListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {}
            override fun onAnimationCancel(animation: Animator) {}
            override fun onAnimationRepeat(animation: Animator) {}
            override fun onAnimationEnd(animation: Animator) {
                if (dismissSlideAnimator !== animator) return
                layout.visibility = View.INVISIBLE
                layout.translationY = dp(200).toFloat()
                dismissCircleBg?.setColor(Color.parseColor("#EF4444"))
                dismissCircleBg?.setStroke(0, Color.TRANSPARENT)
                dismissCircleBg?.alpha = 200
            }
        })
        animator.start()
        overDismiss = false
    }

    /**
     * The user dropped the bubble on the dismiss target: turn the
     * bubble off for good (persisted, so it does not come back on the
     * next boot) and stop the service — without opening the app. A
     * recording in progress is stopped silently and its audio
     * discarded; nothing is transcribed or inserted. The position is
     * deliberately not saved: the last thing the user did was drag
     * the bubble onto dismiss, which is nowhere they would want it
     * to reappear.
     */
    private fun dismissBubble() {
        hideDismiss(animate = false)
        if (state == BubbleState.RECORDING) {
            cancelRecording()
        }
        haptic()
        scope.launch { Store.setBubbleEnabled(this@BubbleService, false) }
        stopSelf()
    }

    /** Stop a recording without transcribing or inserting anything. */
    private fun cancelRecording() {
        val rec = recorder
        val file = recordingFile
        recorder = null
        recordingFile = null
        recordingHostPackage = null
        stopWaveAnimation()
        if (rec != null && file != null) {
            scope.launch(Dispatchers.IO) {
                try { rec.stop(file) } catch (_: Exception) { }
                try { file.delete() } catch (_: Exception) { }
            }
        }
        setState(BubbleState.IDLE)
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
        private var dragged = false
        private var holdingToTalk = false
        private val holdRunnable = Runnable {
            // The bubble may have been rebuilt under the finger; only the
            // current bubble may start a hold-to-talk recording.
            if (bubbleView === rootView && !dragged && state == BubbleState.IDLE) {
                holdingToTalk = true
                recordingByHold = true
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
                    dragged = false
                    holdingToTalk = false
                    snapAnimator?.cancel()
                    v.postDelayed(holdRunnable, 500)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Once hold-to-talk is live the bubble is a mic button,
                    // not a draggable thing: dragging it would move it
                    // mid-sentence and could drop it on the dismiss target
                    // while recording. Tap-initiated recording (and idle)
                    // can still be dragged.
                    if (holdingToTalk) return true
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (!dragged && kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(8)) {
                        // Past the drag slop: this is a drag, not a hold.
                        v.removeCallbacks(holdRunnable)
                        dragged = true
                        // The dismiss target appears only for a drag from
                        // idle or from tap-initiated recording — never
                        // while transcribing, when the words are in flight.
                        if (state == BubbleState.IDLE || state == BubbleState.RECORDING) {
                            showDismiss(false)
                        }
                    }
                    if (dragged) {
                        params?.x = startX + dx
                        params?.y = startY + dy
                        if (state == BubbleState.IDLE || state == BubbleState.RECORDING) {
                            val wasOver = overDismiss
                            overDismiss = isOverDismiss()
                            // Tick on the way in, so "let go now and it
                            // is gone" is felt rather than watched.
                            if (overDismiss && !wasOver) haptic()
                            showDismiss(overDismiss)
                            if (overDismiss) {
                                // Magnetise into the target for a clear
                                // "drop to remove" feel.
                                val (cx, cy) = dismissCenter()
                                val w = params?.width ?: 0
                                val h = params?.height ?: 0
                                params?.x = (cx - w / 2f).toInt()
                                params?.y = (cy - h / 2f).toInt()
                            }
                        }
                        try { windowManager?.updateViewLayout(bubbleView, params) } catch (_: Exception) { }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(holdRunnable)
                    if (holdingToTalk) {
                        holdingToTalk = false
                        stopAndTranscribe()
                    } else if (dragged && overDismiss &&
                        (state == BubbleState.IDLE || state == BubbleState.RECORDING)
                    ) {
                        // Dropped on the target: turn the bubble off.
                        // Merely passing over it and releasing elsewhere
                        // does nothing — only a drop inside dismisses.
                        dismissBubble()
                    } else if (dragged) {
                        hideDismiss()
                        lastX = params?.x
                        lastY = params?.y
                        if (prefs.snapToEdge) snapToEdge()
                    } else {
                        // A clean press-release is always a tap, however
                        // long the press lasted. The 500 ms line only
                        // decides whether a hold-to-talk started; it must
                        // never silently drop a tap — a slow press to
                        // stop a recording used to do nothing at all.
                        onBubbleTap()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(holdRunnable)
                    hideDismiss()
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
        widthAnimator?.cancel()
        widthAnimator = null
        hideDismiss(animate = false)
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
        cancelIcon = null
        dotsRow = null
        dotViews = emptyList()
        thinkingAnimators = emptyList()
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
        // Width is the user's idle shape, except while recording and
        // while transcribing: one tap expands the compact bubble into
        // a 2.0x capsule for the seven-bar waveform plus the cancel X,
        // and it stays a capsule while the three dots load — the dots
        // need the width, and shrinking back to a round bubble while
        // thinking is what used to clip the loading animation at the
        // window's edges. The 2.0x capsule still fits the bars (7 x 8
        // dp) plus the live dot and the cancel X with room to spare,
        // even at the Small bubble size (48 dp -> 96 dp capsule).
        // Copied keeps a 1.9x pill: the word "Copied" cannot fit inside
        // a round bubble, whatever shape the user picked.
        val idleWidth = if (prefs.bubbleShape == "pill") (sizePx * 1.9).toInt() else sizePx
        val targetWidth = when (state) {
            BubbleState.RECORDING, BubbleState.TRANSCRIBING -> (sizePx * 2.0).toInt()
            BubbleState.COPIED -> (sizePx * 1.9).toInt()
            else -> idleWidth
        }
        // Animated morph (~280 ms, FastOutSlowInEasing), not a snap:
        // the bubble grows into the capsule and shrinks back. A new
        // state cancels the in-flight morph and starts from wherever
        // the width currently is.
        val currentWidth = params?.width ?: targetWidth
        widthAnimator?.cancel()
        widthAnimator = null
        if (currentWidth == targetWidth) {
            params?.width = targetWidth
            try { windowManager?.updateViewLayout(bubbleView, params) } catch (_: Exception) { }
        } else {
            widthAnimator = ValueAnimator.ofInt(currentWidth, targetWidth).apply {
                duration = 280
                interpolator = PathInterpolatorCompat.create(0.4f, 0f, 0.2f, 1f)
                addUpdateListener { anim ->
                    params?.width = anim.animatedValue as Int
                    try { windowManager?.updateViewLayout(bubbleView, params) } catch (_: Exception) { }
                }
                start()
            }
        }
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
        // Waveform while recording, three dots while transcribing.
        // The bars mean "I can hear you", the dots mean "I'm thinking".
        when (state) {
            BubbleState.RECORDING -> {
                dotsRow?.visibility = View.GONE
                if (prefs.showWaveform) {
                    waveRow?.visibility = View.VISIBLE
                    liveDot?.visibility = View.VISIBLE
                    startMicWavePoll()
                } else {
                    waveRow?.visibility = View.GONE
                    stopWaveAnimation()
                }
                // Runs whether or not the bars are shown: a hidden
                // waveform must not disable the long-pause stop.
                startAutoStopPoll()
            }
            BubbleState.TRANSCRIBING -> {
                waveRow?.visibility = View.GONE
                dotsRow?.visibility = View.VISIBLE
                startDotsAnimation()
            }
            else -> {
                waveRow?.visibility = View.GONE
                dotsRow?.visibility = View.GONE
                stopWaveAnimation()
            }
        }
        bubbleView?.invalidate()
    }

    /**
     * Transcribing, made unmistakable: three dots fading in and out in
     * turn, over a slow breathing glow. A full loading screen cannot
     * fit this small overlay — and opening one would cover the very
     * app the user is dictating into — so the dots are the loading
     * state. They animate alpha only: nothing here is scaled past
     * 1.0, which is what used to cut the old breathing round off at
     * the window's edges (the window is exactly the bubble's size,
     * so a view scaled to 1.04 or 1.18 overflows it and is clipped).
     * The dots mean "I'm thinking" and can never be mistaken for
     * the bars, which mean "I can hear you".
     */
    private fun startDotsAnimation() {
        stopWaveAnimation()
        val bg = bubbleBg ?: return
        val dots = dotViews
        if (dots.isEmpty()) return
        val animators = mutableListOf<Animator>()
        dots.forEachIndexed { i, dot ->
            val dotAlpha = ObjectAnimator.ofFloat(dot, "alpha", 0.3f, 1f).apply {
                duration = 500L
                startDelay = (i * 160).toLong()
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
            animators.add(dotAlpha)
        }
        val glowAnimator = ObjectAnimator.ofInt(
            bg, "alpha", (bubbleBaseAlpha * 0.8f).toInt(), bubbleBaseAlpha
        ).apply {
            duration = 1200L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
        animators.add(glowAnimator)
        thinkingAnimators = animators
    }

    /**
     * Mic-driven waveform: every 55 ms the live level is read and the
     * bars shift left, newest on the right — a scrolling voice trace,
     * not a static row. The level is eased, never stepped: it rises
     * toward a louder sound fast (65% per poll) and falls back
     * slower, so the bars jump with the voice instead of lagging it.
     * The height mapping is a square root, not linear: quiet speech
     * lifts the bars visibly while loud speech still stops at the
     * same peak — the cap never moves, only the sensitivity does.
     * All bars share one alpha: no left-to-right fade, the row reads
     * as one voice trace. Bar heights are set directly on the Views
     * — draw-phase properties, no layout, no recomposition. The level
     * array is a primitive FloatArray: nothing is allocated here.
     */
    private fun startMicWavePoll() {
        stopWaveAnimation()
        waveLevels = FloatArray(WAVE_BAR_COUNT) { 0.2f }
        smoothLevel = 0.2f
        wavePollJob = scope.launch {
            while (state == BubbleState.RECORDING) {
                val raw = recorder?.amplitude() ?: 0.2f
                // Eased, not stepped: a loud sound swells the bars up
                // over a couple of polls, silence lets them settle.
                // Attack is fast on purpose — the bars must react to
                // the voice the moment it starts, not a beat later.
                val rate = if (raw >= smoothLevel) 0.65f else 0.25f
                smoothLevel += (raw - smoothLevel) * rate
                for (i in 0 until WAVE_BAR_COUNT - 1) waveLevels[i] = waveLevels[i + 1]
                waveLevels[WAVE_BAR_COUNT - 1] = smoothLevel
                for (i in waveBars.indices) {
                    // Square root: level 0.2 reads 0.45, level 0.5 reads
                    // 0.71, level 1.0 still reads 1.0 — quiet speech
                    // moves the bars, loud speech hits the same peak.
                    val shaped = kotlin.math.sqrt(waveLevels[i].coerceIn(0f, 1f))
                    waveBars[i].scaleY = 0.35f + shaped * 1.35f
                    waveBars[i].alpha = 0.9f
                }
                liveDot?.alpha = 0.3f + smoothLevel * 0.7f
                delay(55)
            }
        }
    }

    /**
     * Long-pause auto-stop: if the user falls silent for longer than
     * [WavRecorder.AUTO_STOP_SILENCE_SECONDS] during a tap-initiated
     * recording, stop and transcribe exactly as if they had tapped.
     * Normal gaps between sentences (1–2 s) never trigger it, and the
     * recorder only starts counting once speech has begun, so
     * thinking before the first word is safe. Hold-to-talk is
     * excluded (releasing already stops it). A 250 ms poll is plenty
     * for a 5 s threshold and costs nothing.
     */
    private fun startAutoStopPoll() {
        autoStopJob?.cancel()
        autoStopJob = scope.launch {
            while (state == BubbleState.RECORDING) {
                if (prefs.stopOnLongPause && !recordingByHold &&
                    (recorder?.silenceSeconds() ?: 0f) >= WavRecorder.AUTO_STOP_SILENCE_SECONDS
                ) {
                    stopAndTranscribe()
                    return@launch
                }
                delay(250)
            }
        }
    }

    private fun stopWaveAnimation() {
        wavePollJob?.cancel()
        wavePollJob = null
        autoStopJob?.cancel()
        autoStopJob = null
        thinkingAnimators.forEach { try { it.cancel() } catch (_: Exception) { } }
        thinkingAnimators = emptyList()
        dotViews.forEach { it.alpha = 0.3f }
        bubbleBg?.alpha = bubbleBaseAlpha
    }

    private fun onBubbleTap() {
        haptic()
        when (state) {
            BubbleState.IDLE, BubbleState.INSERTED, BubbleState.COPIED -> {
                recordingByHold = false
                startRecording()
            }
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
        // Two-tone "du du" on the music stream, like Wispr Flow —
        // the tap is answered before the waveform even appears.
        DictationChime.play(this)
        setState(BubbleState.RECORDING)
    }

    private fun stopAndTranscribe() {
        val rec = recorder ?: return
        val file = recordingFile ?: return
        recorder = null
        recordingFile = null
        recordingByHold = false
        setState(BubbleState.TRANSCRIBING)
        scope.launch(Dispatchers.IO) {
            // WAV finalisation (thread join + header write) happens off
            // the main thread: tap-stop must return instantly. The
            // in-memory samples come from the same recorder — the
            // on-device engine transcribes them in one call, while
            // the WAV file stays for the Groq fallback.
            val seconds = rec.stop(file)
            val samples = rec.samples()
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
                samples,
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
