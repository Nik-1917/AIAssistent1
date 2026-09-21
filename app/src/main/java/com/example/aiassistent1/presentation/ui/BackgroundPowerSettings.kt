package com.example.aiassistent1.presentation.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun BackgroundPowerSettings() {
    val context = LocalContext.current
    val power = context.getSystemService(PowerManager::class.java)
    var unrestricted by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
    }
    Text(
        if (unrestricted) "Работа во сне: оптимизация батареи Android отключена"
        else "Для диалога во время глубокого сна разрешите работу без оптимизации батареи. Это увеличивает расход заряда.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (!unrestricted) {
        TextButton(onClick = {
            val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"))
            launcher.launch(if (request.resolveActivity(context.packageManager) != null) request
                else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }) {
            Text("Разрешить работу во сне")
        }
    }
}
