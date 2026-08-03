package com.example.runstef.ui.export

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.runstef.data.PlanRepository
import com.example.runstef.data.SavedPlan
import kotlinx.coroutines.launch

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

    var plans by remember { mutableStateOf(planRepo.listPlans()) }
    var selectedPlan by remember {
        mutableStateOf(plans.firstOrNull { it.filePath == preselectedFilePath } ?: plans.firstOrNull())
    }
    LaunchedEffect(Unit) {
        plans = planRepo.listPlans()
        if (selectedPlan == null) {
            selectedPlan = plans.firstOrNull { it.filePath == preselectedFilePath } ?: plans.firstOrNull()
        }
    }

    var dryRun by rememberSaveable { mutableStateOf(false) }
    val skipCross = remember { mutableStateOf(setOf<String>()) }
    var tabIndex by rememberSaveable { mutableStateOf(0) }

    val logLines by vm.log.collectAsState()
    val isRunning by vm.isRunning.collectAsState()
    val mfaRequested by vm.mfaRequested.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Экспорт плана", style = MaterialTheme.typography.headlineSmall)
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
            PlanPicker(plans, selectedPlan, onSelect = { selectedPlan = it })

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Text("Общие опции", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = dryRun, onCheckedChange = { dryRun = it })
                Text("Тестовый прогон (ничего не отправлять)")
            }
            Text("Пропустить кросс:", style = MaterialTheme.typography.bodyMedium)
            CrossTypeChips(selected = skipCross.value, onChange = { skipCross.value = it })

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            SecondaryTabRow(selectedTabIndex = tabIndex) {
                Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("Garmin Connect") })
                Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("intervals.icu") })
            }

            val plan = selectedPlan?.plan
            if (tabIndex == 0) {
                GarminTab(
                    savedAccounts = vm.savedGarminAccounts(),
                    hasSavedToken = { acc -> vm.hasSavedGarminToken(acc) },
                    enabled = !isRunning && plan != null,
                    onSubmit = { account, password, testFirstWeek, clearAll, clearPast, before ->
                        plan?.let {
                            vm.exportToGarmin(it, account, password, skipCross.value, dryRun, testFirstWeek, clearAll, clearPast, before)
                        }
                    }
                )
            } else {
                IntervalsTab(
                    vm = vm,
                    enabled = !isRunning && plan != null,
                    onSubmit = { apiKey, athlete, clear ->
                        plan?.let { vm.exportToIntervals(it, apiKey, athlete, skipCross.value, dryRun, clear) }
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

    if (mfaRequested) {
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { vm.cancelMfa() },
            title = { Text("Код 2FA Garmin") },
            text = {
                OutlinedTextField(
                    value = code, onValueChange = { code = it },
                    label = { Text("Код из приложения/SMS") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = { TextButton(onClick = { vm.submitMfaCode(code) }) { Text("Подтвердить") } },
            dismissButton = { TextButton(onClick = { vm.cancelMfa() }) { Text("Отмена") } }
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GarminTab(
    savedAccounts: List<String>,
    hasSavedToken: (String) -> Boolean,
    enabled: Boolean,
    onSubmit: (account: String, password: String, testFirstWeek: Boolean, clearAll: Boolean, clearPast: Boolean, before: java.time.LocalDate?) -> Unit
) {
    var account by remember { mutableStateOf(savedAccounts.firstOrNull() ?: "") }
    var password by remember { mutableStateOf("") }
    // Пока true — в поле пароля вместо реального значения показывается декоративная маска
    // "••••••••", сигнализирующая, что для аккаунта уже есть сохранённый вход. Сбрасывается,
    // как только пользователь реально заходит в поле, — тогда оно становится обычным пустым
    // полем ввода нового пароля.
    var passwordRevealed by remember { mutableStateOf(false) }
    var accountMenuExpanded by remember { mutableStateOf(false) }
    var testFirstWeek by remember { mutableStateOf(false) }
    var clearAll by remember { mutableStateOf(false) }
    var clearPast by remember { mutableStateOf(false) }
    var beforeDate by remember { mutableStateOf("") }

    val accountHasSavedToken = hasSavedToken(account)
    val showSavedPasswordMask = accountHasSavedToken && !passwordRevealed

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        ExposedDropdownMenuBox(
            expanded = accountMenuExpanded && savedAccounts.isNotEmpty(),
            onExpandedChange = { if (savedAccounts.isNotEmpty()) accountMenuExpanded = it }
        ) {
            OutlinedTextField(
                value = account,
                onValueChange = { account = it; password = ""; passwordRevealed = false },
                label = { Text("Аккаунт Garmin (email)") },
                trailingIcon = {
                    if (savedAccounts.isNotEmpty()) {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = accountMenuExpanded)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
            )
            DropdownMenu(
                expanded = accountMenuExpanded && savedAccounts.isNotEmpty(),
                onDismissRequest = { accountMenuExpanded = false }
            ) {
                savedAccounts.forEach { acc ->
                    DropdownMenuItem(
                        text = { Text(acc) },
                        onClick = { account = acc; password = ""; passwordRevealed = false; accountMenuExpanded = false }
                    )
                }
            }
        }
        OutlinedTextField(
            value = if (showSavedPasswordMask) "••••••••" else password,
            onValueChange = { password = it },
            label = { Text("Пароль") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .onFocusChanged { state -> if (state.isFocused && showSavedPasswordMask) passwordRevealed = true }
        )
        Text(
            when {
                showSavedPasswordMask -> "Сохранён вход для этого аккаунта — можно отправлять без ввода пароля."
                accountHasSavedToken -> "Пароль изменён — при отправке выполню повторный вход и обновлю сохранённый токен."
                else -> "Нужен для первого входа в этот аккаунт."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = testFirstWeek, onCheckedChange = { testFirstWeek = it })
            Text("Только первая неделя")
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = clearAll, onCheckedChange = { clearAll = it; if (it) clearPast = false })
            Text("Удалить все тренировки плана перед загрузкой")
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = clearPast, onCheckedChange = { clearPast = it; if (it) clearAll = false })
            Text("Удалить только прошедшие")
        }
        if (clearPast) {
            OutlinedTextField(
                value = beforeDate, onValueChange = { beforeDate = it },
                label = { Text("до: ГГГГ-ММ-ДД (пусто = сегодня)") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Button(
            enabled = enabled && account.isNotBlank(),
            onClick = {
                val before = beforeDate.takeIf { it.isNotBlank() }?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                onSubmit(account.trim(), password, testFirstWeek, clearAll, clearPast, before)
            },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) { Text("Отправить в Garmin Connect") }
    }
}

@Composable
private fun IntervalsTab(
    vm: ExportViewModel,
    enabled: Boolean,
    onSubmit: (apiKey: String, athlete: String, clear: Boolean) -> Unit
) {
    var apiKey by remember { mutableStateOf("") }
    var athlete by remember { mutableStateOf("") }
    var clear by remember { mutableStateOf(false) }
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
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = clear, onCheckedChange = { clear = it })
            Text("Удалить ранее загруженные события плана перед загрузкой")
        }
        Button(
            enabled = enabled && apiKey.isNotBlank() && athlete.isNotBlank(),
            onClick = { onSubmit(apiKey.trim(), athlete.trim(), clear) },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) { Text("Отправить в intervals.icu") }
    }
}
