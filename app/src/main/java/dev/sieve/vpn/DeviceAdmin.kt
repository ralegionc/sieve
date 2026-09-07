package dev.sieve.vpn

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log

/**
 * Two levels of tamper resistance, both optional.
 *
 * **Device admin** is one tap inside the app. Android will not let an active device admin be
 * uninstalled — you have to deactivate it first, which is a deliberate trip through Settings
 * rather than a long-press on the icon.
 *
 * **Device owner** is stronger and cannot be granted from inside the app at all: it requires adb
 * on a device with no accounts configured, which in practice means straight after a factory reset.
 * As device owner, Sieve can pin itself as an always-on VPN, forbid VPN reconfiguration outright,
 * and block its own uninstall. See the README for the provisioning command.
 */
class SieveDeviceAdminReceiver : DeviceAdminReceiver() {

    /**
     * Shown in the confirmation dialog when the user tries to deactivate. It is the last honest
     * chance to say what deactivating actually costs.
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Deactivating this lets Sieve be uninstalled, which removes the filter and every rule " +
            "and waiting period along with it."

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin disabled")
    }

    private companion object {
        const val TAG = "Sieve/Admin"
    }
}

object DeviceAdmin {

    private const val TAG = "Sieve/Admin"

    fun component(context: Context) =
        ComponentName(context.applicationContext, SieveDeviceAdminReceiver::class.java)

    private fun policyManager(context: Context): DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)

    fun isActiveAdmin(context: Context): Boolean =
        policyManager(context)?.isAdminActive(component(context)) == true

    fun isDeviceOwner(context: Context): Boolean =
        policyManager(context)?.isDeviceOwnerApp(context.packageName) == true

    /** The system dialog that asks the user to grant device-admin rights. */
    fun activationIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Lets Sieve refuse to be uninstalled while the filter is locked.",
            )

    fun deactivate(context: Context) {
        val manager = policyManager(context) ?: return
        runCatching { manager.removeActiveAdmin(component(context)) }
            .onFailure { Log.w(TAG, "Could not remove active admin", it) }
    }

    /**
     * Applies the device-owner restrictions. Returns the ones that took effect.
     *
     * Lockdown ("block connections without VPN") is deliberately *not* enabled: Sieve routes only
     * DNS into its tunnel, so lockdown would drop every other packet and leave the phone with no
     * working connection at all.
     */
    fun applyOwnerPolicy(context: Context): List<String> {
        val manager = policyManager(context) ?: return emptyList()
        if (!isDeviceOwner(context)) return emptyList()

        val admin = component(context)
        val applied = mutableListOf<String>()

        runCatching {
            manager.setUninstallBlocked(admin, context.packageName, true)
        }.onSuccess { applied += "Uninstall blocked" }
            .onFailure { Log.w(TAG, "setUninstallBlocked failed", it) }

        runCatching {
            manager.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN)
        }.onSuccess { applied += "VPN settings locked" }
            .onFailure { Log.w(TAG, "DISALLOW_CONFIG_VPN failed", it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                manager.setAlwaysOnVpnPackage(admin, context.packageName, false)
            }.onSuccess { applied += "Pinned as always-on VPN" }
                .onFailure { Log.w(TAG, "setAlwaysOnVpnPackage failed", it) }
        }

        return applied
    }

    /** Undoes [applyOwnerPolicy], for when the phone is being handed on or repurposed. */
    fun releaseOwnerPolicy(context: Context) {
        val manager = policyManager(context) ?: return
        if (!isDeviceOwner(context)) return
        val admin = component(context)

        runCatching { manager.setUninstallBlocked(admin, context.packageName, false) }
        runCatching { manager.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { manager.setAlwaysOnVpnPackage(admin, null, false) }
        }
        runCatching { manager.clearDeviceOwnerApp(context.packageName) }
    }
}
