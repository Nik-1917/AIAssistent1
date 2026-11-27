package com.example.aiassistent1

import android.app.ActivityManager
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.aiassistent1.di.AppModule
import com.example.aiassistent1.domain.model.AppDestination
import com.example.aiassistent1.domain.model.AppNavigationState
import com.example.aiassistent1.presentation.viewmodel.ChatViewModel
import com.example.aiassistent1.presentation.viewmodel.ModelAvailability
import com.example.aiassistent1.presentation.viewmodel.ModelStartup
import java.io.File
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CalendarNavigationInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val settings get() = AppModule.provideSettingsRepository(app)
    private lateinit var originalPage: AppNavigationState
    private lateinit var originalModel: String
    private var fixture: File? = null
    private lateinit var activity: MainActivity
    private lateinit var vm: ChatViewModel

    @Before fun prepareModel() {
        originalPage = runBlocking { settings.navigationState.first() }
        originalModel = runBlocking { settings.selectedModel.filterNotNull().first() }
        val directory = requireNotNull(app.getExternalFilesDir("models"))
        if (InstrumentationRegistry.getArguments().getString("startupFixture") == "true" ||
            originalModel.isBlank() || !File(directory, originalModel).isFile) {
            directory.mkdirs()
            val file = File.createTempFile("startup-test-", ".gguf", directory)
            fixture = file
            // Android's asset merger expands the checked-in .gz fixture.
            instrumentation.context.assets.open("model-startup.gguf").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            runBlocking { settings.setSelectedModel(file.name) }
        }
    }

    @After fun restore() {
        if (::activity.isInitialized && !activity.isDestroyed) {
            instrumentation.runOnMainSync { activity.finishAndRemoveTask() }
            await("закрытие Activity") { activity.isDestroyed }
        }
        runBlocking {
            if (::originalPage.isInitialized) settings.setNavigationState(originalPage)
            fixture?.let { file ->
                settings.setSelectedModel(originalModel)
                assertTrue(file.delete())
            }
        }
    }

    // Use the launcher so that Android allows a foreground session, including a return from Home.
    private fun launchApp() {
        val component = requireNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName)?.component)
        val launch = instrumentation.uiAutomation.executeShellCommand(
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${component.flattenToString()}"
        )
        ParcelFileDescriptor.AutoCloseInputStream(launch).bufferedReader().use {
            val output = it.readText()
            assertTrue(output, output.contains("Starting:") && !output.contains("Error:"))
        }
    }

    private fun resumedActivity(): MainActivity? {
        var result: MainActivity? = null
        instrumentation.runOnMainSync {
            result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().singleOrNull()
        }
        return result
    }

    private fun await(description: String, timeout: Long = 20_000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) {
            if (SystemClock.elapsedRealtime() >= end) {
                val state = if (::vm.isInitialized) vm.uiState.value else null
                fail("Истекло время ожидания: $description. ${state?.navigationState}, ${state?.selectedModel}, " +
                    "${state?.modelAvailability}, ${state?.modelStartup}")
            }
            SystemClock.sleep(100)
        }
    }

    private fun label(text: String) = hasText(text) or hasContentDescription(text)

    private fun visible(text: String) = compose.onAllNodes(label(text))
        .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    private fun click(text: String) {
        await(text) { visible(text) }
        compose.onNode(label(text)).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun openPage(page: AppNavigationState) {
        runBlocking { settings.setNavigationState(page) }
        launchApp()
        await("Activity") { resumedActivity() != null }
        activity = requireNotNull(resumedActivity())
        instrumentation.runOnMainSync { vm = ViewModelProvider(activity)[ChatViewModel::class.java] }
        val name = fixture?.name ?: originalModel
        await("выбранный GGUF и диалог") {
            val state = vm.uiState.value
            state.navigationState == page && state.selectedModel == name && state.modelAvailability == ModelAvailability.Available &&
                visible("ИИ не запущен")
        }
    }

    private fun startModel(afterForcedDeath: Boolean = false) {
        val retryUntil = if (afterForcedDeath) SystemClock.elapsedRealtime() + 360_000 else 0L
        while (true) {
            click("Запустить")
            await("загрузка GGUF", 180_000) {
                vm.uiState.value.modelStartup.let { it == ModelStartup.Ready || it is ModelStartup.Failed }
            }
            val result = vm.uiState.value.modelStartup
            if (result is ModelStartup.Failed && result.message.startsWith("Android не запустил рабочий процесс модели") &&
                SystemClock.elapsedRealtime() < retryUntil) {
                // MIUI can temporarily refuse to start a process after repeated forced deaths.
                await("ошибка и кнопка повтора") { visible(result.message) && visible("Запустить") }
                Log.i("ModelStartupTest", "Android declined the worker restart; retrying after 30s")
                SystemClock.sleep(30_000)
                continue
            }
            assertEquals(ModelStartup.Ready, result)
            break
        }
        await("закрытие диалога") { !visible("Запустить") && !visible("Выбрать модель") }
        val actual = vm.inferenceExecution.value
        assertNotNull(actual.backend)
        Log.i("ModelStartupTest", "Ready ${actual.modelName} ${actual.backend} ${actual.offloadedLayers}/${actual.totalLayers} init=${actual.initializationMillis}")
    }

    private fun workerPid(): Int = requireNotNull(app.getSystemService(ActivityManager::class.java)
        .runningAppProcesses.firstOrNull { it.uid == Process.myUid() && it.processName == "${app.packageName}:llm" }).pid

    @Test fun opensCalendarFromChatAndReturnsBack() {
        openPage(AppNavigationState(AppDestination.CHAT, isCalendarMode = false))
        startModel()
        val pid = workerPid()
        click("Открыть календарь")
        click("Вернуться в чат")
        await("кнопка календаря") { visible("Открыть календарь") }
        assertEquals(pid, workerPid())
    }

    @Test fun calendarPageSurvivesActivityRecreation() {
        openPage(AppNavigationState(AppDestination.CALENDAR, isCalendarMode = true))
        startModel()
        val previous = activity
        instrumentation.runOnMainSync { previous.recreate() }
        await("пересозданный календарь") {
            val resumed = resumedActivity()
            resumed != null && resumed !== previous && visible("Вернуться в чат")
        }
        activity = requireNotNull(resumedActivity())
        instrumentation.runOnMainSync { assertSame(vm, ViewModelProvider(activity)[ChatViewModel::class.java]) }
        click("Вернуться в чат")
        await("кнопка календаря") { visible("Открыть календарь") }
    }

    @Test fun backgroundKeepsModelAndRemovingTaskReleasesIt() {
        openPage(AppNavigationState(AppDestination.CALENDAR, isCalendarMode = true))
        startModel()
        val pid = workerPid()
        instrumentation.runOnMainSync { assertTrue(activity.moveTaskToBack(true)) }
        await("сворачивание") { activity.lifecycle.currentState == Lifecycle.State.CREATED }
        launchApp()
        await("возврат из фона") { resumedActivity() === activity && visible("Вернуться в чат") }
        assertEquals(pid, workerPid())
        assertEquals(ModelStartup.Ready, vm.uiState.value.modelStartup)
        instrumentation.runOnMainSync { activity.finishAndRemoveTask() }
        @Suppress("DEPRECATION")
        await("освобождение рабочего сервиса после удаления задачи") {
            activity.isDestroyed && app.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
                .none { it.service.className.endsWith(".LlamaWorkerService") }
        }
        openPage(AppNavigationState(AppDestination.CALENDAR, isCalendarMode = true))
        assertEquals(ModelStartup.Idle, vm.uiState.value.modelStartup)
    }

    @Test fun workerDeathReopensDialogAndAllowsRestart() {
        assumeTrue("Opt in to terminating the worker with exerciseWorkerDeath=true",
            InstrumentationRegistry.getArguments().getString("exerciseWorkerDeath") == "true")
        openPage(AppNavigationState(AppDestination.CALENDAR, isCalendarMode = true))
        startModel()
        val pid = workerPid()
        Process.killProcess(pid)
        await("утрата рабочего процесса") { vm.uiState.value.modelStartup == ModelStartup.Idle && visible("ИИ не запущен") }
        startModel(afterForcedDeath = true)
        assertNotEquals(pid, workerPid())
    }
}
