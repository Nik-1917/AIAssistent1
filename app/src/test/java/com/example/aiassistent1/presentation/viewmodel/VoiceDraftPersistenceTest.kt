package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.interfaces.VoiceDraftRepository
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceDraftPersistenceTest {
    private class Repository(var text: String = "") : VoiceDraftRepository {
        val operations = mutableListOf<String>()
        var beforeLoad: suspend () -> Unit = {}
        var beforeSave: suspend (String) -> Unit = {}
        var beforeClear: suspend () -> Unit = {}

        override suspend fun loadDraft(): String {
            val snapshot = text
            beforeLoad()
            return snapshot
        }

        override suspend fun saveDraft(text: String) {
            beforeSave(text)
            this.text = text
            operations += "save:$text"
        }

        override suspend fun clearDraft() {
            beforeClear()
            text = ""
            operations += "clear"
        }
    }

    @Test fun `restores saved text without writing or opening a recording`() = runTest {
        val repository = Repository("Первая фраза")
        val persistence = VoiceDraftPersistence(repository, backgroundScope) { fail(it) }

        assertEquals("Первая фраза", persistence.restore())
        runCurrent()
        assertTrue(repository.operations.isEmpty())
    }

    @Test fun `deletion waits for an in flight save and stays deleted after restoration`() = runTest {
        val releaseSave = CompletableDeferred<Unit>()
        val repository = Repository().apply { beforeSave = { releaseSave.await() } }
        val persistence = VoiceDraftPersistence(repository, backgroundScope) { fail(it) }

        persistence.save("Неотправленная фраза")
        runCurrent()
        persistence.clear()
        runCurrent()
        assertTrue(repository.operations.isEmpty())

        releaseSave.complete(Unit)
        runCurrent()
        assertEquals(listOf("save:Неотправленная фраза", "clear"), repository.operations)
        assertEquals("", VoiceDraftPersistence(repository, backgroundScope) { fail(it) }.restore())
    }

    @Test fun `queued snapshots are committed in order without dropping acknowledgements`() = runTest {
        val releaseSave = CompletableDeferred<Unit>()
        val repository = Repository().apply { beforeSave = { releaseSave.await() } }
        val persistence = VoiceDraftPersistence(repository, backgroundScope) { fail(it) }

        persistence.save("Первая фраза")
        runCurrent()
        persistence.save("Первая фраза") // Collapse while the first save is still running.
        persistence.save("Первая фраза Вторая фраза")
        releaseSave.complete(Unit)
        runCurrent()

        assertEquals("Первая фраза Вторая фраза", repository.text)
        assertEquals(listOf("save:Первая фраза", "save:Первая фраза", "save:Первая фраза Вторая фраза"), repository.operations)
    }

    @Test fun `new recording after deletion survives a slow clear`() = runTest {
        val releaseClear = CompletableDeferred<Unit>()
        val repository = Repository("Удалённый текст").apply { beforeClear = { releaseClear.await() } }
        val persistence = VoiceDraftPersistence(repository, backgroundScope) { fail(it) }

        persistence.clear()
        runCurrent()
        persistence.save("Новый черновик")
        runCurrent()
        assertEquals("Удалённый текст", repository.text)

        releaseClear.complete(Unit)
        runCurrent()
        assertEquals(listOf("clear", "save:Новый черновик"), repository.operations)
        assertEquals("Новый черновик", repository.text)
    }

    @Test fun `deletion invalidates a restore that has already read old text`() = runTest {
        val releaseLoad = CompletableDeferred<Unit>()
        val repository = Repository("Не возвращать после крестика").apply { beforeLoad = { releaseLoad.await() } }
        val persistence = VoiceDraftPersistence(repository, backgroundScope) { fail(it) }
        var restored: String? = "not completed"
        val restore = backgroundScope.launch { restored = persistence.restore() }
        runCurrent()

        persistence.clear()
        runCurrent()
        releaseLoad.complete(Unit)
        runCurrent()

        assertTrue(restore.isCompleted)
        assertNull(restored)
        assertEquals("", repository.text)
    }

    @Test fun `deletion before restore starts cannot bring back old text`() = runTest {
        val repository = Repository("Не возвращать после крестика")
        val persistence = VoiceDraftPersistence(repository, backgroundScope) { fail(it) }

        persistence.clear()
        assertNull(persistence.restore())
        runCurrent()
        assertEquals("", repository.text)
    }

    @Test fun `obsolete save failure does not override deletion`() = runTest {
        val releaseSave = CompletableDeferred<Unit>()
        val repository = Repository().apply {
            beforeSave = { releaseSave.await(); throw IOException("disk error") }
        }
        val errors = mutableListOf<String>()
        val persistence = VoiceDraftPersistence(repository, backgroundScope, errors::add)

        persistence.save("Старый текст")
        runCurrent()
        persistence.clear()
        releaseSave.complete(Unit)
        runCurrent()

        assertEquals(listOf("clear"), repository.operations)
        assertEquals("", repository.text)
        assertTrue(errors.isEmpty())
    }

    @Test fun `failed deletion is reported and a later new recording can be saved`() = runTest {
        val repository = Repository("Старый текст").apply { beforeClear = { throw IOException("disk error") } }
        val errors = mutableListOf<String>()
        val persistence = VoiceDraftPersistence(repository, backgroundScope, errors::add)

        persistence.clear()
        runCurrent()
        assertEquals(listOf("Не удалось удалить голосовой черновик"), errors)

        persistence.save("Новый черновик")
        runCurrent()
        assertEquals("Новый черновик", repository.text)
    }

    @Test fun `acknowledged text cannot be published before the storage write completes`() = runTest {
        val release = CompletableDeferred<Unit>()
        val repository = Repository("Старая фраза").apply { beforeSave = { release.await() } }
        val persistence = VoiceDraftPersistence(repository, backgroundScope) { fail(it) }
        var displayed = repository.text
        val publish = backgroundScope.launch {
            if (persistence.saveAndAwait("Новая фраза")) displayed = "Новая фраза"
        }
        runCurrent()
        assertFalse(publish.isCompleted)
        assertEquals("Старая фраза", displayed)
        release.complete(Unit)
        runCurrent()
        assertTrue(publish.isCompleted)
        assertEquals("Новая фраза", repository.text)
        assertEquals(repository.text, displayed)
    }

    @Test fun `failed write does not acknowledge new text`() = runTest {
        val repository = Repository("Сохранённая фраза").apply { beforeSave = { throw IOException("full") } }
        val errors = mutableListOf<String>()
        val persistence = VoiceDraftPersistence(repository, backgroundScope, errors::add)
        val result = backgroundScope.async { persistence.saveAndAwait("Не записано") }
        runCurrent()
        assertFalse(result.await())
        assertEquals("Сохранённая фраза", repository.text)
        assertEquals(listOf("Не удалось сохранить голосовой черновик"), errors)
    }

    @Test fun `archive operation waits for older writes and no older write recreates current text`() = runTest {
        val release = CompletableDeferred<Unit>()
        val repository = Repository().apply { beforeSave = { release.await() } }
        val persistence = VoiceDraftPersistence(repository, backgroundScope) { fail(it) }
        persistence.save("Архивируемый текст")
        runCurrent()
        val archive = backgroundScope.async {
            persistence.ordered {
                assertEquals("Архивируемый текст", repository.text)
                repository.clearDraft()
            }
        }
        runCurrent()
        assertFalse(archive.isCompleted)
        release.complete(Unit)
        runCurrent()
        assertTrue(archive.isCompleted)
        assertEquals(listOf("save:Архивируемый текст", "clear"), repository.operations)
        assertEquals("", repository.text)
    }

    @Test fun `restore failure stays retryable without writing an empty draft`() = runTest {
        val repository = Repository("Восстановить после ошибки").apply { beforeLoad = { throw IOException("read") } }
        val errors = mutableListOf<String>()
        val persistence = VoiceDraftPersistence(repository, backgroundScope, errors::add)
        assertNull(persistence.restore())
        assertTrue(persistence.restoreFailed)
        repository.beforeLoad = {}
        assertEquals(repository.text, persistence.restore())
        assertFalse(persistence.restoreFailed)
        assertTrue(repository.operations.isEmpty())
        assertEquals(1, errors.size)
    }
}
