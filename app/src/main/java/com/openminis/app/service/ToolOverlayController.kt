package com.openminis.app.service

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Chronometer
import android.widget.TextView
import com.openminis.app.MinisApp
import com.openminis.app.agent.SoulIcon
import com.openminis.app.agent.SoulMetadata
import com.openminis.app.agent.SoulStore
import com.openminis.app.ui.chat.friendlyToolTitleFor
import com.openminis.app.ui.chat.toolAccentColorInt
import com.openminis.app.ui.chat.toolIconResFor
import com.openminis.app.R
import kotlin.math.abs
import androidx.compose.ui.graphics.toArgb

class ToolOverlayController(private val context: Context) {

    companion object {
        private const val TAG = "ToolOverlayController"
        private const val DRAG_SLOP_DP = 8f
        private const val LOGO_SIZE_DP = 26
        private const val RING_GAP_DP = 0f
        private const val RING_STROKE_DP = RING_STROKE_DP_VALUE

        private const val GLYPH_INSET_FRACTION = 0.26f

        private const val PLATE_ALPHA = 38

        private const val TOOL_ICON_SIZE_DP = 13
        private const val EDGE_PADDING_DP = 10
        private const val CAPSULE_HEIGHT_DP = 46
        private const val CAPSULE_PADDING_START_DP = 10
        private const val CAPSULE_NON_TEXT_DP = 70

        private const val TASK_PILL_HEIGHT_DP = 16
        private const val CAPSULE_WIDTH_FRACTION = 0.50f
        private const val CAPSULE_WIDTH_FLOOR_DP = 180
        private const val CAPSULE_WIDTH_CAP_FRACTION = 0.70f
        private const val CAPSULE_WIDTH_CAP_DP = 240
        private val RING_COLOR = Color.argb(230, 120, 200, 255)
        private val RING_TRACK_COLOR = Color.argb(RING_TRACK_ALPHA, 120, 200, 255)
        private val LOGO_DISC_COLOR = com.openminis.app.ui.theme.DarkChatPalette.sheetHeaderBg.toArgb()
        private val SUCCESS_COLOR = Color.parseColor("#4CAF50")
        private val FAILURE_COLOR = Color.parseColor("#E53935")
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundRepo =
        (context.applicationContext as MinisApp).backgroundSettingsRepository

    private val positionStore = object : OverlayPosition.Store {
        override fun get(orientation: OverlayPosition.Orientation): OverlayPosition.Point? =
            backgroundRepo.getOverlayPosition(landscape = orientation == OverlayPosition.Orientation.LANDSCAPE)
                ?.let { OverlayPosition.Point(it.first, it.second) }

        override fun set(orientation: OverlayPosition.Orientation, point: OverlayPosition.Point) =
            backgroundRepo.setOverlayPosition(point.x, point.y, landscape = orientation == OverlayPosition.Orientation.LANDSCAPE)
    }

    private var view: View? = null
    private var logoView: ImageView? = null
    private var plateView: View? = null
    private var ringView: RotatingRingView? = null
    private var lastLogoToolName: String? = null
    private var logoAppliedNight: Boolean = false
    private var ringAnimator: ObjectAnimator? = null
    private var labelView: TextView? = null
    private var statusView: TextView? = null

    private var taskIndexView: TextView? = null

    private var rightColumnViews: List<View> = emptyList()
    private var elapsedView: Chronometer? = null
    private var statusRowView: LinearLayout? = null
    private var replyGlyphView: StatusGlyphView? = null
    private var cachedEmojiBitmap: Pair<Pair<String, Int>, Bitmap>? = null
    private var closeView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var pendingSessionId: String? = null
    var onDismissByUser: (() -> Unit)? = null

    @Volatile
    var isShown: Boolean = false
        private set

    fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    data class Waiting(val statusText: String, val nextRunElapsedMs: Long?)

    fun show(
        toolName: String?,
        statusText: String,
        isRunning: Boolean = true,
        outcome: ToolOutcome = ToolOutcome.Unknown,
        replyExcerpt: String? = null,
        targetSessionId: String? = null,
        toolTitle: String? = null,
        isToolRunning: Boolean = isRunning,
        isThinking: Boolean = false,
        sessionTitle: String? = null,
        taskIndex: Int = 0,
        taskTotal: Int = 0,
        waiting: Waiting? = null,
    ) {
        Log.d(
            TAG,
            "show() waiting=${waiting != null} toolName=$toolName toolTitle=${toolTitle?.take(40)} " +
                "status=${statusText.take(40)} " +
                "running=$isRunning toolRunning=$isToolRunning thinking=$isThinking outcome=$outcome " +
                "reply=${replyExcerpt?.take(20)} " +
                "sessionTitle=${sessionTitle?.take(24)} task=$taskIndex/$taskTotal " +
                "session=$targetSessionId perm=${hasOverlayPermission()}",
        )
        if (!hasOverlayPermission()) {
            if (isShown) hide()
            return
        }
        mainHandler.post {
            try {
                pendingSessionId = targetSessionId
                if (view == null) {
                    Log.d(TAG, "show() attach() — view was null")
                    attach()
                }
                updateContent(
                    toolName, statusText, isRunning, outcome, replyExcerpt, toolTitle,
                    isToolRunning, isThinking, sessionTitle, taskIndex, taskTotal, waiting,
                )
            } catch (e: Throwable) {
                Log.w(TAG, "show failed: ${e.message}", e)
            }
        }
    }

    fun hide() {
        mainHandler.post {
            val v = view ?: return@post
            try {
                ringAnimator?.cancel()
                ringAnimator = null
                windowManager.removeView(v)
            } catch (e: Throwable) {
                Log.w(TAG, "removeView failed: ${e.message}")
            }
            elapsedView?.stop()
            elapsedView = null
            view = null
            logoView = null
            plateView = null
            ringView = null
            labelView = null
            statusView = null
            statusRowView = null
            replyGlyphView = null
            closeView = null
            layoutParams = null
            isShown = false
        }
    }

    private fun attach() {
        val container = buildView()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            fixedCapsuleWidthPx(),
            dpToPx(CAPSULE_HEIGHT_DP),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val metrics = context.resources.displayMetrics
            val at = OverlayPosition.displayed(
                positionStore,
                defaultPosition(),
                metrics.widthPixels,
                metrics.heightPixels,
                width,
                height,
                systemBarAvoidance(metrics.widthPixels, metrics.heightPixels),
            )
            x = at.x
            y = at.y
        }
        layoutParams = params
        attachTouchListener(container, params)
        windowManager.addView(container, params)
        view = container
        isShown = true
    }

