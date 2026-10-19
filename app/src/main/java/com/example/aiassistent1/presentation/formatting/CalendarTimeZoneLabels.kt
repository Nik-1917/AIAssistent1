package com.example.aiassistent1.presentation.formatting

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

internal object CalendarTimeZoneLabels {
    private val russian = Locale.forLanguageTag("ru")
    private val locationNames by lazy { runCatching { android.icu.text.TimeZoneNames.getInstance(russian) }.getOrNull() }
    private val russianCities = mapOf(
        "Europe/Kaliningrad" to "Калининград", "Europe/Moscow" to "Москва",
        "Europe/Kirov" to "Киров", "Europe/Volgograd" to "Волгоград",
        "Europe/Samara" to "Самара", "Europe/Saratov" to "Саратов",
        "Europe/Ulyanovsk" to "Ульяновск", "Europe/Astrakhan" to "Астрахань",
        "Asia/Yekaterinburg" to "Екатеринбург", "Asia/Omsk" to "Омск",
        "Asia/Novosibirsk" to "Новосибирск", "Asia/Novokuznetsk" to "Новокузнецк",
        "Asia/Barnaul" to "Барнаул", "Asia/Tomsk" to "Томск",
        "Asia/Krasnoyarsk" to "Красноярск", "Asia/Irkutsk" to "Иркутск",
        "Asia/Chita" to "Чита", "Asia/Yakutsk" to "Якутск",
        "Asia/Khandyga" to "Хандыга", "Asia/Vladivostok" to "Владивосток",
        "Asia/Ust-Nera" to "Усть-Нера", "Asia/Magadan" to "Магадан",
        "Asia/Sakhalin" to "Сахалин", "Asia/Srednekolymsk" to "Среднеколымск",
        "Asia/Kamchatka" to "Камчатка", "Asia/Anadyr" to "Анадырь", "UTC" to "UTC",
    )

    fun name(zone: ZoneId, instant: Instant = Instant.now()): String = russianCities[zone.id]
        ?: locationNames?.getExemplarLocationName(zone.id)
        ?: TimeZone.getTimeZone(zone).getDisplayName(zone.rules.isDaylightSavings(instant), TimeZone.LONG, russian)

    fun offset(zone: ZoneId, instant: Instant = Instant.now()): String =
        "UTC" + zone.rules.getOffset(instant).id.let { if (it == "Z") "+00:00" else it }

    fun label(zone: ZoneId, instant: Instant = Instant.now()): String = "${name(zone, instant)} · ${offset(zone, instant)}"
}
