package com.example.runstef.ui.security

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.runstef.data.UpdateInfo
import com.example.runstef.security.BiometricAuth
import com.example.runstef.ui.auth.AuthViewModel
import com.example.runstef.ui.home.HomeViewModel
import com.example.runstef.ui.update.UpdateAvailableDialog
import kotlinx.coroutines.launch

/**
 * Настройки безопасности вкладки «Экспорт»: включение биометрии (если доступна на устройстве)
 * и смена ПИН-кода. Сама вкладка «Экспорт» защищена ПИН/биометрией — остальное приложение открыто.
 *
 * Здесь же — «Проверить обновления»: в отличие от диалога на старте приложения, эта проверка
 * игнорирует и «Позже» (HomeViewModel.updateDismissed), и «Пропустить эту версию»
 * (HomeViewModel.skippedVersion) — пользователь явно попросил проверить, значит должен увидеть
 * результат, даже если сам раньше отклонил или пропустил именно эту версию.
 */
@Composable
fun SecurityScreen(authViewModel: AuthViewModel, homeViewModel: HomeViewModel, onLockNow: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val biometricAvailable = remember { BiometricAuth.isAvailable(context) }
    var biometricEnabled by remember { mutableStateOf(authViewModel.isBiometricEnabled()) }
    var showChangePin by remember { mutableStateOf(false) }
    var checkingUpdates by remember { mutableStateOf(false) }
    var foundUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    val effectiveConfig by homeViewModel.effectiveConfig.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Безопасность", style = MaterialTheme.typography.headlineSmall)
        Text(
            "ПИН-код защищает только вкладку «Экспорт». Токены Garmin/intervals.icu и сам ПИН хранятся в зашифрованном виде на устройстве.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Вход по биометрии", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (biometricAvailable) "Отпечаток или лицо как быстрый вход, ПИН всегда доступен как запасной"
                    else "Недоступно на этом устройстве (нет сенсора или он не настроен)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = biometricEnabled && biometricAvailable,
                enabled = biometricAvailable,
                onCheckedChange = { enabled ->
                    biometricEnabled = enabled
                    authViewModel.setBiometricEnabled(enabled)
                }
            )
        }

        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ПИН-код", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { showChangePin = true }) { Text("Изменить") }
        }

        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Заблокировать «Экспорт»", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onLockNow) { Text("Заблокировать") }
        }

        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Проверить обновления", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Версия: ${effectiveConfig?.ownVersion ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                enabled = !checkingUpdates,
                onClick = {
                    checkingUpdates = true
                    scope.launch {
                        val update = homeViewModel.checkForUpdatesNow()
                        checkingUpdates = false
                        if (update != null) {
                            foundUpdate = update
                        } else {
                            Toast.makeText(context, "У вас установлена последняя версия", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            ) { Text(if (checkingUpdates) "Проверка…" else "Проверить") }
        }
    }

    if (showChangePin) {
        ChangePinDialog(
            onDismiss = { showChangePin = false },
            onSubmit = { old, new -> authViewModel.changePin(old, new) },
            onDone = { showChangePin = false }
        )
    }

    foundUpdate?.let { update ->
        UpdateAvailableDialog(
            latestVersion = update.latestVersion,
            apkUrl = update.apkUrl,
            onDismiss = { foundUpdate = null },
            // Пропуск версии из ручной проверки ведёт себя так же, как из диалога на старте —
            // сохраняется на диск, чтобы при следующем автозапуске это обновление снова не всплывало.
            onSkip = {
                homeViewModel.skipVersion(update.latestVersion)
                foundUpdate = null
            }
        )
    }
}

@Composable
private fun ChangePinDialog(
    onDismiss: () -> Unit,
    onSubmit: (old: String, new: String) -> Boolean,
    onDone: () -> Unit
) {
    var oldPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val numeric = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Смена ПИН-кода") },
        text = {
            Column {
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
                }
                OutlinedTextField(
                    value = oldPin, onValueChange = { oldPin = it.filter(Char::isDigit).take(4) },
                    label = { Text("Текущий ПИН") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = numeric,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newPin, onValueChange = { newPin = it.filter(Char::isDigit).take(4) },
                    label = { Text("Новый ПИН (4 цифры)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = numeric,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = confirmPin, onValueChange = { confirmPin = it.filter(Char::isDigit).take(4) },
                    label = { Text("Повторите новый ПИН") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = numeric,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    newPin.length != 4 -> error = "Новый ПИН должен состоять из 4 цифр"
                    newPin != confirmPin -> error = "Новый ПИН и повтор не совпадают"
                    !onSubmit(oldPin, newPin) -> error = "Текущий ПИН неверен"
                    else -> onDone()
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
