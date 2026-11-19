package com.example.region

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import com.example.compat.PlatformCompat
import java.util.concurrent.atomic.AtomicLong

/**
 * Optional floating region indicator overlay. When RegionAnimationOption.SHOW is enabled AND the
 * user has granted Display Over Other Apps permission, attaches a non-touchable animated deep-red
 * dashed border strictly OUTSIDE the crop rectangle (with fitInsetsTypes = 0, screen-origin
 * correction, and clipOutRect protection) so zero border or badge pixels are ever recorded into the
 * cropped MP4 output.
 */
class RegionOverlayManager(private val context: Context) {

  private val windowManager: WindowManager by lazy {
    PlatformCompat.resolveOverlayWindowManager(context)
  }
  private val mainHandler = Handler(Looper.getMainLooper())
  private val generation = AtomicLong(0L)
  private var overlayView: RegionBorderOverlayView? = null

  fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(context)

  fun show(region: PixelRegion, enabled: Boolean) {
    val token = generation.incrementAndGet()
    runOnMainThread {
      if (generation.get() != token) return@runOnMainThread
      hideInternal()
      if (!enabled) return@runOnMainThread
      if (!canDrawOverlay()) {
        Log.d(
          TAG,
          "Overlay permission unavailable; continuing region recording without floating border",
        )
        return@runOnMainThread
      }

      val view = RegionBorderOverlayView(context, region)
      val params =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
              WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
              WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
              WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
          )
          .apply {
            x = 0
            y = 0
            gravity = Gravity.TOP or Gravity.START
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode =
              WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
          }

      runCatching {
          windowManager.addView(view, params)
          overlayView = view
          view.startAnimation()
        }
        .onFailure {
          Log.w(TAG, "Unable to attach region overlay; continuing without border", it)
          view.stopAnimation()
          overlayView = null
        }
    }
  }

  fun setPaused(paused: Boolean) {
    runOnMainThread { overlayView?.setPaused(paused) }
  }

  fun hide() {
    generation.incrementAndGet()
    runOnMainThread { hideInternal() }
  }

  private fun hideInternal() {
    val view = overlayView ?: return
    overlayView = null
    runCatching { view.stopAnimation() }
    view.visibility = View.GONE
    runCatching { windowManager.removeViewImmediate(view) }
      .onFailure { runCatching { windowManager.removeView(view) } }
  }

  private fun runOnMainThread(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      block()
    } else {
      mainHandler.post(block)
    }
  }

  companion object {
    private const val TAG = "RegionOverlayManager"
  }
}

