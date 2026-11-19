package com.example.compat

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.example.settings.AudioSourceOption

/**
 * Centralized forward-compatible platform abstraction layer for Android 16 (API 36), Android 17
 * (API 37), and subsequent Android releases (API 38+).
 *
 * All version-gated platform calls (visual WindowContext creation, MediaProjection capture intents,
 * foreground service type resolution, notification priority behavior, and memory-trim heuristics)
 * are isolated here so future SDK bumps require changes in a single place.
 */
object PlatformCompat {

  private const val TAG = "PlatformCompat"

  const val API_ANDROID_16 = 36
  const val API_ANDROID_17 = 37
  const val API_FUTURE_BASE = 38

  enum class PlatformGeneration {
    ANDROID_16,
    ANDROID_17,
    FUTURE_ANDROID,
  }

  fun currentGeneration(sdkInt: Int = Build.VERSION.SDK_INT): PlatformGeneration {
    return when {
      sdkInt >= API_FUTURE_BASE -> PlatformGeneration.FUTURE_ANDROID
      sdkInt >= API_ANDROID_17 -> PlatformGeneration.ANDROID_17
      else -> PlatformGeneration.ANDROID_16
    }
  }

  /**
   * Builds a MediaProjection consent intent tailored to Android 16/17+ capture behavior while
   * remaining forward-compatible with future OS versions.
   */
  fun createScreenCaptureIntent(manager: MediaProjectionManager): Intent {
    return when (currentGeneration()) {
      PlatformGeneration.ANDROID_16,
      PlatformGeneration.ANDROID_17,
      PlatformGeneration.FUTURE_ANDROID -> {
        runCatching {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
          }
          .getOrElse { manager.createScreenCaptureIntent() }
      }
    }
  }

  /**
   * Creates a visual overlay WindowContext bound to the default display so Service and Application
   * contexts can query WindowManager metrics or attach TYPE_APPLICATION_OVERLAY windows without
   * triggering StrictMode IncorrectContextUseViolation on Android 16/17+.
   */
  fun resolveOverlayWindowManager(context: Context): WindowManager {
    val visualContext = createOverlayVisualContext(context)
    return visualContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  }

  @SuppressLint("WrongConstant")
  fun createOverlayVisualContext(context: Context): Context {
    if (context.isUiContextCompat()) {
      return context
    }
    return runCatching {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
          val displayContext = context.createDisplayContext(display)
          displayContext.createWindowContext(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            null,
          )
        } else {
          context
        }
      }
      .getOrElse { context }
  }

  /**
   * Queries maximum display bounds using a visual WindowContext on Android 16/17+.
   */
  fun queryMaximumDisplayMetrics(context: Context): Triple<Int, Int, Int> {
    val windowManager = resolveOverlayWindowManager(context)
    val bounds = windowManager.maximumWindowMetrics.bounds
    val dpi = context.resources.displayMetrics.densityDpi.coerceAtLeast(DisplayMetrics.DENSITY_MEDIUM)
    return Triple(bounds.width().coerceAtLeast(128), bounds.height().coerceAtLeast(128), dpi)
  }

  /**
   * Resolves the least-privilege foreground service type bitmask required for MediaProjection and
   * optional Microphone capture.
   */
  fun resolveRecordingForegroundServiceTypes(
    context: Context,
    audioSource: AudioSourceOption,
  ): Int {
    val hasRecordAudio =
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    return if (hasRecordAudio && audioSource == AudioSourceOption.MICROPHONE) {
      ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    } else {
      ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    }
  }

  /**
   * Starts the foreground recording service with explicit types before MediaProjection activation.
   * Handles Android 16/17 While-In-Use (WIU) foreground service start restrictions safely.
   */
  fun startRecordingForeground(
    service: Service,
    notificationId: Int,
    notification: Notification,
    audioSource: AudioSourceOption,
  ): Boolean {
    val fgsTypes = resolveRecordingForegroundServiceTypes(service, audioSource)
    return try {
      service.startForeground(notificationId, notification, fgsTypes)
      true
    } catch (e: SecurityException) {
      Log.e(TAG, "Foreground service type rejected on ${currentGeneration()}", e)
      false
    } catch (e: RuntimeException) {
      Log.e(TAG, "Unable to enter foreground service state on ${currentGeneration()}", e)
      false
    }
  }

  /**
   * Determines whether the OS has signaled non-foreground/background memory pressure using only
   * non-deprecated ComponentCallbacks2 trim levels for Android 16/17+.
   */
  fun shouldReleaseIdleCachesOnTrim(level: Int): Boolean {
    return level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
  }

  private fun Context.isUiContextCompat(): Boolean {
    return runCatching { isUiContext }.getOrDefault(false)
  }
}
