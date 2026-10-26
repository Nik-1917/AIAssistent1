package com.example.aiassistent1.presentation.viewmodel

import com.example.aiassistent1.calendar.core.domain.CalendarEvent
import com.example.aiassistent1.domain.interfaces.CalendarDraftRepository
import com.example.aiassistent1.domain.model.CalendarDraftRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class SavedCalendarCardsControllerTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private fun card(request: String = "reply", event: String? = "event") = CalendarEventDraftUiState(
        requestId = request, savedEventId = event, title = "Прежнее название", date = "2026-10-20", time = "09:00")
    private fun event() = CalendarEvent("event", "Встреча", Instant.parse("2026-10-20T06:00:00Z").toEpochMilli(),
        Instant.parse("2026-10-20T07:00:00Z").toEpochMilli(), 1, 1)

    @Test fun `changes appear only in live display and never rewrite saved or unfinished drafts`() = runTest {
        val records = listOf(
            CalendarDraftRecord("reply", "calendar", 1, "Встреча", "2026-10-20", "09:00", savedEventId = "event"),
            CalendarDraftRecord("pending", "calendar", 2, "Несохранённое", fieldInputs = mapOf("Time" to "в пол", "Value" to "")),
        )
        var writes = 0
        val draftRepository = object : CalendarDraftRepository {
            override suspend fun load() = records
            override suspend fun save(drafts: List<CalendarDraftRecord>) { writes++ }
        }
        val drafts = CalendarDraftController(draftRepository, backgroundScope)
        runCurrent()
        drafts.open("pending")
        val before = drafts.state.value
        val snapshots = MutableStateFlow<Map<String, CalendarEvent?>>(mapOf("event" to event()))
        var requested = emptySet<String>()
        val cards = SavedCalendarCardsController(backgroundScope, { ids, _ -> requested = ids; snapshots })
        cards.watch(before.drafts, setOf("reply", "pending"), zone)
        runCurrent()
        assertEquals(setOf("event"), requested)
        val changed = event().copy(title = "Перенесённая встреча", startsAtEpochMillis = event().startsAtEpochMillis + 172_800_000,
            endsAtEpochMillis = event().endsAtEpochMillis + 172_800_000, revision = 1, notes = "  Текст  ", value = 0)
        snapshots.value = mapOf("event" to changed)
        runCurrent()
        assertEquals(changed, (cards.state.value["event"] as SavedCalendarEventCardState.Available).event)
        assertEquals(before, drafts.state.value)
        assertEquals("в пол", drafts.find("pending")!!.fieldText(CalendarEventField.Time))
        assertEquals(0, writes)
        assertEquals(records, draftRepository.load())
    }

    @Test fun `only existing saved message links are observed and duplicate links share one request`() = runTest {
        val requests = mutableListOf<Set<String>>()
        val cards = SavedCalendarCardsController(backgroundScope, { ids, _ ->
            requests.add(ids)
            MutableStateFlow(ids.associateWith { event() })
        })
        cards.watch(listOf(card(event = null)), setOf("reply"), zone)
        runCurrent()
        assertTrue(requests.isEmpty())
        val inputs = listOf(card(), card("second"), card("orphan", "not-in-chat"), card("pending", null),
            card("other").copy(chatId = "general"))
        cards.watch(inputs, setOf("reply", "second", "pending", "other"), zone)
        runCurrent()
        assertEquals(listOf(setOf("event")), requests)
        cards.watch(inputs + card("pending-2", null).copy(input = "Не закончено"), setOf("reply", "second"), zone)
        runCurrent()
        assertEquals(1, requests.size)
        cards.watch(inputs, emptySet(), zone)
        runCurrent()
        assertTrue(cards.state.value.isEmpty())
    }

    @Test fun `a new observer after restart reads current event using the persisted saved ID`() = runTest {
        val moved = event().copy(startsAtEpochMillis = event().startsAtEpochMillis + 86_400_000,
            endsAtEpochMillis = event().endsAtEpochMillis + 86_400_000, revision = 1)
        val source = MutableStateFlow<Map<String, CalendarEvent?>>(mapOf("event" to event()))
        val first = SavedCalendarCardsController(backgroundScope, { _, _ -> source })
        first.watch(listOf(card()), setOf("reply"), zone)
        runCurrent()
        first.watch(emptyList(), emptySet(), zone)
        source.value = mapOf("event" to moved)
        val restored = SavedCalendarCardsController(backgroundScope, { _, _ -> source })
        restored.watch(listOf(card()), setOf("reply"), zone)
        runCurrent()
        assertEquals(moved, (restored.state.value["event"] as SavedCalendarEventCardState.Available).event)
    }

    @Test fun `storage failures are not presented as deletion and can be retried`() = runTest {
        var failed = true
        val cards = SavedCalendarCardsController(backgroundScope, { _, _ -> flow {
            if (failed) throw IOException("Read failed")
            emit(mapOf("event" to event()))
        } })
        cards.watch(listOf(card()), setOf("reply"), zone)
        runCurrent()
        assertEquals(SavedCalendarEventCardState.Failed, cards.state.value["event"])
        failed = false
        cards.retry()
        runCurrent()
        assertEquals(event(), (cards.state.value["event"] as SavedCalendarEventCardState.Available).event)
    }

    @Test fun `missing row and incomplete snapshot have distinct states`() = runTest {
        val source = MutableStateFlow<Map<String, CalendarEvent?>>(mapOf("event" to null))
        val cards = SavedCalendarCardsController(backgroundScope, { _, _ -> source })
        cards.watch(listOf(card()), setOf("reply"), zone)
        runCurrent()
        assertEquals(SavedCalendarEventCardState.Missing, cards.state.value["event"])
        source.value = emptyMap()
        runCurrent()
        assertEquals(SavedCalendarEventCardState.Failed, cards.state.value["event"])
    }

    @Test fun `removing a link or changing viewing zone cancels the previous observer`() = runTest {
        val first = MutableStateFlow<Map<String, CalendarEvent?>>(mapOf("event" to event()))
        val second = MutableStateFlow<Map<String, CalendarEvent?>>(mapOf("event" to event().copy(title = "В другом поясе")))
        val requestedZones = mutableListOf<ZoneId>()
        val samara = ZoneId.of("Europe/Samara")
        val cards = SavedCalendarCardsController(backgroundScope, { _, selected ->
            requestedZones.add(selected)
            if (selected == zone) first else second
        })
        cards.watch(listOf(card()), setOf("reply"), zone)
        runCurrent()
        cards.watch(listOf(card()), setOf("reply"), samara)
        runCurrent()
        assertEquals(listOf(zone, samara), requestedZones)
        assertEquals(0, first.subscriptionCount.value)
        first.value = mapOf("event" to event().copy(title = "Устаревший ответ"))
        runCurrent()
        assertEquals("В другом поясе", (cards.state.value["event"] as SavedCalendarEventCardState.Available).event.title)
        cards.watch(emptyList(), emptySet(), samara)
        runCurrent()
        assertEquals(0, second.subscriptionCount.value)
        assertTrue(cards.state.value.isEmpty())
    }

    @Test fun `a mismatched event cannot be displayed under another saved link`() = runTest {
        val cards = SavedCalendarCardsController(backgroundScope, { _, _ ->
            MutableStateFlow(mapOf("event" to event().copy(id = "other-event")))
        })
        cards.watch(listOf(card()), setOf("reply"), zone)
        runCurrent()
        assertEquals(SavedCalendarEventCardState.Failed, cards.state.value["event"])
    }

    @Test fun `adding a saved card does not hide another already loaded card`() = runTest {
        val source = MutableStateFlow<Map<String, CalendarEvent?>>(mapOf("event" to event()))
        val neverReady: Flow<Map<String, CalendarEvent?>> = flow { kotlinx.coroutines.awaitCancellation() }
        val cards = SavedCalendarCardsController(backgroundScope, { ids, _ -> if (ids.size == 1) source else neverReady })
        cards.watch(listOf(card()), setOf("reply"), zone)
        runCurrent()
        val available = cards.state.value["event"]
        cards.watch(listOf(card(), card("new", "second")), setOf("reply", "new"), zone)
        assertEquals(available, cards.state.value["event"])
        assertEquals(SavedCalendarEventCardState.Loading, cards.state.value["second"])
    }
}