internal class RegionBorderOverlayView(
  context: Context,
  private val region: PixelRegion,
) : View(context) {

  private val density = resources.displayMetrics.density
  private val strokePx = 3f * density
  private val backingExtraPx = 2f * density
  private val safetyGapPx = 3f * density
  private val dashLength = 14f * density
  private val gapLength = 9f * density
  private val dashPeriod = dashLength + gapLength

  private val dashIntervals = floatArrayOf(dashLength, gapLength)
  private val phaseSteps = 16
  private val dashEffects =
    Array(phaseSteps) { step ->
      val stepPhase = -(step.toFloat() / phaseSteps) * dashPeriod
      DashPathEffect(dashIntervals, stepPhase)
    }

  private var phaseIndex = 0
  private var paused = false

  private val borderPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = strokePx
      color = Color.rgb(220, 38, 38) // Deep studio crimson
      pathEffect = dashEffects[0]
    }

  private val backingPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = strokePx + backingExtraPx
      color = Color.argb(110, 17, 19, 24)
    }

  private val badgePaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = Color.rgb(185, 28, 28)
    }

  private val textPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = 10f * density
      isFakeBoldText = true
    }

  private val badgePadHorizontal = 14f * density
  private val recBadgeWidth = textPaint.measureText(LABEL_RECORDING) + badgePadHorizontal
  private val pausedBadgeWidth = textPaint.measureText(LABEL_PAUSED) + badgePadHorizontal
  private val labelHeight = 18f * density
  private val badgeGap = 4f * density
  private val badgeCornerRadius = 5f * density
  private val badgeTextInsetX = 7f * density
  private val badgeTextInsetBottom = 5f * density
  private val edgeMargin = 4f * density
  private val halfOuterStroke = (strokePx + backingExtraPx) / 2f

  private val windowLocation = IntArray(2)
  private val drawRect = RectF()
  private val badgeRect = RectF()
  private var clipL = 0f
  private var clipT = 0f
  private var clipR = 0f
  private var clipB = 0f
  private var hasBadge = false
  private var geometryReady = false
  private var animator: ValueAnimator? = null

  fun startAnimation() {
    if (animator != null) return
    animator =
      ValueAnimator.ofInt(0, phaseSteps).apply {
        duration = 800L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener { anim ->
          val nextIndex = (anim.animatedValue as Int) % phaseSteps
          if (nextIndex != phaseIndex) {
            phaseIndex = nextIndex
            borderPaint.pathEffect = dashEffects[nextIndex]
            invalidate()
          }
        }
        start()
      }
  }

  fun setPaused(isPaused: Boolean) {
    paused = isPaused
    if (isPaused) {
      animator?.pause()
      borderPaint.color = Color.rgb(217, 119, 6) // Amber when paused
      badgePaint.color = Color.rgb(180, 83, 9)
    } else {
      borderPaint.color = Color.rgb(220, 38, 38)
      badgePaint.color = Color.rgb(185, 28, 28)
      animator?.resume()
    }
    updateGeometry()
    invalidate()
  }

  fun stopAnimation() {
    animator?.removeAllUpdateListeners()
    animator?.cancel()
    animator = null
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    geometryReady = false
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    super.onLayout(changed, left, top, right, bottom)
    if (changed || !geometryReady) {
      updateGeometry()
    }
  }

  private fun updateGeometry() {
    if (width <= 0 || height <= 0) return
    getLocationOnScreen(windowLocation)
    val offsetX = windowLocation[0].toFloat()
    val offsetY = windowLocation[1].toFloat()

    // Convert physical screen crop coordinates into this overlay view's local coordinates
    val localLeft = region.left - offsetX
    val localTop = region.top - offsetY
    val localRight = region.right - offsetX
    val localBottom = region.bottom - offsetY

    // Offset stroke strictly outside the crop rectangle + safety gap so zero pixels bleed into video
    val outwardOffset = safetyGapPx + halfOuterStroke
    drawRect.set(
      localLeft - outwardOffset,
      localTop - outwardOffset,
      localRight + outwardOffset,
      localBottom + outwardOffset,
    )
    clipL = localLeft - safetyGapPx
    clipT = localTop - safetyGapPx
    clipR = localRight + safetyGapPx
    clipB = localBottom + safetyGapPx

    val labelW = if (paused) pausedBadgeWidth else recBadgeWidth
    val outerStrokeEdgeTop = drawRect.top - halfOuterStroke
    val outerStrokeEdgeBottom = drawRect.bottom + halfOuterStroke

    val badgeTop =
      when {
        outerStrokeEdgeTop - labelHeight - badgeGap >= 0f ->
          outerStrokeEdgeTop - labelHeight - badgeGap
        outerStrokeEdgeBottom + labelHeight + badgeGap <= height ->
          outerStrokeEdgeBottom + badgeGap
        else -> null
      }

    if (badgeTop != null) {
      val badgeLeft =
        drawRect.left.coerceIn(edgeMargin, (width - labelW - edgeMargin).coerceAtLeast(edgeMargin))
      badgeRect.set(badgeLeft, badgeTop, badgeLeft + labelW, badgeTop + labelHeight)
      hasBadge = true
    } else {
      hasBadge = false
    }

    geometryReady = true
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    if (!geometryReady) {
      updateGeometry()
    }
    if (!geometryReady) return

    val saveCount = canvas.save()
    try {
      // Hard-exclude the entire recorded crop area + safety margin from receiving any draw pixels
      canvas.clipOutRect(clipL, clipT, clipR, clipB)

      canvas.drawRect(drawRect, backingPaint)
      canvas.drawRect(drawRect, borderPaint)

      if (hasBadge) {
        val label = if (paused) LABEL_PAUSED else LABEL_RECORDING
        canvas.drawRoundRect(badgeRect, badgeCornerRadius, badgeCornerRadius, badgePaint)
        canvas.drawText(
          label,
          badgeRect.left + badgeTextInsetX,
          badgeRect.bottom - badgeTextInsetBottom,
          textPaint,
        )
      }
    } finally {
      canvas.restoreToCount(saveCount)
    }
  }

  private companion object {
    const val LABEL_RECORDING = "REC REGION"
    const val LABEL_PAUSED = "PAUSED"
  }

  override fun onDetachedFromWindow() {
    stopAnimation()
    super.onDetachedFromWindow()
  }
}
