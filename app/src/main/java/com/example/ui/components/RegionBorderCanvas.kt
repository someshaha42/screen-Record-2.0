package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
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

private const val DASH_PHASE_STEPS = 24

/**
 * Lightweight GPU-friendly deep red animated dashed border. When [showBorder] or [animate] is
 * false, no infinite transition is created or ticked, keeping GPU and battery overhead at zero.
 * Pre-allocates quantized [Stroke]/[PathEffect] steps and defers animation state reads to the
 * Draw phase so Composition/Layout are skipped on animation ticks.
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

  val shadowColor = remember { Color.Black.copy(alpha = 0.35f) }
  val shadowStroke = remember(strokePx) { Stroke(width = strokePx + 1.5f) }
  val neutralStroke = remember(neutralStrokePx) { Stroke(width = neutralStrokePx) }
  val animatedStrokes =
    remember(showBorder, dashPx, gapPx, strokePx) {
      if (!showBorder) {
        emptyArray()
      } else {
        val intervals = floatArrayOf(dashPx, gapPx)
        val period = dashPx + gapPx
        Array(DASH_PHASE_STEPS) { step ->
          val stepPhase = -(step.toFloat() / DASH_PHASE_STEPS) * period
          Stroke(width = strokePx, pathEffect = PathEffect.dashPathEffect(intervals, stepPhase))
        }
      }
    }

  val stepState: State<Float>? =
    if (showBorder && animate) {
      val transition = rememberInfiniteTransition(label = "region_border_phase")
      transition.animateFloat(
        initialValue = 0f,
        targetValue = DASH_PHASE_STEPS.toFloat(),
        animationSpec =
          infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
          ),
        label = "dash_phase_step",
      )
    } else {
      null
    }

  val stepIndexState: State<Int>? =
    remember(stepState) {
      stepState?.let { animState ->
        derivedStateOf { animState.value.toInt().mod(DASH_PHASE_STEPS) }
      }
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

    if (showBorder && animatedStrokes.isNotEmpty()) {
      val stepIndex = stepIndexState?.value ?: 0
      drawRect(
        color = shadowColor,
        topLeft = rectTopLeft,
        size = rectSize,
        style = shadowStroke,
      )
      drawRect(
        color = studioColors.recordAccent,
        topLeft = rectTopLeft,
        size = rectSize,
        style = animatedStrokes[stepIndex],
      )
    } else if (neutralGuideColor != Color.Transparent) {
      drawRect(
        color = neutralGuideColor,
        topLeft = rectTopLeft,
        size = rectSize,
        style = neutralStroke,
      )
    }
  }
}
