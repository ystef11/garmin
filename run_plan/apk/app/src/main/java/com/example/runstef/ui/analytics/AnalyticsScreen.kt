package com.example.runstef.ui.analytics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.draw.clip
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
 * возможный год активностей+самочувствия дорого по трафику и времени.
 *
 * ИСПРАВЛЕНО (ревью п.16, таблица "стале-комментарии/мёртвый autoCatchUp"): этот docstring
 * раньше утверждал, что "при следующих открытиях вкладки запускается тихое авто-обновление
 * (AnalyticsViewModel.autoCatchUp)" — неверно уже с 2026-08-23 (см. комментарий у отключённого
 * вызова ниже, LaunchedEffect с account): по явному запросу пользователя авто-догрузка
 * отключена, обновление данных теперь только по нажатию «Импортировать тренировки». Сам метод
 * AnalyticsViewModel.autoCatchUp() оставлен в коде НЕ вызываемым ниоткуда (мог бы пригодиться,
 * если авто-догрузку решат вернуть, например под отдельный тумблер в настройках) — но раз он
 * сейчас мёртвый код, этот docstring больше не должен описывать его как активное поведение.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(onOpenReport: (String) -> Unit) {
    val vm: AnalyticsViewModel = viewModel()

    var savedAccounts by remember { mutableStateOf(vm.savedGarminAccounts()) }
    var account by rememberSaveable { mutableStateOf("") }
    // Основной аккаунт — из его локальной базы аналитики калькуляторы берут данные автоподстановки
    // (см. ToolUrlBuilder.kt). Отдельно от [account] выше: [account] — какой аккаунт СЕЙЧАС открыт/
    // импортируется на этой вкладке, основной может быть другим (или тем же, если он один).
    var primaryAccount by remember { mutableStateOf(vm.primaryGarminAccount()) }

    var startDate by rememberSaveable { mutableStateOf("") }
    // Стало true, как только пользователь САМ поменял поле «С» руками (см. DateField ниже).
    // Раньше вместо этого флага использовалась проверка startDate.isBlank() в LaunchedEffect
    // (lastActivityDate) — но после первого же авто-подставления значения поле уже не пустое,
    // поэтому она неотличима от «пользователь трогал поле руками» и импорт базы из файла
    // (см. AnalyticsViewModel.importDbFromUri) её больше не обновлял, хотя lastActivityDate
    // в шине менялся правильно.
    var startDateTouchedByUser by rememberSaveable { mutableStateOf(false) }
    var endDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    // Больше не переключается пользователем - по умолчанию всегда выгружаем всё (включая самочувствие).
    val withWellness = true
    var forceRefresh by rememberSaveable { mutableStateOf(false) }

    val logLines by vm.log.collectAsState()
    val isRunning by vm.isRunning.collectAsState()
    val progress by vm.progress.collectAsState()
    val lastActivityDate by vm.lastActivityDate.collectAsState()
    val firstActivityDate by vm.firstActivityDate.collectAsState()
    val activityCount by vm.activityCount.collectAsState()
    val reportPath by vm.reportPath.collectAsState()
    val cancelRequested by vm.cancelRequested.collectAsState()
    val busyOther by vm.busyOther.collectAsState()

    LaunchedEffect(Unit) {
        // По умолчанию при открытии вкладки в поле подставляется ОСНОВНОЙ аккаунт (см.
        // GarminTokenStore.primaryAccount()), а не последний использованный на вкладке «Экспорт»
        // (vm.lastUsedAccount() — отдельное, не связанное с этим понятие). primaryAccount уже
        // инициализирован синхронно при composition (см. remember выше), поэтому здесь просто
        // берём его as-is — с тем же fallback на первый сохранённый, что и в самом primaryAccount().
        account = primaryAccount.ifBlank { savedAccounts.firstOrNull() ?: "" }
    }
    LaunchedEffect(account) {
        if (account.isNotBlank()) {
            vm.refreshLastActivityDate(account)
        }
    }
    // ОТКЛЮЧЕНО по явному запросу пользователя (2026-08-23: "и отключи автозагрузку данных -
    // она работает некорректно") - раньше здесь была тихая авто-догрузка свежих тренировок при
    // каждом открытии вкладки (vm.autoCatchUp(account)). Пользователь теперь явно нажимает
    // "Импортировать тренировки" сам, когда нужно обновить данные.
    // if (account.isNotBlank()) vm.autoCatchUp(account)
    // По умолчанию поле «С» — дата последней загруженной тренировки (см. doc выше). Реагируем на
    // сам lastActivityDate (а не только на первый рендер), т.к. после авто-обновления/импорта он
    // может смениться, а поле «С» ещё не трогали руками (startDateTouchedByUser == false —
    // это касается и импорта базы из файла, не только сетевой авто-догрузки).
    LaunchedEffect(lastActivityDate) {
        if (!startDateTouchedByUser) {
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
                    primaryAccount = vm.primaryGarminAccount()
                },
                showManagement = true,
                primaryAccount = primaryAccount,
                onPrimaryAccountChanged = { acc ->
                    vm.setPrimaryGarminAccount(acc)
                    primaryAccount = acc
                },
                onAccountDeleted = { acc ->
                    vm.deleteGarminAccount(acc)
                    savedAccounts = savedAccounts - acc
                    if (account == acc) account = savedAccounts.firstOrNull() ?: ""
                    primaryAccount = vm.primaryGarminAccount()
                },
                // При выходе из модалки управления аккаунтами поле выбора снова подставляет
                // ОСНОВНОЙ аккаунт по умолчанию (тот же принцип, что и при открытии вкладки, см.
                // LaunchedEffect(Unit) выше) — даже если пока модалка была открыта, пользователь
                // успел выбрать в самом поле что-то другое.
                onManagementDialogClosed = {
                    val primary = vm.primaryGarminAccount()
                    primaryAccount = primary
                    if (primary.isNotBlank()) account = primary
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
                    onValueChange = { startDate = it; startDateTouchedByUser = true },
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = forceRefresh, onCheckedChange = { forceRefresh = it })
                Text("Перевыгрузить/перезаписать уже загруженные дни этого периода")
            }

            var showImportMenu by remember { mutableStateOf(false) }
            var showImportConfirm by remember { mutableStateOf<android.net.Uri?>(null) }
            val importDbLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri -> if (uri != null) showImportConfirm = uri }
            val exportDbLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/octet-stream")
            ) { uri -> if (uri != null) vm.exportDbToUri(account.trim(), uri) }

            // Визуально единая "капсула" (как в стандартных split-button из Material) - общая
            // рамка/скругление на весь Row, а не два отдельных OutlinedButton рядом: внутри
            // тонкий вертикальный разделитель между текстовой частью и стрелкой раскрытия меню.
            // Раньше проверялось только startDate.isNotBlank() - при недописанной вручную дате
            // (например, ещё не введён год целиком) кнопка оставалась активной, LocalDate.parse
            // падал молча (см. runCatching ниже), и клик просто ничего не делал без объяснения
            // — или, того хуже, использовал последнее успешно распарсенное значение ДО того, как
            // пользователь закончил править поле. Теперь обе даты должны быть валидны целиком.
            val startDateValid = runCatching { LocalDate.parse(startDate) }.isSuccess
            val endDateValid = endDate.isBlank() || runCatching { LocalDate.parse(endDate) }.isSuccess
            val importButtonEnabled = !isRunning && !busyOther && account.isNotBlank() && startDateValid && endDateValid
            val importMenuEnabled = account.isNotBlank() && !isRunning && !busyOther
            if (busyOther) {
                Text(
                    "Идёт экспорт плана — загрузка и отчёт станут доступны после его окончания.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            // Пока идёт импорт/авто-догрузка, на месте этой капсулы ("Загрузить тренировки" +
            // стрелка меню) показываем кнопку «Стоп» — по месту, где пользователь только что
            // нажал загрузку, а не отдельным элементом внизу под «Построить отчёт» (см. правку
            // 2026-09-23: раньше Стоп был ниже и терялся). Останов не мгновенный — кооперативный
            // флаг проверяется между запросами (см. GarminActivitiesApi.importRange), поэтому
            // после нажатия кнопка показывает "Останавливаю…" и блокируется до конца сервиса.
            if (isRunning) {
                OutlinedButton(
                    enabled = !cancelRequested,
                    onClick = { vm.cancelImport() },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) { Text(if (cancelRequested) "Останавливаю…" else "Стоп") }
            } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(IntrinsicSize.Min)
                    .clip(RoundedCornerShape(50))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(50)
                    )
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(enabled = importButtonEnabled) {
                            val s = runCatching { LocalDate.parse(startDate) }.getOrNull()
                            val e = runCatching { LocalDate.parse(endDate) }.getOrNull() ?: LocalDate.now()
                            if (s != null) {
                                vm.importActivities(account.trim(), s, e, withWellness, forceRefresh)
                            }
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Загрузить тренировки",
                        color = if (importButtonEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight().padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outline
                )
                Box {
                    // Стрелка справа от кнопки "Загрузить тренировки" - скрывает редко нужный
                    // функционал "Импортировать базу из файла..." (отдельная кнопка убрана по
                    // запросу пользователя), но сама возможность осталась доступна через это меню.
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clickable(enabled = importMenuEnabled) { showImportMenu = true }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = "Ещё способы загрузки",
                            tint = if (importMenuEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            }
                        )
                    }
                    DropdownMenu(expanded = showImportMenu, onDismissRequest = { showImportMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Импортировать базу из файла...") },
                            onClick = {
                                showImportMenu = false
                                importDbLauncher.launch(arrayOf("*/*"))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Экспортировать базу в файл...") },
                            enabled = importMenuEnabled,
                            onClick = {
                                showImportMenu = false
                                exportDbLauncher.launch("garmin_${account.trim()}.db")
                            }
                        )
                    }
                }
            }
            }
            // ГЛАВНОЕ действие вкладки - построение отчёта (по запросу пользователя 2026-08-23:
            // "так же выделена кнопка 'импортировать тренировки', хотя главная - 'построить
            // отчет'") - обычная закрашенная Button (акцентная), импорт выше - OutlinedButton.
            Button(
                enabled = lastActivityDate != null && !isRunning && !busyOther,
                onClick = { vm.buildReport(account.trim()) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Построить отчёт") }

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
            if (isRunning) {
                val pct = progress
                if (pct != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { pct / 100f },
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "$pct%",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                } else {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                }
            }
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
