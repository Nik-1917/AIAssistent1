package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.interfaces.SavedTextDraftRepository
import com.example.aiassistent1.domain.model.SavedTextDraft
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SavedTextDraftsState(
    val drafts: List<SavedTextDraft> = emptyList(),
    val showList: Boolean = false,
    val isLoaded: Boolean = false,
    val isSaving: Boolean = false,
    val deletingId: String? = null,
    val error: String? = null,
) {
    val isBusy: Boolean get() = isSaving || deletingId != null
}

internal class SavedTextDraftController(
    private val repository: SavedTextDraftRepository,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutableState = MutableStateFlow(SavedTextDraftsState())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var loadJob: Job? = null
    private var pendingSave: SavedTextDraft? = null

    fun show() {
        mutableState.update { it.copy(showList = true) }
        reload()
    }

    fun close() {
        mutableState.update { it.copy(showList = false, error = null) }
    }

    fun reportError(message: String) {
        mutableState.update { it.copy(error = message) }
    }

    fun resetPendingSave() {
        pendingSave = null
    }

    fun reload() {
        if (loadJob?.isActive == true) return
        loadJob = scope.launch {
            mutex.withLock {
                try {
                    val drafts = repository.loadTextDrafts()
                    mutableState.update { it.copy(drafts = drafts, isLoaded = true, error = null) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.update { it.copy(error = "Не удалось загрузить текстовые черновики.") }
                }
            }
        }
    }

    suspend fun save(text: String): Boolean {
        if (text.isBlank() || state.value.isBusy) return false
        val draft = pendingSave?.takeIf { it.text == text } ?: SavedTextDraft(
            id = UUID.randomUUID().toString(), text = text, createdAtEpochMillis = clock.millis(),
        ).also { pendingSave = it }
        mutableState.update { it.copy(isSaving = true, error = null) }
        return try {
            mutex.withLock {
                val drafts = repository.archiveVoiceDraft(draft)
                mutableState.update { it.copy(drafts = drafts, isLoaded = true) }
                pendingSave = null
                true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            reportError("Не удалось сохранить текстовый черновик. Текст оставлен в окне.")
            false
        } finally {
            mutableState.update { it.copy(isSaving = false) }
        }
    }

    fun delete(id: String) {
        if (state.value.isBusy) return
        mutableState.update { it.copy(deletingId = id, error = null) }
        scope.launch {
            try {
                mutex.withLock {
                    val drafts = repository.deleteTextDraft(id)
                    mutableState.update { it.copy(drafts = drafts, isLoaded = true) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                reportError("Не удалось удалить текстовый черновик.")
            } finally {
                mutableState.update { it.copy(deletingId = null) }
            }
        }
    }
}
