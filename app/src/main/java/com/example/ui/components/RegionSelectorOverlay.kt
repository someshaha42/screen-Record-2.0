package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.region.RegionSelectionManager
import com.example.settings.NormalizedRegion
import com.example.settings.RegionAnimationOption
import com.example.ui.theme.LocalStudioColors
import kotlin.math.roundToInt

private enum class AspectPreset(val label: String, val ratio: Float?) {
  FREE("Free", null),
  R16_9("16:9", 16f / 9f),
  R9_16("9:16", 9f / 16f),
  R4_3("4:3", 4f / 3f),
  R1_1("1:1", 1f),
}

@Composable
fun RegionSelectorOverlay(
  initialRegion: NormalizedRegion,
  screenWidthPx: Int,
  screenHeightPx: Int,
  regionAnimation: RegionAnimationOption,
  onConfirm: (NormalizedRegion) -> Unit,
  onCancel: () -> Unit,
) {
  BackHandler(onBack = onCancel)

  val studioColors = LocalStudioColors.current
  val density = LocalDensity.current
  val minSpanFraction = 0.14f

  var draft by remember(initialRegion) { mutableStateOf(initialRegion.normalized()) }
  var containerWidthPx by remember { mutableFloatStateOf(screenWidthPx.toFloat().coerceAtLeast(1f)) }
  var containerHeightPx by remember {
    mutableFloatStateOf(screenHeightPx.toFloat().coerceAtLeast(1f))
  }
  var selectedPreset by remember { mutableStateOf(AspectPreset.FREE) }

  val pixelRegion =
    remember(draft, screenWidthPx, screenHeightPx) {
      RegionSelectionManager.toPixelRegion(draft, screenWidthPx, screenHeightPx)
    }

  val handleTouchSize = 48.dp
  val handleTouchPx = with(density) { handleTouchSize.toPx() }
  val halfTouchPx = handleTouchPx / 2f

  val leftPx = draft.left * containerWidthPx
  val topPx = draft.top * containerHeightPx
  val rightPx = draft.right * containerWidthPx
  val bottomPx = draft.bottom * containerHeightPx

  // Place control HUD at bottom unless the selection box covers the bottom, then dock at top
  val hudAtTop = draft.bottom > 0.74f && draft.top > 0.24f
  val canvasAspect =
    (screenWidthPx.toFloat() / screenHeightPx.toFloat().coerceAtLeast(1f)).coerceIn(0.3f, 3.0f)

  BoxWithConstraints(
    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.88f)),
  ) {
    val parentAspect =
      (maxWidth.value / maxHeight.value.coerceAtLeast(1f)).coerceIn(0.3f, 3.0f)
    val sameOrientation = kotlin.math.abs(parentAspect - canvasAspect) < 0.12f

    val canvasModifier =
      if (sameOrientation) {
        Modifier.fillMaxSize()
      } else if (canvasAspect > parentAspect) {
        Modifier.align(Alignment.Center)
          .padding(horizontal = 16.dp)
          .fillMaxWidth()
          .aspectRatio(canvasAspect)
          .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
          .clip(RoundedCornerShape(12.dp))
      } else {
        Modifier.align(Alignment.Center)
          .padding(vertical = 24.dp)
          .fillMaxHeight(0.68f)
          .aspectRatio(canvasAspect)
          .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
          .clip(RoundedCornerShape(12.dp))
      }

    Box(
      modifier =
        canvasModifier.onSizeChanged { size ->
          if (size.width > 0 && size.height > 0) {
            containerWidthPx = size.width.toFloat()
            containerHeightPx = size.height.toFloat()
          }
        },
    ) {
    val showRedBorder = regionAnimation == RegionAnimationOption.SHOW
    RegionBorderOverlayCanvas(
      leftFraction = draft.left,
      topFraction = draft.top,
      rightFraction = draft.right,
      bottomFraction = draft.bottom,
      animate = showRedBorder,
      showBorder = showRedBorder,
      scrimColor = Color.Black.copy(alpha = 0.58f),
      neutralGuideColor = Color.White.copy(alpha = 0.78f),
      strokeWidth = 3.dp,
      modifier = Modifier.fillMaxSize(),
    )

    // Draggable interior area to reposition the rectangle
    Box(
      modifier =
        Modifier.offset { IntOffset(leftPx.roundToInt(), topPx.roundToInt()) }
          .size(
            width = with(density) { (rightPx - leftPx).coerceAtLeast(1f).toDp() },
            height = with(density) { (bottomPx - topPx).coerceAtLeast(1f).toDp() },
          )
          .pointerInput(containerWidthPx, containerHeightPx) {
            detectDragGestures { change, dragAmount ->
              change.consume()
              val dx = dragAmount.x / containerWidthPx
              val dy = dragAmount.y / containerHeightPx
              val widthFrac = draft.right - draft.left
              val heightFrac = draft.bottom - draft.top
              val newLeft = (draft.left + dx).coerceIn(0f, 1f - widthFrac)
              val newTop = (draft.top + dy).coerceIn(0f, 1f - heightFrac)
              draft =
                NormalizedRegion(
                  left = newLeft,
                  top = newTop,
                  right = newLeft + widthFrac,
                  bottom = newTop + heightFrac,
                )
            }
          },
      contentAlignment = Alignment.Center,
    ) {
      Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.55f),
        contentColor = Color.White,
      ) {
        Icon(
          imageVector = Icons.Default.OpenWith,
          contentDescription = "Drag to move region",
          modifier = Modifier.padding(10.dp).size(20.dp),
        )
      }
    }

    // Top-Left corner resize handle
    ResizeHandle(
      xPx = leftPx - halfTouchPx,
      yPx = topPx - halfTouchPx,
      accent = studioColors.recordAccent,
      description = "Resize top left corner",
      testTag = "handle_top_left",
      onDrag = { dxPx, dyPx ->
        selectedPreset = AspectPreset.FREE
        val dx = dxPx / containerWidthPx
        val dy = dyPx / containerHeightPx
        val newLeft = (draft.left + dx).coerceIn(0f, draft.right - minSpanFraction)
        val newTop = (draft.top + dy).coerceIn(0f, draft.bottom - minSpanFraction)
        draft = draft.copy(left = newLeft, top = newTop).normalized()
      },
    )

    // Top-Right corner resize handle
    ResizeHandle(
      xPx = rightPx - halfTouchPx,
      yPx = topPx - halfTouchPx,
      accent = studioColors.recordAccent,
      description = "Resize top right corner",
      testTag = "handle_top_right",
      onDrag = { dxPx, dyPx ->
        selectedPreset = AspectPreset.FREE
        val dx = dxPx / containerWidthPx
        val dy = dyPx / containerHeightPx
        val newRight = (draft.right + dx).coerceIn(draft.left + minSpanFraction, 1f)
        val newTop = (draft.top + dy).coerceIn(0f, draft.bottom - minSpanFraction)
        draft = draft.copy(right = newRight, top = newTop).normalized()
      },
    )

    // Bottom-Left corner resize handle
    ResizeHandle(
      xPx = leftPx - halfTouchPx,
      yPx = bottomPx - halfTouchPx,
      accent = studioColors.recordAccent,
      description = "Resize bottom left corner",
      testTag = "handle_bottom_left",
      onDrag = { dxPx, dyPx ->
        selectedPreset = AspectPreset.FREE
        val dx = dxPx / containerWidthPx
        val dy = dyPx / containerHeightPx
        val newLeft = (draft.left + dx).coerceIn(0f, draft.right - minSpanFraction)
        val newBottom = (draft.bottom + dy).coerceIn(draft.top + minSpanFraction, 1f)
        draft = draft.copy(left = newLeft, bottom = newBottom).normalized()
      },
    )

    // Bottom-Right corner resize handle
    ResizeHandle(
      xPx = rightPx - halfTouchPx,
      yPx = bottomPx - halfTouchPx,
      accent = studioColors.recordAccent,
      description = "Resize bottom right corner",
      testTag = "handle_bottom_right",
      onDrag = { dxPx, dyPx ->
        selectedPreset = AspectPreset.FREE
        val dx = dxPx / containerWidthPx
        val dy = dyPx / containerHeightPx
        val newRight = (draft.right + dx).coerceIn(draft.left + minSpanFraction, 1f)
        val newBottom = (draft.bottom + dy).coerceIn(draft.top + minSpanFraction, 1f)
        draft = draft.copy(right = newRight, bottom = newBottom).normalized()
      },
    )

    // Edge handles for independent width/height adjustment
    ResizeHandle(
      xPx = (leftPx + rightPx) / 2f - halfTouchPx,
      yPx = topPx - halfTouchPx,
      accent = Color.White,
      description = "Adjust top edge",
      testTag = "handle_top",
      onDrag = { _, dyPx ->
        selectedPreset = AspectPreset.FREE
        val dy = dyPx / containerHeightPx
        val newTop = (draft.top + dy).coerceIn(0f, draft.bottom - minSpanFraction)
        draft = draft.copy(top = newTop).normalized()
      },
    )

    ResizeHandle(
      xPx = (leftPx + rightPx) / 2f - halfTouchPx,
      yPx = bottomPx - halfTouchPx,
      accent = Color.White,
      description = "Adjust bottom edge",
      testTag = "handle_bottom",
      onDrag = { _, dyPx ->
        selectedPreset = AspectPreset.FREE
        val dy = dyPx / containerHeightPx
        val newBottom = (draft.bottom + dy).coerceIn(draft.top + minSpanFraction, 1f)
        draft = draft.copy(bottom = newBottom).normalized()
      },
    )

    ResizeHandle(
      xPx = leftPx - halfTouchPx,
      yPx = (topPx + bottomPx) / 2f - halfTouchPx,
      accent = Color.White,
      description = "Adjust left edge",
      testTag = "handle_left",
      onDrag = { dxPx, _ ->
        selectedPreset = AspectPreset.FREE
        val dx = dxPx / containerWidthPx
        val newLeft = (draft.left + dx).coerceIn(0f, draft.right - minSpanFraction)
        draft = draft.copy(left = newLeft).normalized()
      },
    )

    ResizeHandle(
      xPx = rightPx - halfTouchPx,
      yPx = (topPx + bottomPx) / 2f - halfTouchPx,
      accent = Color.White,
      description = "Adjust right edge",
      testTag = "handle_right",
      onDrag = { dxPx, _ ->
        selectedPreset = AspectPreset.FREE
        val dx = dxPx / containerWidthPx
        val newRight = (draft.right + dx).coerceIn(draft.left + minSpanFraction, 1f)
        draft = draft.copy(right = newRight).normalized()
      },
    )

    }

    // Floating control HUD
    Surface(
      modifier =
        Modifier.align(if (sameOrientation && hudAtTop) Alignment.TopCenter else Alignment.BottomCenter)
          .systemBarsPadding()
          .padding(horizontal = 16.dp, vertical = 12.dp)
          .widthIn(max = 520.dp)
          .fillMaxWidth(),
      shape = RoundedCornerShape(20.dp),
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
      tonalElevation = 6.dp,
    ) {
      Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column {
            Text(
              text = "${pixelRegion.width} × ${pixelRegion.height} px",
              style = MaterialTheme.typography.titleMedium,
            )
            Text(
              text = "Origin (${pixelRegion.left}, ${pixelRegion.top})",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(
              onClick = {
                selectedPreset = AspectPreset.FREE
                draft = NormalizedRegion()
              },
              modifier =
                Modifier.minimumInteractiveComponentSize()
                  .overlayFocusRing(CircleShape)
                  .testTag("reset_region_button"),
            ) {
              Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.action_reset_region),
              )
            }
            IconButton(
              onClick = onCancel,
              modifier =
                Modifier.minimumInteractiveComponentSize()
                  .overlayFocusRing(CircleShape)
                  .testTag("cancel_region_button"),
            ) {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.action_cancel),
              )
            }
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          AspectPreset.entries.forEach { preset ->
            FilterChip(
              selected = selectedPreset == preset,
              onClick = {
                selectedPreset = preset
                preset.ratio?.let { targetRatio ->
                  draft =
                    applyAspectRatioPreset(
                      current = draft,
                      targetRatio = targetRatio,
                      screenW = containerWidthPx,
                      screenH = containerHeightPx,
                    )
                }
              },
              modifier = Modifier.overlayFocusRing(RoundedCornerShape(8.dp)),
              label = { Text(preset.label, style = MaterialTheme.typography.labelSmall) },
            )
          }
        }

        Button(
          onClick = { onConfirm(draft.normalized()) },
          modifier =
            Modifier.fillMaxWidth()
              .minimumInteractiveComponentSize()
              .overlayFocusRing(CircleShape)
              .testTag("confirm_region_button"),
          colors =
            ButtonDefaults.buttonColors(
              containerColor = studioColors.recordAccent,
              contentColor = Color.White,
            ),
        ) {
          Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            modifier = Modifier.padding(end = 8.dp).size(18.dp),
          )
          Text(stringResource(R.string.action_confirm_region))
        }
      }
    }
  }
}

