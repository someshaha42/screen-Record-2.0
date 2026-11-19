package com.example.ui

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.capture.ScreenCaptureManager
import com.example.recordings.RecordedVideoItem
import com.example.recordings.RecordingsRepository
import com.example.region.PixelRegion
import com.example.region.RegionSelectionManager
import com.example.service.RecordingPhase
import com.example.service.RecordingService
import com.example.service.RecordingSessionState
import com.example.service.RecordingSessionStore
import com.example.settings.AudioSourceOption
import com.example.settings.NormalizedRegion
import com.example.settings.OrientationMode
import com.example.settings.RecordingMode
import com.example.settings.RecordingSettings
import com.example.settings.RecordingSettingsRepository
import com.example.settings.RegionAnimationOption
import com.example.settings.VideoBitrateOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class RecorderUiState(
  val settings: RecordingSettings = RecordingSettings(),
  val session: RecordingSessionState = RecordingSessionState(),
  val screenWidthPx: Int = 1080,
  val screenHeightPx: Int = 2400,
  val canvasWidthPx: Int = 1080,
  val canvasHeightPx: Int = 2400,
  val selectedPixelRegion: PixelRegion =
    RegionSelectionManager.toPixelRegion(NormalizedRegion(), 1080, 2400),
  val hasOverlayPermission: Boolean = false,
  val isEditingRegion: Boolean = false,
  val recordings: List<RecordedVideoItem> = emptyList(),
  val isLoadingRecordings: Boolean = false,
)

class RecorderViewModel(application: Application) : AndroidViewModel(application) {

  private val appContext = application.applicationContext
  private val settingsRepo = RecordingSettingsRepository(appContext)
  private val recordingsRepo = RecordingsRepository(appContext)

  private val overlayPermissionFlow = MutableStateFlow(checkOverlayPermission())
  private val isEditingRegionFlow = MutableStateFlow(false)
  private val recordingsFlow = MutableStateFlow<List<RecordedVideoItem>>(emptyList())
  private val isLoadingRecordingsFlow = MutableStateFlow(false)
  private val screenDimensionsFlow = MutableStateFlow(queryScreenDimensions())

  private data class AuxiliaryState(
    val hasOverlay: Boolean,
    val isEditingRegion: Boolean,
    val recordings: List<RecordedVideoItem>,
    val isLoadingRecordings: Boolean,
    val screenDims: Pair<Int, Int>,
  )

  private val auxFlow =
    combine(
      overlayPermissionFlow,
      isEditingRegionFlow,
      recordingsFlow,
      isLoadingRecordingsFlow,
      screenDimensionsFlow,
    ) { hasOverlay, editing, recordings, loading, dims ->
      AuxiliaryState(hasOverlay, editing, recordings, loading, dims)
    }

