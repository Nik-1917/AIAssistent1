package com.example.aiassistent1.presentation.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.example.aiassistent1.presentation.playback.SpeechPlaybackState

@Composable
fun SpeechPlaybackStatusCard(
    state: SpeechPlaybackState,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    isCollapsed: Boolean = true,
    onCollapsedChange: (Boolean) -> Unit = {},
    autoPlaybackEnabled: Boolean = false,
    interactionEnabled: Boolean = true,
) {
    val useExpandedLayout = !isCollapsed
    val cornerRadius = if (isCollapsed) 12.dp else 24.dp
    AnimatedVisibility(
        visible = state !is SpeechPlaybackState.Idle || autoPlaybackEnabled,
        modifier = modifier,
    ) {
        val isActive = state is SpeechPlaybackState.Generating || state is SpeechPlaybackState.Playing
        val palette = when (state) {
            SpeechPlaybackState.Generating -> MaterialTheme.colorScheme.primary
            SpeechPlaybackState.Playing -> MaterialTheme.colorScheme.primary
            is SpeechPlaybackState.Stopped -> MaterialTheme.colorScheme.secondary
            is SpeechPlaybackState.Error -> MaterialTheme.colorScheme.error
            SpeechPlaybackState.Idle -> MaterialTheme.colorScheme.surface
        }
        val title = when (state) {
            SpeechPlaybackState.Generating -> null
            SpeechPlaybackState.Playing -> null
            is SpeechPlaybackState.Stopped -> null
            is SpeechPlaybackState.Error -> "Голосовой вывод недоступен"
            SpeechPlaybackState.Idle -> null
        }
        val subtitle = when (state) {
            SpeechPlaybackState.Generating -> "Создаю аудио на устройстве"
            SpeechPlaybackState.Playing -> "Нажмите стоп, чтобы прервать"
            is SpeechPlaybackState.Stopped -> state.reason.label
            is SpeechPlaybackState.Error -> state.message
            SpeechPlaybackState.Idle -> if (autoPlaybackEnabled) "Ответ будет озвучен автоматически" else "Нажмите на сообщение для озвучки"
        }
        val cardTextColor = when {
            isSystemInDarkTheme() -> Color.White
            !useExpandedLayout -> MaterialTheme.colorScheme.onSurface
            else -> Color.White
        }
        val containerColor by animateColorAsState(
            targetValue = if (isCollapsed) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            } else {
                Color.Black.copy(alpha = 0.65f)
            },
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
            label = "speechPlaybackContainerColor",
        )
        val infiniteTransition = rememberInfiniteTransition(label = "speechPlaybackPulse")
        val pulse by infiniteTransition.animateFloat(
            initialValue = 0.74f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(760),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "speechPlaybackPulseValue",
        )

        Card(
            onClick = { onCollapsedChange(!isCollapsed) },
            enabled = interactionEnabled,
            shape = RoundedCornerShape(cornerRadius),
            colors = CardDefaults.cardColors(
                containerColor = containerColor,
                contentColor = cardTextColor,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
            modifier = if (useExpandedLayout) Modifier.fillMaxWidth() else Modifier.size(66.dp),
        ) {
            Row(
                modifier = Modifier
                    .then(if (useExpandedLayout) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                    .padding(horizontal = if (useExpandedLayout) 14.dp else 0.dp, vertical = if (useExpandedLayout) 11.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (useExpandedLayout) Arrangement.spacedBy(10.dp) else Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .graphicsLayer {
                            val activePulse = if (isActive) pulse else 1f
                            scaleX = activePulse
                            scaleY = activePulse
                        }
                        .background(palette.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isCollapsed && isActive) {
                        IconButton(onClick = onStop) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Остановить озвучивание",
                                tint = cardTextColor,
                            )
                        }
                    } else {
                        when (state) {
                            SpeechPlaybackState.Generating -> CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 3.dp,
                                color = if (isSystemInDarkTheme()) palette else Color.White,
                            )
                            SpeechPlaybackState.Playing -> Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = null,
                                tint = cardTextColor,
                                modifier = Modifier.size(27.dp),
                            )
                            is SpeechPlaybackState.Stopped,
                            SpeechPlaybackState.Idle -> Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = null,
                                tint = cardTextColor,
                            )
                            is SpeechPlaybackState.Error -> Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                
                if (useExpandedLayout) {
                    Column {
                        title?.let {
                            Text(it, style = MaterialTheme.typography.labelLarge)
                            Spacer(modifier = Modifier.size(2.dp))
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!useExpandedLayout) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                cardTextColor
                            },
                        )
                    }
                }
                if (isActive && useExpandedLayout) {
                    IconButton(onClick = onStop) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Остановить озвучивание",
                            tint = cardTextColor,
                        )
                    }
                }
            }
        }
    }
}
