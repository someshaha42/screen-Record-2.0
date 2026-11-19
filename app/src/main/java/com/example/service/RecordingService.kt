package com.example.service

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.audio.AudioCaptureManager
import com.example.capture.ScreenCaptureManager
import com.example.compat.PlatformCompat
import com.example.diagnostics.RecordingDiagnostics
import com.example.encoder.EncoderSelection
import com.example.encoder.VideoEncoder
import com.example.muxer.MediaMuxerManager
import com.example.region.PixelRegion
import com.example.region.RegionOverlayManager
import com.example.region.RegionSelectionManager
import com.example.settings.AudioFormatOption
import com.example.settings.AudioSourceOption
import com.example.settings.BitrateModeOption
import com.example.settings.ColorDepthOption
import com.example.settings.ColorRangeOption
import com.example.settings.GopIntervalOption
import com.example.settings.NormalizedRegion
import com.example.settings.OrientationMode
import com.example.settings.RecordingMode
import com.example.settings.RecordingSettings
import com.example.settings.RegionAnimationOption
import com.example.settings.VideoBitrateOption
import com.example.settings.VideoFpsOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

enum class RecordingPhase {
  IDLE,
  RECORDING,
  PAUSED,
  FINALIZING,
}

@Immutable
data class RecordingSessionState(
  val phase: RecordingPhase = RecordingPhase.IDLE,
  val elapsedMillis: Long = 0L,
  val activeMode: RecordingMode = RecordingMode.FULL_SCREEN,
  val activeAudioLabel: String = "",
  val activeEncoderName: String = "",
  val activeBitrateMbps: Int = 0,
  val activeResolutionLabel: String = "",
  val activeRegion: PixelRegion? = null,
  val showRegionBorder: Boolean = false,
  val lastSavedUri: Uri? = null,
  val lastSavedName: String? = null,
  val warningMessage: String? = null,
  val errorMessage: String? = null,
)

/**
 * Shared observable state store connecting the background RecordingService to the Jetpack Compose
 * UI layer cleanly without binding leaks.
 */
object RecordingSessionStore {
  private val _state = MutableStateFlow(RecordingSessionState())
  val state: StateFlow<RecordingSessionState> = _state.asStateFlow()

  internal fun update(transform: (RecordingSessionState) -> RecordingSessionState) {
    _state.update(transform)
  }

  fun clearNotifications() {
    _state.update { it.copy(warningMessage = null, errorMessage = null) }
  }
}

class RecordingService : Service() {

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var timerJob: Job? = null
  private var startJob: Job? = null

  @Volatile private var muxerManager: MediaMuxerManager? = null
  @Volatile private var videoEncoder: VideoEncoder? = null
  @Volatile private var audioCaptureManager: AudioCaptureManager? = null
  @Volatile private var screenCaptureManager: ScreenCaptureManager? = null
  @Volatile private var diagnostics: RecordingDiagnostics? = null
  private var overlayManager: RegionOverlayManager? = null
  @Volatile private var activeMediaProjection: MediaProjection? = null

