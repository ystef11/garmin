package com.example.runstef.ui.analytics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.runstef.ui.common.GarminAccountSelector
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Вкладка «Аналитика»: выгрузка тренировок (и упрощённого самочувствия — RHR/HRV) из Garmin
 * Connect в локальную SQLite-базу на телефоне (см. data/AnalyticsDb.kt) и сборка HTML-отчёта,
 * который открывается тут же во встроенном WebView (см. AnalyticsReportScreen).
 *
 * Аккаунт Garmin — тот же самый общий пул токенов, что и на вкладке «Экспорт» (GarminTokenStore/
 * SettingsStore.lastGarminAccount) — вход под одним аккаунтом сразу доступен в обеих вкладках.
 * Выбор аккаунта и добавление нового — через общий GarminAccountSelector (пароль показывается
 * только во всплывающем окне AddGarminAccountDialog, а не на самой вкладке).
 *
 * Поля дат «С»/«По» — не текстовый ввод, а только календарь Material3 (см. [DateField]):
 * тап по полю открывает DatePickerDialog. Поле «С» по умолчанию подставляется как дата последней
 * загруженной тренировки в базе (см. LaunchedEffect(lastActivityDate) ниже) — обычно это ровно
 * то, что нужно для «догрузить свежее»; если база ещё пуста, по умолчанию берётся год назад.
 *
 * Первая загрузка требует явно указать период — это осознанно: на телефоне тянуть сразу весь
 * возможный год активностей+самочувствия дорого по трафику и времени. При следующих открытиях
 * вкладки, если для аккаунта уже есть непустая база, запускается тихое авто-обновление (только
 * новые дни, см. AnalyticsViewModel.autoCatchUp).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(onOpenReport: (String) -> Unit) {
    val vm: AnalyticsViewModel = viewModel()

    var savedAccounts by remember { mutableStateOf(vm.savedGarminAccounts()) }
    var account by rememberSaveable { mutableStateOf("") }

    var startDate by rememberSaveable { mutableStateOf("") }
    var endDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var withWellness by rememberSaveable { mutableStateOf(true) }
    var forceRefresh by rememberSaveable { mutableStateOf(false) }

    val logLines by vm.log.collectAsState()
    val isRunning by vm.isRunning.collectAsState()
    val lastActivityDate by vm.lastActivityDate.collectAsState()
    val firstActivityDate by vm.firstActivityDate.collectAsState()
    val activityCount by vm.activityCount.collectAsState()
    val reportPath by vm.reportPath.collectAsState()

    LaunchedEffect(Unit) {
        val last = vm.lastUsedAccount()
        account = last.ifBlank { savedAccounts.firstOrNull() ?: "" }
    }
    LaunchedEffect(account) {
        if (account.isNotBlank()) {
            vm.refreshLastActivityDate(account)
        }
    }
    // При первом открытии вкладки (для уже знакомого аккаунта с непустой базой) — тихая
    // догрузка свежих тренировок с (последняя дата в базе − 2 дня) по сегодня.
    LaunchedEffect(account) {
        if (account.isNotBlank()) vm.autoCatchUp(account)
    }
    // По умолчанию поле «С» — дата последней загруженной тренировки (см. doc выше). Реагируем на
    // сам lastActivityDate (а не только на первый рендер), т.к. после авто-обновления/импорта он
    // может смениться, а поле «С» ещё не трогали руками (startDate.isBlank()).
    LaunchedEffect(lastActivityDate) {
        if (startDate.isBlank()) {
            startDate = lastActivityDate ?: LocalDate.now().minusDays(365).toString()
        }
    }
    LaunchedEffect(reportPath) {
        reportPath?.let { onOpenReport(it); vm.consumeReportPath() }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Аналитика", style = MaterialTheme.typography.headlineSmall)
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
            Text(
                "Выгружает тренировки (и упрощённое самочувствие) в локальную базу на телефоне и строит отчёт.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            GarminAccountSelector(
                savedAccounts = savedAccounts,
                account = account,
                onAccountSelected = { account = it },
                onAccountAdded = { newAccount ->
                    if (newAccount !in savedAccounts) savedAccounts = savedAccounts + newAccount
                    account = newAccount
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            if (activityCount > 0) {
                Text(
                    "Тренировок в базе: $activityCount (с $firstActivityDate по $lastActivityDate)",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text("База пуста — укажи период для первой загрузки.", style = MaterialTheme.typography.bodySmall)
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                DateField(
                    label = "С",
                    value = startDate,
                    onValueChange = { startDate = it },
                    modifier = Modifier.weight(1f)
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))
                DateField(
                    label = "По",
                    value = endDate,
                    onValueChange = { endDate = it },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Checkbox(checked = withWellness, onCheckedChange = { withWellness = it })
                Text("Выгружать самочувствие (RHR/HRV)")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = forceRefresh, onCheckedChange = { forceRefresh = it })
                Text("Перевыгрузить/перезаписать уже загруженные дни этого периода")
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Button(
                    enabled = !isRunning && account.isNotBlank() && startDate.isNotBlank(),
                    onClick = {
                        val s = runCatching { LocalDate.parse(startDate) }.getOrNull()
                        val e = runCatching { LocalDate.parse(endDate) }.getOrNull() ?: LocalDate.now()
                        if (s != null) {
                            vm.importActivities(account.trim(), s, e, withWellness, forceRefresh)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Импортировать тренировки") }
            }
            OutlinedButton(
                enabled = lastActivityDate != null,
                onClick = { vm.buildReport(account.trim()) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Собрать и открыть отчёт") }

            var showImportConfirm by remember { mutableStateOf<android.net.Uri?>(null) }
            val importDbLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri -> if (uri != null) showImportConfirm = uri }
            OutlinedButton(
                enabled = account.isNotBlank() && !isRunning,
                onClick = { importDbLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Импортировать базу из файла...") }
            if (showImportConfirm != null) {
                AlertDialog(
                    onDismissRequest = { showImportConfirm = null },
                    title = { Text("Заменить базу аналитики?") },
                    text = {
                        Text("Текущая локальная база аккаунта \"$account\" будет полностью заменена выбранным файлом. Отменить это действие нельзя.")
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val uri = showImportConfirm
                            showImportConfirm = null
                            if (uri != null) vm.importDbFromUri(account.trim(), uri)
                        }) { Text("Заменить") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showImportConfirm = null }) { Text("Отмена") }
                    }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Text("Лог", style = MaterialTheme.typography.titleMedium)
            if (isRunning) CircularProgressIndicator(modifier = Modifier.padding(8.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().height(220.dp)) {
                items(logLines) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/**
 * Поле даты — теперь и ручной ввод текста (формат ГГГГ-ММ-ДД), и календарь одновременно: сам
 * текстфилд редактируемый (можно напечатать дату руками), а иконка-календарь справа открывает
 * Material3 DatePickerDialog (что удобнее для дат в прошлом — не листать текст руками). Ввод,
 * не похожий на корректную дату ГГГГ-ММ-ДД, подсвечивается как ошибка (isError), но само
 * значение всё равно передаётся наверх как есть — валидация "можно ли начинать импорт" по
 * этому полю уже есть на уровне кнопок экрана (runCatching { LocalDate.parse(...) }).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPicker by remember { mutableStateOf(false) }
    val isValid = value.isBlank() || runCatching { LocalDate.parse(value) }.isSuccess

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text("ГГГГ-ММ-ДД") },
        isError = !isValid,
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = { showPicker = true }) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = "Открыть календарь")
            }
        },
        modifier = modifier.fillMaxWidth()
    )

    if (showPicker) {
        val initialMillis = runCatching { LocalDate.parse(value) }.getOrNull()
            ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onValueChange(date.toString())
                    }
                    showPicker = false
                }) { Text("ОК") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Отмена") }
            }
        ) {
            DatePicker(state = state)
        }
    }
}
