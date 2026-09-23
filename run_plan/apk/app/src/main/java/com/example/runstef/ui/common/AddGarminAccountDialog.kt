package com.example.runstef.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.runstef.network.garmin.GarminAuth
import com.example.runstef.network.garmin.GarminTokenStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Всплывающее окно «Добавить аккаунт Garmin» — общее для вкладок «Экспорт» и «Аналитика»
 * (см. ui/common/GarminAccountSelector.kt, где оно открывается по нажатию «＋»). Поле пароля
 * показывается ТОЛЬКО здесь, в момент реального входа — на самих вкладках экспорта/аналитики
 * пароль больше не отображается вообще: там только выбор уже сохранённого аккаунта (токен
 * лежит в GarminTokenStore, см. класс), а логин/перелогин — исключительно через это окно.
 *
 * Полностью самодостаточно: логинится через GarminAuth.login и сохраняет токен в
 * GarminTokenStore само, без участия ExportViewModel/AnalyticsViewModel — обеим вкладкам после
 * успеха достаточно обновить список сохранённых аккаунтов и выбрать [onAccountAdded].
 */
@Composable
fun AddGarminAccountDialog(
    onDismiss: () -> Unit,
    onAccountAdded: (String) -> Unit,
    // Заполнен, когда окно открыто из AccountManagementDialog действием "Изменить пароль" для
    // уже сохранённого аккаунта — тогда e-mail фиксирован (только повторный вход тем же логином
    // с новым паролем перезапишет токен, см. submit()/tokenStore.save() ниже), а не добавление
    // нового аккаунта. При обычном добавлении (кнопка "＋"/"+ Добавить аккаунт") остаётся null.
    lockedEmail: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokenStore = remember { GarminTokenStore(context) }

    var email by remember { mutableStateOf(lockedEmail ?: "") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var mfaRequested by remember { mutableStateOf(false) }
    var mfaDeferred by remember { mutableStateOf<CompletableDeferred<String>?>(null) }

    fun submit() {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isBlank() || password.isBlank()) {
            error = "Укажи e-mail и пароль."
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                val auth = GarminAuth()
                val tokens = withContext(Dispatchers.IO) {
                    auth.login(trimmedEmail, password, mfaPrompt = GarminAuth.MfaPrompt {
                        val deferred = CompletableDeferred<String>()
                        mfaDeferred = deferred
                        mfaRequested = true
                        deferred.await()
                    })
                }
                tokenStore.save(trimmedEmail, tokens)
                onAccountAdded(trimmedEmail)
            } catch (e: Exception) {
                error = e.message ?: "Не удалось войти"
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (lockedEmail != null) "Изменить пароль" else "Добавить аккаунт Garmin") },
        text = {
            Column {
                OutlinedTextField(
                    value = email, onValueChange = { email = it },
                    label = { Text("E-mail") }, enabled = !busy && lockedEmail == null,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Пароль") }, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp))
                }
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = !busy) { Text("Войти") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") }
        }
    )

    if (mfaRequested) {
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {
                mfaDeferred?.completeExceptionally(RuntimeException("Ввод кода 2FA отменён"))
                mfaRequested = false
            },
            title = { Text("Код 2FA Garmin") },
            text = {
                OutlinedTextField(
                    value = code, onValueChange = { code = it },
                    label = { Text("Код из приложения/SMS") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    mfaDeferred?.complete(code)
                    mfaRequested = false
                }) { Text("Подтвердить") }
            },
            dismissButton = {
                TextButton(onClick = {
                    mfaDeferred?.completeExceptionally(RuntimeException("Ввод кода 2FA отменён"))
                    mfaRequested = false
                }) { Text("Отмена") }
            }
        )
    }
}
