package com.example.region

import android.graphics.Rect
import androidx.compose.runtime.Immutable
import com.example.settings.NormalizedRegion
import com.example.settings.OrientationMode
import kotlin.math.roundToInt

/**
 * Pixel-exact crop rectangle aligned to even dimensions for hardware YUV420 encoders.
 */
@Immutable
data class PixelRegion(
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
  val screenWidth: Int,
  val screenHeight: Int,
) {
  val width: Int
    get() = (right - left).coerceAtLeast(2)

  val height: Int
    get() = (bottom - top).coerceAtLeast(2)

  fun toAndroidRect(): Rect = Rect(left, top, right, bottom)
}

/**
 * Handles conversion, validation, and H.264 even-dimension alignment for rectangular screen
 * regions without distorting aspect ratio.
 */
object RegionSelectionManager {

  private const val MIN_PIXEL_SPAN = 128

  fun resolveFullScreenDimensions(
    rawWidth: Int,
    rawHeight: Int,
    orientationMode: OrientationMode = OrientationMode.PORTRAIT,
  ): Pair<Int, Int> {
    val evenW = alignEven(rawWidth.coerceAtLeast(128))
    val evenH = alignEven(rawHeight.coerceAtLeast(128))
    return when (orientationMode) {
      OrientationMode.PORTRAIT -> minOf(evenW, evenH) to maxOf(evenW, evenH)
      OrientationMode.LANDSCAPE -> maxOf(evenW, evenH) to minOf(evenW, evenH)
    }
  }

  fun toPixelRegion(
    normalized: NormalizedRegion,
    screenWidth: Int,
    screenHeight: Int,
  ): PixelRegion {
    val safeScreenW = alignEven(screenWidth.coerceAtLeast(MIN_PIXEL_SPAN))
    val safeScreenH = alignEven(screenHeight.coerceAtLeast(MIN_PIXEL_SPAN))
    val clean = normalized.normalized()

    var left = alignEven((clean.left * safeScreenW).roundToInt()).coerceIn(0, safeScreenW - MIN_PIXEL_SPAN)
    var top = alignEven((clean.top * safeScreenH).roundToInt()).coerceIn(0, safeScreenH - MIN_PIXEL_SPAN)
    var right = alignEven((clean.right * safeScreenW).roundToInt()).coerceIn(left + MIN_PIXEL_SPAN, safeScreenW)
    var bottom = alignEven((clean.bottom * safeScreenH).roundToInt()).coerceIn(top + MIN_PIXEL_SPAN, safeScreenH)

    if ((right - left) % 2 != 0) {
      right = if (right > left + MIN_PIXEL_SPAN) right - 1 else (right + 1).coerceAtMost(safeScreenW)
    }
    if ((bottom - top) % 2 != 0) {
      bottom = if (bottom > top + MIN_PIXEL_SPAN) bottom - 1 else (bottom + 1).coerceAtMost(safeScreenH)
    }

    if (left == right) right = (left + 2).coerceAtMost(safeScreenW)
    if (top == bottom) bottom = (top + 2).coerceAtMost(safeScreenH)

    return PixelRegion(
      left = left,
      top = top,
      right = right,
      bottom = bottom,
      screenWidth = safeScreenW,
      screenHeight = safeScreenH,
    )
  }

  fun fromPixelBounds(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    containerWidth: Float,
    containerHeight: Float,
  ): NormalizedRegion {
    if (containerWidth <= 0f || containerHeight <= 0f) return NormalizedRegion()
    return NormalizedRegion(
        left = left / containerWidth,
        top = top / containerHeight,
        right = right / containerWidth,
        bottom = bottom / containerHeight,
      )
      .normalized()
  }

  fun alignEven(value: Int): Int {
    return if (value % 2 == 0) value else (value - 1).coerceAtLeast(2)
  }
}
