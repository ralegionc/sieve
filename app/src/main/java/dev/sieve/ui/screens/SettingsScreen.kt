package dev.sieve.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sieve.data.UpstreamMode
import dev.sieve.ui.SieveViewModel

@Composable
fun SettingsScreen(viewModel: SieveViewModel) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val unlockState by viewModel.unlockState.collectAsStateWithLifecycle()
    val current = preferences ?: return

    var customUpstream by remember(current.customUpstream) {
        mutableStateOf(current.customUpstream)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ProtectionSection(viewModel, current, unlockState) }

        item {
            SettingsCard("Behaviour") {
                SwitchRow(
                    title = "Start when the phone boots",
                    subtitle = "Resumes filtering after a restart, without opening the app.",
                    checked = current.startOnBoot,
                    onCheckedChange = viewModel::setStartOnBoot,
                )
                HorizontalDivider()
                SwitchRow(
                    title = "Record recent activity",
                    subtitle = "Keeps the last 250 lookups in memory for the Activity tab. " +
                        "Never written to disk.",
                    checked = current.logQueries,
                    onCheckedChange = viewModel::setLogQueries,
                )
                HorizontalDivider()
                SwitchRow(
                    title = "Update lists automatically",
                    subtitle = "Once a day, on unmetered Wi-Fi.",
                    checked = current.autoUpdate,
                    onCheckedChange = viewModel::setAutoUpdate,
                )
            }
        }

        item {
            SettingsCard("Upstream resolver") {
                Text(
                    text = "Where allowed lookups are sent. Blocked ones never leave the phone.",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                UpstreamMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setUpstream(mode, customUpstream) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = current.upstreamMode == mode,
                            onClick = { viewModel.setUpstream(mode, customUpstream) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (current.upstreamMode == UpstreamMode.CUSTOM) {
                    OutlinedTextField(
                        value = customUpstream,
                        onValueChange = { customUpstream = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        label = { Text("IP addresses, comma separated") },
                        placeholder = { Text("9.9.9.9, 149.112.112.112") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.setUpstream(UpstreamMode.CUSTOM, customUpstream) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) { Text("Apply") }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        item {
            SettingsCard("Statistics") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Lifetime totals", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${current.lifetimeBlocked} blocked of ${current.lifetimeQueries}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = viewModel::resetStatistics) { Text("Reset") }
                }
            }
        }

        item {
            SettingsCard("What this still does not cover") {
                Text(
                    text = """
                        Strict mode closes the encrypted-DNS and hardcoded-resolver bypasses.
                        These remain:

                        • Private DNS in Android settings talks to its server over TLS on port
                        853, past the tunnel. No app can intercept it — set it to Off or
                        Automatic.

                        • Clearing this app's storage resets the lock. Only device owner mode
                        can prevent that.

                        • Force-stopping the app from Android settings stops filtering until
                        you open it again.

                        • It blocks whole domains, never individual pages.

                        • A second device, or a factory reset, defeats anything on this one.

                        For a lock you cannot undo alone, see Always-on VPN in the README.
                    """.trimIndent(),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
internal fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Card(Modifier.fillMaxWidth()) {
            Column { content() }
        }
    }
}

@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
