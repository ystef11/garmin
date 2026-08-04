package com.example.runstef.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Состав экрана строится из [state] (см. HomeViewModel/ConfigRepository) — заголовок, подпись
 * и список инструментов приходят из конфига (бэк или, если сети/кэша ещё нет, встроенный в
 * apk fallback), а не захардкожены здесь.
 */
@Composable
fun HomeScreen(state: HomeUiState, onOpenTool: (CalculatorTool) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        item {
            Text(
                text = state.title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            if (state.subtitle.isNotBlank()) {
                Text(
                    text = state.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }
        }
        items(state.tools) { tool ->
            ToolCard(tool = tool, onClick = { onOpenTool(tool) })
        }
    }
}

@Composable
private fun ToolCard(tool: CalculatorTool, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = tool.icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                Text(text = tool.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = tool.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.width(20.dp)
            )
        }
    }
}
