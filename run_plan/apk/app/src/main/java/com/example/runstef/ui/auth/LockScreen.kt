package com.example.runstef.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.example.runstef.security.BiometricAuth

private const val PIN_LENGTH = 4

/**
 * Экран блокировки вкладки «Экспорт» — показывается при каждом её открытии, пока
 * [AuthViewModel.unlocked] не станет true (сбрасывается при выходе с вкладки, см. MainActivity).
 * Остальное приложение (калькуляторы, мои планы) не защищено — здесь лежат только
 * токены Garmin/intervals.icu. Если ПИН ещё не задан — ведёт по первичной настройке,
 * иначе просит ввести ПИН (и предлагает биометрию, если она включена и доступна на устройстве).
 */
@Composable
fun LockScreen(activity: FragmentActivity, authViewModel: AuthViewModel) {
    val context = LocalContext.current
    val hasPin = remember { authViewModel.hasPin() }
    val biometricAvailable = remember { BiometricAuth.isAvailable(context) }
    val biometricEnabled = remember { authViewModel.isBiometricEnabled() }

    if (!hasPin) {
        PinSetupContent(onPinSet = { pin -> authViewModel.setupPin(pin) })
    } else {
        PinUnlockContent(
            activity = activity,
            showBiometric = biometricAvailable && biometricEnabled,
            onVerify = { pin -> authViewModel.tryUnlockWithPin(pin) },
            onBiometricSuccess = { authViewModel.unlockWithBiometric() }
        )
    }
}

@Composable
private fun PinSetupContent(onPinSet: (String) -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    var firstPin by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (!confirming) "Придумайте ПИН-код" else "Повторите ПИН-код",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "Он потребуется при каждом открытии вкладки «Экспорт»",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
        )
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp))
        }
        PinDots(length = pin.length, maxLength = PIN_LENGTH)
        Spacer(modifier = Modifier.height(32.dp))
        PinPad(
            onDigit = { digit ->
                if (pin.length < PIN_LENGTH) {
                    val next = pin + digit
                    pin = next
                    if (next.length == PIN_LENGTH) {
                        if (!confirming) {
                            firstPin = next
                            pin = ""
                            error = null
                            confirming = true
                        } else if (next == firstPin) {
                            onPinSet(next)
                        } else {
                            error = "ПИН-коды не совпадают, попробуйте снова"
                            pin = ""
                            firstPin = ""
                            confirming = false
                        }
                    }
                }
            },
            onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) }
        )
    }
}

@Composable
private fun PinUnlockContent(
    activity: FragmentActivity,
    showBiometric: Boolean,
    onVerify: (String) -> Boolean,
    onBiometricSuccess: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun triggerBiometric() {
        BiometricAuth.prompt(
            activity = activity,
            onSuccess = onBiometricSuccess,
            onError = { /* пользователь отменил или ошибка сенсора — остаётся ввод ПИН */ }
        )
    }

    LaunchedEffect(Unit) {
        if (showBiometric) triggerBiometric()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Введите ПИН-код", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(if (error != null) 4.dp else 24.dp))
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 20.dp))
        }
        PinDots(length = pin.length, maxLength = PIN_LENGTH)
        Spacer(modifier = Modifier.height(32.dp))
        PinPad(
            onDigit = { digit ->
                if (pin.length < PIN_LENGTH) {
                    val next = pin + digit
                    pin = next
                    if (next.length == PIN_LENGTH) {
                        if (onVerify(next)) {
                            error = null
                        } else {
                            error = "Неверный ПИН-код"
                            pin = ""
                        }
                    }
                }
            },
            onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) }
        )
        if (showBiometric) {
            Spacer(modifier = Modifier.height(20.dp))
            TextButton(onClick = { triggerBiometric() }) {
                Icon(Icons.Filled.Fingerprint, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text("Войти по биометрии")
            }
        }
    }
}

@Composable
private fun PinDots(length: Int, maxLength: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(maxLength) { i ->
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(
                        color = if (i < length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    )
            )
        }
    }
}

private val PIN_PAD_ROWS = listOf(
    listOf('1', '2', '3'),
    listOf('4', '5', '6'),
    listOf('7', '8', '9')
)

@Composable
private fun PinPad(onDigit: (Char) -> Unit, onBackspace: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PIN_PAD_ROWS.forEach { row ->
            Row {
                row.forEach { digit ->
                    PinKey(digit) { onDigit(digit) }
                }
            }
        }
        Row {
            Box(modifier = Modifier.size(72.dp))
            PinKey('0') { onDigit('0') }
            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = onBackspace) {
                    Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Удалить цифру")
                }
            }
        }
    }
}

@Composable
private fun PinKey(digit: Char?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .padding(6.dp)
            .clickable(enabled = digit != null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (digit != null) {
            Text(digit.toString(), style = MaterialTheme.typography.headlineMedium)
        }
    }
}
