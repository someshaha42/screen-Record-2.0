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

  private var phase = 0f
  private var paused = false

  private val borderPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = strokePx
      color = Color.rgb(220, 38, 38) // Deep studio crimson
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

  private val windowLocation = IntArray(2)
  private val drawRect = RectF()
  private val badgeRect = RectF()
  private var animator: ValueAnimator? = null

  fun startAnimation() {
    if (animator != null) return
    animator =
      ValueAnimator.ofFloat(0f, dashPeriod).apply {
        duration = 700L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener { anim ->
          phase = -(anim.animatedValue as Float)
          invalidate()
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
    invalidate()
  }

  fun stopAnimation() {
    animator?.cancel()
    animator = null
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    getLocationOnScreen(windowLocation)
    val offsetX = windowLocation[0].toFloat()
    val offsetY = windowLocation[1].toFloat()

    // Convert physical screen crop coordinates into this overlay view's local coordinates
    val localLeft = region.left - offsetX
    val localTop = region.top - offsetY
    val localRight = region.right - offsetX
    val localBottom = region.bottom - offsetY

    // Offset stroke strictly outside the crop rectangle + safety gap so zero pixels bleed into video
    val outwardOffset = safetyGapPx + (strokePx + backingExtraPx) / 2f
    drawRect.set(
      localLeft - outwardOffset,
      localTop - outwardOffset,
      localRight + outwardOffset,
      localBottom + outwardOffset,
    )

    val saveCount = canvas.save()
    try {
      // Hard-exclude the entire recorded crop area + safety margin from receiving any draw pixels
      val clipL = localLeft - safetyGapPx
      val clipT = localTop - safetyGapPx
      val clipR = localRight + safetyGapPx
      val clipB = localBottom + safetyGapPx
      canvas.clipOutRect(clipL, clipT, clipR, clipB)

      canvas.drawRect(drawRect, backingPaint)
      borderPaint.pathEffect = DashPathEffect(floatArrayOf(dashLength, gapLength), phase)
      canvas.drawRect(drawRect, borderPaint)

      val label = if (paused) "PAUSED" else "REC REGION"
      val labelW = textPaint.measureText(label) + 14f * density
      val labelH = 18f * density
      val badgeGap = 4f * density
      val outerStrokeEdgeTop = drawRect.top - (strokePx + backingExtraPx) / 2f
      val outerStrokeEdgeBottom = drawRect.bottom + (strokePx + backingExtraPx) / 2f

      // Draw badge only where it fits cleanly outside the crop rectangle
      val badgeTop =
        when {
          outerStrokeEdgeTop - labelH - badgeGap >= 0f -> outerStrokeEdgeTop - labelH - badgeGap
          outerStrokeEdgeBottom + labelH + badgeGap <= height -> outerStrokeEdgeBottom + badgeGap
          else -> null
        }

      if (badgeTop != null) {
        val badgeLeft =
          drawRect.left.coerceIn(4f * density, (width - labelW - 4f * density).coerceAtLeast(4f * density))
        badgeRect.set(badgeLeft, badgeTop, badgeLeft + labelW, badgeTop + labelH)
        canvas.drawRoundRect(badgeRect, 5f * density, 5f * density, badgePaint)
        canvas.drawText(
          label,
          badgeRect.left + 7f * density,
          badgeRect.bottom - 5f * density,
          textPaint,
        )
      }
    } finally {
      canvas.restoreToCount(saveCount)
    }
  }

  override fun onDetachedFromWindow() {
    stopAnimation()
    super.onDetachedFromWindow()
  }
}
