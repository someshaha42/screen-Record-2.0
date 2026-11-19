package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalStudioColors

/**
 * Lightweight GPU-friendly deep red animated dashed border. When [showBorder] or [animate] is
 * false, no infinite transition is created or ticked, keeping GPU and battery overhead at zero.
 */
@Composable
fun RegionBorderOverlayCanvas(
  leftFraction: Float,
  topFraction: Float,
  rightFraction: Float,
  bottomFraction: Float,
  animate: Boolean,
  showBorder: Boolean,
  modifier: Modifier = Modifier,
  strokeWidth: Dp = 2.5.dp,
  scrimColor: Color = Color.Transparent,
  neutralGuideColor: Color = Color.Transparent,
) {
  val studioColors = LocalStudioColors.current
  val density = LocalDensity.current
  val dashPx = with(density) { 12.dp.toPx() }
  val gapPx = with(density) { 8.dp.toPx() }
  val strokePx = with(density) { strokeWidth.toPx() }
  val neutralStrokePx = with(density) { 1.5.dp.toPx() }

  val phase =
    if (showBorder && animate) {
      val transition = rememberInfiniteTransition(label = "region_border_phase")
      val animatedPhase by
        transition.animateFloat(
          initialValue = 0f,
          targetValue = -(dashPx + gapPx),
          animationSpec =
            infiniteRepeatable(
              animation = tween(durationMillis = 650, easing = LinearEasing),
              repeatMode = RepeatMode.Restart,
            ),
          label = "dash_phase",
        )
      animatedPhase
    } else {
      0f
    }

  Canvas(modifier = modifier) {
    val l = (leftFraction * size.width).coerceIn(0f, size.width)
    val t = (topFraction * size.height).coerceIn(0f, size.height)
    val r = (rightFraction * size.width).coerceIn(l, size.width)
    val b = (bottomFraction * size.height).coerceIn(t, size.height)

    if (scrimColor != Color.Transparent) {
      // 4 surrounding rectangles avoid offscreen layer allocation
      drawRect(color = scrimColor, topLeft = Offset.Zero, size = Size(size.width, t))
      drawRect(
        color = scrimColor,
        topLeft = Offset(0f, b),
        size = Size(size.width, (size.height - b).coerceAtLeast(0f)),
      )
      drawRect(
        color = scrimColor,
        topLeft = Offset(0f, t),
        size = Size(l, (b - t).coerceAtLeast(0f)),
      )
      drawRect(
        color = scrimColor,
        topLeft = Offset(r, t),
        size = Size((size.width - r).coerceAtLeast(0f), (b - t).coerceAtLeast(0f)),
      )
    }

    val rectTopLeft = Offset(l, t)
    val rectSize = Size((r - l).coerceAtLeast(1f), (b - t).coerceAtLeast(1f))

    if (showBorder) {
      val effect = PathEffect.dashPathEffect(floatArrayOf(dashPx, gapPx), phase)
      drawRect(
        color = Color.Black.copy(alpha = 0.35f),
        topLeft = rectTopLeft,
        size = rectSize,
        style = Stroke(width = strokePx + 1.5f),
      )
      drawRect(
        color = studioColors.recordAccent,
        topLeft = rectTopLeft,
        size = rectSize,
        style = Stroke(width = strokePx, pathEffect = effect),
      )
    } else if (neutralGuideColor != Color.Transparent) {
      drawRect(
        color = neutralGuideColor,
        topLeft = rectTopLeft,
        size = rectSize,
        style = Stroke(width = neutralStrokePx),
      )
    }
  }
}
