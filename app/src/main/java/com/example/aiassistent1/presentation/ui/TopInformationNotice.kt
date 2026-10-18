package com.example.aiassistent1.presentation.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.aiassistent1.presentation.viewmodel.VoiceModeNotice

internal const val INFORMATION_NOTICE_DURATION_MILLIS = 5_000L

/** The saved-event card and microphone notices use the same styling and transitions. */
@Composable
internal fun TopInformationNotice(
    visible: Boolean,
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val visibility = remember { MutableTransitionState(false) }
    var lastText by remember { mutableStateOf(text) }
    SideEffect { if (visible) lastText = text }
    visibility.targetState = visible
    AnimatedVisibility(
        visibleState = visibility,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            val hasAction = actionLabel != null && onAction != null
            Row(
                modifier = Modifier.padding(start = 16.dp, end = if (hasAction) 4.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (visible) text else lastText,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelMedium,
                )
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction) { Text(actionLabel) }
                }
            }
        }
    }
}

@Composable
internal fun VoiceModeNoticeCard(notice: VoiceModeNotice?, modifier: Modifier = Modifier) {
    TopInformationNotice(
        visible = notice != null,
        text = notice?.text.orEmpty(),
        modifier = modifier,
    )
}