private fun Modifier.overlayFocusRing(shape: Shape): Modifier = composed {
  var isFocused by remember { mutableStateOf(false) }
  this.onFocusChanged { isFocused = it.isFocused }
    .then(
      if (isFocused) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
      } else {
        Modifier
      },
    )
}

@Composable
private fun ResizeHandle(
  xPx: Float,
  yPx: Float,
  accent: Color,
  description: String,
  testTag: String,
  onDrag: (Float, Float) -> Unit,
) {
  Box(
    modifier =
      Modifier.offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) }
        .size(48.dp)
        .testTag(testTag)
        .semantics { contentDescription = description }
        .pointerInput(Unit) {
          detectDragGestures { change, dragAmount ->
            change.consume()
            onDrag(dragAmount.x, dragAmount.y)
          }
        },
    contentAlignment = Alignment.Center,
  ) {
    Box(
      modifier =
        Modifier.size(16.dp)
          .clip(CircleShape)
          .background(accent)
          .border(2.dp, Color(0xFF111318), CircleShape),
    )
  }
}

private fun applyAspectRatioPreset(
  current: NormalizedRegion,
  targetRatio: Float,
  screenW: Float,
  screenH: Float,
): NormalizedRegion {
  val centerX = (current.left + current.right) / 2f
  val centerY = (current.top + current.bottom) / 2f
  val curW = (current.right - current.left) * screenW
  val curH = (current.bottom - current.top) * screenH

  var targetW = curW
  var targetH = targetW / targetRatio
  if (targetH > screenH * 0.85f) {
    targetH = screenH * 0.85f
    targetW = targetH * targetRatio
  }
  if (targetW > screenW * 0.92f) {
    targetW = screenW * 0.92f
    targetH = targetW / targetRatio
  }

  val halfFracW = (targetW / screenW) / 2f
  val halfFracH = (targetH / screenH) / 2f
  val clampedCx = centerX.coerceIn(halfFracW, 1f - halfFracW)
  val clampedCy = centerY.coerceIn(halfFracH, 1f - halfFracH)

  return NormalizedRegion(
      left = clampedCx - halfFracW,
      top = clampedCy - halfFracH,
      right = clampedCx + halfFracW,
      bottom = clampedCy + halfFracH,
    )
    .normalized()
}