  private val isStarting = AtomicBoolean(false)
  private val isStopping = AtomicBoolean(false)
  private var recordingStartElapsedMs = 0L
  private var pauseStartElapsedMs = 0L
  private var pausedAccumulatedMs = 0L
  private var currentMode = RecordingMode.FULL_SCREEN

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    overlayManager = RegionOverlayManager(this)
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START -> handleStartAction(intent)
      ACTION_PAUSE ->
        if (RecordingSessionStore.state.value.phase == RecordingPhase.RECORDING) pauseRecording()
        else if (RecordingSessionStore.state.value.phase == RecordingPhase.IDLE) stopSelf()
      ACTION_RESUME ->
        if (RecordingSessionStore.state.value.phase == RecordingPhase.PAUSED) resumeRecording()
        else if (RecordingSessionStore.state.value.phase == RecordingPhase.IDLE) stopSelf()
      ACTION_STOP -> stopRecording()
      ACTION_SET_REGION_ANIMATION -> handleRegionAnimationChange(intent)
      else -> if (RecordingSessionStore.state.value.phase == RecordingPhase.IDLE) stopSelf()
    }
    return START_NOT_STICKY
  }

  private fun handleRegionAnimationChange(intent: Intent) {
    val animation =
      runCatching {
          RegionAnimationOption.valueOf(
            intent.getStringExtra(EXTRA_REGION_ANIMATION) ?: RegionAnimationOption.SHOW.name,
          )
        }
        .getOrDefault(RegionAnimationOption.SHOW)

    val current = RecordingSessionStore.state.value
    val isActiveSession =
      current.phase == RecordingPhase.RECORDING || current.phase == RecordingPhase.PAUSED
    if (!isActiveSession) {
      overlayManager?.hide()
      return
    }

    val shouldShow =
      current.activeMode == RecordingMode.REGION &&
        animation == RegionAnimationOption.SHOW &&
        current.activeRegion != null

    if (shouldShow) {
      overlayManager?.show(current.activeRegion!!, enabled = true)
      if (current.phase == RecordingPhase.PAUSED) {
        overlayManager?.setPaused(true)
      }
    } else {
      overlayManager?.hide()
    }
    RecordingSessionStore.update { it.copy(showRegionBorder = shouldShow) }
  }

  private fun handleStartAction(intent: Intent) {
    if (RecordingSessionStore.state.value.phase != RecordingPhase.IDLE) return
    if (!isStarting.compareAndSet(false, true)) return

    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
    val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)

    if (resultCode != Activity.RESULT_OK || resultData == null) {
      isStarting.set(false)
      RecordingSessionStore.update {
        it.copy(errorMessage = "Screen capture permission was not granted.")
      }
      stopSelf()
      return
    }

    val mode =
      runCatching {
          RecordingMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: RecordingMode.FULL_SCREEN.name)
        }
        .getOrDefault(RecordingMode.FULL_SCREEN)
    val orientationMode =
      when (intent.getStringExtra(EXTRA_ORIENTATION_MODE)) {
        OrientationMode.LANDSCAPE.name,
        "LANDSCAPE_FIXED" -> OrientationMode.LANDSCAPE
        else -> OrientationMode.PORTRAIT
      }
    val audioSource =
      runCatching {
          AudioSourceOption.valueOf(
            intent.getStringExtra(EXTRA_AUDIO_SOURCE) ?: AudioSourceOption.INTERNAL.name,
          )
        }
        .getOrDefault(AudioSourceOption.INTERNAL)
    val bitrate =
      runCatching {
          VideoBitrateOption.valueOf(
            intent.getStringExtra(EXTRA_BITRATE) ?: VideoBitrateOption.MBPS_150.name,
          )
        }
        .getOrDefault(VideoBitrateOption.MBPS_150)
    val bitrateMode =
      runCatching {
          BitrateModeOption.valueOf(
            intent.getStringExtra(EXTRA_BITRATE_MODE) ?: BitrateModeOption.VBR.name,
          )
        }
        .getOrDefault(BitrateModeOption.VBR)
    val colorDepth =
      runCatching {
          ColorDepthOption.valueOf(
            intent.getStringExtra(EXTRA_COLOR_DEPTH) ?: ColorDepthOption.BIT_8.name,
          )
        }
        .getOrDefault(ColorDepthOption.BIT_8)
    val videoFps =
      runCatching {
          VideoFpsOption.valueOf(
            intent.getStringExtra(EXTRA_VIDEO_FPS) ?: VideoFpsOption.FPS_30.name,
          )
        }
        .getOrDefault(VideoFpsOption.FPS_30)
    val gopInterval =
      runCatching {
          GopIntervalOption.valueOf(
            intent.getStringExtra(EXTRA_GOP_INTERVAL) ?: GopIntervalOption.SEC_1.name,
          )
        }
        .getOrDefault(GopIntervalOption.SEC_1)
    val colorRange =
      runCatching {
          ColorRangeOption.valueOf(
            intent.getStringExtra(EXTRA_COLOR_RANGE) ?: ColorRangeOption.LIMITED.name,
          )
        }
        .getOrDefault(ColorRangeOption.LIMITED)
    val audioFormat =
      runCatching {
          AudioFormatOption.valueOf(
            intent.getStringExtra(EXTRA_AUDIO_FORMAT) ?: AudioFormatOption.MONO_AAC.name,
          )
        }
        .getOrDefault(AudioFormatOption.MONO_AAC)
    val animation =
      runCatching {
          RegionAnimationOption.valueOf(
            intent.getStringExtra(EXTRA_REGION_ANIMATION) ?: RegionAnimationOption.SHOW.name,
          )
        }
        .getOrDefault(RegionAnimationOption.SHOW)

    val normRegion =
      NormalizedRegion(
          left = intent.getFloatExtra(EXTRA_REGION_LEFT, 0.1f),
          top = intent.getFloatExtra(EXTRA_REGION_TOP, 0.2f),
          right = intent.getFloatExtra(EXTRA_REGION_RIGHT, 0.9f),
          bottom = intent.getFloatExtra(EXTRA_REGION_BOTTOM, 0.7f),
        )
        .normalized()

    val settings =
      RecordingSettings(
        recordingMode = mode,
        orientationMode = orientationMode,
        audioSource = audioSource,
        videoBitrate = bitrate,
        regionAnimation = animation,
        selectedRegion = normRegion,
        bitrateMode = bitrateMode,
        colorDepth = colorDepth,
        videoFps = videoFps,
        gopInterval = gopInterval,
        colorRange = colorRange,
        audioFormat = audioFormat,
      )
    currentMode = mode

    // Android 14+/16/17 requires startForeground() BEFORE MediaProjectionManager.getMediaProjection()
    if (!startForegroundCompat(isPaused = false, audioSource = audioSource)) {
      isStarting.set(false)
      RecordingSessionStore.update {
        it.copy(
          phase = RecordingPhase.IDLE,
          errorMessage = "Foreground recording service could not be started.",
        )
      }
      stopSelf()
      return
    }

    val projectionManager =
      getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    val mediaProjection =
      try {
        projectionManager.getMediaProjection(resultCode, resultData)
      } catch (e: Exception) {
        Log.e(TAG, "getMediaProjection failed", e)
        isStarting.set(false)
        RecordingSessionStore.update {
          it.copy(phase = RecordingPhase.IDLE, errorMessage = "Unable to start screen projection.")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return
      }

    if (mediaProjection == null) {
      isStarting.set(false)
      RecordingSessionStore.update {
        it.copy(phase = RecordingPhase.IDLE, errorMessage = "Screen projection session was null.")
      }
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return
    }

    activeMediaProjection = mediaProjection
    isStopping.set(false)
    startJob =
      serviceScope.launch(Dispatchers.IO) {
        startEngine(mediaProjection, settings)
      }
  }

  private fun startEngine(mediaProjection: MediaProjection, settings: RecordingSettings) {
    try {
      if (isStopping.get()) {
        releaseAllEngineResources()
        return
      }
      val (screenW, screenH, _) = ScreenCaptureManager.queryScreenMetrics(this)
      val (orientedW, orientedH) =
        RegionSelectionManager.resolveFullScreenDimensions(
          screenW,
          screenH,
          settings.orientationMode,
        )
      val pixelRegion: PixelRegion? =
        if (settings.recordingMode == RecordingMode.REGION) {
          RegionSelectionManager.toPixelRegion(settings.selectedRegion, orientedW, orientedH)
        } else {
          null
        }

      val targetW = pixelRegion?.width ?: orientedW
      val targetH = pixelRegion?.height ?: orientedH

      val hasAudioPermission =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
          PackageManager.PERMISSION_GRANTED
      val expectAudio = hasAudioPermission

      val sessionDiagnostics = RecordingDiagnostics(targetFps = settings.videoFps.fps)
      diagnostics = sessionDiagnostics

      val muxer =
        MediaMuxerManager(
          context = this,
          expectAudioTrackInitial = expectAudio,
          diagnostics = sessionDiagnostics,
        )
      muxer.prepare()
      muxerManager = muxer

      val encoder =
        VideoEncoder(
          requestedWidth = targetW,
          requestedHeight = targetH,
          bitrateOption = settings.videoBitrate,
          muxerManager = muxer,
          fpsOption = settings.videoFps,
          bitrateMode = settings.bitrateMode,
          colorDepth = settings.colorDepth,
          colorRange = settings.colorRange,
          gopInterval = settings.gopInterval,
          diagnostics = sessionDiagnostics,
        )
      val selection: EncoderSelection = encoder.prepare()
      videoEncoder = encoder

      val audioMgr =
        AudioCaptureManager(
          context = this,
          audioSource = settings.audioSource,
          audioFormatOption = settings.audioFormat,
          mediaProjection = mediaProjection,
          muxerManager = muxer,
          diagnostics = sessionDiagnostics,
          onStatusChanged = { status ->
            RecordingSessionStore.update {
              it.copy(
                activeAudioLabel = status.activeDeviceLabel,
                warningMessage = status.warningMessage ?: it.warningMessage,
              )
            }
          },
        )
      audioCaptureManager = audioMgr
      val audioStatus = audioMgr.start()

      val captureMgr =
        ScreenCaptureManager(
          context = this,
          mediaProjection = mediaProjection,
          outputSurface = encoder.getInputSurface(),
          outputWidth = selection.alignedWidth,
          outputHeight = selection.alignedHeight,
          onProjectionStopped = { stopRecording() },
        )
      screenCaptureManager = captureMgr
      captureMgr.start()

      val showBorder =
        settings.recordingMode == RecordingMode.REGION &&
          settings.regionAnimation == RegionAnimationOption.SHOW
      if (pixelRegion != null && showBorder) {
        overlayManager?.show(pixelRegion, enabled = true)
      } else {
        overlayManager?.hide()
      }

      recordingStartElapsedMs = SystemClock.elapsedRealtime()
      pauseStartElapsedMs = 0L
      pausedAccumulatedMs = 0L

      RecordingSessionStore.update {
        RecordingSessionState(
          phase = RecordingPhase.RECORDING,
          elapsedMillis = 0L,
          activeMode = settings.recordingMode,
          activeAudioLabel = audioStatus.activeDeviceLabel,
          activeEncoderName = selection.codecName,
          activeBitrateMbps = (selection.effectiveBitrateBps / 1_000_000).coerceAtLeast(1),
          activeResolutionLabel = "${selection.alignedWidth}×${selection.alignedHeight}",
          activeRegion = pixelRegion,
          showRegionBorder = showBorder,
          lastSavedUri = it.lastSavedUri,
          lastSavedName = it.lastSavedName,
          warningMessage = audioStatus.warningMessage,
          errorMessage = null,
        )
      }

      startTimerLoop()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start recording engine", e)
      releaseAllEngineResources()
      RecordingSessionStore.update {
        it.copy(
          phase = RecordingPhase.IDLE,
          elapsedMillis = 0L,
          errorMessage = e.message ?: "Failed to initialize screen recording engine.",
        )
      }
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
    } finally {
      isStarting.set(false)
    }
  }

  private fun pauseRecording() {
    if (RecordingSessionStore.state.value.phase != RecordingPhase.RECORDING) return
    pauseStartElapsedMs = SystemClock.elapsedRealtime()
    screenCaptureManager?.setPaused(true)
    videoEncoder?.setPaused(true)
    audioCaptureManager?.setPaused(true)
    overlayManager?.setPaused(true)
    RecordingSessionStore.update { it.copy(phase = RecordingPhase.PAUSED) }
    updateNotification(isPaused = true)
  }

  private fun resumeRecording() {
    if (RecordingSessionStore.state.value.phase != RecordingPhase.PAUSED) return
    if (pauseStartElapsedMs > 0L) {
      pausedAccumulatedMs += (SystemClock.elapsedRealtime() - pauseStartElapsedMs).coerceAtLeast(0L)
      pauseStartElapsedMs = 0L
    }
    videoEncoder?.setPaused(false)
    screenCaptureManager?.setPaused(false)
    audioCaptureManager?.setPaused(false)
    overlayManager?.setPaused(false)
    RecordingSessionStore.update { it.copy(phase = RecordingPhase.RECORDING) }
    updateNotification(isPaused = false)
  }

  private fun stopRecording() {
    val currentPhase = RecordingSessionStore.state.value.phase
    if (currentPhase == RecordingPhase.IDLE && !isStarting.get()) {
      overlayManager?.hide()
      stopSelf()
      return
    }
    if (currentPhase == RecordingPhase.FINALIZING) {
      overlayManager?.hide()
      return
    }
    if (!isStopping.compareAndSet(false, true)) {
      return
    }

    timerJob?.cancel()
    timerJob = null
    overlayManager?.hide()
    RecordingSessionStore.update {
      it.copy(phase = RecordingPhase.FINALIZING, showRegionBorder = false)
    }

    serviceScope.launch(Dispatchers.IO) {
      startJob?.join()
      startJob = null
      val savedOutput = releaseAllEngineResources()
      RecordingSessionStore.update { prev ->
        prev.copy(
          phase = RecordingPhase.IDLE,
          elapsedMillis = 0L,
          activeRegion = null,
          showRegionBorder = false,
          lastSavedUri = savedOutput?.uri ?: prev.lastSavedUri,
          lastSavedName = savedOutput?.displayName ?: prev.lastSavedName,
          errorMessage =
            if (savedOutput == null) "Recording ended before any video frames were captured."
            else prev.errorMessage,
        )
      }
      isStopping.set(false)
      runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
      runCatching {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
      }
      stopSelf()
    }
  }

  private fun releaseAllEngineResources() =
    run {
      overlayManager?.hide()
      runCatching { screenCaptureManager?.stopAndRelease() }
        .onFailure { Log.e(TAG, "Error stopping ScreenCaptureManager", it) }
      screenCaptureManager = null

      runCatching { activeMediaProjection?.stop() }
        .onFailure { Log.w(TAG, "Error stopping active MediaProjection", it) }
      activeMediaProjection = null

      runCatching { audioCaptureManager?.stopAndRelease() }
        .onFailure { Log.e(TAG, "Error stopping AudioCaptureManager", it) }
      audioCaptureManager = null

      runCatching { videoEncoder?.stopAndRelease() }
        .onFailure { Log.e(TAG, "Error stopping VideoEncoder", it) }
      videoEncoder = null

      runCatching { muxerManager?.finishAndRelease() }
        .onFailure { Log.e(TAG, "Error finalizing MediaMuxerManager", it) }
        .getOrNull()
        .also {
          muxerManager = null
          runCatching { diagnostics?.logFinalSummary(this) }
          diagnostics = null
        }
    }

  private fun startTimerLoop() {
    timerJob?.cancel()
    timerJob =
      serviceScope.launch {
        var lastEmittedSecond = -1L
        while (isActive) {
          when (RecordingSessionStore.state.value.phase) {
            RecordingPhase.RECORDING -> {
              val elapsed =
                (SystemClock.elapsedRealtime() - recordingStartElapsedMs - pausedAccumulatedMs)
                  .coerceAtLeast(0L)
              val currentSecond = elapsed / 1000L
              if (currentSecond != lastEmittedSecond) {
                lastEmittedSecond = currentSecond
                RecordingSessionStore.update { it.copy(elapsedMillis = elapsed) }
                if (currentSecond > 0L && currentSecond % DIAGNOSTICS_SUMMARY_INTERVAL_SECONDS == 0L) {
                  diagnostics?.logPeriodicSummary(this@RecordingService)
                }
              }
              val nextTickDelayMs = (1000L - (elapsed % 1000L)).coerceIn(50L, 1000L)
              delay(nextTickDelayMs)
            }
            RecordingPhase.PAUSED -> {
              // Suspend with zero CPU wakeups until recording resumes or stops
              RecordingSessionStore.state.first { it.phase != RecordingPhase.PAUSED }
            }
            RecordingPhase.IDLE,
            RecordingPhase.FINALIZING -> break
          }
        }
      }
  }

  private fun startForegroundCompat(isPaused: Boolean, audioSource: AudioSourceOption): Boolean {
    val notification = buildNotification(isPaused)
    return PlatformCompat.startRecordingForeground(
      service = this,
      notificationId = NOTIFICATION_ID,
      notification = notification,
      audioSource = audioSource,
    )
  }

  private fun updateNotification(isPaused: Boolean) {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(NOTIFICATION_ID, buildNotification(isPaused))
  }

  private fun buildNotification(isPaused: Boolean): Notification {
    val launchIntent =
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val contentPendingIntent =
      PendingIntent.getActivity(
        this,
        0,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val toggleActionIntent =
      Intent(this, RecordingService::class.java).apply {
        action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
      }
    val togglePendingIntent =
      PendingIntent.getService(
        this,
        1,
        toggleActionIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val stopIntent = Intent(this, RecordingService::class.java).apply { action = ACTION_STOP }
    val stopPendingIntent =
      PendingIntent.getService(
        this,
        2,
        stopIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val title =
      if (isPaused) getString(R.string.notification_title_paused)
      else getString(R.string.notification_title_recording)
    val contentText =
      if (currentMode == RecordingMode.REGION) getString(R.string.notification_text_region)
      else getString(R.string.notification_text_fullscreen)

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_stat_recording)
      .setContentTitle(title)
      .setContentText(contentText)
      .setContentIntent(contentPendingIntent)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .addAction(
        0,
        if (isPaused) getString(R.string.notification_action_resume)
        else getString(R.string.notification_action_pause),
        togglePendingIntent,
      )
      .addAction(0, getString(R.string.notification_action_stop), stopPendingIntent)
      .build()
  }

  private fun createNotificationChannel() {
    val channel =
      NotificationChannel(
          CHANNEL_ID,
          getString(R.string.notification_channel_name),
          NotificationManager.IMPORTANCE_LOW,
        )
        .apply { description = getString(R.string.notification_channel_description) }
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(channel)
  }

  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    if (
      PlatformCompat.shouldReleaseIdleCachesOnTrim(level) &&
        RecordingSessionStore.state.value.phase == RecordingPhase.IDLE
    ) {
      overlayManager?.hide()
      stopSelf()
    }
  }

  override fun onDestroy() {
    timerJob?.cancel()
    overlayManager?.hide()
    runCatching { activeMediaProjection?.stop() }
    activeMediaProjection = null
    serviceScope.cancel()
    super.onDestroy()
  }

  companion object {
    private const val TAG = "RecordingService"
    private const val CHANNEL_ID = "framecut_recording_channel"
    private const val NOTIFICATION_ID = 4102
    private const val DIAGNOSTICS_SUMMARY_INTERVAL_SECONDS = 10L

    const val ACTION_START = "com.example.service.ACTION_START"
    const val ACTION_PAUSE = "com.example.service.ACTION_PAUSE"
    const val ACTION_RESUME = "com.example.service.ACTION_RESUME"
    const val ACTION_STOP = "com.example.service.ACTION_STOP"
    const val ACTION_SET_REGION_ANIMATION = "com.example.service.ACTION_SET_REGION_ANIMATION"

    const val EXTRA_RESULT_CODE = "extra_result_code"
    const val EXTRA_RESULT_DATA = "extra_result_data"
    const val EXTRA_MODE = "extra_mode"
    const val EXTRA_ORIENTATION_MODE = "extra_orientation_mode"
    const val EXTRA_AUDIO_SOURCE = "extra_audio_source"
    const val EXTRA_BITRATE = "extra_bitrate"
    const val EXTRA_BITRATE_MODE = "extra_bitrate_mode"
    const val EXTRA_COLOR_DEPTH = "extra_color_depth"
    const val EXTRA_VIDEO_FPS = "extra_video_fps"
    const val EXTRA_GOP_INTERVAL = "extra_gop_interval"
    const val EXTRA_COLOR_RANGE = "extra_color_range"
    const val EXTRA_AUDIO_FORMAT = "extra_audio_format"
    const val EXTRA_REGION_ANIMATION = "extra_region_animation"
    const val EXTRA_REGION_LEFT = "extra_region_left"
    const val EXTRA_REGION_TOP = "extra_region_top"
    const val EXTRA_REGION_RIGHT = "extra_region_right"
    const val EXTRA_REGION_BOTTOM = "extra_region_bottom"

    fun startRecordingIntent(
      context: Context,
      resultCode: Int,
      resultData: Intent,
      settings: RecordingSettings,
    ): Intent {
      return Intent(context, RecordingService::class.java).apply {
        action = ACTION_START
        putExtra(EXTRA_RESULT_CODE, resultCode)
        putExtra(EXTRA_RESULT_DATA, resultData)
        putExtra(EXTRA_MODE, settings.recordingMode.name)
        putExtra(EXTRA_ORIENTATION_MODE, settings.orientationMode.name)
        putExtra(EXTRA_AUDIO_SOURCE, settings.audioSource.name)
        putExtra(EXTRA_BITRATE, settings.videoBitrate.name)
        putExtra(EXTRA_BITRATE_MODE, settings.bitrateMode.name)
        putExtra(EXTRA_COLOR_DEPTH, settings.colorDepth.name)
        putExtra(EXTRA_VIDEO_FPS, settings.videoFps.name)
        putExtra(EXTRA_GOP_INTERVAL, settings.gopInterval.name)
        putExtra(EXTRA_COLOR_RANGE, settings.colorRange.name)
        putExtra(EXTRA_AUDIO_FORMAT, settings.audioFormat.name)
        putExtra(EXTRA_REGION_ANIMATION, settings.regionAnimation.name)
        putExtra(EXTRA_REGION_LEFT, settings.selectedRegion.left)
        putExtra(EXTRA_REGION_TOP, settings.selectedRegion.top)
        putExtra(EXTRA_REGION_RIGHT, settings.selectedRegion.right)
        putExtra(EXTRA_REGION_BOTTOM, settings.selectedRegion.bottom)
      }
    }

    fun pauseIntent(context: Context): Intent =
      Intent(context, RecordingService::class.java).apply { action = ACTION_PAUSE }

    fun resumeIntent(context: Context): Intent =
      Intent(context, RecordingService::class.java).apply { action = ACTION_RESUME }

    fun stopIntent(context: Context): Intent =
      Intent(context, RecordingService::class.java).apply { action = ACTION_STOP }

    fun setRegionAnimationIntent(
      context: Context,
      animation: RegionAnimationOption,
    ): Intent =
      Intent(context, RecordingService::class.java).apply {
        action = ACTION_SET_REGION_ANIMATION
        putExtra(EXTRA_REGION_ANIMATION, animation.name)
      }
  }
}
