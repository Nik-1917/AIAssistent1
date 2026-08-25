package com.example.aiassistent1.calendar.core.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Identifies which existing event an update command addresses. */
enum class CalendarTargetMode {
    BY_QUERY,
    LAST_CREATED,
    /** The final event, ordered by start time then id, within a supplied period. */
    LAST_IN_RANGE,
    /** Reserved for a later multi-turn event-reference feature. */
    LAST_REFERENCED,
}

data class CalendarUpdateTarget(
    val mode: CalendarTargetMode,
    val query: String? = null,
    val rangeStartEpochMillis: Long? = null,
    val rangeEndEpochMillis: Long? = null,
) {
    init {
        when (mode) {
            CalendarTargetMode.BY_QUERY -> {
                require(!query.isNullOrBlank()) { "A query target requires a non-blank query." }
            }
            CalendarTargetMode.LAST_CREATED,
            CalendarTargetMode.LAST_REFERENCED -> {
                require(query == null) { "A last-event target must not contain a query." }
            }
            CalendarTargetMode.LAST_IN_RANGE -> {
                require(query == null) { "A period-last target must not contain a query." }
            }
        }
        require((rangeStartEpochMillis == null) == (rangeEndEpochMillis == null)) {
            "Both range boundaries must be supplied together."
        }
        require(
            mode == CalendarTargetMode.BY_QUERY ||
                mode == CalendarTargetMode.LAST_IN_RANGE ||
                rangeStartEpochMillis == null,
        ) {
            "A target range can only be used with a query or period-last target."
        }
        require(mode != CalendarTargetMode.LAST_IN_RANGE || rangeStartEpochMillis != null) {
            "A period-last target requires a target range."
        }
        if (rangeStartEpochMillis != null && rangeEndEpochMillis != null) {
            require(rangeStartEpochMillis < rangeEndEpochMillis) {
                "The target range start must be before its end."
            }
        }
    }
}

/** The fields that will replace values on the resolved event. Null means preserve the current field. */
data class CalendarEventChanges(
    val title: String? = null,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val durationMinutes: Int? = null,
) {
    init {
        require(durationMinutes == null || durationMinutes > 0) {
            "Event duration must be positive."
        }
        require(title == null || title.isNotBlank()) { "Event title must not be blank." }
    }

    val isEmpty: Boolean
        get() = title == null && date == null && time == null && durationMinutes == null
}

data class CalendarUpdateCommand(
    val target: CalendarUpdateTarget,
    val changes: CalendarEventChanges,
)

sealed interface CalendarUpdateTargetResolution {
    data class Resolved(val event: CalendarEvent) : CalendarUpdateTargetResolution
    data class Ambiguous(val candidates: List<CalendarEvent>) : CalendarUpdateTargetResolution
    data object NotFound : CalendarUpdateTargetResolution
}

class ResolveCalendarUpdateTargetUseCase(
    private val repository: CalendarEventRepository,
) {
    suspend operator fun invoke(target: CalendarUpdateTarget): Result<CalendarUpdateTargetResolution> = runCatching {
        val candidates = when (target.mode) {
            CalendarTargetMode.BY_QUERY -> repository.findForUpdate(
                query = requireNotNull(target.query),
                rangeStartEpochMillis = target.rangeStartEpochMillis,
                rangeEndEpochMillis = target.rangeEndEpochMillis,
            ).getOrThrow()
            CalendarTargetMode.LAST_CREATED -> listOfNotNull(repository.getLastCreated().getOrThrow())
            CalendarTargetMode.LAST_IN_RANGE -> listOfNotNull(
                repository.getLastInRange(
                    rangeStartEpochMillis = requireNotNull(target.rangeStartEpochMillis),
                    rangeEndEpochMillis = requireNotNull(target.rangeEndEpochMillis),
                ).getOrThrow(),
            )
            CalendarTargetMode.LAST_REFERENCED -> error("Последнее упомянутое событие пока недоступно.")
        }

        when (candidates.size) {
            0 -> CalendarUpdateTargetResolution.NotFound
            1 -> CalendarUpdateTargetResolution.Resolved(candidates.single())
            else -> CalendarUpdateTargetResolution.Ambiguous(candidates)
        }
    }
}

/** Builds a full repository update without coupling the domain command to Room or Compose UI. */
class PrepareCalendarEventUpdateUseCase(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    operator fun invoke(
        event: CalendarEvent,
        changes: CalendarEventChanges,
    ): Result<CalendarEventUpdate> = runCatching {
        require(!changes.isEmpty) { "Укажите хотя бы одно поле для изменения события." }

        val currentStart = Instant.ofEpochMilli(event.startsAtEpochMillis)
            .atZone(zoneId)
            .toLocalDateTime()
        val currentDurationMinutes = Duration.between(
            Instant.ofEpochMilli(event.startsAtEpochMillis),
            Instant.ofEpochMilli(event.endsAtEpochMillis),
        ).toMinutes()
        require(currentDurationMinutes > 0) { "У события некорректная длительность." }

        val newStart = currentStart
            .with(changes.date ?: currentStart.toLocalDate())
            .with(changes.time ?: currentStart.toLocalTime())
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val durationMillis = Math.multiplyExact(
            (changes.durationMinutes ?: currentDurationMinutes).toLong(),
            MILLIS_PER_MINUTE,
        )

        CalendarEventUpdate(
            id = event.id,
            title = changes.title ?: event.title,
            startsAtEpochMillis = newStart,
            endsAtEpochMillis = Math.addExact(newStart, durationMillis),
        )
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
