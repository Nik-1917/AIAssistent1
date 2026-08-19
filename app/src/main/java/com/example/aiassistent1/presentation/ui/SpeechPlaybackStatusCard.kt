package com.example.aiassistent1.presentation.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.example.aiassistent1.presentation.playback.SpeechPlaybackState

@Composable
fun SpeechPlaybackStatusCard(
    state: SpeechPlaybackState,
    isVoiceMode: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state !is SpeechPlaybackState.Idle || isVoiceMode,
        modifier = modifier,
    ) {
        val isActive = state is SpeechPlaybackState.Generating || state is SpeechPlaybackState.Playing
        val palette = when (state) {
            SpeechPlaybackState.Generating -> MaterialTheme.colorScheme.primary
            SpeechPlaybackState.Playing -> MaterialTheme.colorScheme.tertiary
            is SpeechPlaybackState.Stopped -> MaterialTheme.colorScheme.secondary
            is SpeechPlaybackState.Error -> MaterialTheme.colorScheme.error
            SpeechPlaybackState.Idle -> MaterialTheme.colorScheme.surface
        }
        val title = when (state) {
            SpeechPlaybackState.Generating -> "Готовлю голосовой ответ"
            SpeechPlaybackState.Playing -> "Ответ озвучивается"
            is SpeechPlaybackState.Stopped -> "Озвучивание остановлено"
            is SpeechPlaybackState.Error -> "Голосовой вывод недоступен"
            SpeechPlaybackState.Idle -> "Голосовой вывод готов"
        }
        val subtitle = when (state) {
            SpeechPlaybackState.Generating -> "Создаю аудио на устройстве"
            SpeechPlaybackState.Playing -> "Нажмите стоп, чтобы прервать"
            is SpeechPlaybackState.Stopped -> state.reason.label
            is SpeechPlaybackState.Error -> state.message
            SpeechPlaybackState.Idle -> "Ответ будет озвучен автоматически"
        }
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
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = palette.copy(alpha = 0.15f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                    when (state) {
                        SpeechPlaybackState.Generating -> CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 3.dp,
                            color = palette,
                        )
                        SpeechPlaybackState.Playing -> Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null,
                            tint = palette,
                            modifier = Modifier.size(27.dp),
                        )
                        is SpeechPlaybackState.Stopped -> Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = null,
                            tint = palette,
                        )
                        is SpeechPlaybackState.Error -> Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = palette,
                        )
                        SpeechPlaybackState.Idle -> Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null,
                            tint = palette,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.size(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isActive) {
                    IconButton(onClick = onStop) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Остановить озвучивание",
                            tint = palette,
                        )
                    }
                }
            }
        }
    }
}
