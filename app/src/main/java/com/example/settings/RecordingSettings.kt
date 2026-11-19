package com.example.settings

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by
  preferencesDataStore(name = "framecut_recording_settings")

enum class RecordingMode {
  FULL_SCREEN,
  REGION,
}

enum class OrientationMode {
  PORTRAIT,
  LANDSCAPE,
}

enum class AudioSourceOption {
  INTERNAL,
  MICROPHONE,
}

enum class VideoBitrateOption(val targetBitsPerSecond: Int, val label: String) {
  MBPS_100(100_000_000, "100 Mbps"),
  MBPS_150(150_000_000, "150 Mbps"),
  MBPS_200(200_000_000, "200 Mbps"),
  MBPS_250(250_000_000, "250 Mbps"),
  MBPS_300(300_000_000, "300 Mbps"),
  MBPS_350(350_000_000, "350 Mbps"),
}

enum class RegionAnimationOption {
  SHOW,
  HIDE,
}

/**
 * Normalized rectangular region in [0f..1f] relative to the full screen dimensions. Storing
 * normalized fractions allows restoring the selected area consistently across launches.
 */
@Immutable
data class NormalizedRegion(
  val left: Float = 0.1f,
  val top: Float = 0.2f,
  val right: Float = 0.9f,
  val bottom: Float = 0.7f,
) {
  fun normalized(): NormalizedRegion {
    val minSpan = 0.12f
    val clampedLeft = left.coerceIn(0f, 1f - minSpan)
    val clampedTop = top.coerceIn(0f, 1f - minSpan)
    val clampedRight = right.coerceIn(clampedLeft + minSpan, 1f)
    val clampedBottom = bottom.coerceIn(clampedTop + minSpan, 1f)
    return NormalizedRegion(clampedLeft, clampedTop, clampedRight, clampedBottom)
  }
}

@Immutable
data class RecordingSettings(
  val recordingMode: RecordingMode = RecordingMode.FULL_SCREEN,
  val orientationMode: OrientationMode = OrientationMode.PORTRAIT,
  val audioSource: AudioSourceOption = AudioSourceOption.INTERNAL,
  val videoBitrate: VideoBitrateOption = VideoBitrateOption.MBPS_150,
  val regionAnimation: RegionAnimationOption = RegionAnimationOption.SHOW,
  val selectedRegion: NormalizedRegion = NormalizedRegion(),
)

/**
 * Fixed internal recording parameters. These are fixed in code and intentionally not exposed for
 * user modification in the UI.
 */
object InternalRecordingConfig {
  const val TARGET_FPS = 30
  const val VIDEO_MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_HEVC

  const val SNAPDRAGON_HEVC_ENCODER = "c2.qti.hevc.encoder"
  const val SNAPDRAGON_LEGACY_HEVC_ENCODER = "OMX.qcom.video.encoder.hevc"
  const val MEDIATEK_HEVC_ENCODER = "c2.mtk.hevc.encoder"
  const val MEDIATEK_LEGACY_HEVC_ENCODER = "OMX.MTK.VIDEO.ENCODER.HEVC"

  const val SURFACE_COLOR_FORMAT = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
  const val I_FRAME_INTERVAL_SECONDS = 1

  const val AUDIO_MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
  const val AUDIO_BITRATE_BPS = 192_000
}

class RecordingSettingsRepository(private val context: Context) {

  private object Keys {
    val RECORDING_MODE = stringPreferencesKey("recording_mode")
    val ORIENTATION_MODE = stringPreferencesKey("orientation_mode")
    val AUDIO_SOURCE = stringPreferencesKey("audio_source")
    val VIDEO_BITRATE = stringPreferencesKey("video_bitrate")
    val REGION_ANIMATION = stringPreferencesKey("region_animation")
    val REGION_LEFT = floatPreferencesKey("region_left")
    val REGION_TOP = floatPreferencesKey("region_top")
    val REGION_RIGHT = floatPreferencesKey("region_right")
    val REGION_BOTTOM = floatPreferencesKey("region_bottom")
  }

  val settingsFlow: Flow<RecordingSettings> =
    context.settingsDataStore.data.map { prefs ->
      RecordingSettings(
        recordingMode =
          enumValueOrDefault(prefs[Keys.RECORDING_MODE], RecordingMode.FULL_SCREEN),
        orientationMode = parseOrientationMode(prefs[Keys.ORIENTATION_MODE]),
        audioSource =
          enumValueOrDefault(prefs[Keys.AUDIO_SOURCE], AudioSourceOption.INTERNAL),
        videoBitrate =
          enumValueOrDefault(prefs[Keys.VIDEO_BITRATE], VideoBitrateOption.MBPS_150),
        regionAnimation =
          enumValueOrDefault(prefs[Keys.REGION_ANIMATION], RegionAnimationOption.SHOW),
        selectedRegion =
          NormalizedRegion(
              left = prefs[Keys.REGION_LEFT] ?: 0.1f,
              top = prefs[Keys.REGION_TOP] ?: 0.2f,
              right = prefs[Keys.REGION_RIGHT] ?: 0.9f,
              bottom = prefs[Keys.REGION_BOTTOM] ?: 0.7f,
            )
            .normalized(),
      )
    }

  suspend fun setRecordingMode(mode: RecordingMode) {
    context.settingsDataStore.edit { it[Keys.RECORDING_MODE] = mode.name }
  }

  suspend fun setOrientationMode(mode: OrientationMode) {
    context.settingsDataStore.edit { it[Keys.ORIENTATION_MODE] = mode.name }
  }

  suspend fun setAudioSource(source: AudioSourceOption) {
    context.settingsDataStore.edit { it[Keys.AUDIO_SOURCE] = source.name }
  }

  suspend fun setVideoBitrate(bitrate: VideoBitrateOption) {
    context.settingsDataStore.edit { it[Keys.VIDEO_BITRATE] = bitrate.name }
  }

  suspend fun setRegionAnimation(animation: RegionAnimationOption) {
    context.settingsDataStore.edit { it[Keys.REGION_ANIMATION] = animation.name }
  }

  suspend fun saveSelectedRegion(region: NormalizedRegion) {
    val clean = region.normalized()
    context.settingsDataStore.edit { prefs ->
      prefs[Keys.REGION_LEFT] = clean.left
      prefs[Keys.REGION_TOP] = clean.top
      prefs[Keys.REGION_RIGHT] = clean.right
      prefs[Keys.REGION_BOTTOM] = clean.bottom
    }
  }

  private fun parseOrientationMode(raw: String?): OrientationMode {
    return when (raw) {
      OrientationMode.LANDSCAPE.name,
      "LANDSCAPE_FIXED" -> OrientationMode.LANDSCAPE
      else -> OrientationMode.PORTRAIT
    }
  }

  private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String?, default: T): T {
    if (raw == null) return default
    return runCatching { enumValueOf<T>(raw) }.getOrDefault(default)
  }
}
