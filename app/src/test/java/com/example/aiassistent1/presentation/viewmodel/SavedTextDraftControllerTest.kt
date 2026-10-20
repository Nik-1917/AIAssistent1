package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.interfaces.SavedTextDraftRepository
import com.example.aiassistent1.domain.model.SavedTextDraft
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SavedTextDraftControllerTest {
    private class Repository : SavedTextDraftRepository {
        var drafts = emptyList<SavedTextDraft>()
        val attempts = mutableListOf<SavedTextDraft>()
        var beforeArchive: suspend () -> Unit = {}
        var failLoad = false
        var failDelete = false
        override suspend fun loadTextDrafts(): List<SavedTextDraft> {
            if (failLoad) throw IOException("load")
            return drafts
        }
        override suspend fun archiveVoiceDraft(draft: SavedTextDraft): List<SavedTextDraft> {
            attempts += draft
            beforeArchive()
            drafts = listOf(draft) + drafts.filterNot { it.id == draft.id }
            return drafts
        }
        override suspend fun deleteTextDraft(id: String): List<SavedTextDraft> {
            if (failDelete) throw IOException("delete")
            drafts = drafts.filterNot { it.id == id }
            return drafts
        }
    }

    @Test fun `successful save preserves exact text and creates separate records`() = runTest {
        val repository = Repository()
        val clock = Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC)
        val controller = SavedTextDraftController(repository, backgroundScope, clock)
        assertTrue(controller.save("Первая строка\nВторая строка"))
        assertTrue(controller.save("Следующий черновик"))
        assertEquals(2, controller.state.value.drafts.size)
        assertEquals(2, repository.attempts.map { it.id }.toSet().size)
        assertEquals("Первая строка\nВторая строка", repository.attempts.first().text)
        assertEquals(1234L, repository.attempts.first().createdAtEpochMillis)
        assertFalse(controller.state.value.isBusy)
    }

    @Test fun `double save during a blocked write creates only one record`() = runTest {
        val release = CompletableDeferred<Unit>()
        val repository = Repository().apply { beforeArchive = { release.await() } }
        val controller = SavedTextDraftController(repository, backgroundScope)
        val first = backgroundScope.async { controller.save("Текст") }
        runCurrent()
        assertTrue(controller.state.value.isSaving)
        assertFalse(controller.save("Текст"))
        assertTrue(controller.state.value.drafts.isEmpty())
        release.complete(Unit)
        runCurrent()
        assertTrue(first.await())
        assertEquals(1, repository.attempts.size)
        assertEquals(1, controller.state.value.drafts.size)
    }

    @Test fun `retry after failed save uses the same id without reporting success early`() = runTest {
        val repository = Repository().apply { beforeArchive = { throw IOException("write") } }
        val controller = SavedTextDraftController(repository, backgroundScope)
        assertFalse(controller.save("Не потерять"))
        assertNotNull(controller.state.value.error)
        assertTrue(controller.state.value.drafts.isEmpty())
        repository.beforeArchive = {}
        assertTrue(controller.save("Не потерять"))
        assertEquals(repository.attempts[0], repository.attempts[1])
        assertEquals(1, controller.state.value.drafts.size)
        assertNull(controller.state.value.error)
    }

    @Test fun `load failure is retryable and does not replace an existing list`() = runTest {
        val draft = SavedTextDraft("id", "Сохранённый текст", 100)
        val repository = Repository().apply { drafts = listOf(draft) }
        val controller = SavedTextDraftController(repository, backgroundScope)
        controller.show()
        runCurrent()
        assertTrue(controller.state.value.showList)
        assertEquals(listOf(draft), controller.state.value.drafts)
        repository.failLoad = true
        controller.reload()
        runCurrent()
        assertEquals(listOf(draft), controller.state.value.drafts)
        assertNotNull(controller.state.value.error)
        repository.failLoad = false
        controller.reload()
        runCurrent()
        assertNull(controller.state.value.error)
    }

    @Test fun `delete failure leaves saved text available and retry removes only its id`() = runTest {
        val first = SavedTextDraft("first", "Первый", 100)
        val second = SavedTextDraft("second", "Второй", 200)
        val repository = Repository().apply { drafts = listOf(first, second) }
        val controller = SavedTextDraftController(repository, backgroundScope)
        controller.show()
        runCurrent()
        repository.failDelete = true
        controller.delete(first.id)
        runCurrent()
        assertEquals(listOf(first, second), controller.state.value.drafts)
        assertNotNull(controller.state.value.error)
        repository.failDelete = false
        controller.delete(first.id)
        runCurrent()
        assertEquals(listOf(second), controller.state.value.drafts)
    }

    @Test fun `blank text cannot be saved`() = runTest {
        val repository = Repository()
        val controller = SavedTextDraftController(repository, backgroundScope)
        assertFalse(controller.save(" \n "))
        assertTrue(repository.attempts.isEmpty())
    }

    @Test fun `a new working draft with identical text does not reuse a failed operation id`() = runTest {
        val repository = Repository().apply { beforeArchive = { throw IOException("write") } }
        val controller = SavedTextDraftController(repository, backgroundScope)
        assertFalse(controller.save("Одинаковый текст"))
        controller.resetPendingSave()
        repository.beforeArchive = {}
        assertTrue(controller.save("Одинаковый текст"))
        assertNotEquals(repository.attempts[0].id, repository.attempts[1].id)
    }
}
