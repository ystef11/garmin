package com.example.runstef.ui.plans

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.runstef.data.PlanHtmlParser
import com.example.runstef.data.PlanRepository
import com.example.runstef.data.SavedPlan
import com.example.runstef.network.PlanUrlLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PlansScreen(
    onExportPlan: (String) -> Unit,
    onViewPlan: (String) -> Unit,
    onCreatePlan: () -> Unit = {},
    onOpenUrl: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val repo = remember { PlanRepository(context) }
    var plans by remember { mutableStateOf(repo.listPlans()) }
    var pendingDelete by remember { mutableStateOf<SavedPlan?>(null) }
    var pendingRename by remember { mutableStateOf<SavedPlan?>(null) }
    var fabMenuExpanded by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { plans = repo.listPlans() }

    // «Из файла» — импорт ранее скачанного HTML-плана через системный файловый менеджер.
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val html = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                if (html != null) {
                    val saved = repo.savePlan(html)
                    plans = repo.listPlans()
                    scope.launch {
                        snackbarHostState.showSnackbar("План «${saved.plan.meta.name}» импортирован")
                    }
                } else {
                    scope.launch { snackbarHostState.showSnackbar("Не удалось прочитать файл") }
                }
            } catch (e: Exception) {
                scope.launch { snackbarHostState.showSnackbar("Ошибка импорта: ${e.message}") }
            }
        }
    }

    Scaffold(
        // Внешний Scaffold в MainActivity (см. RunstefApp) уже резервирует отступ под статус-бар
        // для этого маршрута (передаёт его через innerPadding) — если не занулить здесь, этот,
        // внутренний Scaffold резервирует его ЕЩЁ РАЗ (contentWindowInsets по умолчанию), из-за
        // чего над списком планов была лишняя пустая полоса (тот же баг, что уже чинили для
        // WebView-экранов — см. contentWindowInsets в ToolWebViewScreen/RunstefApp).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { fabMenuExpanded = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Добавить план")
                }
                DropdownMenu(expanded = fabMenuExpanded, onDismissRequest = { fabMenuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Создать план") },
                        leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                        onClick = { fabMenuExpanded = false; onCreatePlan() }
                    )
                    DropdownMenuItem(
                        text = { Text("Из файла") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null) },
                        onClick = {
                            fabMenuExpanded = false
                            importLauncher.launch(arrayOf("text/html", "application/xhtml+xml", "*/*"))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("По URL") },
                        leadingIcon = { Icon(Icons.Filled.Language, contentDescription = null) },
                        onClick = { fabMenuExpanded = false; showUrlDialog = true }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (plans.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                    Text(
                        text = "Пока нет сохранённых планов.\nСкачайте план в разделе «Главная» → " +
                            "«Калькулятор беговых планов», нажмите «+» ниже, или импортируйте файл/URL.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                var draggedItemPath by remember { mutableStateOf<String?>(null) }
                var dragOffsetPx by remember { mutableStateOf(0f) }
                var rowHeightPx by remember { mutableStateOf(0f) }

                LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    items(plans, key = { it.filePath }) { saved ->
                        val isDragging = saved.filePath == draggedItemPath
                        Box(
                            modifier = Modifier
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer { translationY = if (isDragging) dragOffsetPx else 0f }
                                .onGloballyPositioned { coords ->
                                    if (rowHeightPx == 0f) rowHeightPx = coords.size.height.toFloat()
                                }
                                .pointerInput(saved.filePath) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { draggedItemPath = saved.filePath; dragOffsetPx = 0f },
                                        onDragEnd = { draggedItemPath = null; dragOffsetPx = 0f },
                                        onDragCancel = { draggedItemPath = null; dragOffsetPx = 0f },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetPx += dragAmount.y
                                            val h = rowHeightPx
                                            if (h > 0f) {
                                                val currentIndex = plans.indexOfFirst { it.filePath == saved.filePath }
                                                val shift = (dragOffsetPx / h).toInt()
                                                if (currentIndex >= 0 && shift != 0) {
                                                    val targetIndex = (currentIndex + shift).coerceIn(0, plans.lastIndex)
                                                    if (targetIndex != currentIndex) {
                                                        val mutable = plans.toMutableList()
                                                        val item = mutable.removeAt(currentIndex)
                                                        mutable.add(targetIndex, item)
                                                        plans = mutable
                                                        repo.saveOrder(mutable.map { it.fileName })
                                                        dragOffsetPx -= shift * h
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                        ) {
                            PlanCard(
                                saved = saved,
                                onView = { onViewPlan(saved.filePath) },
                                onRename = { pendingRename = saved },
                                onSend = {
                                    val uri = repo.getShareUri(saved.filePath)
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/html"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_SUBJECT, saved.plan.meta.name)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Отправить план"))
                                },
                                onExport = { onExportPlan(saved.filePath) },
                                onDelete = { pendingDelete = saved }
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { toDelete ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить план?") },
            text = { Text(toDelete.plan.meta.name) },
            confirmButton = {
                TextButton(onClick = {
                    repo.deletePlan(toDelete.filePath)
                    plans = repo.listPlans()
                    pendingDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Отмена") }
            }
        )
    }

    pendingRename?.let { toRename ->
        var name by remember(toRename.filePath) { mutableStateOf(toRename.plan.meta.name) }
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("Переименовать план") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newName = name.trim()
                        if (newName.isNotEmpty()) {
                            repo.renamePlan(toRename.filePath, newName)
                            plans = repo.listPlans()
                        }
                        pendingRename = null
                    }
                ) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) { Text("Отмена") }
            }
        )
    }

    if (showUrlDialog) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("Открыть план по URL") },
            text = {
                Column {
                    Text(
                        "Страница будет закэширована и доступна офлайн; при наличии интернета — " +
                            "обновляется автоматически.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        singleLine = true,
                        label = { Text("https://...") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = url.isNotBlank(),
                    onClick = {
                        val trimmed = url.trim()
                        showUrlDialog = false
                        if (trimmed.isNotEmpty()) {
                            scope.launch {
                                val html = withContext(Dispatchers.IO) {
                                    runCatching { PlanUrlLoader.loadHtml(context, trimmed) }.getOrNull()
                                }
                                when {
                                    html != null && PlanHtmlParser.looksLikePlanHtml(html) -> {
                                        try {
                                            val saved = repo.savePlan(html)
                                            plans = repo.listPlans()
                                            snackbarHostState.showSnackbar("План «${saved.plan.meta.name}» сохранён из URL")
                                            onViewPlan(saved.filePath)
                                        } catch (e: Exception) {
                                            snackbarHostState.showSnackbar("Не удалось разобрать план: ${e.message}")
                                        }
                                    }
                                    html != null -> {
                                        // Не похоже на план — просто открываем страницу как обычный сайт (с офлайн-кэшем).
                                        onOpenUrl(trimmed)
                                    }
                                    else -> {
                                        snackbarHostState.showSnackbar("Нет соединения и нет кэша для этой страницы")
                                    }
                                }
                            }
                        }
                    }
                ) { Text("Открыть") }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun PlanCard(
    saved: SavedPlan,
    onView: () -> Unit,
    onRename: () -> Unit,
    onSend: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable(onClick = onView),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = saved.plan.meta.name, style = MaterialTheme.typography.titleMedium)
                    saved.plan.meta.marathon?.let {
                        Text(
                            text = "Цель: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "${saved.plan.workouts.size} тренировок · сохранено ${dateFmt.format(Date(saved.savedAtMillis))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Ещё")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Переименовать") },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = { menuExpanded = false; onRename() }
                        )
                        DropdownMenuItem(
                            text = { Text("Отправить") },
                            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                            onClick = { menuExpanded = false; onSend() }
                        )
                        DropdownMenuItem(
                            text = { Text("Экспорт") },
                            leadingIcon = { Icon(Icons.Filled.UploadFile, contentDescription = null) },
                            onClick = { menuExpanded = false; onExport() }
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
    }
}
