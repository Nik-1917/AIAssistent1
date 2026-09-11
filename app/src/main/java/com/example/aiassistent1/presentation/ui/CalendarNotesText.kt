package com.example.aiassistent1.presentation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
internal fun CalendarNotesText(notes: String?) {
    if (!notes.isNullOrBlank()) {
        Text("Примечание: $notes", style = MaterialTheme.typography.bodyMedium)
    }
}
