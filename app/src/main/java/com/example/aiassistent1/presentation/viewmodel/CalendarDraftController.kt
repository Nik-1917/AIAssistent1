package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.interfaces.CalendarDraftRepository
import com.example.aiassistent1.domain.model.CalendarDraftRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CalendarDraftsState(
    val drafts: List<CalendarEventDraftUiState> = emptyList(),
    val selectedId: String? = null,
    val showList: Boolean = false,
    val isLoaded: Boolean = false,
    val storageError: String? = null,
) {
    val selected: CalendarEventDraftUiState? get() = drafts.firstOrNull { it.requestId == selectedId }
}

/** Owned by the ViewModel's main scope. Writes are ordered, including the write before a commit. */
class CalendarDraftController(
    private val repository: CalendarDraftRepository,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(CalendarDraftsState())
    val state = mutableState.asStateFlow()
    private val restored = CompletableDeferred<Boolean>()
    private var lastWrite: Deferred<Boolean>? = null
    private var loading = false

    init {
        scope.launch {
            restore()
            restored.complete(state.value.isLoaded)
        }
    }

    private suspend fun restore() {
        if (loading) return
        loading = true
        try {
            val loaded = repository.load().map { it.toUi() }
            val local = state.value.drafts.associateBy { it.requestId }
            mutableState.value = state.value.copy(
                drafts = loaded.map { local[it.requestId] ?: it } + local.values.filter { draft -> loaded.none { it.requestId == draft.requestId } },
                isLoaded = true, storageError = null,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.value = state.value.copy(storageError = "Не удалось загрузить черновики: ${error.message}")
        } finally { loading = false }
    }

    fun retryStorage() {
        scope.launch {
            restored.await()
            if (!state.value.isLoaded) restore()
            if (state.value.isLoaded) persist()
        }
    }

    fun add(draft: CalendarEventDraftUiState) {
        scope.launch {
            restored.await()
            if (state.value.drafts.any { it.requestId == draft.requestId }) return@launch
            mutableState.value = state.value.copy(drafts = state.value.drafts + draft)
            persist()
        }
    }

    fun open(id: String) {
        if (state.value.drafts.none { it.requestId == id && it.savedEventId == null }) return
        mutableState.value = state.value.copy(selectedId = id, showList = false)
    }

    fun close() { mutableState.value = state.value.copy(selectedId = null, showList = false) }
    fun showList() { mutableState.value = state.value.copy(selectedId = null, showList = true) }

    fun edit(id: String, field: CalendarEventField, value: String) {
        update(id) { draft ->
            if (!draft.isEditable) draft
            else draft.copy(fieldInputs = draft.fieldInputs + (field to value), error = null)
        }
    }

    fun editNotes(id: String, value: String) {
        update(id) { if (it.isEditable) it.copy(notes = value.ifEmpty { null }, error = null) else it }
    }

    fun discard(id: String) {
        val draft = find(id) ?: return
        if (!draft.isEditable) return
        mutableState.value = state.value.copy(
            drafts = state.value.drafts.filterNot { it.requestId == id },
            selectedId = state.value.selectedId.takeUnless { it == id },
        )
        persist()
    }

    fun find(id: String): CalendarEventDraftUiState? = state.value.drafts.firstOrNull { it.requestId == id }

    fun update(id: String, persist: Boolean = true, change: (CalendarEventDraftUiState) -> CalendarEventDraftUiState) {
        val before = find(id) ?: return
        val after = change(before)
        if (before == after) return
        mutableState.value = state.value.copy(drafts = state.value.drafts.map { if (it.requestId == id) after else it })
        if (persist) this.persist()
    }

    /** An asynchronous formatter may update only the field and draft that requested it. */
    fun finishFormatting(id: String, field: CalendarEventField, raw: String, result: Result<String>) {
        update(id) { draft ->
            if (!draft.isEditable || draft.formattingField != field) return@update draft
            val idle = draft.copy(isFormatting = false, formattingField = null)
            if (draft.fieldText(field) != raw) idle
            else result.fold(
                onSuccess = { idle.copy(fieldInputs = idle.fieldInputs + (field to it), error = null) },
                onFailure = { idle.copy(error = it.message ?: "Не удалось распознать значение поля") },
            )
        }
    }

    fun save(id: String, commit: suspend (CalendarEventDraftUiState) -> String) {
        val draft = find(id) ?: return
        if (draft.savedEventId != null || draft.isSaving || draft.isFormatting || draft.isVoiceInputActive) return
        val prepared = try {
            draft.resolveInputs().also { require(it.isComplete) { "Заполните обязательные поля события." } }
        } catch (error: Exception) {
            update(id, persist = false) { it.copy(error = error.message ?: "Проверьте данные события") }
            return
        }
        update(id, persist = false) { prepared.copy(saveRequested = true, isSaving = true, error = null) }
        val durableRequest = persist()
        scope.launch {
            if (!durableRequest.await()) {
                update(id, persist = false) { it.copy(isSaving = false, error = "Не удалось сохранить черновик. Повторите сохранение.") }
                return@launch
            }
            try {
                val eventId = commit(prepared)
                update(id) { it.copy(savedEventId = eventId, saveRequested = false, isSaving = false, error = null) }
                if (state.value.selectedId == id) close()
            } catch (cancelled: CancellationException) {
                // The persisted request remains immutable and can be safely retried using its receipt ID.
                throw cancelled
            } catch (error: Exception) {
                update(id, persist = false) { it.copy(isSaving = false, error = error.message ?: "Не удалось сохранить событие") }
            }
        }
    }

    private fun persist(): Deferred<Boolean> {
        if (!state.value.isLoaded) return CompletableDeferred(false)
        val snapshot = state.value.drafts.map { it.toRecord() }
        val previous = lastWrite
        return scope.async {
            previous?.await()
            try {
                repository.save(snapshot)
                mutableState.value = state.value.copy(storageError = null)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = state.value.copy(storageError = "Не удалось сохранить черновики: ${error.message}")
                false
            }
        }.also { lastWrite = it }
    }
}

private fun CalendarEventDraftUiState.toRecord() = CalendarDraftRecord(
    requestId = requestId, chatId = chatId, createdAtEpochMillis = createdAtEpochMillis,
    title = title, date = date, time = time, durationMinutes = durationMinutes, value = value,
    notes = notes, endsAt = endsAt, fieldInputs = fieldInputs.mapKeys { it.key.name },
    savedEventId = savedEventId, saveRequested = saveRequested,
)

private fun CalendarDraftRecord.toUi() = CalendarEventDraftUiState(
    requestId = requestId, chatId = chatId, createdAtEpochMillis = createdAtEpochMillis,
    title = title, date = date, time = time, durationMinutes = durationMinutes, value = value,
    notes = notes, endsAt = endsAt, fieldInputs = fieldInputs.mapKeys { CalendarEventField.valueOf(it.key) },
    savedEventId = savedEventId, saveRequested = saveRequested,
)
