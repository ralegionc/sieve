package dev.sieve.vpn

import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.sieve.Graph
import dev.sieve.MainActivity
import dev.sieve.R
import dev.sieve.data.Lock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Quick Settings toggle.
 *
 * If consent has not been granted yet the tile cannot show the system dialog itself, so it hands
 * off to [MainActivity], which can.
 */
class SieveTileService : TileService() {

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        scope?.cancel()
        scope = CoroutineScope(Dispatchers.Main + Job()).also { active ->
            active.launch {
                SieveVpnService.state.collect { render(it) }
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        Graph.ensure(this)
        if (SieveVpnService.state.value == SieveVpnService.State.STOPPED) {
            if (VpnService.prepare(this) == null) {
                SieveVpnService.start(this)
            } else {
                val intent = Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivityAndCollapse(
                    android.app.PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
        } else {
            // Turning it off is a change that weakens the filter, so it goes through the lock.
            scope?.launch {
                val preferences = Graph.settings.preferences.first()
                if (Lock.stateOf(preferences).allowsWeakening) {
                    SieveVpnService.stop(this@SieveTileService)
                } else {
                    startActivityAndCollapse(
                        android.app.PendingIntent.getActivity(
                            this@SieveTileService,
                            0,
                            Intent(this@SieveTileService, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            android.app.PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                }
            }
        }
    }

    private fun render(state: SieveVpnService.State) {
        val tile: Tile = qsTile ?: return
        tile.state = when (state) {
            SieveVpnService.State.RUNNING -> Tile.STATE_ACTIVE
            SieveVpnService.State.STARTING -> Tile.STATE_UNAVAILABLE
            SieveVpnService.State.STOPPED -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.app_name)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_shield)
        tile.updateTile()
    }
}
