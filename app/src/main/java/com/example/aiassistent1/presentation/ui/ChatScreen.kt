package com.example.aiassistent1.presentation.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import kotlin.math.abs
import com.example.aiassistent1.domain.model.ChatMessage
import com.example.aiassistent1.domain.model.ChatScrollPosition
import com.example.aiassistent1.domain.model.GenerationParams
import com.example.aiassistent1.domain.model.FloatingControlPositions
import com.example.aiassistent1.domain.model.MessageRole
import com.example.aiassistent1.domain.model.ModelState
import com.example.aiassistent1.domain.model.SpeechRate
import com.example.aiassistent1.presentation.viewmodel.ChatViewModel
import com.example.aiassistent1.presentation.viewmodel.CalendarEventDraftUiState
import com.example.aiassistent1.presentation.viewmodel.VoiceDraftState
import com.example.aiassistent1.presentation.playback.SpeechPlaybackState
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withTimeoutOrNull

private val CALENDAR_BUTTON_SIZE = 66.dp
private val CALENDAR_BUTTON_VERTICAL_OFFSET = 167.dp
private val FLOATING_CARD_GAP = 5.dp

@Composable
@OptIn(ExperimentalLayoutApi::class, FlowPreview::class)
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenCalendar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val lastMessage = uiState.messages.lastOrNull()
    var pendingMicrophoneAction by remember { mutableStateOf<MicrophoneAction?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var messageToDelete by remember { mutableStateOf<ChatMessage?>(null) }
    var showClearChatDialog by remember { mutableStateOf(false) }

    var shouldAutoScroll by rememberSaveable { mutableStateOf(false) }
    var hasRestoredChatScrollPosition by remember { mutableStateOf(false) }
    var restoredLastMessageId by remember { mutableStateOf<String?>(null) }
    val latestMessages = rememberUpdatedState(uiState.messages)

    // --- Автоматическое скрытие футера при прокрутке ---
    var isFooterVisible by rememberSaveable { mutableStateOf(true) }
    var previousIndex by remember { mutableIntStateOf(listState.firstVisibleItemIndex) }
    var previousScrollOffset by remember { mutableIntStateOf(listState.firstVisibleItemScrollOffset) }

    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.canScrollForward) {
        val currentIndex = listState.firstVisibleItemIndex
        val currentOffset = listState.firstVisibleItemScrollOffset

        if (listState.isScrollInProgress) {
            val isMovingTowardsTop = if (currentIndex != previousIndex) {
                currentIndex < previousIndex
            } else {
                currentOffset < previousScrollOffset
            }

            if (isMovingTowardsTop) {
                // Прокрутка вверх — скрываем поле
                isFooterVisible = false
            } else if (listState.canScrollForward) {
                // Прокрутка вниз, но еще не конец — скрываем
                isFooterVisible = false
            }
        }

        // Если достигли самого конца (или чат короткий, или чат пуст), всегда показываем поле
        if ((uiState.messages.isEmpty() || !listState.canScrollForward) && !uiState.isProcessing) {
            isFooterVisible = true
        }

        previousIndex = currentIndex
        previousScrollOffset = currentOffset
    }

    // Показываем футер при появлении клавиатуры или изменении состояния
    val isImeVisible = WindowInsets.isImeVisible
    LaunchedEffect(isImeVisible, uiState.isProcessing, uiState.messages.size, listState.canScrollForward) {
        if (isImeVisible) {
            isFooterVisible = true
        } else if (uiState.isProcessing) {
            isFooterVisible = false
        } else if (uiState.messages.isEmpty() || !listState.canScrollForward) {
            isFooterVisible = true
        }
    }
    // ------------------------------------------------

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            val isAtBottom = !listState.canScrollForward
            if (!isAtBottom) {
                shouldAutoScroll = false
            }
        }
    }

    LaunchedEffect(listState.canScrollForward, hasRestoredChatScrollPosition) {
        if (hasRestoredChatScrollPosition && !listState.canScrollForward) {
            shouldAutoScroll = true
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.importModel(it) }
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingMicrophoneAction
        pendingMicrophoneAction = null
        if (granted) {
            when (action) {
                MicrophoneAction.TAP -> viewModel.onMicrophoneTap()
                MicrophoneAction.LONG_PRESS -> viewModel.startVoiceDraft()
                null -> Unit
            }
        }
    }

    val requestMicrophoneAction: (MicrophoneAction) -> Unit = { action ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            when (action) {
                MicrophoneAction.TAP -> viewModel.onMicrophoneTap()
                MicrophoneAction.LONG_PRESS -> viewModel.startVoiceDraft()
            }
        } else {
            pendingMicrophoneAction = action
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    var isSpeechCardCollapsed by rememberSaveable { mutableStateOf(true) }
    var speechCardOffset by remember { mutableStateOf(Offset.Zero) }
    var speechCardSize by remember { mutableStateOf(IntSize.Zero) }
    var calendarButtonOffset by remember { mutableStateOf(Offset.Zero) }
    var hasRestoredFloatingControlPositions by remember { mutableStateOf(false) }

    LaunchedEffect(
        uiState.floatingControlPositions,
        uiState.isFloatingControlPositionsLoaded,
        context.resources.displayMetrics.density,
        hasRestoredFloatingControlPositions,
    ) {
        if (!uiState.isFloatingControlPositionsLoaded || hasRestoredFloatingControlPositions) {
            return@LaunchedEffect
        }

        val savedPositions = uiState.floatingControlPositions
        val density = context.resources.displayMetrics.density
        speechCardOffset = Offset(
            x = savedPositions.speechCardXdp * density,
            y = savedPositions.speechCardYdp * density,
        )
        calendarButtonOffset = Offset(
            x = savedPositions.calendarButtonXdp * density,
            y = savedPositions.calendarButtonYdp * density,
        )
        hasRestoredFloatingControlPositions = true
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshModelStatus()
            }
            if (event == Lifecycle.Event.ON_STOP) {
                latestMessages.value.getOrNull(listState.firstVisibleItemIndex)?.let { message ->
                    viewModel.saveChatScrollPosition(
                        ChatScrollPosition(
                            anchorMessageId = message.id,
                            offset = listState.firstVisibleItemScrollOffset,
                        ),
                    )
                }
                val isChangingConfigurations = (context as? android.app.Activity)?.isChangingConfigurations ?: false
                viewModel.stopVoiceCaptureForBackground(isChangingConfigurations)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var isExtendingScreenOn by remember { mutableStateOf(false) }
    var previousSpeechPlaybackState by remember { mutableStateOf(uiState.speechPlaybackState) }
    val isSpeechPlaybackActive = uiState.speechPlaybackState is SpeechPlaybackState.Generating ||
        uiState.speechPlaybackState is SpeechPlaybackState.Playing

    LaunchedEffect(uiState.isProcessing, uiState.speechPlaybackState) {
        val currentSpeechPlaybackState = uiState.speechPlaybackState
        if (uiState.isProcessing || isSpeechPlaybackActive) {
            isExtendingScreenOn = false
        } else if (previousSpeechPlaybackState is SpeechPlaybackState.Playing &&
            currentSpeechPlaybackState is SpeechPlaybackState.Idle
        ) {
            isExtendingScreenOn = true
            delay(SCREEN_ON_GRACE_PERIOD_MILLIS)
            isExtendingScreenOn = false
        }
        previousSpeechPlaybackState = currentSpeechPlaybackState
    }

    DisposableEffect(
        view,
        uiState.voiceDraft.isRecording,
        uiState.isProcessing,
        isSpeechPlaybackActive,
        isExtendingScreenOn,
    ) {
        view.keepScreenOn = uiState.voiceDraft.isRecording || uiState.isProcessing ||
            isSpeechPlaybackActive || isExtendingScreenOn
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        if (uiState.snackbarMessage != null) {
            delay(5000)
            viewModel.clearSnackbar()
        }
    }

    LaunchedEffect(
        uiState.isHistoryLoaded,
        uiState.isChatScrollPositionLoaded,
        uiState.chatScrollPosition,
        uiState.messages,
        hasRestoredChatScrollPosition,
    ) {
        if (!uiState.isHistoryLoaded || !uiState.isChatScrollPositionLoaded ||
            hasRestoredChatScrollPosition
        ) {
            return@LaunchedEffect
        }

        val savedPosition = uiState.chatScrollPosition
        val savedIndex = savedPosition.anchorMessageId?.let { messageId ->
            uiState.messages.indexOfFirst { message -> message.id == messageId }
                .takeIf { it >= 0 }
        }
        if (savedIndex != null) {
            shouldAutoScroll = false
            listState.scrollToItem(savedIndex, savedPosition.offset)
        }
        restoredLastMessageId = uiState.messages.lastOrNull()?.id
        hasRestoredChatScrollPosition = true
    }

    LaunchedEffect(listState, hasRestoredChatScrollPosition) {
        if (!hasRestoredChatScrollPosition) return@LaunchedEffect

        snapshotFlow {
            latestMessages.value.getOrNull(listState.firstVisibleItemIndex)?.let { message ->
                ChatScrollPosition(
                    anchorMessageId = message.id,
                    offset = listState.firstVisibleItemScrollOffset,
                )
            }
        }
            .filterNotNull()
            .distinctUntilChanged()
            .debounce(CHAT_SCROLL_POSITION_SAVE_DEBOUNCE_MILLIS)
            .collect(viewModel::saveChatScrollPosition)
    }

    LaunchedEffect(
        lastMessage?.id,
        hasRestoredChatScrollPosition,
        restoredLastMessageId,
    ) {
        if (!hasRestoredChatScrollPosition) return@LaunchedEffect

        if (lastMessage?.id != null && lastMessage.id != restoredLastMessageId) {
            shouldAutoScroll = true
        }
        if (
            shouldAutoScrollToNewestMessage(
                hasRestoredPosition = hasRestoredChatScrollPosition,
                isAutoScrollEnabled = shouldAutoScroll,
                restoredLastMessageId = restoredLastMessageId,
                lastMessageId = lastMessage?.id,
            )
        ) {
            listState.scrollToItem(uiState.messages.lastIndex, Int.MAX_VALUE)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ChatTopBar(
                modelState = uiState.modelState,
                isProcessing = uiState.isProcessing,
                hasMessages = uiState.messages.isNotEmpty(),
                isModelMissing = uiState.isModelMissing,
                selectedModel = uiState.selectedModel,
                availableModels = uiState.availableModels,
                onStop = viewModel::stopGeneration,
                onClearChat = {
                    if (uiState.showClearChatConfirmation) {
                        showClearChatDialog = true
                    } else {
                        viewModel.clearChat()
                    }
                },
                onLoadModel = { filePickerLauncher.launch("*/*") },
                onSelectModel = viewModel::selectModel,
                onOpenSettings = { showSettingsDialog = true },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
        ) {
            val density = LocalDensity.current
            val calendarButtonBottom =
                (maxHeight / 2f) + (CALENDAR_BUTTON_SIZE / 2f) - CALENDAR_BUTTON_VERTICAL_OFFSET + FLOATING_CARD_GAP
            val containerWidthPx = with(density) { maxWidth.roundToPx() }
            val containerHeightPx = with(density) { maxHeight.roundToPx() }
            val edgeGapPx = with(density) { FLOATING_CARD_GAP.roundToPx() }
            val collapsedCardSizePx = with(density) { CALENDAR_BUTTON_SIZE.roundToPx() }
            val speechCardWidthPx = speechCardSize.width.takeIf { it > 0 } ?: collapsedCardSizePx
            val speechCardHeightPx = speechCardSize.height.takeIf { it > 0 } ?: collapsedCardSizePx
            val speechCardBaseY = with(density) { calendarButtonBottom.roundToPx() }
            val minCollapsedSpeechX = -(
                (containerWidthPx - speechCardWidthPx - (edgeGapPx * 2)).coerceAtLeast(0)
            )
            val minSpeechY = edgeGapPx - speechCardBaseY
            val maxSpeechY = (
                containerHeightPx - edgeGapPx - speechCardHeightPx - speechCardBaseY
            ).coerceAtLeast(minSpeechY)
            val effectiveSpeechX = if (isSpeechCardCollapsed) {
                speechCardOffset.x.roundToInt().coerceIn(minCollapsedSpeechX, 0)
            } else {
                0
            }
            val effectiveSpeechY = speechCardOffset.y.roundToInt().coerceIn(minSpeechY, maxSpeechY)
            val calendarButtonBaseY =
                ((containerHeightPx - collapsedCardSizePx) / 2) -
                    with(density) { CALENDAR_BUTTON_VERTICAL_OFFSET.roundToPx() }
            val minCalendarButtonX = -(
                (containerWidthPx - collapsedCardSizePx - edgeGapPx).coerceAtLeast(edgeGapPx)
            )
            val maxCalendarButtonX = -edgeGapPx
            val minCalendarButtonY = edgeGapPx - calendarButtonBaseY
            val maxCalendarButtonY = (
                containerHeightPx - edgeGapPx - collapsedCardSizePx - calendarButtonBaseY
            ).coerceAtLeast(minCalendarButtonY)
            val effectiveCalendarButtonX = calendarButtonOffset.x.roundToInt()
                .coerceIn(minCalendarButtonX, maxCalendarButtonX)
            val effectiveCalendarButtonY = calendarButtonOffset.y.roundToInt()
                .coerceIn(minCalendarButtonY, maxCalendarButtonY)

            fun normalizeSpeechCardOffset(offset: Offset): Offset = Offset(
                x = if (isSpeechCardCollapsed) {
                    offset.x.coerceIn(minCollapsedSpeechX.toFloat(), 0f)
                } else {
                    0f
                },
                y = offset.y.coerceIn(minSpeechY.toFloat(), maxSpeechY.toFloat()),
            )

            fun normalizeCalendarButtonOffset(offset: Offset): Offset = Offset(
                x = offset.x.coerceIn(
                    minCalendarButtonX.toFloat(),
                    maxCalendarButtonX.toFloat(),
                ),
                y = offset.y.coerceIn(
                    minCalendarButtonY.toFloat(),
                    maxCalendarButtonY.toFloat(),
                ),
            )

            fun speechCardBounds(offset: Offset): Rect {
                val normalizedOffset = normalizeSpeechCardOffset(offset)
                val left = containerWidthPx - edgeGapPx - speechCardWidthPx + normalizedOffset.x
                val top = speechCardBaseY + normalizedOffset.y
                return Rect(
                    left = left,
                    top = top,
                    right = left + speechCardWidthPx,
                    bottom = top + speechCardHeightPx,
                )
            }

            fun calendarButtonBounds(offset: Offset): Rect {
                val normalizedOffset = normalizeCalendarButtonOffset(offset)
                val left = containerWidthPx - collapsedCardSizePx + normalizedOffset.x
                val top = calendarButtonBaseY + normalizedOffset.y
                return Rect(
                    left = left,
                    top = top,
                    right = left + collapsedCardSizePx,
                    bottom = top + collapsedCardSizePx,
                )
            }

            fun violatesFloatingCardGap(first: Rect, second: Rect): Boolean =
                first.left < second.right + edgeGapPx &&
                    first.right > second.left - edgeGapPx &&
                    first.top < second.bottom + edgeGapPx &&
                    first.bottom > second.top - edgeGapPx

            fun isSpeechCardOffsetAllowed(offset: Offset): Boolean =
                !violatesFloatingCardGap(
                    speechCardBounds(offset),
                    calendarButtonBounds(calendarButtonOffset),
                )

            fun isCalendarButtonOffsetAllowed(offset: Offset): Boolean =
                !violatesFloatingCardGap(
                    calendarButtonBounds(offset),
                    speechCardBounds(speechCardOffset),
                )

            fun constrainSpeechCardOffset(proposedOffset: Offset): Offset {
                val normalizedCurrent = normalizeSpeechCardOffset(speechCardOffset)
                val normalizedProposed = normalizeSpeechCardOffset(proposedOffset)
                if (isSpeechCardOffsetAllowed(normalizedProposed)) return normalizedProposed

                val horizontalCandidate = normalizeSpeechCardOffset(
                    Offset(normalizedProposed.x, normalizedCurrent.y),
                )
                val verticalCandidate = normalizeSpeechCardOffset(
                    Offset(normalizedCurrent.x, normalizedProposed.y),
                )
                val canMoveHorizontally = isSpeechCardOffsetAllowed(horizontalCandidate)
                val canMoveVertically = isSpeechCardOffsetAllowed(verticalCandidate)
                return when {
                    canMoveHorizontally && canMoveVertically -> {
                        if (abs(normalizedProposed.x - normalizedCurrent.x) >=
                            abs(normalizedProposed.y - normalizedCurrent.y)
                        ) {
                            horizontalCandidate
                        } else {
                            verticalCandidate
                        }
                    }
                    canMoveHorizontally -> horizontalCandidate
                    canMoveVertically -> verticalCandidate
                    else -> normalizedCurrent
                }
            }

            fun constrainCalendarButtonOffset(proposedOffset: Offset): Offset {
                val normalizedCurrent = normalizeCalendarButtonOffset(calendarButtonOffset)
                val normalizedProposed = normalizeCalendarButtonOffset(proposedOffset)
                if (isCalendarButtonOffsetAllowed(normalizedProposed)) return normalizedProposed

                val horizontalCandidate = normalizeCalendarButtonOffset(
                    Offset(normalizedProposed.x, normalizedCurrent.y),
                )
                val verticalCandidate = normalizeCalendarButtonOffset(
                    Offset(normalizedCurrent.x, normalizedProposed.y),
                )
                val canMoveHorizontally = isCalendarButtonOffsetAllowed(horizontalCandidate)
                val canMoveVertically = isCalendarButtonOffsetAllowed(verticalCandidate)
                return when {
                    canMoveHorizontally && canMoveVertically -> {
                        if (abs(normalizedProposed.x - normalizedCurrent.x) >=
                            abs(normalizedProposed.y - normalizedCurrent.y)
                        ) {
                            horizontalCandidate
                        } else {
                            verticalCandidate
                        }
                    }
                    canMoveHorizontally -> horizontalCandidate
                    canMoveVertically -> verticalCandidate
                    else -> normalizedCurrent
                }
            }

            val persistFloatingControlPositions = { savedSpeechOffset: Offset, savedCalendarOffset: Offset ->
                viewModel.saveFloatingControlPositions(
                    FloatingControlPositions(
                        speechCardXdp = with(density) { savedSpeechOffset.x.toDp().value },
                        speechCardYdp = with(density) { savedSpeechOffset.y.toDp().value },
                        calendarButtonXdp = with(density) { savedCalendarOffset.x.toDp().value },
                        calendarButtonYdp = with(density) { savedCalendarOffset.y.toDp().value },
                    )
                )
            }

            val saveFloatingControlPositions = {
                val savedSpeechOffset = constrainSpeechCardOffset(speechCardOffset)
                val savedCalendarOffset = constrainCalendarButtonOffset(calendarButtonOffset)
                speechCardOffset = savedSpeechOffset
                calendarButtonOffset = savedCalendarOffset
                persistFloatingControlPositions(savedSpeechOffset, savedCalendarOffset)
            }

            LaunchedEffect(
                hasRestoredFloatingControlPositions,
                speechCardSize,
                isSpeechCardCollapsed,
                effectiveSpeechX,
                effectiveSpeechY,
                effectiveCalendarButtonX,
                effectiveCalendarButtonY,
            ) {
                if (!hasRestoredFloatingControlPositions ||
                    speechCardSize == IntSize.Zero ||
                    isSpeechCardOffsetAllowed(speechCardOffset)
                ) {
                    return@LaunchedEffect
                }

                val currentSpeechOffset = normalizeSpeechCardOffset(speechCardOffset)
                val calendarBounds = calendarButtonBounds(calendarButtonOffset)
                val candidates = listOf(
                    normalizeSpeechCardOffset(
                        currentSpeechOffset.copy(
                            y = calendarBounds.top - edgeGapPx - speechCardHeightPx - speechCardBaseY,
                        ),
                    ),
                    normalizeSpeechCardOffset(
                        currentSpeechOffset.copy(
                            y = calendarBounds.bottom + edgeGapPx - speechCardBaseY,
                        ),
                    ),
                ).filter(::isSpeechCardOffsetAllowed)

                val resolvedSpeechOffset = candidates.minByOrNull { candidate ->
                    abs(candidate.x - currentSpeechOffset.x) + abs(candidate.y - currentSpeechOffset.y)
                } ?: return@LaunchedEffect

                speechCardOffset = resolvedSpeechOffset
                persistFloatingControlPositions(resolvedSpeechOffset, normalizeCalendarButtonOffset(calendarButtonOffset))
            }

            // Основной контейнер с навигационными отступами
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) {
                // Область сообщений
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val modelState = uiState.modelState

                    if (modelState is ModelState.Importing) {
                        ImportProgress(progress = modelState.progress)
                    }

                    if (uiState.isModelMissing && uiState.messages.isNotEmpty()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "Файл модели не найден",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }

                    if (uiState.messages.isEmpty()) {
                        EmptyConversation(
                            isModelMissing = uiState.isModelMissing,
                            onLoadModel = { filePickerLauncher.launch("*/*") },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                top = 12.dp,
                                end = 16.dp,
                                bottom = 120.dp, // Увеличено для overlay поля ввода
                            ),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            itemsIndexed(
                                items = uiState.messages,
                                key = { _, message -> message.id },
                            ) { index, message ->
                                val isLast = index == uiState.messages.lastIndex
                                val showRetry = isLast && !uiState.isProcessing && !uiState.isStopping
                                val showDelete = showRetry
                                    
                                MessageBubble(
                                    message = message,
                                    isStopping = uiState.isStopping,
                                    isStreaming = !uiState.isStopping && uiState.isProcessing &&
                                        isLast && message.role == MessageRole.ASSISTANT,
                                    smoothResponseEnabled = uiState.smoothResponseEnabled,
                                    onRetry = if (showRetry) viewModel::retry else null,
                                    onDelete = if (showDelete) {
                                        {
                                            if (uiState.showDeleteMessageConfirmation) {
                                                messageToDelete = message
                                            } else {
                                                viewModel.deleteMessage(message.id)
                                            }
                                        }
                                    } else null,
                                    onCopy = { text ->
                                        viewModel.copyToClipboard(text)
                                    },
                                    onSpeak = viewModel::speakMessage,
                                )
                            }
                        }
                    }
                }

                // Голосовой черновик (overlay)
                androidx.compose.animation.AnimatedVisibility(
                    visible = uiState.voiceDraft.isVisible && isFooterVisible,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = 110.dp),
                    enter = expandVertically(expandFrom = Alignment.Bottom),
                    exit = shrinkVertically(shrinkTowards = Alignment.Bottom),
                ) {
                    VoiceDraftCard(
                        state = uiState.voiceDraft,
                        onDelete = viewModel::deleteVoiceDraft,
                        onSend = viewModel::sendVoiceDraft,
                    )
                }

                // Панель ввода (overlay), больше не выталкивает чат
                androidx.compose.animation.AnimatedVisibility(
                    visible = isFooterVisible,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
                ) {
                    InputPanel(
                        textInputEnabled = !uiState.isProcessing &&
                            !uiState.isStopping &&
                            uiState.modelState !is ModelState.Loading &&
                            !uiState.isModelMissing &&
                            !uiState.voiceDraft.isVisible,
                        microphoneEnabled = !uiState.isProcessing &&
                            !uiState.isStopping &&
                            uiState.modelState !is ModelState.Loading &&
                            !uiState.isModelMissing,
                        isProcessing = uiState.isProcessing,
                        isVoiceMode = uiState.isVoiceMode,
                        onSend = viewModel::sendMessage,
                        onStop = viewModel::stopGeneration,
                        onVoiceTap = { requestMicrophoneAction(MicrophoneAction.TAP) },
                        onVoiceLongPress = { requestMicrophoneAction(MicrophoneAction.LONG_PRESS) },
                    )
                }
            }

            SpeechPlaybackStatusCard(
                state = uiState.speechPlaybackState,
                autoPlaybackEnabled = uiState.autoPlaybackEnabled,
                onStop = viewModel::stopSpeechPlayback,
                isCollapsed = isSpeechCardCollapsed,
                onCollapsedChange = { collapsed ->
                    isSpeechCardCollapsed = collapsed
                    if (!collapsed) {
                        speechCardOffset = constrainSpeechCardOffset(
                            speechCardOffset.copy(x = 0f),
                        )
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        IntOffset(
                            effectiveSpeechX,
                            effectiveSpeechY + speechCardBaseY,
                        )
                    }
                    .pointerInput(
                        isSpeechCardCollapsed,
                        minCollapsedSpeechX,
                        minSpeechY,
                        maxSpeechY,
                    ) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragEnd = saveFloatingControlPositions,
                            onDragCancel = saveFloatingControlPositions,
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val nextY = (speechCardOffset.y + dragAmount.y)
                                    .coerceIn(minSpeechY.toFloat(), maxSpeechY.toFloat())
                                val proposedOffset = if (isSpeechCardCollapsed) {
                                    Offset(
                                        x = (speechCardOffset.x + dragAmount.x)
                                            .coerceIn(minCollapsedSpeechX.toFloat(), 0f),
                                        y = nextY,
                                    )
                                } else {
                                    speechCardOffset.copy(y = nextY)
                                }
                                speechCardOffset = constrainSpeechCardOffset(proposedOffset)
                            }
                        )
                    }
                    .padding(horizontal = FLOATING_CARD_GAP)
                    .onSizeChanged { speechCardSize = it },
            )

            Card(
                onClick = onOpenCalendar,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset {
                        IntOffset(
                            effectiveCalendarButtonX,
                            effectiveCalendarButtonY - CALENDAR_BUTTON_VERTICAL_OFFSET.roundToPx()
                        )
                    }
                    .pointerInput(
                        minCalendarButtonX,
                        maxCalendarButtonX,
                        minCalendarButtonY,
                        maxCalendarButtonY,
                    ) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragEnd = saveFloatingControlPositions,
                            onDragCancel = saveFloatingControlPositions,
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val proposedOffset = Offset(
                                    x = (calendarButtonOffset.x + dragAmount.x).coerceIn(
                                        minCalendarButtonX.toFloat(),
                                        maxCalendarButtonX.toFloat(),
                                    ),
                                    y = (calendarButtonOffset.y + dragAmount.y).coerceIn(
                                        minCalendarButtonY.toFloat(),
                                        maxCalendarButtonY.toFloat(),
                                    ),
                                )
                                calendarButtonOffset = constrainCalendarButtonOffset(proposedOffset)
                            }
                        )
                    }
                    .size(CALENDAR_BUTTON_SIZE)
                    .semantics { contentDescription = "Открыть календарь" },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(27.dp)
                        )
                    }
                }
            }

            // Уведомление о копировании
            androidx.compose.animation.AnimatedVisibility(
                visible = uiState.snackbarMessage != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Text(
                        text = uiState.snackbarMessage ?: "",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }

    if (showSettingsDialog) {
        ModelSettingsDialog(
            params = uiState.modelParams,
            smoothResponseEnabled = uiState.smoothResponseEnabled,
            systemPromptEnabled = uiState.systemPromptEnabled,
            dialogueModeEnabled = uiState.dialogueModeEnabled,
            autoPlaybackEnabled = uiState.autoPlaybackEnabled,
            speechRate = uiState.speechRate,
            onDismiss = { showSettingsDialog = false },
            onSave = { updatedParams, smoothResponseEnabled, systemPromptEnabled, dialogueModeEnabled, autoPlaybackEnabled, speechRate ->
                viewModel.updateModelParams(updatedParams)
                viewModel.setSmoothResponseEnabled(smoothResponseEnabled)
                viewModel.setSystemPromptEnabled(systemPromptEnabled)
                viewModel.setDialogueModeEnabled(dialogueModeEnabled)
                viewModel.setAutoPlaybackEnabled(autoPlaybackEnabled)
                viewModel.setSpeechRate(speechRate)
                showSettingsDialog = false
            }
        )
    }

    uiState.calendarEventDraft?.let { draft ->
        CalendarEventDraftDialog(
            draft = draft,
            onValueChange = viewModel::updateCalendarDraftInput,
            onSubmit = viewModel::submitCalendarDraftField,
            onVoiceInput = viewModel::startCalendarDraftVoiceInput,
            onDismiss = viewModel::cancelCalendarEventDraft,
        )
    }

    if (showClearChatDialog) {
        var dontAskAgain by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showClearChatDialog = false },
            title = { Text("Очистить чат?") },
            text = {
                Column {
                    Text("Все сообщения будут удалены навсегда. Это действие нельзя отменить.")
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { dontAskAgain = !dontAskAgain }
                    ) {
                        Checkbox(
                            checked = dontAskAgain,
                            onCheckedChange = { dontAskAgain = it }
                        )
                        Text(
                            text = "Больше не спрашивать",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (dontAskAgain) viewModel.setShowClearChatConfirmation(false)
                        viewModel.clearChat()
                        showClearChatDialog = false
                    }
                ) {
                    Text("Очистить", color = Color(0xFFF44336))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearChatDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    if (messageToDelete != null) {
        var dontAskAgain by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { messageToDelete = null },
            title = { Text("Удалить сообщение?") },
            text = {
                Column {
                    Text("Это действие нельзя будет отменить. Контекст диалога может измениться.")
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { dontAskAgain = !dontAskAgain }
                    ) {
                        Checkbox(
                            checked = dontAskAgain,
                            onCheckedChange = { dontAskAgain = it }
                        )
                        Text(
                            text = "Больше не спрашивать",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (dontAskAgain) viewModel.setShowDeleteMessageConfirmation(false)
                        messageToDelete?.let { viewModel.deleteMessage(it.id) }
                        messageToDelete = null
                    }
                ) {
                    Text("Удалить", color = Color(0xFFF44336))
                }
            },
            dismissButton = {
                TextButton(onClick = { messageToDelete = null }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun CalendarEventDraftDialog(
    draft: CalendarEventDraftUiState,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onVoiceInput: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.isComplete) "Создать событие?" else "Заполните данные события") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Сохранённые поля", style = MaterialTheme.typography.labelLarge)
                draft.title?.let { Text("Название: $it") }
                draft.startsAt?.let { Text("Дата и время: $it") }
                draft.durationMinutes?.let { Text("Длительность: $it мин") }

                if (!draft.isComplete) {
                    Spacer(modifier = Modifier.height(16.dp))
                    val field = requireNotNull(draft.activeField)
                    Text(field.label, style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = draft.input,
                        onValueChange = onValueChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !draft.isFormatting,
                        isError = draft.error != null,
                        supportingText = draft.error?.let { { Text(it) } },
                        trailingIcon = {
                            IconButton(
                                onClick = onVoiceInput,
                                enabled = !draft.isFormatting && !draft.isVoiceInputActive,
                            ) {
                                Icon(
                                    imageVector = if (draft.isVoiceInputActive) Icons.Filled.MicOff else Icons.Filled.Mic,
                                    contentDescription = "Голосовой ввод поля ${field.label}",
                                )
                            }
                        },
                    )
                    if (draft.isFormatting) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Форматирование значения…")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSubmit,
                enabled = !draft.isFormatting && (draft.isComplete || draft.input.isNotBlank()),
            ) {
                Text(if (draft.isComplete) "Создать событие" else "Далее")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        },
    )
}

@Composable
private fun ImportProgress(progress: Float) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Копирование модели: ${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            strokeCap = StrokeCap.Round
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ChatTopBar(
    modelState: ModelState,
    isProcessing: Boolean,
    hasMessages: Boolean,
    isModelMissing: Boolean,
    selectedModel: String,
    availableModels: List<String>,
    onStop: () -> Unit,
    onClearChat: () -> Unit,
    onLoadModel: () -> Unit,
    onSelectModel: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Column(
                modifier = Modifier.clickable { showMenu = true }
            ) {
                Text(text = "AI Assistant")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isModelMissing) "Модель не найдена" else modelState.label(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "($selectedModel)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    availableModels.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model) },
                            onClick = {
                                onSelectModel(model)
                                showMenu = false
                            },
                            trailingIcon = {
                                if (model == selectedModel) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                }
                            }
                        )
                    }
                }
            }
        },
        actions = {
            if (isModelMissing && modelState !is ModelState.Importing) {
                IconButton(onClick = onLoadModel) {
                    Icon(Icons.Default.CloudUpload, contentDescription = "Загрузить модель")
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Настройки модели")
            }
            if (hasMessages) {
                IconButton(onClick = onClearChat) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Удалить чат",
                        tint = Color(0xFF2196F3)
                    )
                }
            }
            if (isProcessing) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = "Остановить генерацию")
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
private fun EmptyConversation(
    isModelMissing: Boolean,
    onLoadModel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (isModelMissing) "Для начала работы нужно загрузить модель" else "Напишите сообщение, чтобы начать разговор",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            if (isModelMissing) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Выберите файл модели .gguf для импорта",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onLoadModel) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Выбрать файл .gguf")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    isStopping: Boolean = false,
    isStreaming: Boolean = false,
    smoothResponseEnabled: Boolean,
    onRetry: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onCopy: (String) -> Unit,
    onSpeak: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == MessageRole.USER
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleShape = RoundedCornerShape(12.dp)
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val haptic = LocalHapticFeedback.current

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = alignment) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.84f)
                .graphicsLayer {
                    shape = bubbleShape
                    clip = true
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            onSpeak(message.content)
                        },
                        onLongPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onCopy(message.content)
                        }
                    )
                }
                .animateContentSize(animationSpec = tween(durationMillis = 120)),
            shape = bubbleShape,
            colors = CardDefaults.cardColors(containerColor = containerColor),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        text = if (isUser) "Вы" else "Ассистент",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val messageText = if (message.content.isBlank()) {
                        if (isStopping) "Остановка..." else "Думаю..."
                    } else {
                        message.content
                    }
                    if (smoothResponseEnabled) {
                        StreamingMessageText(
                            text = message.content,
                            isStreaming = isStreaming,
                            placeholderText = when {
                                isStreaming -> "Думаю..."
                                message.content.isBlank() -> messageText
                                else -> null
                            },
                        )
                    } else {
                        Text(
                            text = messageText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (message.isInterrupted) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Генерация остановлена",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                if (onDelete != null) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Удалить сообщение",
                            tint = Color(0xFF2196F3),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                if (onRetry != null) {
                    IconButton(
                        onClick = onRetry,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Повторить генерацию",
                            tint = Color(0xFF2196F3),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamingMessageText(
    text: String,
    isStreaming: Boolean,
    placeholderText: String?,
) {
    val enteringAlpha = remember { Animatable(1f) }
    var settledText by remember { mutableStateOf("") }
    var enteringText by remember { mutableStateOf("") }
    val latestText by rememberUpdatedState(text)
    val visibleText = settledText + enteringText
    val scrollState = rememberScrollState()
    val textColor = MaterialTheme.colorScheme.onSurface

    LaunchedEffect(isStreaming) {
        if (!isStreaming) {
            settledText = latestText
            enteringText = ""
            enteringAlpha.snapTo(1f)
            return@LaunchedEffect
        }

        while (isStreaming) {
            val currentVisible = settledText + enteringText
            when {
                !latestText.startsWith(currentVisible) -> {
                    settledText = latestText
                    enteringText = ""
                    enteringAlpha.snapTo(1f)
                }
                enteringText.isNotEmpty() -> {
                    enteringAlpha.animateTo(1f, tween(WORD_REVEAL_DURATION_MILLIS, easing = FastOutSlowInEasing))
                    settledText += enteringText
                    enteringText = ""
                }
                else -> {
                    val nextSegment = latestText.drop(currentVisible.length).nextCompleteWordOrNull()
                    if (nextSegment == null) {
                        delay(STREAM_POLL_INTERVAL_MILLIS)
                    } else {
                        enteringText = nextSegment
                        enteringAlpha.snapTo(0f)
                    }
                }
            }
        }
    }

    LaunchedEffect(visibleText, isStreaming) {
        if (isStreaming) scrollState.animateScrollTo(scrollState.maxValue, tween(STREAM_SCROLL_DURATION_MILLIS))
    }

    val annotatedText = buildAnnotatedString {
        append(settledText)
        if (enteringText.isNotEmpty()) {
            pushStyle(
                SpanStyle(
                    color = textColor.copy(alpha = enteringAlpha.value),
                    baselineShift = BaselineShift(-0.12f * (1f - enteringAlpha.value)),
                ),
            )
            append(enteringText)
            pop()
        }
    }

    val textModifier = if (isStreaming) {
        Modifier
            .fillMaxWidth()
            .height(STREAMING_TEXT_VIEWPORT_HEIGHT)
            .verticalScroll(scrollState)
    } else {
        Modifier.fillMaxWidth()
    }
    val showPlaceholder = visibleText.isBlank() && placeholderText != null
    Box(modifier = textModifier) {
        AnimatedVisibility(
            visible = showPlaceholder,
            enter = fadeIn(animationSpec = tween(THINKING_INDICATOR_DURATION_MILLIS)),
            exit = fadeOut(animationSpec = tween(THINKING_INDICATOR_DURATION_MILLIS)),
        ) {
            Text(
                text = placeholderText.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = textColor,
            )
        }
        AnimatedVisibility(
            visible = !showPlaceholder,
            enter = fadeIn(animationSpec = tween(THINKING_INDICATOR_DURATION_MILLIS)),
            exit = fadeOut(animationSpec = tween(THINKING_INDICATOR_DURATION_MILLIS)),
        ) {
            Text(
                text = annotatedText,
                style = MaterialTheme.typography.bodyLarge,
                color = textColor,
            )
        }
    }
}

private fun String.nextCompleteWordOrNull(): String? {
    val boundary = indexOfFirst(Char::isWhitespace)
    return if (boundary < 0) null else substring(0, boundary + 1)
}

private const val WORD_REVEAL_DURATION_MILLIS = 140
private const val STREAM_POLL_INTERVAL_MILLIS = 32L
private const val STREAM_SCROLL_DURATION_MILLIS = 180
private const val THINKING_INDICATOR_DURATION_MILLIS = 160
private const val CHAT_SCROLL_POSITION_SAVE_DEBOUNCE_MILLIS = 500L
private const val SCREEN_ON_GRACE_PERIOD_MILLIS = 25_000L
private val STREAMING_TEXT_VIEWPORT_HEIGHT = 144.dp

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun InputPanel(
    textInputEnabled: Boolean,
    microphoneEnabled: Boolean,
    isProcessing: Boolean,
    isVoiceMode: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onVoiceTap: () -> Unit,
    onVoiceLongPress: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val canSend = textInputEnabled && text.trim().isNotEmpty()
    val isKeyboardVisible = WindowInsets.isImeVisible

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
            .imePadding()
            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(MAX_MESSAGE_LENGTH) },
            modifier = Modifier
                .weight(1f)
                .offset(y = 0.dp),
            enabled = textInputEnabled,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                disabledContainerColor = MaterialTheme.colorScheme.surface,
            ),
            placeholder = { Text("Сообщение") },
            supportingText = {
                Text(
                    text = "${text.length}/$MAX_MESSAGE_LENGTH",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                    fontSize = 12.sp,
                )
            },
            shape = RoundedCornerShape(12.dp),
            maxLines = if (isKeyboardVisible) 8 else 1,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (canSend) {
                        onSend(text)
                        text = ""
                    }
                }
            )
        )
        
        if (!isKeyboardVisible) {
            Spacer(modifier = Modifier.width(8.dp))
            VoiceMicrophoneButton(
                enabled = microphoneEnabled,
                isVoiceMode = isVoiceMode,
                onTap = onVoiceTap,
                onLongPress = onVoiceLongPress,
            )
            if (isProcessing) {
                IconButton(
                    onClick = onStop,
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Остановить генерацию")
                }
            } else {
                IconButton(
                    enabled = canSend,
                    onClick = {
                        onSend(text)
                        text = ""
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить сообщение")
                }
            }
        }
    }
}

