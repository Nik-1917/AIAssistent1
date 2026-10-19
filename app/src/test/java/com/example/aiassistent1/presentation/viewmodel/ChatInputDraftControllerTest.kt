package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.domain.interfaces.ChatInputDraftRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ChatInputDraftControllerTest {
    private class Repository(var text: String = "") : ChatInputDraftRepository {
        val writes = mutableListOf<String>()
        var beforeLoad: suspend () -> Unit = {}
        var beforeSave: suspend (String) -> Unit = {}
        override suspend fun load(): String {
            beforeLoad()
            return text
        }
        override suspend fun save(text: String) {
            beforeSave(text)
            this.text = text
            writes += text
        }
    }

    @Test fun `restoring blocks edits and sending until the saved text is available`() = runTest {
        val ready = CompletableDeferred<Unit>()
        val repository = Repository("  Сохранённый\nтекст  ").apply { beforeLoad = { ready.await() } }
        val controller = ChatInputDraftController(repository, backgroundScope)
        runCurrent()

        controller.updateText("")
        controller.send { fail("An unloaded draft must not be sent"); true }
        assertFalse(controller.state.value.isLoaded)
        assertTrue(repository.writes.isEmpty())

        ready.complete(Unit)
        runCurrent()
        assertTrue(controller.state.value.isLoaded)
        assertEquals(repository.text, controller.state.value.text)
        assertTrue(repository.writes.isEmpty())
    }

    @Test fun `typing persists without a background or stop lifecycle event`() = runTest {
        val repository = Repository()
        val controller = ChatInputDraftController(repository, backgroundScope)
        runCurrent()

        val text = "  Полный текст 🙂\n".repeat(10_000)
        controller.updateText(text)
        assertEquals(text, controller.state.value.text)
        runCurrent()
        assertEquals(text, repository.text)

        val restored = ChatInputDraftController(repository, backgroundScope)
        runCurrent()
        assertEquals(text, restored.state.value.text)
    }

    @Test fun `rejected send retains input and accepted send clears stored input`() = runTest {
        val repository = Repository("  Незаконченный текст\n")
        val controller = ChatInputDraftController(repository, backgroundScope)
        runCurrent()
        val text = repository.text

        controller.send { assertEquals(text, it); false }
        runCurrent()
        assertEquals(text, controller.state.value.text)
        assertEquals(text, repository.text)

        controller.send { assertEquals(text, it); true }
        assertEquals("", controller.state.value.text)
        runCurrent()
        assertEquals("", repository.text)
    }

    @Test fun `slow old write cannot resurrect cleared text`() = runTest {
        val releaseWrite = CompletableDeferred<Unit>()
        val repository = Repository().apply {
            beforeSave = { text -> if (text == "Первый") releaseWrite.await() }
        }
        val controller = ChatInputDraftController(repository, backgroundScope)
        runCurrent()
        controller.updateText("Первый")
        runCurrent()
        controller.updateText("Второй")
        controller.send { assertEquals("Второй", it); true }
        runCurrent()
        assertTrue(repository.writes.isEmpty())

        releaseWrite.complete(Unit)
        runCurrent()
        assertEquals(listOf("Первый", ""), repository.writes)
        assertEquals("", repository.text)
        assertEquals("", controller.state.value.text)
    }

    @Test fun `editing after clearing wins over an older queued clear`() = runTest {
        val releaseWrite = CompletableDeferred<Unit>()
        val repository = Repository().apply { beforeSave = { releaseWrite.await() } }
        val controller = ChatInputDraftController(repository, backgroundScope)
        runCurrent()
        controller.updateText("Отправленный")
        runCurrent()
        controller.send { true }
        controller.updateText("Следующий черновик")
        releaseWrite.complete(Unit)
        runCurrent()

        assertEquals("Следующий черновик", repository.text)
        assertEquals("Следующий черновик", controller.state.value.text)
    }

    @Test fun `storage read failure cannot erase unread text and can be retried`() = runTest {
        val repository = Repository("Не потерять").apply { beforeLoad = { throw IOException("read") } }
        val controller = ChatInputDraftController(repository, backgroundScope)
        runCurrent()
        assertFalse(controller.state.value.isLoaded)
        assertNotNull(controller.state.value.storageError)
        controller.updateText("")
        assertTrue(repository.writes.isEmpty())

        repository.beforeLoad = {}
        controller.retryStorage()
        runCurrent()
        assertTrue(controller.state.value.isLoaded)
        assertEquals("Не потерять", controller.state.value.text)
        assertNull(controller.state.value.storageError)
    }

    @Test fun `save failure retains live text and retry writes its latest version`() = runTest {
        val repository = Repository().apply { beforeSave = { throw IOException("write") } }
        val controller = ChatInputDraftController(repository, backgroundScope)
        runCurrent()
        controller.updateText("Последний текст")
        runCurrent()
        assertEquals("Последний текст", controller.state.value.text)
        assertNotNull(controller.state.value.storageError)

        repository.beforeSave = {}
        controller.retryStorage()
        runCurrent()
        assertEquals("Последний текст", repository.text)
        assertNull(controller.state.value.storageError)
    }

    @Test fun `closing a screen observer does not cancel its pending save`() = runTest {
        val releaseWrite = CompletableDeferred<Unit>()
        val repository = Repository().apply { beforeSave = { releaseWrite.await() } }
        val controller = ChatInputDraftController(repository, backgroundScope)
        val screen = backgroundScope.launch { controller.state.collect {} }
        runCurrent()
        controller.updateText("До закрытия экрана")
        runCurrent()
        screen.cancel()
        releaseWrite.complete(Unit)
        runCurrent()

        assertEquals("До закрытия экрана", repository.text)
        assertEquals("До закрытия экрана", controller.state.value.text)
    }
}