    private fun buildView(): View {
        val density = context.resources.displayMetrics.density
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dpToPx(CAPSULE_PADDING_START_DP), dpToPx(6), dpToPx(14), dpToPx(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = (CAPSULE_HEIGHT_DP / 2f) * density
                setColor(Color.argb(230, 28, 28, 30))
                setStroke(dpToPx(1), Color.argb(40, 255, 255, 255))
            }
            elevation = 8f * density
            gravity = Gravity.CENTER_VERTICAL
            minimumWidth = dpToPx(120)
        }

        val logoSize = dpToPx(LOGO_SIZE_DP)
        val ringStrokePx = RING_STROKE_DP * density
        val ringGapPx = RING_GAP_DP * density
        val ringTotal = OverlayRingGeometry.ringBoxPx(logoSize, ringGapPx, ringStrokePx)
        val logoStack = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(logoSize, logoSize).apply {
                gravity = Gravity.CENTER_VERTICAL
                rightMargin = dpToPx(6)
            }
            clipChildren = false
        }
        container.clipChildren = false
        container.clipToPadding = false

        val plate = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(logoSize, logoSize).apply {
                gravity = Gravity.CENTER
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
            }
            visibility = View.GONE
        }
        plateView = plate
        logoStack.addView(plate)

        val logo = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(logoSize, logoSize).apply {
                gravity = Gravity.CENTER
            }
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setOval(0, 0, v.width, v.height)
                }
            }
            clipToOutline = true
        }
        logoView = logo
        logoStack.addView(logo)

        val ring = RotatingRingView(
            context,
            strokeWidthPx = ringStrokePx,
            centreRadiusPx = OverlayRingGeometry.arcCentreRadiusPx(logoSize, ringGapPx, ringStrokePx),
            ringColor = RING_COLOR,
            trackColor = RING_TRACK_COLOR,
        ).apply {
            layoutParams = FrameLayout.LayoutParams(ringTotal, ringTotal).apply {
                gravity = Gravity.CENTER
            }
            visibility = View.GONE
        }
        ringView = ring
        logoStack.addView(ring)

        container.addView(logoStack)

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipToPadding = true
            clipChildren = true
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { gravity = Gravity.CENTER_VERTICAL }
        }

        val labelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val label = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            text = ""
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        labelView = label
        labelRow.addView(
            label,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        val elapsed = Chronometer(context).apply {
            setTextColor(Color.argb(200, 220, 220, 220))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = dpToPx(6)
            }
            visibility = View.GONE
        }
        elapsedView = elapsed

        val taskIndex = TextView(context).apply {
            setTextColor(Color.argb(200, 220, 220, 220))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            text = ""
            maxLines = 1
            val vPad = dpToPx(1)
            val hPad = dpToPx(6)
            setPadding(hPad, vPad, hPad, vPad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = TASK_PILL_HEIGHT_DP / 2f * density
                setColor(Color.TRANSPARENT)
                setStroke(dpToPx(1), Color.argb(70, 255, 255, 255))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dpToPx(6) }
            visibility = View.GONE
        }
        taskIndexView = taskIndex
        labelRow.addView(taskIndex)

        textCol.addView(labelRow)


        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val replyGlyph = StatusGlyphView(context).apply {
            val s = dpToPx(TOOL_ICON_SIZE_DP)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                rightMargin = dpToPx(4)
                gravity = Gravity.CENTER_VERTICAL
            }
            visibility = View.GONE
        }
        replyGlyphView = replyGlyph
        statusRow.addView(replyGlyph)

        val status = TextView(context).apply {
            setTextColor(Color.argb(200, 220, 220, 220))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            text = ""
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        statusView = status
        statusRow.addView(
            status,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        elapsed.gravity = Gravity.CENTER_HORIZONTAL
        statusRow.addView(elapsed)
        taskIndexView?.gravity = Gravity.CENTER_HORIZONTAL
        rightColumnViews = listOf(taskIndex, elapsed)

        statusRowView = statusRow
        textCol.addView(statusRow)

        container.addView(textCol)

        val closeBtn = CloseGlyphView(context).apply {
            val s = dpToPx(14)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                leftMargin = dpToPx(10)
                gravity = Gravity.CENTER_VERTICAL
            }
            setOnClickListener { onUserDismiss() }
            contentDescription = context.getString(R.string.overlay_dismiss)
        }
        closeView = closeBtn
        container.addView(closeBtn)

        return container
    }

    private fun updateContent(
        toolName: String?,
        statusText: String,
        isRunning: Boolean,
        outcome: ToolOutcome,
        replyExcerpt: String?,
        toolTitle: String?,
        isToolRunning: Boolean,
        isThinking: Boolean,
        sessionTitle: String?,
        taskIndex: Int,
        taskTotal: Int,
        waiting: Waiting? = null,
    ) {
        val isWaiting = waiting != null && !isRunning
        val hasToolIdentity = !toolTitle.isNullOrBlank() || toolName != null
        val showStatusRow =
            isRunning || (hasToolIdentity && !statusText.equals("Idle", ignoreCase = true))

        val completionWord: String? = if (!isRunning && !isWaiting) when (outcome) {
            ToolOutcome.Success -> context.getString(R.string.overlay_completion_completed)
            ToolOutcome.Error -> context.getString(R.string.overlay_completion_failed)
            ToolOutcome.Timeout -> context.getString(R.string.overlay_completion_timeout)
            ToolOutcome.Cancelled -> context.getString(R.string.overlay_completion_cancelled)
            ToolOutcome.Unknown -> null
        } else null

        labelView?.let { lv ->
            val identityName = SoulStore.cachedMetadata.value.name
                .ifBlank { SoulMetadata.DEFAULT.name }
            val labelText: String? =
                sessionTitle?.takeIf { it.isNotBlank() } ?: completionWord ?: identityName
            if (labelText != null) {
                lv.text = labelText
                lv.visibility = View.VISIBLE
            } else {
                lv.text = ""
                lv.visibility = View.GONE
            }
        }
        taskIndexView?.let { tv ->
            if (taskTotal >= 2 && taskIndex >= 1) {
                tv.text = context.getString(R.string.overlay_task_index, taskIndex, taskTotal)
                tv.visibility = View.VISIBLE
            } else {
                tv.text = ""
                tv.visibility = View.GONE
            }
        }
        val rowToolName = toolName
        val replyText = replyExcerpt?.takeIf { it.isNotBlank() }
        val showReplyVariant = !isRunning && replyText != null

        val showStreamingVariant = isRunning && !isToolRunning

        applyLogoFor(if (isToolRunning) rowToolName else null)

        val rowTitle: String? = when {
            isWaiting -> waiting!!.statusText
            showReplyVariant -> replyText
            showStreamingVariant -> context.getString(
                if (isThinking) R.string.overlay_thinking else R.string.overlay_streaming,
            )
            !showStatusRow -> null
            !toolTitle.isNullOrBlank() -> toolTitle
            rowToolName != null -> friendlyToolTitleFor(rowToolName)
            else -> null
        }
        val replyGlyphState: Pair<StatusGlyphView.State, Int>? =
            if (!showReplyVariant || isWaiting) null else when (outcome) {
                ToolOutcome.Success -> StatusGlyphView.State.Success to SUCCESS_COLOR
                ToolOutcome.Error, ToolOutcome.Timeout ->
                    StatusGlyphView.State.Error to FAILURE_COLOR
                ToolOutcome.Cancelled, ToolOutcome.Unknown -> null
            }

        val showToolRow = rowTitle != null
        statusRowView?.visibility = if (showToolRow) View.VISIBLE else View.GONE
        statusView?.let { sv ->
            if (showToolRow) {
                sv.text = rowTitle
                sv.visibility = View.VISIBLE
            } else {
                sv.text = ""
                sv.visibility = View.GONE
            }
        }
        replyGlyphView?.let { gv ->
            val st = replyGlyphState
            if (showToolRow && st != null) {
                gv.setState(st.first, st.second)
                gv.visibility = View.VISIBLE
            } else {
                gv.visibility = View.GONE
            }
        }
        val maxW = (fixedCapsuleWidthPx() - dpToPx(CAPSULE_PADDING_START_DP + CAPSULE_NON_TEXT_DP)).coerceAtLeast(dpToPx(80))
        labelView?.maxWidth = maxW
        statusView?.maxWidth = maxW

        closeView?.visibility = if (isRunning) View.GONE else View.VISIBLE

        val elapsed = elapsedView
        if (elapsed != null && isWaiting) {
            val next = waiting!!.nextRunElapsedMs
            if (next != null && next > SystemClock.elapsedRealtime()) {
                elapsed.isCountDown = true
                if (elapsed.base != next) elapsed.base = next
                elapsed.setOnChronometerTickListener { c ->
                    if (c.isCountDown && c.base <= SystemClock.elapsedRealtime()) c.stop()
                }
                elapsed.visibility = View.VISIBLE
                elapsed.start()
            } else {
                elapsed.stop()
                elapsed.visibility = View.GONE
            }
        } else if (elapsed != null) {
            if (elapsed.isCountDown) {
                elapsed.isCountDown = false
                elapsed.onChronometerTickListener = null
            }
            val runStart = SessionActivityTracker.currentRunStartedAtMs.value
            if (runStart != null) {
                if (isRunning) {
                    if (elapsed.base != runStart) {
                        elapsed.base = runStart
                    }
                    elapsed.visibility = View.VISIBLE
                    elapsed.start()
                } else {
                    elapsed.stop()
                    elapsed.visibility =
                        if (OverlayClock.completedShowsClock(elapsed.base, SystemClock.elapsedRealtime())) View.VISIBLE else View.GONE
                }
            } else {
                elapsed.stop()
                elapsed.visibility = View.GONE
            }
        }

        val ring = ringView
        if (ring != null) {
            if (isRunning) {
                if (ring.visibility != View.VISIBLE) ring.visibility = View.VISIBLE
                if (ringAnimator?.isStarted != true) startRingAnimation(ring)
            } else {
                if (ring.visibility != View.GONE) ring.visibility = View.GONE
                ringAnimator?.cancel()
                ringAnimator = null
            }
        }

        equaliseRightColumn()
    }

    private fun equaliseRightColumn() {
        val views = rightColumnViews.filter { it.visibility == View.VISIBLE }
        if (views.size < 2) {
            rightColumnViews.forEach { v ->
                if (v.layoutParams?.width != LinearLayout.LayoutParams.WRAP_CONTENT) {
                    v.layoutParams = (v.layoutParams as LinearLayout.LayoutParams).apply {
                        width = LinearLayout.LayoutParams.WRAP_CONTENT
                    }
                }
            }
            return
        }
        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        var widest = 0
        for (v in views) {
            val lp = v.layoutParams as LinearLayout.LayoutParams
            if (lp.width != LinearLayout.LayoutParams.WRAP_CONTENT) {
                lp.width = LinearLayout.LayoutParams.WRAP_CONTENT
                v.layoutParams = lp
            }
            v.measure(unspec, unspec)
            if (v.measuredWidth > widest) widest = v.measuredWidth
        }
        if (widest <= 0) return
        for (v in views) {
            val lp = v.layoutParams as LinearLayout.LayoutParams
            if (lp.width != widest) {
                lp.width = widest
                v.layoutParams = lp
            }
        }
    }

    private fun applyLogoFor(toolName: String?) {
        val iv = logoView ?: return
        lastLogoToolName = toolName
        logoAppliedNight = isNightNow()
        if (toolName == null) {
            iv.setPadding(0, 0, 0, 0)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.clearColorFilter()
            applySoulIdentityIcon(iv)
            return
        }
        val inset = (dpToPx(LOGO_SIZE_DP) * GLYPH_INSET_FRACTION).toInt()
        iv.setPadding(inset, inset, inset, inset)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        val accent = toolAccentColorInt(toolName)
        iv.setImageResource(toolIconResFor(toolName))
        iv.setColorFilter(accent)
        applyPlate(Color.argb(PLATE_ALPHA, Color.red(accent), Color.green(accent), Color.blue(accent)))
    }

    private fun applyPlate(color: Int?) {
        val pv = plateView ?: return
        if (color == null) {
            pv.visibility = View.GONE
            return
        }
        (pv.background as? GradientDrawable)?.setColor(color)
        pv.visibility = View.VISIBLE
    }


    private fun applySoulIdentityIcon(iv: ImageView) {
        val icon = SoulStore.cachedMetadata.value.icon
        if (icon.isBlank()) {
            applyLauncherMark(iv)
            return
        }
        SoulIcon.decode(icon)?.let { bmp ->
            iv.setPadding(0, 0, 0, 0)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.clearColorFilter()
            iv.setImageBitmap(bmp)
            applyPlate(LOGO_DISC_COLOR)
            return
        }
        val px = dpToPx(LOGO_SIZE_DP)
        emojiBitmap(icon, px)?.let {
            iv.setPadding(0, 0, 0, 0)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.clearColorFilter()
            iv.setImageBitmap(it)
            applyPlate(LOGO_DISC_COLOR)
            return
        }
        applyLauncherMark(iv)
    }

    private fun applyLauncherMark(iv: ImageView) {
        iv.setPadding(0, 0, 0, 0)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.clearColorFilter()
        iv.setImageResource(R.mipmap.ic_launcher)
        applyPlate(null)
    }

    private fun isNightNow(): Boolean =
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun emojiBitmap(glyph: String, sizePx: Int): Bitmap? {
        if (sizePx <= 0) return null
        cachedEmojiBitmap?.let { (key, bmp) ->
            if (key == glyph to sizePx) return bmp
        }
        return runCatching {
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                textSize = sizePx * 0.72f
            }
            val fm = paint.fontMetrics
            val baseline = sizePx / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(glyph, sizePx / 2f, baseline, paint)
            cachedEmojiBitmap = (glyph to sizePx) to bmp
            bmp
        }.getOrNull()
    }

    private fun startRingAnimation(target: View) {
        ringAnimator?.cancel()
        ringAnimator = ObjectAnimator.ofFloat(target, View.ROTATION, 0f, 360f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = android.view.animation.LinearInterpolator()
            start()
        }
    }

    private fun attachTouchListener(target: View, params: WindowManager.LayoutParams) {
        val slopPx = (DRAG_SLOP_DP * context.resources.displayMetrics.density).toInt()
        var downX = 0f
        var downY = 0f
        var startParamX = 0
        var startParamY = 0
        var dragging = false
        target.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startParamX = params.x
                    startParamY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()
                    if (!dragging && (abs(dx) > slopPx || abs(dy) > slopPx)) dragging = true
                    if (dragging) {
                        params.x = startParamX + dx
                        params.y = startParamY + dy
                        try {
                            windowManager.updateViewLayout(target, params)
                        } catch (e: Throwable) {
                            Log.w(TAG, "updateViewLayout failed: ${e.message}")
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        val dm = context.resources.displayMetrics
                        OverlayPosition.dragEnded(positionStore, dm.widthPixels, dm.heightPixels, OverlayPosition.Point(params.x, params.y))
                        val at = OverlayPosition.displayed(
                            positionStore,
                            defaultPosition(),
                            dm.widthPixels,
                            dm.heightPixels,
                            fixedCapsuleWidthPx(),
                            dpToPx(CAPSULE_HEIGHT_DP),
                            systemBarAvoidance(dm.widthPixels, dm.heightPixels),
                        )
                        if (at.x != params.x || at.y != params.y) {
                            params.x = at.x
                            params.y = at.y
                            try {
                                windowManager.updateViewLayout(target, params)
                            } catch (e: Throwable) {
                                Log.w(TAG, "drag snap relayout failed: ${e.message}")
                            }
                        }
                    } else {
                        onTap()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun onTap() {
        try {
            val sid = pendingSessionId
            val launchIntent = Intent(
                context,
                Class.forName("com.openminis.app.MainActivity"),
            ).apply {
                if (!sid.isNullOrBlank()) {
                    data = Uri.parse("minis://session/$sid")
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(launchIntent)
        } catch (e: Throwable) {
            Log.w(TAG, "tap-to-foreground failed: ${e.message}")
        }
        onUserDismiss()
    }

    private fun onUserDismiss() {
        try { onDismissByUser?.invoke() } catch (_: Throwable) {}
        hide()
    }

    private fun fixedCapsuleWidthPx(): Int {
        val dm = context.resources.displayMetrics
        val cap = minOf(
            (dm.widthPixels * CAPSULE_WIDTH_CAP_FRACTION).toInt(),
            dpToPx(CAPSULE_WIDTH_CAP_DP),
        )
        return (dm.widthPixels * CAPSULE_WIDTH_FRACTION).toInt()
            .coerceAtMost(cap)
            .coerceAtLeast(dpToPx(CAPSULE_WIDTH_FLOOR_DP))
    }

    fun onConfigurationChanged() {
        mainHandler.post {
            val v = view ?: return@post
            if (isNightNow() != logoAppliedNight) applyLogoFor(lastLogoToolName)
            val params = layoutParams ?: return@post
            val dm = context.resources.displayMetrics
            val width = fixedCapsuleWidthPx()
            val height = dpToPx(CAPSULE_HEIGHT_DP)
            val at = OverlayPosition.displayed(
                positionStore,
                defaultPosition(),
                dm.widthPixels,
                dm.heightPixels,
                width,
                height,
                systemBarAvoidance(dm.widthPixels, dm.heightPixels),
            )
            if (params.width == width && params.x == at.x && params.y == at.y) return@post
            params.width = width
            params.x = at.x
            params.y = at.y
            try {
                windowManager.updateViewLayout(v, params)
            } catch (e: Throwable) {
                Log.w(TAG, "onConfigurationChanged relayout failed: ${e.message}")
            }
        }
    }

    private fun defaultPosition(): OverlayPosition.Point {
        val metrics = context.resources.displayMetrics
        val padding = dpToPx(EDGE_PADDING_DP)
        val approxOverlayHeight = dpToPx(LOGO_SIZE_DP + 16)
        val approxNavBar = dpToPx(48)
        return OverlayPosition.Point(
            padding,
            metrics.heightPixels - approxOverlayHeight - approxNavBar - padding,
        )
    }

    private fun systemBarAvoidance(screenW: Int, screenH: Int): OverlayPosition.Avoid {
        val orientation = OverlayPosition.Orientation.of(screenW, screenH)
        val top = statusBarTop()
        if (orientation != OverlayPosition.Orientation.LANDSCAPE) return OverlayPosition.avoidFor(orientation, 0, 0, 0, top)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return OverlayPosition.avoidFor(orientation, 0, 0, 0, top)
        val insets = runCatching {
            windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.navigationBars() or
                    android.view.WindowInsets.Type.mandatorySystemGestures(),
            )
        }.onFailure { Log.w(TAG, "system bar insets unavailable: ${it.message}") }
            .getOrNull() ?: return OverlayPosition.avoidFor(orientation, 0, 0, 0, top)
        return OverlayPosition.avoidFor(orientation, insets.left, insets.right, insets.bottom, top)
    }

    private fun statusBarTop(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout(),
                ).top
            }.onFailure { Log.w(TAG, "status bar inset unavailable: ${it.message}") }
                .getOrNull()?.let { return it.coerceAtLeast(0) }
        }
        @Suppress("DiscouragedApi")
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id == 0) return 0
        return runCatching { context.resources.getDimensionPixelSize(id) }.getOrDefault(0).coerceAtLeast(0)
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    private class RotatingRingView(
        context: Context,
        private val strokeWidthPx: Float,
        private val centreRadiusPx: Float,
        ringColor: Int,
        trackColor: Int,
    ) : View(context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.BUTT
            color = trackColor
            strokeWidth = strokeWidthPx
        }
        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = ringColor
            strokeWidth = strokeWidthPx
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            rect.set(cx - centreRadiusPx, cy - centreRadiusPx, cx + centreRadiusPx, cy + centreRadiusPx)
            canvas.drawArc(rect, 0f, 360f, false, trackPaint)
            canvas.drawArc(rect, -90f, RING_SWEEP_DEG, false, arcPaint)
        }
    }

    private class CloseGlyphView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(220, 200, 200, 200)
        }
        private val path = Path()
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            paint.strokeWidth = (w * 0.14f).coerceAtLeast(2f)
            path.reset()
            path.moveTo(w * 0.25f, h * 0.25f)
            path.lineTo(w * 0.75f, h * 0.75f)
            path.moveTo(w * 0.75f, h * 0.25f)
            path.lineTo(w * 0.25f, h * 0.75f)
            canvas.drawPath(path, paint)
        }
    }

    private class StatusGlyphView(context: Context) : View(context) {

        enum class State { Success, Error }

        private var state: State = State.Success
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.parseColor("#4CAF50")
        }
        private val path = Path()

        fun setState(newState: State, color: Int) {
            state = newState
            paint.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            paint.strokeWidth = (w * 0.16f).coerceAtLeast(2f)
            path.reset()
            when (state) {
                State.Success -> {
                    path.moveTo(w * 0.18f, h * 0.52f)
                    path.lineTo(w * 0.42f, h * 0.74f)
                    path.lineTo(w * 0.82f, h * 0.30f)
                }
                State.Error -> {
                    path.moveTo(w * 0.22f, h * 0.22f)
                    path.lineTo(w * 0.78f, h * 0.78f)
                    path.moveTo(w * 0.78f, h * 0.22f)
                    path.lineTo(w * 0.22f, h * 0.78f)
                }
            }
            canvas.drawPath(path, paint)
        }
    }
}

