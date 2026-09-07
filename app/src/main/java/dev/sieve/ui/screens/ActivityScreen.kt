package dev.sieve.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sieve.filter.FilterEngine
import dev.sieve.filter.QueryLog
import dev.sieve.ui.SieveViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ActivityScreen(viewModel: SieveViewModel) {
    val entries by viewModel.logEntries.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    var blockedOnly by remember { mutableStateOf(false) }

    val visible = remember(entries, blockedOnly) {
        if (blockedOnly) entries.filter { it.verdict.isBlocked } else entries
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = blockedOnly,
                onClick = { blockedOnly = !blockedOnly },
                label = { Text("Blocked only") },
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = viewModel::clearActivity) { Text("Clear") }
        }

        when {
            preferences?.logQueries == false -> Placeholder(
                "Activity logging is off. Turn it back on in Settings if you want to see " +
                    "which lookups are being blocked.",
            )

            visible.isEmpty() -> Placeholder(
                "Nothing yet. Recent lookups appear here while filtering is on — they are " +
                    "kept in memory only and cleared when the app stops.",
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(visible, key = { it.timestamp.toString() + it.domain }) { entry ->
                    LogRow(entry)
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: QueryLog.Entry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.domain,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = TIME_FORMAT.format(Date(entry.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AssistChip(
            onClick = {},
            enabled = false,
            label = { Text(entry.verdict.shortLabel()) },
            colors = AssistChipDefaults.assistChipColors(
                disabledLabelColor = if (entry.verdict.isBlocked) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ),
        )
    }
}

@Composable
private fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            modifier = Modifier.padding(32.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun FilterEngine.Verdict.shortLabel(): String = when (this) {
    FilterEngine.Verdict.ALLOWED -> "allowed"
    FilterEngine.Verdict.ALLOWED_BY_RULE -> "allow rule"
    FilterEngine.Verdict.BLOCKED_BY_LIST -> "blocked"
    FilterEngine.Verdict.BLOCKED_BY_RULE -> "block rule"
    FilterEngine.Verdict.BLOCKED_BY_GUARD -> "bypass guard"
}

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
