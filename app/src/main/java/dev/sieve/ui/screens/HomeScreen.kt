package dev.sieve.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sieve.data.LockMode
import dev.sieve.data.UnlockState
import dev.sieve.ui.SieveViewModel
import dev.sieve.vpn.SieveVpnService
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun HomeScreen(viewModel: SieveViewModel, onRequestStart: () -> Unit) {
    val state by viewModel.vpnState.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val sessionQueries by viewModel.sessionQueries.collectAsStateWithLifecycle()
    val sessionBlocked by viewModel.sessionBlocked.collectAsStateWithLifecycle()
    val unlockState by viewModel.unlockState.collectAsStateWithLifecycle()

    val listSize = preferences?.blocklistSize ?: 0
    val running = state == SieveVpnService.State.RUNNING
    val lockMode = preferences?.lockMode ?: LockMode.NONE
    val strict = preferences?.strictMode ?: true

    // Re-read when the tunnel comes up: that is when a bypass would start to matter.
    val privateDns = remember(running) { viewModel.privateDnsHostname }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (running) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (state == SieveVpnService.State.STARTING) {
                    CircularProgressIndicator(modifier = Modifier.size(56.dp))
                } else {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = if (running) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = when (state) {
                        SieveVpnService.State.RUNNING -> "Filtering is on"
                        SieveVpnService.State.STARTING -> "Starting…"
                        SieveVpnService.State.STOPPED -> "Filtering is off"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (listSize > 0) {
                        "${format(listSize.toLong())} domains in the active lists"
                    } else {
                        "No lists downloaded yet — update them under Lists"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))

                if (running) {
                    TurnOffControl(viewModel, lockMode, unlockState)
                } else {
                    Button(
                        onClick = onRequestStart,
                        enabled = state == SieveVpnService.State.STOPPED,
                    ) { Text("Turn on") }
                }
            }
        }

        if (running && strict && privateDns != null) {
            Spacer(Modifier.height(16.dp))
            WarningCard(
                title = "Private DNS is bypassing Sieve",
                body = "Android is resolving through $privateDns over TLS on port 853, which no " +
                    "app can intercept. Open Settings › Network & internet › Private DNS and " +
                    "choose Off or Automatic.",
            )
        }

        if (running && !strict) {
            Spacer(Modifier.height(16.dp))
            WarningCard(
                title = "Strict mode is off",
                body = "Any browser with Secure DNS enabled, and any app that talks to 8.8.8.8 " +
                    "directly, is skipping the filter entirely. Turn strict mode on in Settings.",
            )
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatTile(
                modifier = Modifier.weight(1f),
                value = format(sessionBlocked),
                label = "Blocked this session",
            )
            StatTile(
                modifier = Modifier.weight(1f),
                value = format(sessionQueries),
                label = "Lookups this session",
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatTile(
                modifier = Modifier.weight(1f),
                value = format(preferences?.lifetimeBlocked ?: 0L),
                label = "Blocked all time",
            )
            StatTile(
                modifier = Modifier.weight(1f),
                value = percentage(
                    preferences?.lifetimeBlocked ?: 0L,
                    preferences?.lifetimeQueries ?: 0L,
                ),
                label = "Share of lookups blocked",
            )
        }

        Spacer(Modifier.height(20.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "How it works",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Sieve runs a local VPN that only carries DNS. When an app looks up a " +
                        "domain, the lookup is checked against your lists; blocked names get " +
                        "an immediate NXDOMAIN and never reach the network. Everything else " +
                        "goes straight to your normal resolver, so browsing speed is unchanged.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Nothing leaves your phone. No account, no remote logging, no traffic " +
                        "proxying — the tunnel is local and the recent-activity list lives only " +
                        "in memory.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The off switch, or whatever the lock has replaced it with.
 *
 * Nothing here decides policy — the view model refuses a stop it should not honour regardless of
 * what this shows. This only has to make the refusal legible.
 */
@Composable
private fun TurnOffControl(
    viewModel: SieveViewModel,
    lockMode: LockMode,
    unlockState: UnlockState,
) {
    var showPassphrase by remember { mutableStateOf(false) }

    when {
        unlockState.allowsWeakening ->
            OutlinedButton(onClick = viewModel::stopFiltering) { Text("Turn off") }

        unlockState is UnlockState.Waiting -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Unlocks in ${formatRemaining(unlockState.remainingMillis)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "The countdown is stored on disk. Closing the app, force-stopping it or " +
                    "rebooting will not skip it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = viewModel::cancelUnlock) { Text("Cancel the request") }
        }

        lockMode == LockMode.COOLDOWN ->
            OutlinedButton(onClick = viewModel::requestUnlock) { Text("Request to turn off") }

        lockMode == LockMode.PASSPHRASE ->
            OutlinedButton(onClick = { showPassphrase = true }) { Text("Enter passphrase") }

        else -> OutlinedButton(onClick = viewModel::stopFiltering) { Text("Turn off") }
    }

    if (showPassphrase) {
        PassphrasePrompt(
            viewModel = viewModel,
            onDismiss = {
                showPassphrase = false
                viewModel.dismissPassphraseError()
            },
        )
    }
}

@Composable
private fun PassphrasePrompt(viewModel: SieveViewModel, onDismiss: () -> Unit) {
    val error by viewModel.passphraseError.collectAsStateWithLifecycle()
    var candidate by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter the passphrase") },
        text = {
            Column {
                OutlinedTextField(
                    value = candidate,
                    onValueChange = {
                        candidate = it
                        viewModel.dismissPassphraseError()
                    },
                    isError = error,
                    label = { Text("Passphrase") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "That is not the passphrase.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.submitPassphrase(candidate) }) { Text("Unlock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WarningCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun StatTile(modifier: Modifier = Modifier, value: String, label: String) {
    Card(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatRemaining(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = TimeUnit.SECONDS.toHours(totalSeconds)
    val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}

private fun format(value: Long): String = NumberFormat.getIntegerInstance().format(value)

private fun percentage(part: Long, total: Long): String =
    if (total == 0L) "—" else "${(part * 100 / total)}%"
