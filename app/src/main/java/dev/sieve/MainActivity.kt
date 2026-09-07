package dev.sieve

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dev.sieve.ui.SieveRoot
import dev.sieve.ui.theme.SieveTheme
import dev.sieve.vpn.SieveVpnService

class MainActivity : ComponentActivity() {

    /** Result of the system VPN consent dialog. Only the user can answer it. */
    private val vpnConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) SieveVpnService.start(this)
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The ongoing notification is a nicety; filtering works either way. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Graph.ensure(this)
        requestNotificationPermissionIfNeeded()

        setContent {
            SieveTheme {
                SieveRoot(onRequestStart = ::requestStart)
            }
        }
    }

    /**
     * Starts filtering, showing the system consent dialog first if this is the first run or if
     * another VPN app has taken over since.
     */
    private fun requestStart() {
        val consentIntent: Intent? = VpnService.prepare(this)
        if (consentIntent == null) {
            SieveVpnService.start(this)
        } else {
            vpnConsent.launch(consentIntent)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