internal const val RING_STROKE_DP_VALUE = 1.5f

internal const val RING_SWEEP_DEG = 100f

internal const val RING_TRACK_ALPHA = 77

internal object OverlayRingGeometry {
    fun ringBoxPx(logoPx: Int, gapPx: Float, strokePx: Float): Int =
        logoPx + 2 * kotlin.math.ceil(gapPx + strokePx).toInt()

    fun arcCentreRadiusPx(logoPx: Int, gapPx: Float, strokePx: Float): Float = logoPx / 2f + gapPx + strokePx / 2f

    fun arcInnerRadiusPx(logoPx: Int, gapPx: Float, strokePx: Float): Float =
        arcCentreRadiusPx(logoPx, gapPx, strokePx) - strokePx / 2f

    fun arcOuterRadiusPx(logoPx: Int, gapPx: Float, strokePx: Float): Float =
        arcCentreRadiusPx(logoPx, gapPx, strokePx) + strokePx / 2f
}

internal object OverlayMargins {
    fun ringToTopDp(capsuleDp: Float, logoDp: Float, strokeDp: Float): Float =
        (capsuleDp - (logoDp + 2 * strokeDp)) / 2f

    fun startPaddingDp(capsuleDp: Float, logoDp: Float, strokeDp: Float): Float =
        ringToTopDp(capsuleDp, logoDp, strokeDp) + strokeDp

    fun ringToStartPx(startPaddingPx: Int, strokePx: Float): Float = startPaddingPx - strokePx

    fun ringToTopBottomPx(capsulePx: Int, logoPx: Int, strokePx: Float): Pair<Float, Float> {
        val top = (capsulePx - logoPx) / 2
        return (top - strokePx) to (capsulePx - logoPx - top - strokePx)
    }
}

internal object OverlayClock {
    fun completedShowsClock(base: Long, nowElapsedMs: Long): Boolean = base <= nowElapsedMs
}
