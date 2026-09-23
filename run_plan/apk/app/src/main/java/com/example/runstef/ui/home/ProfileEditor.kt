package com.example.runstef.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.runstef.data.UserProfileStore

/**
 * Карточка «Мой профиль» на «Главной» + диалог редактирования (возраст/пол/рост/вес). Данные
 * хранятся локально (см. UserProfileStore) и используются ТОЛЬКО для того, чтобы подставлять их
 * в калькуляторы plan/hr_pace/weight/rank (см. ToolUrlBuilder) — Гармин их не отдаёт вообще, их
 * неоткуда взять из аналитики. Необязательно: пустой профиль просто не подставляет эти поля,
 * калькуляторы работают как раньше со своими дефолтами.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSummaryCard() {
    val context = LocalContext.current
    val store = remember { UserProfileStore(context) }
    var age by remember { mutableStateOf(store.getAge()) }
    var sex by remember { mutableStateOf(store.getSex()) }
    var heightCm by remember { mutableStateOf(store.getHeightCm()) }
    var weightKg by remember { mutableStateOf(store.getWeightKg()) }
    var showEditor by remember { mutableStateOf(false) }

    val summary = buildList {
        age?.let { add("$it лет") }
        sex?.let { add(if (it == "m") "муж." else "жен.") }
        heightCm?.let { add("$it см") }
        weightKg?.let { add("${trimW(it)} кг") }
    }.joinToString(" · ").ifBlank { "не заполнен — нажмите, чтобы задать" }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { showEditor = true },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text("Мой профиль", style = MaterialTheme.typography.titleSmall)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Возраст/пол/рост/вес — подставляются в калькуляторы (у Гармин их нет)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showEditor) {
        var ageText by remember { mutableStateOf(age?.toString() ?: "") }
        var sexVal by remember { mutableStateOf(sex ?: "m") }
        var heightText by remember { mutableStateOf(heightCm?.toString() ?: "") }
        var weightText by remember { mutableStateOf(weightKg?.let { trimW(it) } ?: "") }

        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text("Мой профиль") },
            text = {
                Column {
                    Text(
                        "Используется только для подстановки в калькуляторы — Гармин эти данные не хранит.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        SegmentedButton(
                            selected = sexVal == "m",
                            onClick = { sexVal = "m" },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) { Text("Мужчина") }
                        SegmentedButton(
                            selected = sexVal == "f",
                            onClick = { sexVal = "f" },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) { Text("Женщина") }
                    }
                    OutlinedTextField(
                        value = ageText, onValueChange = { ageText = it },
                        label = { Text("Возраст, лет") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = heightText, onValueChange = { heightText = it },
                        label = { Text("Рост, см") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = weightText, onValueChange = { weightText = it },
                        label = { Text("Вес, кг") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val newAge = ageText.trim().toIntOrNull()
                    val newHeight = heightText.trim().toIntOrNull()
                    val newWeight = weightText.trim().replace(',', '.').toDoubleOrNull()
                    store.save(newAge, sexVal, newHeight, newWeight)
                    age = newAge; sex = sexVal; heightCm = newHeight; weightKg = newWeight
                    showEditor = false
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showEditor = false }) { Text("Отмена") }
            }
        )
    }
}

private fun trimW(v: Double): String {
    val r = Math.round(v * 10.0) / 10.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
}
