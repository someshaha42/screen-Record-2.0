package com.example.ui.screens

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.service.RecordingPhase
import com.example.settings.AudioSourceOption
import com.example.settings.OrientationMode
import com.example.settings.RecordingMode
import com.example.settings.RegionAnimationOption
import com.example.settings.VideoBitrateOption
import com.example.ui.RecorderUiState
import com.example.ui.components.RegionBorderOverlayCanvas
import com.example.ui.theme.LocalStudioColors
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
  uiState: RecorderUiState,
  onStartRecordClick: () -> Unit,
  onPauseResumeClick: () -> Unit,
  onStopClick: () -> Unit,
  onOpenRecordings: () -> Unit,
  onSelectMode: (RecordingMode) -> Unit,
  onSelectOrientationMode: (OrientationMode) -> Unit,
  onSelectAudioSource: (AudioSourceOption) -> Unit,
  onSelectBitrate: (VideoBitrateOption) -> Unit,
  onSelectRegionAnimation: (RegionAnimationOption) -> Unit,
  onEditRegion: () -> Unit,
  onRequestOverlayPermission: () -> Unit,
  onDismissMessages: () -> Unit,
) {
  val isIdle = uiState.session.phase == RecordingPhase.IDLE
  val settingsAlpha = if (isIdle) 1f else 0.58f
  val configuration = LocalConfiguration.current
  val isTelevision =
    remember(configuration.uiMode) {
      (configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
    }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Box(
              modifier =
                Modifier.size(30.dp)
                  .clip(RoundedCornerShape(8.dp))
                  .background(LocalStudioColors.current.recordContainer),
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                imageVector = Icons.Default.FiberManualRecord,
                contentDescription = null,
                tint = LocalStudioColors.current.recordAccent,
                modifier = Modifier.size(16.dp),
              )
            }
            Text(
              text = stringResource(R.string.app_name),
              style = MaterialTheme.typography.headlineSmall,
            )
          }
        },
        actions = {
          IconButton(
            onClick = onOpenRecordings,
            modifier =
              Modifier.minimumInteractiveComponentSize()
                .focusRing(shape = CircleShape)
                .testTag("recordings_nav_button"),
          ) {
            BadgedBox(
              badge = {
                if (uiState.recordings.isNotEmpty()) {
                  Badge { Text(uiState.recordings.size.toString()) }
                }
              },
            ) {
              Icon(
                imageVector = Icons.Default.VideoLibrary,
                contentDescription = stringResource(R.string.cd_recordings_button),
              )
            }
          }
        },
        colors =
          TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
      )
    },
  ) { innerPadding ->
    BoxWithConstraints(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentAlignment = Alignment.TopCenter,
    ) {
      val isExpandedTwoPane = maxWidth >= 840.dp
      val useTwoColumnsInRightPane = maxWidth >= 1060.dp
      val isWideSettingsGrid = maxWidth >= 660.dp
      val horizontalPadding = if (isTelevision || isExpandedTwoPane) 24.dp else 16.dp
      val verticalPadding = if (isTelevision) 14.dp else 8.dp
      val bannerText = uiState.session.errorMessage ?: uiState.session.warningMessage

      if (isExpandedTwoPane) {
        // Canonical 2-pane layout for Tablets (Landscape), Android TV, Foldables & ChromeOS
        Row(
          modifier =
            Modifier.widthIn(max = 1280.dp)
              .fillMaxSize()
              .padding(horizontal = horizontalPadding, vertical = verticalPadding),
          horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
          // Left anchored studio console pane (always visible & immediately focusable on TV)
          Column(
            modifier =
              Modifier.widthIn(min = 310.dp, max = 400.dp)
                .weight(0.38f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
          ) {
            FeedbackBanner(
              bannerText = bannerText,
              isError = uiState.session.errorMessage != null,
              onDismissMessages = onDismissMessages,
            )
            RecordingConsoleCard(
              uiState = uiState,
              onStartRecordClick = onStartRecordClick,
              onPauseResumeClick = onPauseResumeClick,
              onStopClick = onStopClick,
            )
            SavedVideoQuickBanner(
              visible = isIdle && uiState.session.lastSavedName != null,
              fileName = uiState.session.lastSavedName,
              onOpenRecordings = onOpenRecordings,
            )
            RecordingsLibraryButton(
              count = uiState.recordings.size,
              onOpenRecordings = onOpenRecordings,
            )
          }

          // Right independently scrollable settings pane (2-column grid on ultra-wide/TV)
          Column(
            modifier =
              Modifier.weight(0.62f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
          ) {
            SettingsCardsSection(
              uiState = uiState,
              isIdle = isIdle,
              settingsAlpha = settingsAlpha,
              twoColumns = useTwoColumnsInRightPane,
              onSelectMode = onSelectMode,
              onSelectOrientationMode = onSelectOrientationMode,
              onSelectAudioSource = onSelectAudioSource,
              onSelectBitrate = onSelectBitrate,
              onSelectRegionAnimation = onSelectRegionAnimation,
              onEditRegion = onEditRegion,
              onRequestOverlayPermission = onRequestOverlayPermission,
            )
            Spacer(modifier = Modifier.height(8.dp))
          }
        }
      } else {
        Column(
          modifier =
            Modifier.widthIn(max = 820.dp)
              .fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(horizontal = horizontalPadding, vertical = verticalPadding),
          verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          FeedbackBanner(
            bannerText = bannerText,
            isError = uiState.session.errorMessage != null,
            onDismissMessages = onDismissMessages,
          )
          RecordingConsoleCard(
            uiState = uiState,
            onStartRecordClick = onStartRecordClick,
            onPauseResumeClick = onPauseResumeClick,
            onStopClick = onStopClick,
          )
          SavedVideoQuickBanner(
            visible = isIdle && uiState.session.lastSavedName != null,
            fileName = uiState.session.lastSavedName,
            onOpenRecordings = onOpenRecordings,
          )
          SettingsCardsSection(
            uiState = uiState,
            isIdle = isIdle,
            settingsAlpha = settingsAlpha,
            twoColumns = isWideSettingsGrid,
            onSelectMode = onSelectMode,
            onSelectOrientationMode = onSelectOrientationMode,
            onSelectAudioSource = onSelectAudioSource,
            onSelectBitrate = onSelectBitrate,
            onSelectRegionAnimation = onSelectRegionAnimation,
            onEditRegion = onEditRegion,
            onRequestOverlayPermission = onRequestOverlayPermission,
          )
          RecordingsLibraryButton(
            count = uiState.recordings.size,
            onOpenRecordings = onOpenRecordings,
          )
          Spacer(modifier = Modifier.height(12.dp))
        }
      }
    }
  }
}

@Composable
private fun FeedbackBanner(
  bannerText: String?,
  isError: Boolean,
  onDismissMessages: () -> Unit,
) {
  AnimatedVisibility(visible = bannerText != null) {
    if (bannerText != null) {
      Surface(
        shape = RoundedCornerShape(14.dp),
        color =
          if (isError) MaterialTheme.colorScheme.errorContainer
          else LocalStudioColors.current.pausedContainer,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = bannerText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
          )
          TextButton(
            onClick = onDismissMessages,
            modifier = Modifier.focusRing(RoundedCornerShape(10.dp)),
          ) {
            Text(stringResource(R.string.action_close))
          }
        }
      }
    }
  }
}

