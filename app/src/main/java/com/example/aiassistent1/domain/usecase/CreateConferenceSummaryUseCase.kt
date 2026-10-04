package com.example.aiassistent1.domain.usecase

import com.example.aiassistent1.domain.interfaces.LLMEngine
import com.example.aiassistent1.data.repository.ConferenceRepositoryImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow

/** Caller serializes requests; the engine sizes each chunk automatically. */
class CreateConferenceSummaryUseCase(private val llmEngine: LLMEngine,
    private val repository: ConferenceRepositoryImpl) {
    suspend fun execute(conferenceId: Long): Result<String> = try {
        val conference = repository.getConferenceById(conferenceId) ?: error("Запись не найдена")
        check(conference.status != "recording") { "Сначала завершите запись" }
        val source = flow {
            var afterId = 0L
            while (true) {
                val page = repository.readPage(conferenceId, afterId)
                if (page.isEmpty()) break
                for (line in page) {
                    emit(line.text + "\n")
                    afterId = line.id
                }
            }
        }
        val summary = LongTextSummarizer(llmEngine).summarize(source)
        repository.saveSummary(conferenceId, summary)
        Result.success(summary)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { Result.failure(error) }
}
