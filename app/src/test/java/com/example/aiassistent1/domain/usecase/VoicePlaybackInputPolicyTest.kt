package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.domain.model.AudioPreferences
import com.example.aiassistent1.domain.model.VoiceActivationMode
import com.example.aiassistent1.domain.model.WakeWordEngine
import org.junit.Assert.*
import org.junit.Test

class VoicePlaybackInputPolicyTest {
    private fun preferences(mode: VoiceActivationMode, bargeIn: Boolean) = AudioPreferences(
        wakeWordEnabled = mode != VoiceActivationMode.DIRECT,
        wakeWordEngine = if (mode == VoiceActivationMode.PERSONAL_WORD) WakeWordEngine.METRIC_KWS else WakeWordEngine.LEGACY_ASR,
        bargeIn = bargeIn,
    )

    @Test fun playbackHonoursTheSwitchInEveryActivationModeAndRetainsTheSelectedMode() {
        for (mode in VoiceActivationMode.entries) {
            assertEquals(VoicePlaybackInputPolicy.Capture.STOP,
                VoicePlaybackInputPolicy.capture(preferences(mode, false), playbackActive = true))
            val enabled = VoicePlaybackInputPolicy.capture(preferences(mode, true), playbackActive = true)
            assertEquals(if (mode == VoiceActivationMode.DIRECT) VoicePlaybackInputPolicy.Capture.DIRECT
                else VoicePlaybackInputPolicy.Capture.ACTIVATION, enabled)
            assertEquals(enabled, VoicePlaybackInputPolicy.capture(preferences(mode, false), playbackActive = false))
        }
    }

    @Test fun manualMicrophoneOverridesTheSwitchAndActivationRequirement() {
        for (mode in VoiceActivationMode.entries) for (enabled in listOf(false, true)) {
            assertEquals(VoicePlaybackInputPolicy.Capture.DIRECT,
                VoicePlaybackInputPolicy.capture(preferences(mode, enabled), playbackActive = true, manualInput = true))
        }
    }

    @Test fun emptyAsrAndPunctuationCannotBecomeAnInterruptingCommand() {
        for (text in listOf("", "   ", "...", "—?!")) assertFalse(VoiceCommandAcceptance.hasSpeechText(text))
        for (text in listOf("да", "нет", "покажи заметки", "завтра", "1")) assertTrue(VoiceCommandAcceptance.hasSpeechText(text))
    }
}