@Composable
private fun SavedVideoQuickBanner(
  visible: Boolean,
  fileName: String?,
  onOpenRecordings: () -> Unit,
) {
  AnimatedVisibility(visible = visible && fileName != null) {
    if (fileName != null) {
      Surface(
        onClick = onOpenRecordings,
        shape = RoundedCornerShape(14.dp),
        color = LocalStudioColors.current.readyContainer,
        modifier =
          Modifier.fillMaxWidth()
            .focusRing(RoundedCornerShape(14.dp))
            .testTag("last_saved_banner"),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Saved to Movies/FrameCut",
              style = MaterialTheme.typography.labelLarge,
              color = LocalStudioColors.current.readyAccent,
            )
            Text(
              text = fileName,
              style = MaterialTheme.typography.bodyMedium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          Text(
            text = stringResource(R.string.action_play),
            style = MaterialTheme.typography.labelLarge,
            color = LocalStudioColors.current.readyAccent,
          )
        }
      }
    }
  }
}

@Composable
private fun RecordingsLibraryButton(
  count: Int,
  onOpenRecordings: () -> Unit,
) {
  OutlinedButton(
    onClick = onOpenRecordings,
    modifier =
      Modifier.fillMaxWidth()
        .height(52.dp)
        .focusRing(RoundedCornerShape(14.dp))
        .testTag("open_recordings_button"),
    shape = RoundedCornerShape(14.dp),
  ) {
    Icon(
      imageVector = Icons.Default.VideoLibrary,
      contentDescription = null,
      modifier = Modifier.padding(end = 8.dp).size(18.dp),
    )
    Text(
      text =
        if (count == 0) stringResource(R.string.recordings_title)
        else "${stringResource(R.string.recordings_title)} ($count)",
    )
  }
}

