package dev.sieve.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sieve.ui.SieveViewModel

private enum class RuleTab(val label: String) { BLOCK("Always block"), ALLOW("Never block") }

@Composable
fun RulesScreen(viewModel: SieveViewModel) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(RuleTab.BLOCK) }
    var draft by remember { mutableStateOf("") }

    val entries = when (tab) {
        RuleTab.BLOCK -> preferences?.customBlocked.orEmpty()
        RuleTab.ALLOW -> preferences?.allowed.orEmpty()
    }.sorted()

    fun submit() {
        val value = draft
        if (value.isBlank()) return
        when (tab) {
            RuleTab.BLOCK -> viewModel.addBlocked(value)
            RuleTab.ALLOW -> viewModel.addAllowed(value)
        }
        draft = ""
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuleTab.entries.forEach { option ->
                    FilterChip(
                        selected = tab == option,
                        onClick = { tab = option },
                        label = { Text(option.label) },
                    )
                }
            }
        }

        item {
            Text(
                text = when (tab) {
                    RuleTab.BLOCK -> "Domains blocked regardless of what the downloaded lists say."
                    RuleTab.ALLOW -> "Domains always allowed. An allow rule beats every list, " +
                        "so this is how you undo a false positive."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("example.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                IconButton(onClick = ::submit) {
                    Icon(Icons.Filled.Add, contentDescription = "Add rule")
                }
            }
        }

        if (entries.isEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "No rules yet. Subdomains are covered automatically — blocking " +
                        "example.com also blocks cdn.example.com.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(entries, key = { it }) { domain ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = domain,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    IconButton(
                        onClick = {
                            when (tab) {
                                RuleTab.BLOCK -> viewModel.removeBlocked(domain)
                                RuleTab.ALLOW -> viewModel.removeAllowed(domain)
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove $domain")
                    }
                }
            }
        }
    }
}
