package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.ShutterSpeed
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.settings.AudioFormatOption
import com.example.settings.BitrateModeOption
import com.example.settings.ColorDepthOption
import com.example.settings.ColorRangeOption
import com.example.settings.ColorStandardOption
import com.example.settings.ContainerMuxerOption
import com.example.settings.GopIntervalOption
import com.example.settings.RecordingSettings
import com.example.settings.RenderingModeOption
import com.example.settings.VideoFpsOption
import com.example.ui.theme.LocalStudioColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  settings: RecordingSettings,
  isIdle: Boolean,
  onBack: () -> Unit,
  onSelectBitrateMode: (BitrateModeOption) -> Unit,
  onSelectColorDepth: (ColorDepthOption) -> Unit,
  onSelectVideoFps: (VideoFpsOption) -> Unit,
  onSelectColorStandard: (ColorStandardOption) -> Unit,
  onSelectContainerMuxer: (ContainerMuxerOption) -> Unit,
  onSelectRenderingMode: (RenderingModeOption) -> Unit,
  onSelectGopInterval: (GopIntervalOption) -> Unit,
  onSelectColorRange: (ColorRangeOption) -> Unit,
  onSelectAudioFormat: (AudioFormatOption) -> Unit,
) {
  val contentAlpha = if (isIdle) 1f else 0.58f

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(
              text = stringResource(R.string.settings_title),
              style = MaterialTheme.typography.titleLarge,
            )
            Text(
              text = stringResource(R.string.settings_subtitle),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        },
        navigationIcon = {
          IconButton(
            onClick = onBack,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("settings_back_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.cd_back_button),
            )
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
      val useTwoColumns = maxWidth >= 780.dp
      val horizontalPadding = if (useTwoColumns) 24.dp else 16.dp

      Column(
        modifier =
          Modifier.widthIn(max = 1120.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = horizontalPadding, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        if (!isIdle) {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = LocalStudioColors.current.pausedContainer,
            modifier = Modifier.fillMaxWidth().testTag("settings_locked_banner"),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = LocalStudioColors.current.pausedAccent,
                modifier = Modifier.size(18.dp),
              )
              Text(
                text = stringResource(R.string.settings_locked_notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
          }
        }

        Column(
          modifier = Modifier.fillMaxWidth().alpha(contentAlpha),
          verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          if (useTwoColumns) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
              Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
              ) {
                BitrateModeCard(settings.bitrateMode, isIdle, onSelectBitrateMode)
                VideoFpsCard(settings.videoFps, isIdle, onSelectVideoFps)
                ContainerMuxerCard(settings.containerMuxer, isIdle, onSelectContainerMuxer)
                GopIntervalCard(settings.gopInterval, isIdle, onSelectGopInterval)
                AudioFormatCard(settings.audioFormat, isIdle, onSelectAudioFormat)
              }
              Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
              ) {
                ColorDepthCard(settings.colorDepth, isIdle, onSelectColorDepth)
                ColorStandardCard(settings.colorStandard, isIdle, onSelectColorStandard)
                RenderingModeCard(settings.renderingMode, isIdle, onSelectRenderingMode)
                ColorRangeCard(settings.colorRange, isIdle, onSelectColorRange)
                ChromaSubsamplingCard()
              }
            }
          } else {
            BitrateModeCard(settings.bitrateMode, isIdle, onSelectBitrateMode)
            ColorDepthCard(settings.colorDepth, isIdle, onSelectColorDepth)
            VideoFpsCard(settings.videoFps, isIdle, onSelectVideoFps)
            ColorStandardCard(settings.colorStandard, isIdle, onSelectColorStandard)
            ContainerMuxerCard(settings.containerMuxer, isIdle, onSelectContainerMuxer)
            RenderingModeCard(settings.renderingMode, isIdle, onSelectRenderingMode)
            GopIntervalCard(settings.gopInterval, isIdle, onSelectGopInterval)
            ColorRangeCard(settings.colorRange, isIdle, onSelectColorRange)
            AudioFormatCard(settings.audioFormat, isIdle, onSelectAudioFormat)
            ChromaSubsamplingCard()
          }
        }

        Spacer(modifier = Modifier.height(12.dp))
      }
    }
  }
}