@Composable
private fun VoiceDraftCard(
    state: VoiceDraftState,
    onDelete: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    LaunchedEffect(state.text) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isRecording) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = if (state.isRecording) "Запись" else "Голосовой черновик",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Удалить голосовой черновик",
                    )
                }
            }
            Text(
                text = state.text.ifBlank { "Говорите..." },
                style = MaterialTheme.typography.bodyLarge,
                color = if (state.text.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp, max = 144.dp)
                    .verticalScroll(scrollState)
                    .padding(top = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(
                    enabled = state.text.isNotBlank(),
                    onClick = onSend,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Отправить голосовой черновик",
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceMicrophoneButton(
    enabled: Boolean,
    isVoiceMode: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isPressed by remember { mutableStateOf(false) }
    var longPressTriggered by remember { mutableStateOf(false) }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val haptic = LocalHapticFeedback.current
    val holdProgress by animateFloatAsState(
        targetValue = if (isPressed && !longPressTriggered) 1f else 0f,
        animationSpec = tween(durationMillis = VOICE_DRAFT_LONG_PRESS_TIMEOUT_MILLIS.toInt()),
        label = "voiceDraftHoldProgress",
    )
    val contentDescription = if (isVoiceMode) {
        "Выключить голосовой режим"
    } else {
        "Включить голосовой режим или удерживать для голосового черновика"
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
                onClick { currentOnTap(); true }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    longPressTriggered = false
                    var gestureFinished = false
                    val up = withTimeoutOrNull(VOICE_DRAFT_LONG_PRESS_TIMEOUT_MILLIS) {
                        waitForUpOrCancellation().also { gestureFinished = true }
                    }
                    if (!gestureFinished) {
                        longPressTriggered = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnLongPress()
                        waitForUpOrCancellation()
                    } else if (up != null) {
                        currentOnTap()
                    }
                    isPressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (isPressed && !longPressTriggered) {
            CircularProgressIndicator(
                progress = { holdProgress },
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 2.dp,
            )
        }
        Icon(
            imageVector = if (isVoiceMode) Icons.Default.MicOff else Icons.Default.Mic,
            contentDescription = null,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            }
        )
    }
}

@Composable
fun ModelSettingsDialog(
    params: GenerationParams,
    smoothResponseEnabled: Boolean,
    systemPromptEnabled: Boolean,
    dialogueModeEnabled: Boolean,
    autoPlaybackEnabled: Boolean,
    speechRate: Float,
    onDismiss: () -> Unit,
    onSave: (GenerationParams, Boolean, Boolean, Boolean, Boolean, Float) -> Unit,
) {
    var temperature by remember { mutableStateOf(params.temperature) }
    var contextSize by remember { mutableStateOf(params.contextSize.toFloat()) }
    var maxTokens by remember { mutableStateOf(params.maxTokens.toFloat()) }
    var topP by remember { mutableStateOf(params.topP) }
    var repeatPenalty by remember { mutableStateOf(params.repeatPenalty) }
    var smoothResponse by remember { mutableStateOf(smoothResponseEnabled) }
    var systemPrompt by remember { mutableStateOf(systemPromptEnabled) }
    var dialogueMode by remember { mutableStateOf(dialogueModeEnabled) }
    var autoPlayback by remember { mutableStateOf(autoPlaybackEnabled) }
    var selectedSpeechRate by remember { mutableStateOf(speechRate) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Модель",
                    style = MaterialTheme.typography.titleLarge
                )

                SettingSlider(
                    label = "Temperature: ${String.format("%.2f", temperature)}",
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0f..2f
                )

                SettingSlider(
                    label = "Context Size: ${contextSize.toInt()}",
                    value = contextSize,
                    onValueChange = { contextSize = it },
                    valueRange = 512f..8192f,
                    steps = 15
                )

                SettingSlider(
                    label = "Max Tokens: ${maxTokens.toInt()}",
                    value = maxTokens,
                    onValueChange = { maxTokens = it },
                    valueRange = 64f..2048f,
                    steps = 30
                )

                SettingSlider(
                    label = "Top P: ${String.format("%.2f", topP)}",
                    value = topP,
                    onValueChange = { topP = it },
                    valueRange = 0f..1f
                )

                SettingSlider(
                    label = "Repeat Penalty: ${String.format("%.2f", repeatPenalty)}",
                    value = repeatPenalty,
                    onValueChange = { repeatPenalty = it },
                    valueRange = 1f..2f
                )

                Text("Чат", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Мягкое появление ответа")
                        Text(
                            "Показывать ответ словами с плавной анимацией",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = smoothResponse,
                        onCheckedChange = { smoothResponse = it },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Использовать системный промпт")
                        Text(
                            "Передавать модели инструкции формата ответов и работы с календарём",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = systemPrompt,
                        onCheckedChange = { systemPrompt = it },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Режим диалога")
                        Text(
                            "После озвучивания автоматически включать микрофон для следующей реплики",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = dialogueMode,
                        onCheckedChange = { dialogueMode = it },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Автовоспроизведение")
                        Text(
                            "Автоматически озвучивать каждый ответ ассистента",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoPlayback,
                        onCheckedChange = { autoPlayback = it },
                    )
                }

                SettingSlider(
                    label = "Скорость речи: ${String.format("%.2f", selectedSpeechRate)}",
                    value = selectedSpeechRate,
                    onValueChange = { selectedSpeechRate = SpeechRate.normalize(it) },
                    valueRange = SpeechRate.MINIMUM..SpeechRate.MAXIMUM,
                    steps = 9,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val default = GenerationParams()
                            temperature = default.temperature
                            contextSize = default.contextSize.toFloat()
                            maxTokens = default.maxTokens.toFloat()
                            topP = default.topP
                            repeatPenalty = default.repeatPenalty
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(Color(0xFF2196F3), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "сброс настроек по умолчанию",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Отмена")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        onSave(
                            params.copy(
                                temperature = temperature,
                                contextSize = contextSize.toInt(),
                                maxTokens = maxTokens.toInt(),
                                topP = topP,
                                repeatPenalty = repeatPenalty,
                            ),
                            smoothResponse,
                            systemPrompt,
                            dialogueMode,
                            autoPlayback,
                            selectedSpeechRate,
                        )
                    }) {
                        Text("Сохранить")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0
) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps
        )
    }
}

private enum class MicrophoneAction {
    TAP,
    LONG_PRESS,
}

private fun ModelState.label(): String = when (this) {
    ModelState.Unloaded -> "Модель будет загружена при первом сообщении"
    ModelState.Loading -> "Загрузка модели"
    ModelState.Ready -> "Модель готова"
    is ModelState.Error -> "Ошибка: ${this.message}"
    is ModelState.Importing -> "Импорт..."
}

private const val MAX_MESSAGE_LENGTH = 3000
private const val VOICE_DRAFT_LONG_PRESS_TIMEOUT_MILLIS = 2_000L
