package com.example.aiassistent1.presentation.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.*
import androidx.paging.compose.*
import com.example.aiassistent1.data.local.ConferenceEntity
import com.example.aiassistent1.di.AppModule
import com.example.aiassistent1.presentation.viewmodel.ChatViewModel
import com.example.aiassistent1.service.ConferenceService
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConferenceScreen(chatViewModel: ChatViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { AppModule.provideConferenceRepository(context) }
    val manager = remember { AppModule.provideConferenceManager(context) }
    val recording by manager.state.collectAsStateWithLifecycle()
    val chatState by chatViewModel.uiState.collectAsStateWithLifecycle()
    val summaryState by chatViewModel.summaryState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable(selectedId) { mutableStateOf("") }
    var debouncedSearch by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ConferenceEntity?>(null) }
    var exportSource by remember { mutableStateOf<ConferenceEntity?>(null) }

    LaunchedEffect(search, selectedId) { delay(250); debouncedSearch = search }
    LaunchedEffect(Unit) {
        if (!recording.recording && !recording.finishing) withContext(Dispatchers.IO) { repository.recoverInterrupted() }
    }

    val player = remember { ConferencePlayer(context) }
    val playerState by player.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { player.close() } }
    LaunchedEffect(selectedId) { player.stop() }
    LaunchedEffect(recording.recording) { if (recording.recording) player.stop() }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mpeg")) { uri ->
        val source = exportSource
        if (uri != null && source != null) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val input = if (source.filePath.startsWith("content:")) context.contentResolver.openInputStream(Uri.parse(source.filePath))
                            else File(source.filePath).inputStream()
                        checkNotNull(input).use { audio ->
                            checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = audio.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                    }
                    error = "Аудиофайл экспортирован"
                } catch (e: Exception) {
                    withContext(NonCancellable + Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) } }
                    if (e is CancellationException) throw e
                    error = e.message ?: "Не удалось экспортировать файл"
                } finally { busy = false }
            }
        }
    }

    fun startRecording() {
        player.stop()
        chatViewModel.prepareForConference()
        scope.launch {
            try { ConferenceService.start(context, title.ifBlank { "Конференция" }); error = null }
            catch (e: Exception) { error = e.message ?: "Не удалось начать запись" }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
        else error = "Для записи необходимо разрешение на микрофон"
    }
    fun back() {
        if (busy) return
        if (selectedId != null) selectedId = null else onClose()
    }

    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = ::back)
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                TopAppBar(title = { Text(if (selectedId == null) "Конференции" else "Запись и транскрипт") },
                    navigationIcon = { IconButton(onClick = ::back, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } })
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (recording.recording || recording.finishing) {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(if (recording.finishing) "Сохранение…" else "Идёт запись • ${formatConferenceTime(recording.elapsedMs)}",
                                    style = MaterialTheme.typography.titleMedium)
                                Text(recording.processingProfile, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = manager::stopConference, enabled = recording.recording) { Text("Завершить запись") }
                            }
                        }
                    }
                    (error ?: recording.error)?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error) }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val id = selectedId
                    if (id == null) {
                        OutlinedTextField(value = title, onValueChange = { title = it.take(160) },
                            label = { Text("Название новой записи") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(enabled = !recording.recording && !recording.finishing && !busy && !chatState.isProcessing,
                            onClick = {
                                val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
                                if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
                                permission.launch(permissions.toTypedArray())
                            }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text("Начать запись")
                        }
                        Text("До 3 часов • MP3 • запись продолжается при выключенном экране",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(search, { search = it.take(200) }, label = { Text("Поиск по названию") },
                            leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        val flow = remember(debouncedSearch) { Pager(PagingConfig(20, enablePlaceholders = false)) { repository.conferencesPaging(debouncedSearch) }.flow }
                        val records = flow.collectAsLazyPagingItems()
                        PagingStatus(records.loadState.refresh, records.itemCount == 0, "Записей пока нет", records::retry)
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                            items(records.itemCount, key = records.itemKey { it.id }) { index ->
                                records[index]?.let { conf ->
                                    Card(onClick = { selectedId = conf.id; search = "" }, modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(conf.title, style = MaterialTheme.typography.titleMedium)
                                            Text(SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(conf.startTime)) +
                                                " • " + formatConferenceTime(conf.duration), style = MaterialTheme.typography.bodySmall)
                                            if (conf.status == "interrupted") Text("Запись прервана", color = MaterialTheme.colorScheme.error)
                                            if (conf.status == "failed") Text("Не удалось записать", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        val detailFlow = remember(id) { repository.observeConference(id) }
                        val conf by detailFlow.collectAsStateWithLifecycle(initialValue = null)
                        val item = conf
                        if (item == null) {
                            Text("Загрузка записи…")
                        } else {
                            val flow = remember(id, debouncedSearch) {
                                Pager(PagingConfig(40, enablePlaceholders = false)) { repository.getTranscriptLinesPaging(id, debouncedSearch) }.flow
                            }
                            val lines = flow.collectAsLazyPagingItems()
                            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                                item(key = "details") {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(item.title, style = MaterialTheme.typography.titleLarge)
                                        Text(formatConferenceTime(item.duration), style = MaterialTheme.typography.bodySmall)
                                        item.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                        if (item.transcriptEvicted) Text("Транскрипт удалён при очистке кэша. Аудиозапись сохранена.")
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            FilledTonalIconButton(enabled = item.status != "recording" && !recording.recording && !recording.finishing,
                                                onClick = { player.toggle(item.filePath) }) {
                                                Icon(if (playerState.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                    if (playerState.playing) "Пауза" else "Воспроизвести")
                                            }
                                            Text(formatConferenceTime(playerState.positionMs))
                                            Spacer(Modifier.weight(1f))
                                            IconButton(enabled = item.status != "recording" && !busy, onClick = {
                                                exportSource = item; exportPicker.launch("conference_${item.id}.mp3")
                                            }) { Icon(Icons.Default.SaveAlt, "Экспортировать MP3") }
                                            IconButton(enabled = item.status != "recording" && !busy, onClick = { deleting = item }) {
                                                Icon(Icons.Default.DeleteOutline, "Удалить запись")
                                            }
                                        }
                                        playerState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                        if (item.duration > 0) Slider(value = playerState.positionMs.toFloat().coerceIn(0f, item.duration.toFloat()),
                                            onValueChange = { player.seek(item.filePath, it.toLong(), play = false) },
                                            valueRange = 0f..item.duration.toFloat(),
                                            enabled = item.status != "recording" && !recording.recording)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(enabled = item.status != "recording" && !chatState.isProcessing && !item.transcriptEvicted,
                                                onClick = { chatViewModel.summarizeConference(id) }) { Text("Создать выжимку") }
                                            if (chatState.isProcessing) TextButton(onClick = { chatViewModel.stopGeneration(false) }) { Text("Остановить") }
                                        }
                                        summaryState?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                        item.summary?.let { summary ->
                                            var expanded by rememberSaveable(id) { mutableStateOf(false) }
                                            Card(onClick = { expanded = !expanded }) {
                                                Column(Modifier.padding(12.dp)) {
                                                    Text("Выжимка", style = MaterialTheme.typography.titleSmall)
                                                    Text(summary, maxLines = if (expanded) Int.MAX_VALUE else 4, style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                        }
                                        OutlinedTextField(search, { search = it.take(200) }, label = { Text("Поиск по словам транскрипта") },
                                            leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                                item(key = "transcript-status") {
                                    PagingStatus(lines.loadState.refresh, lines.itemCount == 0, "Фразы не найдены", lines::retry)
                                }
                                items(lines.itemCount, key = lines.itemKey { it.id }) { index ->
                                    lines[index]?.let { line ->
                                        Column(Modifier.fillMaxWidth().clickable(enabled = item.status != "recording" && !recording.recording) {
                                            player.seek(item.filePath, line.timestamp, play = true)
                                        }.padding(vertical = 10.dp)) {
                                            Text(formatConferenceTime(line.timestamp), color = MaterialTheme.colorScheme.primary,
                                                style = MaterialTheme.typography.labelMedium)
                                            Text(line.text, style = MaterialTheme.typography.bodyMedium)
                                        }
                                        HorizontalDivider()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        deleting?.let { item ->
            AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Удалить запись?") },
                text = { Text("Аудиофайл, транскрипт и выжимка «${item.title}» будут удалены.") },
                confirmButton = { TextButton(onClick = {
                    deleting = null; busy = true; player.stop()
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                if (item.filePath.startsWith("content:")) {
                                    check(DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(item.filePath))) { "Не удалось удалить аудиофайл" }
                                } else {
                                    val file = File(item.filePath)
                                    val root = File(context.filesDir, "conferences").canonicalFile
                                    check(file.canonicalFile.parentFile == root) { "Запись находится вне папки конференций" }
                                    check(!file.exists() || file.delete()) { "Не удалось удалить аудиофайл" }
                                }
                                repository.deleteConference(item.id)
                            }
                            selectedId = null
                        } catch (e: Exception) { error = e.message } finally { busy = false }
                    }
                }) { Text("Удалить") } },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } })
        }
    }
}

@Composable
private fun PagingStatus(state: LoadState, empty: Boolean, emptyLabel: String, retry: () -> Unit) {
    when (state) {
        is LoadState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
        is LoadState.Error -> TextButton(onClick = retry) { Text("Ошибка загрузки. Повторить") }
        else -> if (empty) Text(emptyLabel, style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun formatConferenceTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%02d:%02d:%02d".format(Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
}
