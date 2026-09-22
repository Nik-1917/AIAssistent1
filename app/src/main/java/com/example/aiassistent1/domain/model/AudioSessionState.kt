package com.example.aiassistent1.domain.model

enum class AudioSessionState(val label: String) {
    Idle("Микрофон выключен"), Waiting("Ожидание имени"), Listening("Слушаю"),
    Enrolling("Запоминание голоса"), Rejected("Голос не подтверждён"),
}
