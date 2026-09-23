package com.example.runstef.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Модалка управления сохранёнными аккаунтами Garmin — открывается на вкладке «Аналитика» по
 * карандашу рядом с выбором аккаунта (см. GarminAccountSelector: карандаш вместо «＋» рисуется,
 * когда сохранён хотя бы один аккаунт — если ни одного ещё нет, управлять пока нечем, и «＋»
 * сразу открывает AddGarminAccountDialog, минуя эту модалку).
 *
 * Список всех сохранённых аккаунтов, у каждого — кнопка-шеврон с раскрывающимся меню действий:
 *  - «Сделать основным» — только если аккаунтов больше одного И этот аккаунт ещё не основной
 *    (при единственном аккаунте он и так основной автоматически, см. GarminTokenStore.primaryAccount);
 *  - «Изменить пароль» — открывает AddGarminAccountDialog с заблокированным полем e-mail (тот же
 *    логин, только повторный вход с новым паролем перезаписывает токен);
 *  - «Удалить» — с подтверждением; убирает сохранённый токен аккаунта (локальная база аналитики
 *    на устройстве не трогается — при повторном добавлении того же аккаунта она снова доступна).
 *
 * Основной аккаунт подсвечен фоном строки и звёздочкой — именно из его локальной базы аналитики
 * подставляются данные в калькуляторы (pano/hrmax/curvol/res, см. ui/home/ToolUrlBuilder.kt).
 * Внизу — «Добавить аккаунт», открывающая ту же AddGarminAccountDialog, что и на самих вкладках.
 */
@Composable
fun AccountManagementDialog(
    accounts: List<String>,
    primaryAccount: String,
    onDismiss: () -> Unit,
    onMakePrimary: (String) -> Unit,
    onDeleteAccount: (String) -> Unit,
    onAccountAdded: (String) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var passwordChangeFor by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Аккаунты Garmin") },
        text = {
            if (accounts.isEmpty()) {
                Text("Нет сохранённых аккаунтов.", style = MaterialTheme.typography.bodySmall)
            } else {
                Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    accounts.forEachIndexed { index, acc ->
                        AccountRow(
                            account = acc,
                            isPrimary = acc == primaryAccount,
                            canMakePrimary = accounts.size > 1 && acc != primaryAccount,
                            onMakePrimary = { onMakePrimary(acc) },
                            onChangePassword = { passwordChangeFor = acc },
                            onDelete = { pendingDelete = acc }
                        )
                        if (index < accounts.lastIndex) HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text("Добавить аккаунт")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )

    if (showAddDialog) {
        AddGarminAccountDialog(
            onDismiss = { showAddDialog = false },
            onAccountAdded = { newAccount ->
                showAddDialog = false
                onAccountAdded(newAccount)
            }
        )
    }

    passwordChangeFor?.let { acc ->
        AddGarminAccountDialog(
            lockedEmail = acc,
            onDismiss = { passwordChangeFor = null },
            onAccountAdded = { updated ->
                passwordChangeFor = null
                onAccountAdded(updated)
            }
        )
    }

    pendingDelete?.let { acc ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить аккаунт?") },
            text = {
                Text("Аккаунт \"$acc\" будет удалён из приложения. Локальная база аналитики на устройстве не удаляется.")
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteAccount(acc)
                    pendingDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun AccountRow(
    account: String,
    isPrimary: Boolean,
    canMakePrimary: Boolean,
    onMakePrimary: () -> Unit,
    onChangePassword: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isPrimary) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isPrimary) {
            Icon(
                Icons.Filled.Star,
                contentDescription = "Основной аккаунт",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        Text(
            account,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isPrimary) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Filled.ExpandMore, contentDescription = "Действия с аккаунтом $account")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                if (canMakePrimary) {
                    DropdownMenuItem(
                        text = { Text("Сделать основным") },
                        leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null) },
                        onClick = { menuExpanded = false; onMakePrimary() }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Изменить пароль") },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = { menuExpanded = false; onChangePassword() }
                )
                DropdownMenuItem(
                    text = { Text("Удалить") },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = { menuExpanded = false; onDelete() }
                )
            }
        }
    }
}
