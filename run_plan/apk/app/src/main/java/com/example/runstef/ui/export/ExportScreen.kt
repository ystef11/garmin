package com.example.runstef.ui.export

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.runstef.data.PlanRepository
import com.example.runstef.data.SavedPlan
import com.example.runstef.ui.common.GarminAccountSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CROSS_TYPES = listOf(
    "cycling" to "Велосипед",
    "swimming" to "Плавание",
    "lap_swimming" to "Плавание в бассейне",
    "cardio_training" to "Кардио (устар.)",
    "other" to "Прочий кросс",
    "strength_training" to "Силовая"
)

@Composable
fun ExportScreen(preselectedFilePath: String? = null) {
    val context = LocalContext.current
    val planRepo = remember { PlanRepository(context) }
    val vm: ExportViewModel = viewModel()

    var plans by remember { mutableStateOf<List<SavedPlan>>(emptyList()) }
    var selectedPlan by remember { mutableStateOf<SavedPlan?>(null) }
    LaunchedEffect(Unit) {
        plans = withContext(Dispatchers.IO) { planRepo.listPlans() }
        if (selectedPlan == null) {
            selectedPlan = plans.firstOrNull { it.filePath == preselectedFilePath } ?: plans.firstOrNull()
        }
    }

    // Общие для обеих вкладок опции. dryRun/testFirstWeek — отладочные, скрыты в
    // сворачиваемом разделе «Отладка»; testFirstWeek реально используется только Garmin-веткой.
    var dryRun by rememberSaveable { mutableStateOf(false) }
    var testFirstWeek by rememberSaveable { mutableStateOf(false) }
    val skipCross = remember { mutableStateOf(setOf<String>()) }
    var tabIndex by rememberSaveable { mutableStateOf(0) }

    val logLines by vm.log.collectAsState()
    val isRunning by vm.isRunning.collectAsState()
    val cancelRequested by vm.cancelRequested.collectAsState()
    val busyOther by vm.busyOther.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Экспорт плана", style = MaterialTheme.typography.headlineSmall)
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
            PlanPicker(plans, selectedPlan, onSelect = { selectedPlan = it })

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            ExpandableSection(title = "Пропустить кросс") {
                CrossTypeChips(selected = skipCross.value, onChange = { skipCross.value = it })
            }
            ExpandableSection(title = "Отладка") {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = dryRun, onCheckedChange = { dryRun = it })
                    Text("Тестовый прогон (ничего не отправлять)")
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = testFirstWeek, onCheckedChange = { testFirstWeek = it })
                    Text("Только первая неделя (Garmin)")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            SecondaryTabRow(selectedTabIndex = tabIndex) {
                Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("Garmin Connect") })
                Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("intervals.icu") })
            }

            val planFilePath = selectedPlan?.filePath
            if (busyOther) {
                Text(
                    "Идёт загрузка аналитики — экспорт станет доступен после её окончания.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            if (tabIndex == 0) {
                GarminTab(
                    initialAccounts = vm.savedGarminAccounts(),
                    enabled = !isRunning && !busyOther && planFilePath != null,
                    isRunning = isRunning,
                    cancelRequested = cancelRequested,
                    onCancel = { vm.cancelExport() },
                    onSubmit = { account, allDates, fromDate ->
                        planFilePath?.let {
                            vm.exportToGarmin(it, account, skipCross.value, dryRun, testFirstWeek, allDates, fromDate)
                        }
                    }
                )
            } else {
                IntervalsTab(
                    vm = vm,
                    enabled = !isRunning && !busyOther && planFilePath != null,
                    isRunning = isRunning,
                    cancelRequested = cancelRequested,
                    onCancel = { vm.cancelExport() },
                    onSubmit = { apiKey, athlete ->
                        planFilePath?.let { vm.exportToIntervals(it, apiKey, athlete, skipCross.value, dryRun) }
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

/** Сворачиваемый раздел опций (кросс-фильтр, отладочные флаги) — чтобы не занимать место
 * на экране, когда не нужен. Свёрнут по умолчанию. */
@Composable
private fun ExpandableSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(if (expanded) "▾" else "▸", modifier = Modifier.padding(end = 8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        if (expanded) {
            Column(modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) { content() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanPicker(plans: List<SavedPlan>, selected: SavedPlan?, onSelect: (SavedPlan) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && plans.isNotEmpty(),
        onExpandedChange = { if (plans.isNotEmpty()) expanded = it }
    ) {
        OutlinedTextField(
            value = selected?.plan?.meta?.name ?: "Нет сохранённых планов",
            onValueChange = {},
            readOnly = true,
            label = { Text("План") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = plans.isNotEmpty())
        )
        DropdownMenu(expanded = expanded && plans.isNotEmpty(), onDismissRequest = { expanded = false }) {
            plans.forEach { p ->
                DropdownMenuItem(text = { Text(p.plan.meta.name) }, onClick = { onSelect(p); expanded = false })
            }
        }
    }
}

@Composable
private fun CrossTypeChips(selected: Set<String>, onChange: (Set<String>) -> Unit) {
    Column {
        CROSS_TYPES.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { (key, label) ->
                    Row(
                        modifier = Modifier.padding(end = 12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = key in selected,
                            onCheckedChange = { checked ->
                                onChange(if (checked) selected + key else selected - key)
                            }
                        )
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun GarminTab(
    initialAccounts: List<String>,
    enabled: Boolean,
    isRunning: Boolean,
    cancelRequested: Boolean,
    onCancel: () -> Unit,
    onSubmit: (account: String, allDates: Boolean, fromDate: java.time.LocalDate?) -> Unit
) {
    var savedAccounts by remember { mutableStateOf(initialAccounts) }
    var account by remember { mutableStateOf(savedAccounts.firstOrNull() ?: "") }
    var allDates by remember { mutableStateOf(false) }
    var fromDateStr by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        GarminAccountSelector(
            savedAccounts = savedAccounts,
            account = account,
            onAccountSelected = { account = it },
            onAccountAdded = { newAccount ->
                if (newAccount !in savedAccounts) savedAccounts = savedAccounts + newAccount
                account = newAccount
            }
        )
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Checkbox(checked = allDates, onCheckedChange = { allDates = it })
            Text("Весь план целиком (включая прошедшие даты)")
        }
        if (!allDates) {
            OutlinedTextField(
                value = fromDateStr, onValueChange = { fromDateStr = it },
                label = { Text("с даты: ГГГГ-ММ-ДД (пусто = сегодня)") },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }
        Text(
            "Перед загрузкой уже загруженные тренировки этого плана с такими же именами " +
                "удаляются автоматически — повторная загрузка не создаёт дублей и подхватывает " +
                "изменения плана.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
        // Пока идёт отправка, на месте кнопки отправки показываем «Стоп» — по месту, где
        // пользователь только что нажал отправку (см. аналогичное решение в AnalyticsScreen).
        if (isRunning) {
            OutlinedButton(
                enabled = !cancelRequested,
                onClick = onCancel,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) { Text(if (cancelRequested) "Останавливаю…" else "Стоп") }
        } else {
            Button(
                enabled = enabled && account.isNotBlank(),
                onClick = {
                    val fromDate = fromDateStr.takeIf { it.isNotBlank() }?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                    onSubmit(account.trim(), allDates, fromDate)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) { Text("Отправить в Garmin Connect") }
        }
    }
}

@Composable
private fun IntervalsTab(
    vm: ExportViewModel,
    enabled: Boolean,
    isRunning: Boolean,
    cancelRequested: Boolean,
    onCancel: () -> Unit,
    onSubmit: (apiKey: String, athlete: String) -> Unit
) {
    var apiKey by remember { mutableStateOf("") }
    var athlete by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val (savedKey, savedAthlete) = vm.loadIntervalsCreds()
        apiKey = savedKey
        athlete = savedAthlete
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        OutlinedTextField(
            value = apiKey, onValueChange = { apiKey = it },
            label = { Text("API key") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = athlete, onValueChange = { athlete = it },
            label = { Text("Athlete ID (например i123456)") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        Text(
            "Перед загрузкой ранее загруженные события этого плана удаляются автоматически " +
                "— повторная загрузка не создаёт дублей.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
        if (isRunning) {
            OutlinedButton(
                enabled = !cancelRequested,
                onClick = onCancel,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) { Text(if (cancelRequested) "Останавливаю…" else "Стоп") }
        } else {
            Button(
                enabled = enabled && apiKey.isNotBlank() && athlete.isNotBlank(),
                onClick = { onSubmit(apiKey.trim(), athlete.trim()) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) { Text("Отправить в intervals.icu") }
        }
    }
}
