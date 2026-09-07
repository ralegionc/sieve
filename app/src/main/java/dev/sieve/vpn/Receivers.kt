package dev.sieve.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import dev.sieve.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Backs the "Stop" action on the ongoing notification. */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SieveVpnService.stop(context)
    }
}

/**
 * Restarts filtering after a reboot or an app update, if the user asked for that.
 *
 * [VpnService.prepare] returns null once consent has been granted, which is what makes an
 * unattended start legal — we can never grant it ourselves.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val pending = goAsync()
        Graph.ensure(context)
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val startOnBoot = Graph.settings.preferences.first().startOnBoot
                if (startOnBoot && VpnService.prepare(context) == null) {
                    SieveVpnService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
