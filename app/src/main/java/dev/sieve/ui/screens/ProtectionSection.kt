package dev.sieve.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sieve.data.Lock
import dev.sieve.data.LockMode
import dev.sieve.data.SievePreferences
import dev.sieve.data.TunnelMode
import dev.sieve.data.UnlockState
import dev.sieve.ui.SieveViewModel
import dev.sieve.vpn.DeviceAdmin

/**
 * The protection controls: strict mode, and the lock that stands between you and weakening the
 * filter. Changing anything here is itself gated — see [SieveViewModel.armLock].
 */
@Composable
fun ProtectionSection(
    viewModel: SieveViewModel,
    preferences: SievePreferences,
    unlockState: UnlockState,
) {
    var pendingMode by remember { mutableStateOf<LockMode?>(null) }
    val locked = preferences.lockMode != LockMode.NONE
    val canChange = unlockState.allowsWeakening

    SettingsCard("Coverage") {
        Text(
            text = "How much of the phone's traffic goes through Sieve.",
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TunnelMode.entries.forEach { mode ->
            val selected = preferences.tunnelMode == mode
            // Narrowing coverage weakens the filter; widening it never does.
            val allowed = selected || mode == TunnelMode.FULL || canChange
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = allowed) { viewModel.setTunnelMode(mode) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(
                    selected = selected,
                    enabled = allowed,
                    onClick = { viewModel.setTunnelMode(mode) },
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        mode.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (preferences.tunnelMode == TunnelMode.FULL) {
            Text(
                text = "Now turn on Settings › Network & internet › VPN › ⚙ Sieve › Always-on " +
                    "VPN, and Block connections without VPN underneath it. Nothing reaches the " +
                    "network unless Sieve is running.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    SettingsCard("Protection") {
        SwitchRow(
            title = "Strict mode",
            subtitle = "Blocks encrypted-DNS providers and pulls hardcoded resolvers such as " +
                "8.8.8.8 into the filter. Without this, any browser with Secure DNS walks " +
                "straight past Sieve.",
            checked = preferences.strictMode,
            onCheckedChange = viewModel::setStrictMode,
        )

        Spacer(Modifier.height(4.dp))
        Text(
            text = "Lock",
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Applies to everything that weakens the filter — turning it off, disabling a " +
                "list, adding an allow rule, or changing this setting.",
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LockMode.entries.forEach { mode ->
            val selected = preferences.lockMode == mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = selected || !locked || canChange) {
                        if (mode == LockMode.PASSPHRASE) pendingMode = mode else {
                            viewModel.armLock(mode, cooldownMinutes = preferences.cooldownMinutes)
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(
                    selected = selected,
                    enabled = selected || !locked || canChange,
                    onClick = {
                        if (mode == LockMode.PASSPHRASE) pendingMode = mode else {
                            viewModel.armLock(mode, cooldownMinutes = preferences.cooldownMinutes)
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        mode.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (preferences.lockMode == LockMode.COOLDOWN) {
            Text(
                text = "Waiting period",
                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Lock.COOLDOWN_CHOICES.forEach { minutes ->
                    val isCurrent = preferences.cooldownMinutes == minutes
                    // Lengthening the wait is always allowed; shortening it weakens the lock.
                    val allowed = canChange || minutes > preferences.cooldownMinutes
                    OutlinedButton(
                        onClick = { viewModel.armLock(LockMode.COOLDOWN, cooldownMinutes = minutes) },
                        enabled = allowed && !isCurrent,
                    ) { Text(formatMinutes(minutes)) }
                }
            }
        }

        if (locked && !canChange) {
            Text(
                text = "Locked. Unlock from the Status tab to change any of this.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    UninstallProtectionCard(canChange, preferences.tunnelMode == TunnelMode.FULL)

    if (pendingMode == LockMode.PASSPHRASE) {
        PassphraseSetupDialog(
            onDismiss = { pendingMode = null },
            onConfirm = { passphrase ->
                viewModel.armLock(LockMode.PASSPHRASE, passphrase = passphrase)
                pendingMode = null
            },
        )
    }
}

/**
 * Uninstall protection. Device admin is one tap; device owner cannot be granted from inside the
 * app at all, so when it is absent this only explains where to read about it.
 */
@Composable
private fun UninstallProtectionCard(canChange: Boolean, fullTunnel: Boolean) {
    val context = LocalContext.current
    // Recomposition after returning from the system dialog re-reads the real state.
    var isAdmin by remember { mutableStateOf(DeviceAdmin.isActiveAdmin(context)) }
    val isOwner = remember { DeviceAdmin.isDeviceOwner(context) }
    var uninstallBlocked by remember { mutableStateOf(DeviceAdmin.isUninstallBlocked(context)) }
    var maximum by remember { mutableStateOf(DeviceAdmin.isMaximumApplied(context)) }

    val activate = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { isAdmin = DeviceAdmin.isActiveAdmin(context) }

    fun reapply(withMaximum: Boolean) {
        DeviceAdmin.applyOwnerPolicy(context, lockdown = fullTunnel, maximum = withMaximum)
        uninstallBlocked = DeviceAdmin.isUninstallBlocked(context)
        maximum = DeviceAdmin.isMaximumApplied(context)
    }

    SettingsCard("Uninstall protection") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = when {
                        isOwner && uninstallBlocked -> "Device owner, enforcing"
                        isOwner -> "Device owner, not yet enforcing"
                        isAdmin -> "Device admin active"
                        else -> "Not protected"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when {
                        isOwner && uninstallBlocked ->
                            "Uninstall is refused and VPN settings are locked. Only adb undoes it."
                        isOwner ->
                            "Sieve is device owner but has applied nothing yet. Enforce to block " +
                                "uninstall and lock VPN settings."
                        isAdmin ->
                            "Uninstall needs deactivating here or in Android settings first. " +
                                "That is a speed bump, not a wall — anyone can do it in four taps."
                        else ->
                            "Android will not uninstall an active device admin. Granting this " +
                                "adds a deliberate step before the app can be removed."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            when {
                isOwner && !uninstallBlocked -> Button(onClick = { reapply(maximum) }) {
                    Text("Enforce")
                }

                isOwner -> Unit

                isAdmin -> OutlinedButton(
                    onClick = {
                        DeviceAdmin.deactivate(context)
                        isAdmin = DeviceAdmin.isActiveAdmin(context)
                    },
                    enabled = canChange,
                ) { Text("Remove") }

                else -> Button(onClick = { activate.launch(DeviceAdmin.activationIntent(context)) }) {
                    Text("Grant")
                }
            }
        }

        if (isOwner && uninstallBlocked) {
            HorizontalDivider()
            SwitchRow(
                title = "Also block clearing data and force-stop",
                subtitle = "Closes the two remaining holes: wiping Sieve's storage to reset the " +
                    "lock, and force-stopping it. Also blocks safe mode. The cost is that it " +
                    "applies to every app on the phone, not just this one.",
                checked = maximum,
                // Relaxing it back is a weakening change, so it goes through the lock.
                onCheckedChange = { wanted -> if (wanted || canChange) reapply(wanted) },
            )
        }
    }
}

/**
 * Generates the passphrase rather than letting the user choose one. A passphrase you invented is
 * one you can remember, which defeats the purpose — the point is to hand it to somebody else.
 */
@Composable
private fun PassphraseSetupDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val generated = remember { Lock.generatePassphrase() }
    var confirmation by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a passphrase") },
        text = {
            Column {
                Text(
                    "Send this to someone you trust, or write it down and put it somewhere " +
                        "inconvenient. Do not keep it on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = generated,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text("Type it back to confirm") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(generated) },
                enabled = confirmation.trim() == generated,
            ) { Text("Lock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun formatMinutes(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 && minutes < 1440 -> "${minutes / 60} h"
    else -> "${minutes / 1440} d"
}
