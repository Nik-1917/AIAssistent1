package com.example.aiassistent1.presentation.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable
internal fun ChatActionIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean? = null,
) {
    val colors = MaterialTheme.colorScheme
    val backgroundColor by animateColorAsState(
        targetValue = when {
            active == true && enabled -> colors.primary.copy(alpha = 0.14f)
            active == true -> colors.primary.copy(alpha = 0.07f)
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 180),
        label = "chatActionBackground",
    )
    val iconColor by animateColorAsState(
        targetValue = when {
            !enabled -> colors.onSurface.copy(alpha = 0.38f)
            active == true -> colors.primary
            else -> colors.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 180),
        label = "chatActionIcon",
    )

    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(48.dp)
            .semantics {
                if (active != null) {
                    stateDescription = if (active) "Включено" else "Выключено"
                }
            },
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(backgroundColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                modifier = Modifier.size(24.dp),
                tint = iconColor,
            )
        }
    }
}

internal val VoiceWaveIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "VoiceWave",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.5f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(3f, 10f)
            lineTo(3f, 14f)
            moveTo(7.5f, 7f)
            lineTo(7.5f, 17f)
            moveTo(12f, 4f)
            lineTo(12f, 20f)
            moveTo(16.5f, 7f)
            lineTo(16.5f, 17f)
            moveTo(21f, 10f)
            lineTo(21f, 14f)
        }
    }.build()
}