@Composable
private fun BitrateModeCard(
  selected: BitrateModeOption,
  enabled: Boolean,
  onSelect: (BitrateModeOption) -> Unit,
) {
  PipelineSectionCard(
    title = stringResource(R.string.section_bitrate_mode),
    hint = stringResource(R.string.section_bitrate_mode_hint),
    icon = Icons.Default.Tune,
  ) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      PipelineOptionTile(
        title = stringResource(R.string.bitrate_mode_vbr),
        subtitle = stringResource(R.string.bitrate_mode_vbr_desc),
        icon = Icons.Default.HighQuality,
        selected = selected == BitrateModeOption.VBR,
        enabled = enabled,
        testTag = "bitrate_mode_vbr",
        onClick = { onSelect(BitrateModeOption.VBR) },
        modifier = Modifier.weight(1f),
      )
      PipelineOptionTile(
        title = stringResource(R.string.bitrate_mode_cq),
        subtitle = stringResource(R.string.bitrate_mode_cq_desc),
        icon = Icons.Default.Compare,
        selected = selected == BitrateModeOption.CQ,
        enabled = enabled,
        testTag = "bitrate_mode_cq",
        onClick = { onSelect(BitrateModeOption.CQ) },
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun ColorDepthCard(
  selected: ColorDepthOption,
  enabled: Boolean,
  onSelect: (ColorDepthOption) -> Unit,
) {
  PipelineSectionCard(
    title = stringResource(R.string.section_color_depth),
    hint = stringResource(R.string.section_color_depth_hint),
    icon = Icons.Default.Gradient,
  ) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      PipelineOptionTile(
        title = stringResource(R.string.color_depth_8bit),
        subtitle = stringResource(R.string.color_depth_8bit_desc),
        icon = Icons.Default.Palette,
        selected = selected == ColorDepthOption.BIT_8,
        enabled = enabled,
        testTag = "color_depth_8bit",
        onClick = { onSelect(ColorDepthOption.BIT_8) },
        modifier = Modifier.weight(1f),
      )
      PipelineOptionTile(
        title = stringResource(R.string.color_depth_10bit),
        subtitle = stringResource(R.string.color_depth_10bit_desc),
        icon = Icons.Default.ColorLens,
        selected = selected == ColorDepthOption.BIT_10,
        enabled = enabled,
        testTag = "color_depth_10bit",
        onClick = { onSelect(ColorDepthOption.BIT_10) },
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun VideoFpsCard(
  selected: VideoFpsOption,
  enabled: Boolean,
  onSelect: (VideoFpsOption) -> Unit,
) {
  PipelineSectionCard(
    title = stringResource(R.string.section_video_fps),
    hint = stringResource(R.string.section_video_fps_hint),
    icon = Icons.Default.Speed,
  ) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      PipelineOptionTile(
        title = stringResource(R.string.fps_30),
        subtitle = stringResource(R.string.fps_30_desc),
        icon = Icons.Default.ShutterSpeed,
        selected = selected == VideoFpsOption.FPS_30,
        enabled = enabled,
        testTag = "fps_30",
        onClick = { onSelect(VideoFpsOption.FPS_30) },
        modifier = Modifier.weight(1f),
      )
      PipelineOptionTile(
        title = stringResource(R.string.fps_60),
        subtitle = stringResource(R.string.fps_60_desc),
        icon = Icons.Default.Speed,
        selected = selected == VideoFpsOption.FPS_60,
        enabled = enabled,
        testTag = "fps_60",
        onClick = { onSelect(VideoFpsOption.FPS_60) },
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun ColorStandardCard(
  selected: ColorStandardOption,
  enabled: Boolean,
  onSelect: (ColorStandardOption) -> Unit,
) {
  PipelineSectionCard(
    title = stringResource(R.string.section_color_standard),
    hint = stringResource(R.string.section_color_standard_hint),
    icon = Icons.Default.HdrOn,
  ) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      PipelineOptionTile(
        title = stringResource(R.string.color_std_bt709),
        subtitle = stringResource(R.string.color_std_bt709_desc),
        icon = Icons.Default.Palette,
        selected = selected == ColorStandardOption.BT_709,
        enabled = enabled,
        testTag = "color_std_bt709",
        onClick = { onSelect(ColorStandardOption.BT_709) },
        modifier = Modifier.weight(1f),
      )
      PipelineOptionTile(
        title = stringResource(R.string.color_std_bt2020),
        subtitle = stringResource(R.string.color_std_bt2020_desc),
        icon = Icons.Default.HdrOn,
        selected = selected == ColorStandardOption.BT_2020,
        enabled = enabled,
        testTag = "color_std_bt2020",
        onClick = { onSelect(ColorStandardOption.BT_2020) },
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun ContainerMuxerCard(
  selected: ContainerMuxerOption,
  enabled: Boolean,
  onSelect: (ContainerMuxerOption) -> Unit,
) {
  PipelineSectionCard(
    title = stringResource(R.string.section_container_muxer),
    hint = stringResource(R.string.section_container_muxer_hint),
    icon = Icons.Default.Storage,
  ) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      PipelineOptionTile(
        title = stringResource(R.string.muxer_hybrid_mp4),
        subtitle = stringResource(R.string.muxer_hybrid_mp4_desc),
        icon = Icons.Default.Movie,
        selected = selected == ContainerMuxerOption.HYBRID_MP4,
        enabled = enabled,
        testTag = "muxer_hybrid_mp4",
        onClick = { onSelect(ContainerMuxerOption.HYBRID_MP4) },
        modifier = Modifier.weight(1f),
      )
      PipelineOptionTile(
        title = stringResource(R.string.muxer_mkv_remux),
        subtitle = stringResource(R.string.muxer_mkv_remux_desc),
        icon = Icons.Default.FolderZip,
        selected = selected == ContainerMuxerOption.MKV_REMUX_MP4,
        enabled = enabled,
        testTag = "muxer_mkv_remux",
        onClick = { onSelect(ContainerMuxerOption.MKV_REMUX_MP4) },
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun RenderingModeCard(
  selected: RenderingModeOption,
  enabled: Boolean,
  onSelect: (RenderingModeOption) -> Unit,
) {
  PipelineSectionCard(
    title = stringResource(R.string.section_rendering_mode),
    hint = stringResource(R.string.section_rendering_mode_hint),
    icon = Icons.Default.Bolt,
  ) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      PipelineOptionTile(
        title = stringResource(R.string.rendering_async),
        subtitle = stringResource(R.string.rendering_async_desc),
        icon = Icons.Default.Bolt,
        selected = selected == RenderingModeOption.ASYNC_SURFACE,
        enabled = enabled,
        testTag = "rendering_async",
        onClick = { onSelect(RenderingModeOption.ASYNC_SURFACE) },
        modifier = Modifier.weight(1f),
      )
      PipelineOptionTile(
        title = stringResource(R.string.rendering_direct),
        subtitle = stringResource(R.string.rendering_direct_desc),
        icon = Icons.Default.Sync,
        selected = selected == RenderingModeOption.DIRECT_SURFACE,
        enabled = enabled,
        testTag = "rendering_direct",
        onClick = { onSelect(RenderingModeOption.DIRECT_SURFACE) },
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun GopIntervalCard(
  selected: GopIntervalOption,
  enabled: Boolean,
  onSelect: (GopIntervalOption) -> Unit,
) {
  PipelineSectionCard(
    title = stringResource(R.string.section_gop_interval),
    hint = stringResource(R.string.section_gop_interval_hint),
    icon = Icons.Default.Timelapse,
  ) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      PipelineOptionTile(
        title = stringResource(R.string.gop_1s),
        subtitle = stringResource(R.string.gop_1s_desc),
        icon = Icons.Default.Timelapse,
        selected = selected == GopIntervalOption.SEC_1,
        enabled = enabled,
        testTag = "gop_1s",
        onClick = { onSelect(GopIntervalOption.SEC_1) },
        modifier = Modifier.weight(1f),
      )
      PipelineOptionTile(
        title = stringResource(R.string.gop_0_5s),
        subtitle = stringResource(R.string.gop_0_5s_desc),
        icon = Icons.Default.Update,
        selected = selected == GopIntervalOption.SEC_0_5,
        enabled = enabled,
        testTag = "gop_0_5s",
        onClick = { onSelect(GopIntervalOption.SEC_0_5) },
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun ColorRangeCard(
  selected: ColorRangeOption,
  enabled: Boolean,
  onSelect: (ColorRangeOption) -> Unit,
) {
  PipelineSectionCard(
    title = stringResource(R.string.section_color_range),
    hint = stringResource(R.string.section_color_range_hint),
    icon = Icons.Default.Contrast,
  ) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      PipelineOptionTile(
        title = stringResource(R.string.color_range_limited),
        subtitle = stringResource(R.string.color_range_limited_desc),
        icon = Icons.Default.Contrast,
        selected = selected == ColorRangeOption.LIMITED,
        enabled = enabled,
        testTag = "color_range_limited",
        onClick = { onSelect(ColorRangeOption.LIMITED) },
        modifier = Modifier.weight(1f),
      )
      PipelineOptionTile(
        title = stringResource(R.string.color_range_full),
        subtitle = stringResource(R.string.color_range_full_desc),
        icon = Icons.Default.Contrast,
        selected = selected == ColorRangeOption.FULL,
        enabled = enabled,
        testTag = "color_range_full",
        onClick = { onSelect(ColorRangeOption.FULL) },
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun AudioFormatCard(
  selected: AudioFormatOption,
  enabled: Boolean,
  onSelect: (AudioFormatOption) -> Unit,
) {
  PipelineSectionCard(
    title = stringResource(R.string.section_audio_format),
    hint = stringResource(R.string.section_audio_format_hint),
    icon = Icons.Default.Audiotrack,
  ) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      PipelineOptionTile(
        title = stringResource(R.string.audio_fmt_aac),
        subtitle = stringResource(R.string.audio_fmt_aac_desc),
        icon = Icons.Default.GraphicEq,
        selected = selected == AudioFormatOption.MONO_AAC,
        enabled = enabled,
        testTag = "audio_fmt_aac",
        onClick = { onSelect(AudioFormatOption.MONO_AAC) },
        modifier = Modifier.weight(1f),
      )
      PipelineOptionTile(
        title = stringResource(R.string.audio_fmt_mp3),
        subtitle = stringResource(R.string.audio_fmt_mp3_desc),
        icon = Icons.Default.Audiotrack,
        selected = selected == AudioFormatOption.MONO_MP3,
        enabled = enabled,
        testTag = "audio_fmt_mp3",
        onClick = { onSelect(AudioFormatOption.MONO_MP3) },
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun ChromaSubsamplingCard() {
  PipelineSectionCard(
    title = stringResource(R.string.section_chroma_subsampling),
    hint = stringResource(R.string.section_chroma_subsampling_hint),
    icon = Icons.Default.ColorLens,
  ) {
    PipelineOptionTile(
      title = stringResource(R.string.chroma_420),
      subtitle = stringResource(R.string.chroma_420_desc),
      icon = Icons.Default.ColorLens,
      selected = true,
      enabled = true,
      testTag = "chroma_420_active",
      onClick = {},
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
private fun PipelineSectionCard(
  title: String,
  hint: String,
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
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Box(
          modifier =
            Modifier.size(30.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(MaterialTheme.colorScheme.surfaceVariant),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
          Text(text = title, style = MaterialTheme.typography.titleMedium)
          Text(
            text = hint,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      content()
    }
  }
}

@Composable
private fun PipelineOptionTile(
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