@Composable
private fun SettingsCardsSection(
  uiState: RecorderUiState,
  isIdle: Boolean,
  settingsAlpha: Float,
  twoColumns: Boolean,
  onSelectMode: (RecordingMode) -> Unit,
  onSelectOrientationMode: (OrientationMode) -> Unit,
  onSelectAudioSource: (AudioSourceOption) -> Unit,
  onSelectBitrate: (VideoBitrateOption) -> Unit,
  onSelectRegionAnimation: (RegionAnimationOption) -> Unit,
  onEditRegion: () -> Unit,
  onRequestOverlayPermission: () -> Unit,
) {
  if (twoColumns) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Box(modifier = Modifier.alpha(settingsAlpha)) {
          RecordingModeCard(
            selectedMode = uiState.settings.recordingMode,
            enabled = isIdle,
            onSelectMode = onSelectMode,
          )
        }
        if (uiState.settings.recordingMode == RecordingMode.REGION) {
          Box(modifier = Modifier.alpha(settingsAlpha)) {
            RegionPreviewCard(
              uiState = uiState,
              enabled = isIdle,
              onEditRegion = onEditRegion,
              onRequestOverlayPermission = onRequestOverlayPermission,
            )
          }
        }
        Box(modifier = Modifier.alpha(settingsAlpha)) {
          OrientationModeCard(
            selectedOrientation = uiState.settings.orientationMode,
            enabled = isIdle,
            onSelectOrientation = onSelectOrientationMode,
          )
        }
        RegionAnimationCard(
          selectedAnimation = uiState.settings.regionAnimation,
          enabled = true,
          onSelect = onSelectRegionAnimation,
        )
      }
      Column(
        modifier = Modifier.weight(1f).alpha(settingsAlpha),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        AudioSourceCard(
          selectedAudio = uiState.settings.audioSource,
          enabled = isIdle,
          onSelectAudio = onSelectAudioSource,
        )
        VideoBitrateCard(
          selectedBitrate = uiState.settings.videoBitrate,
          enabled = isIdle,
          onSelectBitrate = onSelectBitrate,
        )
      }
    }
  } else {
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Box(modifier = Modifier.alpha(settingsAlpha)) {
        RecordingModeCard(
          selectedMode = uiState.settings.recordingMode,
          enabled = isIdle,
          onSelectMode = onSelectMode,
        )
      }

      AnimatedVisibility(visible = uiState.settings.recordingMode == RecordingMode.REGION) {
        Box(modifier = Modifier.alpha(settingsAlpha)) {
          RegionPreviewCard(
            uiState = uiState,
            enabled = isIdle,
            onEditRegion = onEditRegion,
            onRequestOverlayPermission = onRequestOverlayPermission,
          )
        }
      }

      Box(modifier = Modifier.alpha(settingsAlpha)) {
        OrientationModeCard(
          selectedOrientation = uiState.settings.orientationMode,
          enabled = isIdle,
          onSelectOrientation = onSelectOrientationMode,
        )
      }

      Box(modifier = Modifier.alpha(settingsAlpha)) {
        AudioSourceCard(
          selectedAudio = uiState.settings.audioSource,
          enabled = isIdle,
          onSelectAudio = onSelectAudioSource,
        )
      }

      Box(modifier = Modifier.alpha(settingsAlpha)) {
        VideoBitrateCard(
          selectedBitrate = uiState.settings.videoBitrate,
          enabled = isIdle,
          onSelectBitrate = onSelectBitrate,
        )
      }

      RegionAnimationCard(
        selectedAnimation = uiState.settings.regionAnimation,
        enabled = true,
        onSelect = onSelectRegionAnimation,
      )
    }
  }
}

