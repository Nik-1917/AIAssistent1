package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.domain.model.AudioPreferences
import com.example.aiassistent1.domain.model.VoiceActivationMode
import com.example.aiassistent1.domain.model.WakeWordEngine
import org.junit.Assert.*
import org.junit.Test

class VoicePlaybackInputPolicyTest {
    private fun preferences(mode: VoiceActivationMode, allow: Boolean = true) = AudioPreferences(
        allowVoiceInterruption = allow,
        wakeWordEnabled = mode != VoiceActivationMode.DIRECT,
        wakeWordEngine = if (mode == VoiceActivationMode.PERSONAL_WORD) WakeWordEngine.METRIC_KWS else WakeWordEngine.LEGACY_ASR,
    )

    @Test fun directInputCannotListenDuringPlaybackAndActivationModesAlwaysRequireActivation() {
        for (mode in VoiceActivationMode.entries) {
            val prefs = preferences(mode)
            assertEquals(mode != VoiceActivationMode.DIRECT, prefs.voiceInterruptionEnabled)
            assertEquals(if (mode == VoiceActivationMode.DIRECT) VoicePlaybackInputPolicy.Capture.STOP
                else VoicePlaybackInputPolicy.Capture.ACTIVATION,
                VoicePlaybackInputPolicy.capture(prefs, playbackActive = true))
            assertEquals(if (mode == VoiceActivationMode.DIRECT) VoicePlaybackInputPolicy.Capture.DIRECT
                else VoicePlaybackInputPolicy.Capture.ACTIVATION,
                VoicePlaybackInputPolicy.capture(prefs, playbackActive = false))
        }
    }

    @Test fun disabledSwitchPausesOnlyPlaybackAndNeverDisablesActivationBetweenReplies() {
        for (mode in VoiceActivationMode.entries) {
            val prefs = preferences(mode, allow = false)
            assertFalse(prefs.voiceInterruptionEnabled)
            assertEquals(VoicePlaybackInputPolicy.Capture.STOP,
                VoicePlaybackInputPolicy.capture(prefs, playbackActive = true))
            assertEquals(if (mode == VoiceActivationMode.DIRECT) VoicePlaybackInputPolicy.Capture.DIRECT
                else VoicePlaybackInputPolicy.Capture.ACTIVATION,
                VoicePlaybackInputPolicy.capture(prefs, playbackActive = false))
        }
    }

    @Test fun manualMicrophoneStillRequestsDirectInputInEveryMode() {
        for (mode in VoiceActivationMode.entries) for (allow in listOf(false, true)) {
            assertEquals(VoicePlaybackInputPolicy.Capture.DIRECT,
                VoicePlaybackInputPolicy.capture(preferences(mode, allow), playbackActive = true, manualInput = true))
        }
    }

    @Test fun queuedInterruptionCannotCrossActivationModeOrPolicyChanges() {
        for (captured in VoiceActivationMode.entries) for (selected in VoiceActivationMode.entries) {
            assertEquals(selected != VoiceActivationMode.DIRECT && captured == selected,
                VoicePlaybackInputPolicy.acceptsInterruption(preferences(selected), preferences(captured).inputPolicy()))
        }
        val name = preferences(VoiceActivationMode.NAME)
        assertFalse(VoicePlaybackInputPolicy.acceptsInterruption(name, null))
        assertFalse(VoicePlaybackInputPolicy.acceptsInterruption(name.copy(policyRevision = 1), name.inputPolicy()))
        assertFalse(VoicePlaybackInputPolicy.acceptsInterruption(name.copy(voiceIdEnabled = true), name.inputPolicy()))
        assertFalse(VoicePlaybackInputPolicy.acceptsInterruption(name.copy(allowVoiceInterruption = false), name.inputPolicy()))
        assertFalse(VoicePlaybackInputPolicy.acceptsInterruption(name,
            name.copy(allowVoiceInterruption = false).inputPolicy()))
    }

    @Test fun emptyAsrAndPunctuationCannotBecomeAnInterruptingCommand() {
        for (text in listOf("", "   ", "...", "—?!")) assertFalse(VoiceCommandAcceptance.hasSpeechText(text))
        for (text in listOf("да", "нет", "покажи заметки", "завтра", "1")) assertTrue(VoiceCommandAcceptance.hasSpeechText(text))
    }
}
