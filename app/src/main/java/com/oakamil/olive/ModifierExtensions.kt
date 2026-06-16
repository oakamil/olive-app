package com.oakamil.olive

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun Modifier.simpleVerticalScrollbar(
    state: ScrollState,
    width: Dp = 4.dp,
    color: Color = Color.Red
): Modifier = composed {
    val targetAlpha = if (state.isScrollInProgress) 1f else 0.4f
    val duration = if (state.isScrollInProgress) 150 else 500

    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = duration),
        label = "ScrollbarAlpha"
    )

    drawWithContent {
        drawContent()

        if (state.maxValue > 0) {
            val visibleHeight = size.height
            val scrollbarHeight = 40.dp.toPx()

            // Draw background track
            drawRect(
                color = color.copy(alpha = alpha * 0.2f),
                topLeft = Offset(size.width - width.toPx(), 0f),
                size = Size(width.toPx(), visibleHeight)
            )

            // Draw thumb
            val scrollbarOffsetY =
                (state.value.toFloat() / state.maxValue) * (visibleHeight - scrollbarHeight)
            drawRect(
                color = color.copy(alpha = alpha),
                topLeft = Offset(size.width - width.toPx(), scrollbarOffsetY),
                size = Size(width.toPx(), scrollbarHeight)
            )
        }
    }
}

fun Modifier.verticalScrollWithScrollbar(
    state: ScrollState,
    enabled: Boolean = true,
    flingBehavior: FlingBehavior? = null,
    reverseScrolling: Boolean = false,
    scrollbarWidth: Dp = 4.dp,
    scrollbarColor: Color = Color.Red
): Modifier = this
    .verticalScroll(state, enabled, flingBehavior, reverseScrolling)
    .simpleVerticalScrollbar(state, scrollbarWidth, scrollbarColor)