@Composable
private fun RecordingConsoleCard(
  uiState: RecorderUiState,
  onStartRecordClick: () -> Unit,
  onPauseResumeClick: () -> Unit,
  onStopClick: () -> Unit,
) {
  val studioColors = LocalStudioColors.current
  val phase = uiState.session.phase

  val statusLabel =
    when (phase) {
      RecordingPhase.IDLE -> stringResource(R.string.status_ready)
      RecordingPhase.RECORDING -> stringResource(R.string.status_recording)
      RecordingPhase.PAUSED -> stringResource(R.string.status_paused)
      RecordingPhase.FINALIZING -> stringResource(R.string.status_saving)
    }

  val statusColor by
    animateColorAsState(
      targetValue =
        when (phase) {
          RecordingPhase.IDLE -> studioColors.readyAccent
          RecordingPhase.RECORDING -> studioColors.recordAccent
          RecordingPhase.PAUSED -> studioColors.pausedAccent
          RecordingPhase.FINALIZING -> MaterialTheme.colorScheme.secondary
        },
      label = "status_color",
    )

  val statusContainer by
    animateColorAsState(
      targetValue =
        when (phase) {
          RecordingPhase.IDLE -> studioColors.readyContainer
          RecordingPhase.RECORDING -> studioColors.recordContainer
          RecordingPhase.PAUSED -> studioColors.pausedContainer
          RecordingPhase.FINALIZING -> MaterialTheme.colorScheme.secondaryContainer
        },
      label = "status_container",
    )

  // Pulse dot ONLY while actively recording to conserve GPU/battery when idle
  val dotAlpha =
    if (phase == RecordingPhase.RECORDING) {
      val transition = rememberInfiniteTransition(label = "rec_dot_pulse")
      val alpha by
        transition.animateFloat(
          initialValue = 1f,
          targetValue = 0.25f,
          animationSpec =
            infiniteRepeatable(animation = tween(700), repeatMode = RepeatMode.Reverse),
          label = "dot_alpha",
        )
      alpha
    } else {
      1f
    }

  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .background(
            Brush.verticalGradient(
              colors =
                listOf(
                  statusContainer.copy(alpha = 0.36f),
                  MaterialTheme.colorScheme.surface,
                ),
            ),
          )
          .padding(20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      // Status badge
      Surface(
        shape = CircleShape,
        color = statusContainer,
        modifier = Modifier.testTag("recording_status_badge"),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Box(
            modifier =
              Modifier.size(8.dp).alpha(dotAlpha).clip(CircleShape).background(statusColor),
          )
          Text(
            text = statusLabel,
            style = MaterialTheme.typography.labelLarge,
            color = statusColor,
          )
        }
      }

      // Recording timer
      Text(
        text = formatElapsedTimer(uiState.session.elapsedMillis),
        style = MaterialTheme.typography.displayMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag("recording_timer"),
      )

      // Contextual live session readout (mode + orientation + active audio route)
      val orientationSummary =
        when (uiState.settings.orientationMode) {
          OrientationMode.PORTRAIT -> stringResource(R.string.orientation_portrait)
          OrientationMode.LANDSCAPE -> stringResource(R.string.orientation_landscape)
        }
      val subtitleText =
        if (phase == RecordingPhase.IDLE) {
          val modeSummary =
            if (uiState.settings.recordingMode == RecordingMode.FULL_SCREEN) {
              "${stringResource(R.string.mode_fullscreen)} (${uiState.canvasWidthPx}×${uiState.canvasHeightPx})"
            } else {
              "${stringResource(R.string.mode_region)} (${uiState.selectedPixelRegion.width}×${uiState.selectedPixelRegion.height})"
            }
          val audioSummary =
            if (uiState.settings.audioSource == AudioSourceOption.INTERNAL) {
              stringResource(R.string.audio_internal)
            } else {
              stringResource(R.string.audio_microphone)
            }
          "$modeSummary • $orientationSummary • $audioSummary"
        } else {
          listOfNotNull(
              uiState.session.activeResolutionLabel.takeIf { it.isNotBlank() },
              orientationSummary,
              uiState.session.activeAudioLabel.takeIf { it.isNotBlank() },
            )
            .joinToString(" • ")
        }

      Text(
        text = subtitleText,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      // Primary action controls
      when (phase) {
        RecordingPhase.IDLE -> {
          Button(
            onClick = onStartRecordClick,
            modifier =
              Modifier.fillMaxWidth()
                .height(56.dp)
                .focusRing(RoundedCornerShape(16.dp))
                .testTag("record_button"),
            shape = RoundedCornerShape(16.dp),
            colors =
              ButtonDefaults.buttonColors(
                containerColor = studioColors.recordAccent,
                contentColor = Color.White,
              ),
            contentPadding = PaddingValues(horizontal = 24.dp),
          ) {
            Icon(
              imageVector = Icons.Default.FiberManualRecord,
              contentDescription = stringResource(R.string.cd_record_button),
              modifier = Modifier.padding(end = 10.dp).size(20.dp),
            )
            Text(
              text = stringResource(R.string.action_record),
              style = MaterialTheme.typography.titleMedium,
            )
          }
        }
        RecordingPhase.RECORDING,
        RecordingPhase.PAUSED -> {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            FilledTonalButton(
              onClick = onPauseResumeClick,
              modifier =
                Modifier.weight(1f)
                  .height(54.dp)
                  .focusRing(RoundedCornerShape(16.dp))
                  .testTag("pause_resume_button"),
              shape = RoundedCornerShape(16.dp),
            ) {
              val isPaused = phase == RecordingPhase.PAUSED
              Icon(
                imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                contentDescription =
                  if (isPaused) stringResource(R.string.cd_resume_button)
                  else stringResource(R.string.cd_pause_button),
                modifier = Modifier.padding(end = 8.dp),
              )
              Text(
                text =
                  if (isPaused) stringResource(R.string.action_resume)
                  else stringResource(R.string.action_pause),
              )
            }

            Button(
              onClick = onStopClick,
              modifier =
                Modifier.weight(1f)
                  .height(54.dp)
                  .focusRing(RoundedCornerShape(16.dp))
                  .testTag("stop_button"),
              shape = RoundedCornerShape(16.dp),
              colors =
                ButtonDefaults.buttonColors(
                  containerColor = studioColors.recordAccent,
                  contentColor = Color.White,
                ),
            ) {
              Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = stringResource(R.string.cd_stop_button),
                modifier = Modifier.padding(end = 8.dp),
              )
              Text(text = stringResource(R.string.action_stop))
            }
          }
        }
        RecordingPhase.FINALIZING -> {
          Button(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
          ) {
            Text(stringResource(R.string.status_saving))
          }
        }
      }
    }
  }
}

