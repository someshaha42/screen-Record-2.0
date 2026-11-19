# FrameCut

Native Android screen and region recorder built with Kotlin, Jetpack Compose, Material 3, MediaProjection, OpenGL ES, MediaCodec, and MediaMuxer.

## Core Features

- **Full Screen & Region Capture**: Record the entire display or an adjustable custom screen rectangle with aspect-ratio presets (`Free`, `16:9`, `9:16`, `4:3`, `1:1`) and GPU-accelerated OpenGL ES cropping.
- **High-Bitrate H.264 Encoding**: Configurable video bitrate presets (`100` to `350 Mbps`) and `Portrait` / `Landscape` orientation modes.
- **Audio Capture**: Supports internal device playback audio (`AudioPlaybackCapture`) and microphone input (`RECORD_AUDIO`).
- **Foreground Recording Service**: Persistent notification controls for Pause, Resume, and Stop, plus optional floating region border overlay (`SYSTEM_ALERT_WINDOW`).
- **Recordings Library**: Browse, play, share, and delete recorded MP4 videos saved via `MediaStore` / FileProvider.

## Tech Stack

- Kotlin + Jetpack Compose (Material 3)
- `MediaProjection`, `MediaCodec`, `MediaMuxer`, OpenGL ES
- `DataStore` Preferences & Navigation Compose
- Gradle Kotlin DSL (`compileSdk` / `targetSdk` 36, AGP 9.1.1)
