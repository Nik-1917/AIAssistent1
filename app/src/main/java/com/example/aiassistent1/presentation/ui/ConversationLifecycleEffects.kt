package com.example.aiassistent1.presentation.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.aiassistent1.presentation.playback.SpeechPlaybackState
import com.example.aiassistent1.presentation.viewmodel.ChatUiState
import com.example.aiassistent1.presentation.viewmodel.ChatViewModel
import kotlinx.coroutines.delay

/** One owner across both pages, including the time when both are animating. */
@Composable
internal fun ConversationLifecycleEffects(viewModel: ChatViewModel, state: ChatUiState) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(lifecycleOwner, viewModel, context) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.refreshModelStatus()
                Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded(
                    (context as? Activity)?.isChangingConfigurations ?: false,
                )
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var isExtendingScreenOn by remember { mutableStateOf(false) }
    var previousSpeechPlaybackState by remember { mutableStateOf(state.speechPlaybackState) }
    val isSpeechPlaybackActive = state.speechPlaybackState is SpeechPlaybackState.Generating ||
        state.speechPlaybackState is SpeechPlaybackState.Playing
    LaunchedEffect(state.isProcessing, state.speechPlaybackState) {
        val currentSpeechPlaybackState = state.speechPlaybackState
        if (state.isProcessing || isSpeechPlaybackActive) {
            isExtendingScreenOn = false
        } else if (previousSpeechPlaybackState is SpeechPlaybackState.Playing &&
            currentSpeechPlaybackState is SpeechPlaybackState.Idle
        ) {
            isExtendingScreenOn = true
            delay(SCREEN_ON_GRACE_PERIOD_MILLIS)
            isExtendingScreenOn = false
        }
        previousSpeechPlaybackState = currentSpeechPlaybackState
    }
    DisposableEffect(view, state.voiceDraft.isRecording, state.isProcessing, isSpeechPlaybackActive, isExtendingScreenOn) {
        view.keepScreenOn = state.voiceDraft.isRecording || state.isProcessing ||
            isSpeechPlaybackActive || isExtendingScreenOn
        onDispose { view.keepScreenOn = false }
    }
}

private const val SCREEN_ON_GRACE_PERIOD_MILLIS = 25_000L
