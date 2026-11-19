package com.example

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.DisplayMetrics
import androidx.test.core.app.ApplicationProvider
import com.example.compat.PlatformCompat
import com.example.service.RecordingPhase
import com.example.service.RecordingService
import com.example.service.RecordingSessionStore
import com.example.settings.AudioSourceOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlatformCompatTest {

  @Test
  fun `platform generation resolves Android 16, 17, and future releases cleanly`() {
    assertEquals(PlatformCompat.PlatformGeneration.ANDROID_16, PlatformCompat.currentGeneration(36))
    assertEquals(PlatformCompat.PlatformGeneration.ANDROID_17, PlatformCompat.currentGeneration(37))
    assertEquals(PlatformCompat.PlatformGeneration.FUTURE_ANDROID, PlatformCompat.currentGeneration(38))
    assertEquals(PlatformCompat.PlatformGeneration.FUTURE_ANDROID, PlatformCompat.currentGeneration(39))
  }

  @Test
  fun `foreground service types enforce least privilege based on audio source and permission`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val shadowApp = Shadows.shadowOf(app)

    // Without RECORD_AUDIO permission, microphone type must never be requested
    shadowApp.denyPermissions(Manifest.permission.RECORD_AUDIO)
    assertEquals(
      ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
      PlatformCompat.resolveRecordingForegroundServiceTypes(app, AudioSourceOption.MICROPHONE),
    )
    assertEquals(
      ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
      PlatformCompat.resolveRecordingForegroundServiceTypes(app, AudioSourceOption.INTERNAL),
    )

    // With RECORD_AUDIO granted, microphone type is included only for MICROPHONE source
    shadowApp.grantPermissions(Manifest.permission.RECORD_AUDIO)
    assertEquals(
      ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
      PlatformCompat.resolveRecordingForegroundServiceTypes(app, AudioSourceOption.INTERNAL),
    )
    assertEquals(
      ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
      PlatformCompat.resolveRecordingForegroundServiceTypes(app, AudioSourceOption.MICROPHONE),
    )
  }

  @Test
  fun `display metrics resolve safely via visual context from application and service`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val (w, h, dpi) = PlatformCompat.queryMaximumDisplayMetrics(app)
    assertTrue(w >= 128)
    assertTrue(h >= 128)
    assertTrue(dpi >= DisplayMetrics.DENSITY_MEDIUM)

    val service = Robolectric.buildService(RecordingService::class.java).create().get()
    val (sw, sh, sdpi) = PlatformCompat.queryMaximumDisplayMetrics(service)
    assertTrue(sw >= 128)
    assertTrue(sh >= 128)
    assertTrue(sdpi >= DisplayMetrics.DENSITY_MEDIUM)
  }

  @Test
  fun `memory trim heuristic triggers cache release only on background or higher pressure`() {
    assertFalse(PlatformCompat.shouldReleaseIdleCachesOnTrim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
    assertTrue(PlatformCompat.shouldReleaseIdleCachesOnTrim(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
  }

  @Test
  fun `recording service handles denied projection consent, idle control commands, and trim cleanly`() {
    RecordingSessionStore.clearNotifications()
    val controller = Robolectric.buildService(RecordingService::class.java).create()
    val service = controller.get()

    // Idle control intents must not crash or alter phase
    service.onStartCommand(RecordingService.pauseIntent(service), 0, 1)
    assertEquals(RecordingPhase.IDLE, RecordingSessionStore.state.value.phase)

    service.onStartCommand(RecordingService.resumeIntent(service), 0, 2)
    assertEquals(RecordingPhase.IDLE, RecordingSessionStore.state.value.phase)

    service.onStartCommand(RecordingService.stopIntent(service), 0, 3)
    assertEquals(RecordingPhase.IDLE, RecordingSessionStore.state.value.phase)

    // Missing MediaProjection consent must stay IDLE and surface error safely
    val badStart =
      Intent(service, RecordingService::class.java).apply {
        action = RecordingService.ACTION_START
        putExtra(RecordingService.EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
      }
    service.onStartCommand(badStart, 0, 4)
    assertEquals(RecordingPhase.IDLE, RecordingSessionStore.state.value.phase)
    assertNotNull(RecordingSessionStore.state.value.errorMessage)

    // Low-memory callback when IDLE must execute cleanly
    service.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
    RecordingSessionStore.clearNotifications()
    controller.destroy()
  }
}
