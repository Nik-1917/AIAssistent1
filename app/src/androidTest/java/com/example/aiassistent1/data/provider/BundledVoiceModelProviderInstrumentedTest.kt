package com.example.aiassistent1.data.provider

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.aiassistent1.di.AppModule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BundledVoiceModelProviderInstrumentedTest {
    @Test
    fun `copies espeak data to private storage and plays bundled voice`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = BundledVoiceModelProvider(context)
        val assets = provider.getAssets().getOrThrow()
        val dataDirectory = File(assets.ttsDataDirectory)

        assertTrue("Каталог espeak-ng-data не создан", dataDirectory.isDirectory)
        assertTrue("Русский словарь espeak-ng не скопирован", File(dataDirectory, "ru_dict").isFile)
        assertFalse("Для espeak-ng передан путь из APK", assets.ttsDataDirectory.startsWith("voice/"))

        val playback = SherpaOnnxSpeechPlayback(
            SherpaOnnxSpeechSynthesizer(
                context,
                provider,
                AppModule.provideSettingsRepository(context),
            ),
        )
        var playbackStarted = false
        val result = try {
            playback.speak("Проверка голосового вывода") { playbackStarted = true }
        } finally {
            playback.close()
        }

        assertTrue(result.exceptionOrNull()?.message ?: "Голос не был воспроизведён", result.isSuccess)
        assertTrue("Воспроизведение штатного голоса не началось", playbackStarted)
    }
}