  val uiState: StateFlow<RecorderUiState> =
    combine(settingsRepo.settingsFlow, RecordingSessionStore.state, auxFlow) {
        settings,
        session,
        aux ->
        val (screenW, screenH) = aux.screenDims
        val (canvasW, canvasH) =
          RegionSelectionManager.resolveFullScreenDimensions(
            screenW,
            screenH,
            settings.orientationMode,
          )
        val pixelRegion =
          RegionSelectionManager.toPixelRegion(settings.selectedRegion, canvasW, canvasH)
        RecorderUiState(
          settings = settings,
          session = session,
          screenWidthPx = screenW,
          screenHeightPx = screenH,
          canvasWidthPx = canvasW,
          canvasHeightPx = canvasH,
          selectedPixelRegion = pixelRegion,
          hasOverlayPermission = aux.hasOverlay,
          isEditingRegion = aux.isEditingRegion,
          recordings = aux.recordings,
          isLoadingRecordings = aux.isLoadingRecordings,
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = RecorderUiState(hasOverlayPermission = checkOverlayPermission()),
      )

  init {
    refreshRecordings()
    // Refresh recordings automatically whenever a recording completes back to IDLE
    viewModelScope.launch {
      var prevPhase = RecordingPhase.IDLE
      RecordingSessionStore.state.collect { state ->
        if (prevPhase != RecordingPhase.IDLE && state.phase == RecordingPhase.IDLE) {
          refreshRecordings()
        }
        prevPhase = state.phase
      }
    }
  }

  fun refreshSystemState() {
    overlayPermissionFlow.value = checkOverlayPermission()
    screenDimensionsFlow.value = queryScreenDimensions()
  }

  fun setRecordingMode(mode: RecordingMode) {
    if (uiState.value.session.phase != RecordingPhase.IDLE) return
    viewModelScope.launch { settingsRepo.setRecordingMode(mode) }
  }

  @Volatile private var latestOrientationModeOverride: OrientationMode? = null

  fun setOrientationMode(mode: OrientationMode) {
    if (uiState.value.session.phase != RecordingPhase.IDLE) return
    latestOrientationModeOverride = mode
    viewModelScope.launch { settingsRepo.setOrientationMode(mode) }
  }

  fun setAudioSource(source: AudioSourceOption) {
    if (uiState.value.session.phase != RecordingPhase.IDLE) return
    viewModelScope.launch { settingsRepo.setAudioSource(source) }
  }

  fun setVideoBitrate(bitrate: VideoBitrateOption) {
    if (uiState.value.session.phase != RecordingPhase.IDLE) return
    viewModelScope.launch { settingsRepo.setVideoBitrate(bitrate) }
  }

  @Volatile private var latestRegionAnimationOverride: RegionAnimationOption? = null

  fun setRegionAnimation(option: RegionAnimationOption) {
    latestRegionAnimationOverride = option
    viewModelScope.launch { settingsRepo.setRegionAnimation(option) }
    val phase = uiState.value.session.phase
    if (phase == RecordingPhase.RECORDING || phase == RecordingPhase.PAUSED) {
      runCatching {
        appContext.startService(RecordingService.setRegionAnimationIntent(appContext, option))
      }
    }
  }

  fun openRegionEditor() {
    if (uiState.value.session.phase != RecordingPhase.IDLE) return
    refreshSystemState()
    isEditingRegionFlow.value = true
  }

  fun closeRegionEditor() {
    isEditingRegionFlow.value = false
  }

  fun saveSelectedRegion(region: NormalizedRegion) {
    viewModelScope.launch {
      settingsRepo.saveSelectedRegion(region)
      isEditingRegionFlow.value = false
    }
  }

  fun resetSelectedRegion() {
    viewModelScope.launch { settingsRepo.saveSelectedRegion(NormalizedRegion()) }
  }

  fun startRecording(resultCode: Int, resultData: Intent) {
    val baseSettings = uiState.value.settings
    val effectiveOrientation = latestOrientationModeOverride ?: baseSettings.orientationMode
    val effectiveAnimation = latestRegionAnimationOverride ?: baseSettings.regionAnimation
    val currentSettings =
      baseSettings.copy(
        orientationMode = effectiveOrientation,
        regionAnimation = effectiveAnimation,
      )
    val intent =
      RecordingService.startRecordingIntent(
        context = appContext,
        resultCode = resultCode,
        resultData = resultData,
        settings = currentSettings,
      )
    ContextCompat.startForegroundService(appContext, intent)
  }

  fun togglePauseResume() {
    val phase = uiState.value.session.phase
    val intent =
      when (phase) {
        RecordingPhase.RECORDING -> RecordingService.pauseIntent(appContext)
        RecordingPhase.PAUSED -> RecordingService.resumeIntent(appContext)
        else -> return
      }
    runCatching { appContext.startService(intent) }
      .onFailure {
        RecordingSessionStore.update {
          it.copy(phase = RecordingPhase.IDLE, elapsedMillis = 0L, showRegionBorder = false)
        }
      }
  }

  fun stopRecording() {
    val phase = uiState.value.session.phase
    if (phase != RecordingPhase.RECORDING && phase != RecordingPhase.PAUSED) return
    val intent = RecordingService.stopIntent(appContext)
    runCatching { appContext.startService(intent) }
      .onFailure {
        RecordingSessionStore.update {
          it.copy(phase = RecordingPhase.IDLE, elapsedMillis = 0L, showRegionBorder = false)
        }
      }
  }

  fun clearMessages() {
    RecordingSessionStore.clearNotifications()
  }

  fun refreshRecordings() {
    viewModelScope.launch {
      isLoadingRecordingsFlow.value = true
      val items = recordingsRepo.loadRecordings()
      recordingsFlow.update { items }
      isLoadingRecordingsFlow.value = false
    }
  }

  fun deleteRecording(item: RecordedVideoItem) {
    viewModelScope.launch {
      if (recordingsRepo.deleteRecording(item)) {
        refreshRecordings()
      }
    }
  }

  private fun checkOverlayPermission(): Boolean = Settings.canDrawOverlays(appContext)

  private fun queryScreenDimensions(): Pair<Int, Int> {
    val (w, h, _) = ScreenCaptureManager.queryScreenMetrics(appContext)
    return w to h
  }
}
