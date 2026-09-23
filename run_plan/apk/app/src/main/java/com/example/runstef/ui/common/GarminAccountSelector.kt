package com.example.runstef.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Выбор аккаунта Garmin — общий для вкладок «Экспорт» и «Аналитика». Поле только ВЫБИРАЕТ уже
 * сохранённый аккаунт (без пароля — токен уже лежит в GarminTokenStore); вход/добавление нового
 * аккаунта — по кнопке «＋» (или карандашу, см. ниже), открывающей AddGarminAccountDialog. Так
 * пароль вообще не отображается там, где он не нужен (обычная работа — только когда реально
 * логинишься/перелогиниваешься).
 *
 * [savedAccounts] и [account] — состояние экрана-владельца (см. ExportScreen/AnalyticsScreen);
 * [onAccountAdded] вызывается после успешного входа в новый (или уже существующий, если это
 * повторный логин/обновление пароля/смена пароля) аккаунт — экран должен обновить список
 * сохранённых аккаунтов и выставить его текущим.
 *
 * [showManagement] (по умолчанию выключен — вкладка «Экспорт» ведёт себя как раньше, без изменений):
 * когда включён и уже есть хотя бы один сохранённый аккаунт, вместо «＋» рисуется карандаш,
 * открывающий AccountManagementDialog — список всех аккаунтов с действиями "сделать основным"/
 * "изменить пароль"/"удалить" и подсветкой основного (см. [primaryAccount],
 * GarminTokenStore.primaryAccount() — из него калькуляторы берут данные, см. ToolUrlBuilder.kt).
 * Если сохранённых аккаунтов ещё нет, кнопка остаётся «＋» и сразу открывает AddGarminAccountDialog
 * — управлять пока нечем.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GarminAccountSelector(
    savedAccounts: List<String>,
    account: String,
    onAccountSelected: (String) -> Unit,
    onAccountAdded: (String) -> Unit,
    label: String = "Аккаунт Garmin",
    showManagement: Boolean = false,
    primaryAccount: String = "",
    onPrimaryAccountChanged: (String) -> Unit = {},
    onAccountDeleted: (String) -> Unit = {},
    // Вызывается при закрытии AccountManagementDialog (кнопкой "Закрыть" или тапом мимо) — экран
    // может, например, подставить в поле выбора основной аккаунт (см. AnalyticsScreen), если за
    // время работы с модалкой он сменился.
    onManagementDialogClosed: () -> Unit = {}
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showManagementDialog by remember { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (savedAccounts.isEmpty()) {
            OutlinedTextField(
                value = "Нет сохранённых аккаунтов",
                onValueChange = {},
                readOnly = true,
                enabled = false,
                label = { Text(label) },
                modifier = Modifier.weight(1f)
            )
        } else {
            ExposedDropdownMenuBox(
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = account.ifBlank { savedAccounts.first() },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(label) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                )
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    savedAccounts.forEach { acc ->
                        DropdownMenuItem(text = { Text(acc) }, onClick = {
                            onAccountSelected(acc); menuExpanded = false
                        })
                    }
                }
            }
        }
        val manageExisting = showManagement && savedAccounts.isNotEmpty()
        IconButton(
            onClick = { if (manageExisting) showManagementDialog = true else showAddDialog = true },
            modifier = Modifier.padding(start = 4.dp)
        ) {
            if (manageExisting) {
                // Карандаш (редактирование уже сохранённых аккаунтов) со значком "+" поверх —
                // подсказывает, что отсюда же можно и добавить ещё один аккаунт, не только
                // управлять существующими (см. AccountManagementDialog, куда ведёт эта кнопка).
                Box {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Управление аккаунтами Garmin",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(12.dp)
                            .background(MaterialTheme.colorScheme.surface, CircleShape)
                    )
                }
            } else {
                Icon(Icons.Filled.Add, contentDescription = "Добавить аккаунт Garmin", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }

    if (showAddDialog) {
        AddGarminAccountDialog(
            onDismiss = { showAddDialog = false },
            onAccountAdded = { newAccount ->
                showAddDialog = false
                onAccountAdded(newAccount)
            }
        )
    }

    if (showManagementDialog) {
        AccountManagementDialog(
            accounts = savedAccounts,
            primaryAccount = primaryAccount,
            onDismiss = {
                showManagementDialog = false
                onManagementDialogClosed()
            },
            onMakePrimary = onPrimaryAccountChanged,
            onDeleteAccount = onAccountDeleted,
            onAccountAdded = onAccountAdded
        )
    }
}
