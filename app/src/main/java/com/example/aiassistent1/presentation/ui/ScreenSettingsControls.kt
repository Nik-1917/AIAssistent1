package com.example.aiassistent1.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.aiassistent1.domain.model.ScreenOrientation
import com.example.aiassistent1.domain.model.ScreenPreferences

@Composable
internal fun ScreenSettingsControls(
    preferences: ScreenPreferences?,
    onOrientationLockedChange: (Boolean) -> Unit,
    onOrientationChange: (ScreenOrientation) -> Unit,
) {
    val locked = preferences?.orientationLocked == true
    val landscape = preferences?.orientation == ScreenOrientation.LANDSCAPE
    val orientationLabel = if (landscape) "Горизонтально" else "Вертикально"

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Фиксация ориентации")
                Text(
                    "При выключении поворот определяется настройками устройства.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = locked,
                onCheckedChange = onOrientationLockedChange,
                enabled = preferences != null,
                modifier = Modifier.semantics { contentDescription = "Фиксация ориентации" },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Вертикально / горизонтально",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (locked) 1f else 0.38f),
                )
                Text(
                    orientationLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (locked) 1f else 0.38f),
                )
            }
            Switch(
                checked = landscape,
                onCheckedChange = { horizontal ->
                    onOrientationChange(if (horizontal) ScreenOrientation.LANDSCAPE else ScreenOrientation.PORTRAIT)
                },
                enabled = locked,
                modifier = Modifier.semantics {
                    contentDescription = "Ориентация экрана"
                    stateDescription = orientationLabel
                },
            )
        }
    }
}
