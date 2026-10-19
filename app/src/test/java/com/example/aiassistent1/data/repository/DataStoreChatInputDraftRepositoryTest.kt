package com.example.aiassistent1.data.repository

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import com.example.aiassistent1.domain.interfaces.ChatInputDraftRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreChatInputDraftRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `full input survives closing and reopening the actual datastore file`() = runBlocking {
        val file = File(temporary.root, "input.preferences_pb")
        val text = "  Не менять пробелы\n🙂 Вторая строка\n".repeat(4_000) + "  "
        withRepository(file) { repository ->
            assertEquals("", repository.load())
            repository.save(text)
        }
        withRepository(file) { assertEquals(text, it.load()) }
    }

    @Test fun `whitespace only input is restored exactly`() = runBlocking {
        val file = File(temporary.root, "spaces.preferences_pb")
        val text = " \n\t  \n"
        withRepository(file) { it.save(text) }
        withRepository(file) { assertEquals(text, it.load()) }
    }

    @Test fun `cleared draft does not return after reopening storage`() = runBlocking {
        val file = File(temporary.root, "cleared.preferences_pb")
        withRepository(file, portableWrites = true) {
            it.save("Удалить вручную или после принятия сообщения")
            it.save("")
        }
        withRepository(file) { assertEquals("", it.load()) }
    }

    private suspend fun withRepository(
        file: File,
        portableWrites: Boolean = false,
        block: suspend (ChatInputDraftRepository) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            // Android JVM stubs select File.renameTo, which cannot replace an existing Windows file.
            // Use DataStore's real JVM/Okio storage for repeated writes, then read via FileStorage.
            val store = if (portableWrites) {
                PreferenceDataStoreFactory.create(
                    storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { file.absolutePath.toPath() },
                    scope = scope,
                )
            } else {
                PreferenceDataStoreFactory.create(scope = scope) { file }
            }
            block(DataStoreChatInputDraftRepository(store))
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
}
