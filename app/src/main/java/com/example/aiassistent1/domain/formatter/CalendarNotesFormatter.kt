package com.example.aiassistent1.domain.formatter

/** Adds optional event text without changing its content or the existing reply. */
fun String.withCalendarNotes(notes: String?): String =
    if (notes.isNullOrBlank()) this else "$this\nПримечание: $notes"
