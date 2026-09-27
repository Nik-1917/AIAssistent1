package com.example.aiassistent1.presentation.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun BackgroundPowerSettings() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val power = context.getSystemService(PowerManager::class.java)
    var unrestricted by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    LaunchedEffect(lifecycleOwner, context, power) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (currentCoroutineContext().isActive) {
                unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
                delay(500L)
            }
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
    }
    val openBatterySettings: () -> Unit = {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"))
        launcher.launch(if (request.resolveActivity(context.packageManager) != null) request
            else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
    Text(
        if (unrestricted) "Работа во сне: активировано"
        else "Для диалогов во время сна устройства Разрешите работу во сне выбрать \"нет ограничений\". Это увеличивает расход заряда.",
        modifier = if (unrestricted) Modifier.clickable(
            role = Role.Button,
            onClickLabel = "Открыть настройки оптимизации батареи",
            onClick = openBatterySettings,
        ) else Modifier,
        style = MaterialTheme.typography.bodySmall,
    )
    if (!unrestricted) {
        TextButton(onClick = openBatterySettings) {
            Text("Разрешить работу во сне")
        }
    }
}
