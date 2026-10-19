package com.example.aiassistent1.presentation.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.aiassistent1.domain.model.AppDestination
import com.example.aiassistent1.domain.model.MessageInputLimits
import com.example.aiassistent1.domain.model.ModelState
import com.example.aiassistent1.presentation.playback.SpeechPlaybackState
import com.example.aiassistent1.presentation.viewmodel.ChatUiState
import com.example.aiassistent1.presentation.viewmodel.ChatViewModel
import com.example.aiassistent1.presentation.viewmodel.ConversationVoiceSession
import com.example.aiassistent1.presentation.viewmodel.ModelAvailability

internal enum class MicrophoneAction {
    TAP,
    VOICE_DRAFT,
}

/** Both calendar pages use the same microphone actions and conversation-ticket checks. */
@Composable
internal fun rememberConversationMicrophoneAction(viewModel: ChatViewModel): (MicrophoneAction) -> Unit {
    val context = LocalContext.current
    var pendingAction by remember { mutableStateOf<MicrophoneAction?>(null) }
    var interruptedPlayback by remember { mutableStateOf(false) }
    var pendingConversation by remember { mutableStateOf<ConversationVoiceSession.Ticket?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingAction
        val forceStart = interruptedPlayback
        val conversation = pendingConversation
        pendingAction = null
        interruptedPlayback = false
        pendingConversation = null
        if (granted && viewModel.isVoiceInteractionCurrent(conversation)) {
            when (action) {
                MicrophoneAction.TAP -> viewModel.onMicrophoneTap(forceStart)
                MicrophoneAction.VOICE_DRAFT -> viewModel.startVoiceDraft(forceStart)
                null -> Unit
            }
        }
    }

    return { action ->
        val interrupted = viewModel.interruptPlaybackForMicrophone()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            when (action) {
                MicrophoneAction.TAP -> viewModel.onMicrophoneTap(interrupted)
                MicrophoneAction.VOICE_DRAFT -> viewModel.startVoiceDraft(interrupted)
            }
        } else {
            pendingAction = action
            interruptedPlayback = interrupted
            pendingConversation = viewModel.voiceInteractionTicket()
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

@Composable
internal fun ConversationInputPanel(
    viewModel: ChatViewModel,
    state: ChatUiState,
    onMicrophoneAction: (MicrophoneAction) -> Unit,
    maxInputLinesWhenKeyboardVisible: Int = Int.MAX_VALUE,
) {
    InputPanel(
        text = state.inputDraft.text,
        onTextChange = viewModel::updateInputText,
        draftStorageError = state.inputDraft.storageError,
        onRetryDraftStorage = viewModel::retryInputDraftStorage,
        textInputEnabled = state.inputDraft.isLoaded && !state.isProcessing && !state.isClearingChat &&
            !state.isStopping && state.modelState !is ModelState.Loading &&
            state.modelAvailability == ModelAvailability.Available && !state.voiceDraft.isVisible,
        microphoneEnabled = if (state.isClearingChat) state.isVoiceMode else
            state.speechPlaybackState is SpeechPlaybackState.Playing ||
                state.speechPlaybackState is SpeechPlaybackState.Generating || (!state.isProcessing &&
                !state.isStopping && state.modelState !is ModelState.Loading &&
                state.modelAvailability == ModelAvailability.Available),
        isProcessing = state.isProcessing,
        isCheckingRequest = state.isCheckingRequest,
        isVoiceMode = state.isVoiceMode,
        isVoiceDraftActive = state.voiceDraft.isVisible,
        onSend = viewModel::sendInputDraft,
        maximumMessageLength = MessageInputLimits.forMode(state.isCalendarMode),
        maxInputLinesWhenKeyboardVisible = maxInputLinesWhenKeyboardVisible,
        onStop = viewModel::stopGeneration,
        onVoiceTap = { onMicrophoneAction(MicrophoneAction.TAP) },
        onVoiceDraft = { onMicrophoneAction(MicrophoneAction.VOICE_DRAFT) },
    )
}

@Composable
internal fun CalendarConversationInput(viewModel: ChatViewModel, state: ChatUiState) {
    // The outgoing calendar can remain composed during a navigation animation.
    if (state.navigationState?.destination != AppDestination.CALENDAR || !state.isCalendarMode) return
    val requestMicrophoneAction = rememberConversationMicrophoneAction(viewModel)
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        AnimatedVisibility(visible = state.voiceDraft.isVisible) {
            VoiceDraftCard(
                state = state.voiceDraft,
                onDelete = viewModel::deleteVoiceDraft,
                onSend = viewModel::sendVoiceDraft,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
            )
        }
        ConversationInputPanel(
            viewModel = viewModel,
            state = state,
            onMicrophoneAction = requestMicrophoneAction,
            maxInputLinesWhenKeyboardVisible = 5,
        )
    }
}
