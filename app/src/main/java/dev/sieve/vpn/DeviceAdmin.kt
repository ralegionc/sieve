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

    fun isUninstallBlocked(context: Context): Boolean {
        val manager = policyManager(context) ?: return false
        if (!isDeviceOwner(context)) return false
        return runCatching {
            manager.isUninstallBlocked(component(context), context.packageName)
        }.getOrDefault(false)
    }

    /** True once the restrictions that also cover clearing data and force-stopping are in force. */
    fun isMaximumApplied(context: Context): Boolean =
        hasRestriction(context, UserManager.DISALLOW_APPS_CONTROL)

    private fun hasRestriction(context: Context, key: String): Boolean =
        context.getSystemService(UserManager::class.java)?.hasUserRestriction(key) == true

    /**
     * Applies the device-owner restrictions. Returns the ones that took effect.
     *
     * [lockdown] pins Sieve as an always-on VPN and blocks traffic while it is down. That is only
     * safe in full-tunnel mode: a DNS-only tunnel routes nothing else, so lockdown would drop
     * every other packet and leave the phone with no working connection.
     *
     * [maximum] adds `DISALLOW_APPS_CONTROL`, which is what actually closes the "clear the app's
     * storage" and "force stop it" holes — but it is a device-wide restriction, not one scoped to
     * Sieve, so it takes those actions away for every app. That is a real cost, and the reason it
     * is a separate choice rather than part of the default.
     *
     * Deliberately *not* applied: `DISALLOW_FACTORY_RESET` and `DISALLOW_DEBUGGING_FEATURES`.
     * Together they would remove every way back out, and a phone you cannot recover is a worse
     * outcome than a filter you can eventually defeat.
     */
    fun applyOwnerPolicy(
        context: Context,
        lockdown: Boolean = false,
        maximum: Boolean = false,
    ): List<String> {
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

        runCatching {
            manager.setAlwaysOnVpnPackage(admin, context.packageName, lockdown)
        }.onSuccess {
            applied += if (lockdown) "Always-on with lockdown" else "Pinned as always-on VPN"
        }.onFailure { Log.w(TAG, "setAlwaysOnVpnPackage failed", it) }

        if (maximum) {
            runCatching {
                manager.addUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL)
            }.onSuccess { applied += "Clearing data and force-stop blocked" }
                .onFailure { Log.w(TAG, "DISALLOW_APPS_CONTROL failed", it) }

            // Safe mode would otherwise boot with third-party apps disabled, filter included.
            runCatching {
                manager.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            }.onSuccess { applied += "Safe mode blocked" }
                .onFailure { Log.w(TAG, "DISALLOW_SAFE_BOOT failed", it) }
        } else {
            runCatching { manager.clearUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL) }
            runCatching { manager.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT) }
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
        runCatching { manager.clearUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL) }
        runCatching { manager.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT) }
        runCatching { manager.setAlwaysOnVpnPackage(admin, null, false) }
        runCatching { manager.clearDeviceOwnerApp(context.packageName) }
    }
}
