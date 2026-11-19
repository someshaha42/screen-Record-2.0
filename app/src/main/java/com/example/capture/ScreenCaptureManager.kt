package com.example.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import com.example.compat.PlatformCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages MediaProjection, VirtualDisplay, and dynamic display resize handling, binding the
 * VirtualDisplay directly to MediaCodec's hardware COLOR_FormatSurface (zero-copy hardware
 * surface pipeline with zero OpenGL, Vulkan, or CPU/ImageReader color conversion).
 */
class ScreenCaptureManager(
  private val context: Context,
  private val mediaProjection: MediaProjection,
  private val outputSurface: Surface,
  private val outputWidth: Int,
  private val outputHeight: Int,
  private val onProjectionStopped: () -> Unit,
) {
  private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
  private val isReleased = AtomicBoolean(false)

  private var callbackThread: HandlerThread? = null
  private var callbackHandler: Handler? = null
  private var virtualDisplay: VirtualDisplay? = null

  private var currentCaptureWidth = outputWidth.coerceAtLeast(128)
  private var currentCaptureHeight = outputHeight.coerceAtLeast(128)
  private var currentDensityDpi = DisplayMetrics.DENSITY_DEFAULT

  private val projectionCallback =
    object : MediaProjection.Callback() {
      override fun onStop() {
        Log.i(TAG, "MediaProjection stopped by system or user")
        if (!isReleased.get()) {
          onProjectionStopped()
        }
      }

      override fun onCapturedContentResize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || isReleased.get()) return
        Log.d(TAG, "onCapturedContentResize: ${width}x$height (encoder=${outputWidth}x$outputHeight)")
      }

      override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
        Log.d(TAG, "onCapturedContentVisibilityChanged: isVisible=$isVisible")
      }
    }

  private val displayListener =
    object : DisplayManager.DisplayListener {
      override fun onDisplayAdded(displayId: Int) = Unit

      override fun onDisplayRemoved(displayId: Int) = Unit

      override fun onDisplayChanged(displayId: Int) {
        if (displayId != Display.DEFAULT_DISPLAY || isReleased.get()) return
        val (_, _, dpi) = queryScreenMetrics(context)
        if (dpi != currentDensityDpi) {
          currentDensityDpi = dpi
          runCatching {
            virtualDisplay?.resize(currentCaptureWidth, currentCaptureHeight, currentDensityDpi)
          }
        }
      }
    }

  fun start() {
    val (_, _, dpi) = queryScreenMetrics(context)
    currentCaptureWidth = outputWidth.coerceAtLeast(128)
    currentCaptureHeight = outputHeight.coerceAtLeast(128)
    currentDensityDpi = dpi

    val thread =
      HandlerThread("FrameCut-CaptureCallbacks", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    callbackThread = thread
    val handler = Handler(thread.looper)
    callbackHandler = handler

    mediaProjection.registerCallback(projectionCallback, handler)
    displayManager.registerDisplayListener(displayListener, handler)

    virtualDisplay =
      mediaProjection.createVirtualDisplay(
        "FrameCut-VirtualDisplay",
        currentCaptureWidth,
        currentCaptureHeight,
        currentDensityDpi,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        outputSurface,
        null,
        handler,
      )

    Log.i(
      TAG,
      "Direct hardware surface VirtualDisplay started: ${currentCaptureWidth}x$currentCaptureHeight @ ${currentDensityDpi}dpi",
    )
  }

  fun setPaused(paused: Boolean) {
    if (isReleased.get()) return
    callbackHandler?.post {
      if (isReleased.get()) return@post
      runCatching {
        virtualDisplay?.setSurface(if (paused) null else outputSurface)
      }.onFailure { Log.w(TAG, "Failed to update VirtualDisplay surface on pause=$paused", it) }
    }
  }

  fun stopAndRelease() {
    if (isReleased.getAndSet(true)) return

    runCatching { displayManager.unregisterDisplayListener(displayListener) }
    runCatching { mediaProjection.unregisterCallback(projectionCallback) }
    runCatching { virtualDisplay?.setSurface(null) }
    runCatching { virtualDisplay?.release() }
    virtualDisplay = null

    callbackThread?.quitSafely()
    runCatching { callbackThread?.join(1000) }
    callbackThread = null
    callbackHandler = null

    runCatching { mediaProjection.stop() }
  }

  companion object {
    private const val TAG = "ScreenCaptureManager"

    fun queryScreenMetrics(context: Context): Triple<Int, Int, Int> {
      return PlatformCompat.queryMaximumDisplayMetrics(context)
    }
  }
}