@Composable
private fun RecordingModeCard(
  selectedMode: RecordingMode,
  enabled: Boolean,
  onSelectMode: (RecordingMode) -> Unit,
) {
  SettingsSectionCard(
    title = stringResource(R.string.section_recording_mode),
    icon = Icons.Default.Fullscreen,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      OptionTile(
        title = stringResource(R.string.mode_fullscreen),
        subtitle = stringResource(R.string.mode_fullscreen_desc),
        icon = Icons.Default.Fullscreen,
        selected = selectedMode == RecordingMode.FULL_SCREEN,
        enabled = enabled,
        testTag = "mode_fullscreen",
        onClick = { onSelectMode(RecordingMode.FULL_SCREEN) },
        modifier = Modifier.weight(1f),
      )
      OptionTile(
        title = stringResource(R.string.mode_region),
        subtitle = stringResource(R.string.mode_region_desc),
        icon = Icons.Default.CropFree,
        selected = selectedMode == RecordingMode.REGION,
        enabled = enabled,
        testTag = "mode_region",
        onClick = { onSelectMode(RecordingMode.REGION) },
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun OrientationModeCard(
  selectedOrientation: OrientationMode,
  enabled: Boolean,
  onSelectOrientation: (OrientationMode) -> Unit,
) {
  SettingsSectionCard(
    title = stringResource(R.string.section_orientation_mode),
    icon = Icons.Default.StayCurrentPortrait,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      OptionTile(
        title = stringResource(R.string.orientation_portrait),
        subtitle = stringResource(R.string.orientation_portrait_desc),
        icon = Icons.Default.StayCurrentPortrait,
        selected = selectedOrientation == OrientationMode.PORTRAIT,
        enabled = enabled,
        testTag = "orientation_portrait",
        onClick = { onSelectOrientation(OrientationMode.PORTRAIT) },
        modifier = Modifier.weight(1f),
      )
      OptionTile(
        title = stringResource(R.string.orientation_landscape),
        subtitle = stringResource(R.string.orientation_landscape_desc),
        icon = Icons.Default.StayCurrentLandscape,
        selected = selectedOrientation == OrientationMode.LANDSCAPE,
        enabled = enabled,
        testTag = "orientation_landscape",
        onClick = { onSelectOrientation(OrientationMode.LANDSCAPE) },
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun RegionPreviewCard(
  uiState: RecorderUiState,
  enabled: Boolean,
  onEditRegion: () -> Unit,
  onRequestOverlayPermission: () -> Unit,
) {
  val norm = uiState.settings.selectedRegion
  val pixel = uiState.selectedPixelRegion
  val aspect =
    (uiState.canvasWidthPx.toFloat() / uiState.canvasHeightPx.toFloat().coerceAtLeast(1f))
      .coerceIn(0.35f, 2.5f)
  val showAnim = uiState.settings.regionAnimation == RegionAnimationOption.SHOW
  val isRecordingActive = uiState.session.phase == RecordingPhase.RECORDING

  SettingsSectionCard(
    title = stringResource(R.string.section_region_preview),
    icon = Icons.Default.CropFree,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Scaled visual screen frame showing saved region & optional border animation
      Box(
        modifier =
          Modifier.width(74.dp)
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
      ) {
        RegionBorderOverlayCanvas(
          leftFraction = norm.left,
          topFraction = norm.top,
          rightFraction = norm.right,
          bottomFraction = norm.bottom,
          animate = showAnim && isRecordingActive,
          showBorder = showAnim,
          scrimColor = Color.Black.copy(alpha = 0.35f),
          neutralGuideColor = MaterialTheme.colorScheme.outline,
          strokeWidth = 2.dp,
          modifier = Modifier.fillMaxSize(),
        )
      }

      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
          text = "${pixel.width} × ${pixel.height} px",
          style = MaterialTheme.typography.titleMedium,
        )
        Text(
          text = "Position (${pixel.left}, ${pixel.top})",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(
          onClick = onEditRegion,
          enabled = enabled,
          modifier = Modifier.fillMaxWidth().testTag("adjust_region_button"),
        ) {
          Icon(
            imageVector = Icons.Default.Tune,
            contentDescription = null,
            modifier = Modifier.padding(end = 6.dp).size(16.dp),
          )
          Text(stringResource(R.string.action_adjust_region))
        }
      }
    }

    // Optional overlay permission row — never forced
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(R.string.overlay_optional_title),
            style = MaterialTheme.typography.labelLarge,
          )
          Text(
            text =
              if (uiState.hasOverlayPermission) stringResource(R.string.overlay_optional_granted)
              else stringResource(R.string.overlay_optional_denied),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (!uiState.hasOverlayPermission) {
          TextButton(
            onClick = onRequestOverlayPermission,
            enabled = enabled,
            modifier = Modifier.testTag("optional_overlay_button"),
          ) {
            Text(stringResource(R.string.overlay_optional_enable))
          }
        }
      }
    }
  }
}

@Composable
private fun AudioSourceCard(
  selectedAudio: AudioSourceOption,
  enabled: Boolean,
  onSelectAudio: (AudioSourceOption) -> Unit,
) {
  SettingsSectionCard(
    title = stringResource(R.string.section_audio_source),
    icon = Icons.Default.GraphicEq,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      OptionTile(
        title = stringResource(R.string.audio_internal),
        subtitle = stringResource(R.string.audio_internal_desc),
        icon = Icons.Default.GraphicEq,
        selected = selectedAudio == AudioSourceOption.INTERNAL,
        enabled = enabled,
        testTag = "audio_internal",
        onClick = { onSelectAudio(AudioSourceOption.INTERNAL) },
        modifier = Modifier.weight(1f),
      )
      OptionTile(
        title = stringResource(R.string.audio_microphone),
        subtitle = stringResource(R.string.audio_microphone_desc),
        icon = Icons.Default.Mic,
        selected = selectedAudio == AudioSourceOption.MICROPHONE,
        enabled = enabled,
        testTag = "audio_microphone",
        onClick = { onSelectAudio(AudioSourceOption.MICROPHONE) },
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoBitrateCard(
  selectedBitrate: VideoBitrateOption,
  enabled: Boolean,
  onSelectBitrate: (VideoBitrateOption) -> Unit,
) {
  SettingsSectionCard(
    title = stringResource(R.string.section_video_bitrate),
    icon = Icons.Default.HighQuality,
  ) {
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      VideoBitrateOption.entries.forEach { option ->
        val isSelected = option == selectedBitrate
        val chipInteraction = remember { MutableInteractionSource() }
        val chipFocused by chipInteraction.collectIsFocusedAsState()
        FilterChip(
          selected = isSelected,
          onClick = { onSelectBitrate(option) },
          enabled = enabled,
          interactionSource = chipInteraction,
          label = { Text(option.label) },
          modifier =
            Modifier.minimumInteractiveComponentSize().testTag("bitrate_${option.name.lowercase()}"),
          border =
            if (chipFocused) {
              BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else {
              FilterChipDefaults.filterChipBorder(enabled = enabled, selected = isSelected)
            },
          colors =
            FilterChipDefaults.filterChipColors(
              selectedContainerColor = LocalStudioColors.current.recordContainer,
              selectedLabelColor = LocalStudioColors.current.recordAccent,
            ),
        )
      }
    }
  }
}

@Composable
private fun RegionAnimationCard(
  selectedAnimation: RegionAnimationOption,
  enabled: Boolean,
  onSelect: (RegionAnimationOption) -> Unit,
) {
  SettingsSectionCard(
    title = stringResource(R.string.section_region_animation),
    icon = Icons.Default.Visibility,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      OptionTile(
        title = stringResource(R.string.anim_show),
        subtitle = "Animated red dashed frame",
        icon = Icons.Default.Visibility,
        selected = selectedAnimation == RegionAnimationOption.SHOW,
        enabled = enabled,
        testTag = "anim_show",
        onClick = { onSelect(RegionAnimationOption.SHOW) },
        modifier = Modifier.weight(1f),
      )
      OptionTile(
        title = stringResource(R.string.anim_hide),
        subtitle = "Border animation disabled",
        icon = Icons.Default.VisibilityOff,
        selected = selectedAnimation == RegionAnimationOption.HIDE,
        enabled = enabled,
        testTag = "anim_hide",
        onClick = { onSelect(RegionAnimationOption.HIDE) },
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun SettingsSectionCard(
  title: String,
  icon: ImageVector,
  content: @Composable () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = title, style = MaterialTheme.typography.titleMedium)
      }
      content()
    }
  }
}

@Composable
private fun OptionTile(
  title: String,
  subtitle: String,
  icon: ImageVector,
  selected: Boolean,
  enabled: Boolean,
  testTag: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val studioColors = LocalStudioColors.current
  val interactionSource = remember { MutableInteractionSource() }
  val isFocused by interactionSource.collectIsFocusedAsState()
  val isHovered by interactionSource.collectIsHoveredAsState()

  val containerColor =
    when {
      selected -> studioColors.recordContainer
      isFocused || isHovered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
      else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
  val borderColor =
    when {
      isFocused -> MaterialTheme.colorScheme.primary
      selected -> studioColors.recordAccent
      isHovered -> MaterialTheme.colorScheme.outline
      else -> MaterialTheme.colorScheme.outlineVariant
    }
  val borderWidth =
    when {
      isFocused -> 2.5.dp
      selected -> 1.5.dp
      else -> 1.dp
    }
  val titleColor =
    if (selected) studioColors.recordAccent else MaterialTheme.colorScheme.onSurface

  Surface(
    onClick = onClick,
    enabled = enabled,
    interactionSource = interactionSource,
    modifier = modifier.minimumInteractiveComponentSize().testTag(testTag),
    shape = RoundedCornerShape(14.dp),
    color = containerColor,
    border = BorderStroke(borderWidth, borderColor),
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
          tint = titleColor,
        )
        Text(
          text = title,
          style = MaterialTheme.typography.labelLarge,
          fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
          color = titleColor,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun Modifier.focusRing(
  shape: Shape = RoundedCornerShape(16.dp),
  focusColor: Color = MaterialTheme.colorScheme.primary,
): Modifier {
  var isFocused by remember { mutableStateOf(false) }
  return this.then(
    if (isFocused) Modifier.border(2.5.dp, focusColor, shape) else Modifier,
  ).onFocusChanged { isFocused = it.isFocused }
}

private fun formatElapsedTimer(elapsedMs: Long): String {
  val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
  val hours = totalSeconds / 3600L
  val minutes = (totalSeconds % 3600L) / 60L
  val seconds = totalSeconds % 60L
  return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}
