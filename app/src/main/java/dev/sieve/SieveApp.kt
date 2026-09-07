package dev.sieve

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.sieve.data.SieveSettings
import dev.sieve.filter.BlocklistRepository
import dev.sieve.work.BlocklistUpdateWorker

class SieveApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Graph.install(this)
        createNotificationChannel()
        BlocklistUpdateWorker.ensureScheduled(this)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_STATUS,
            getString(R.string.channel_status),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_status_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_STATUS = "sieve.status"
    }
}

/**
 * Hand-rolled service locator. The dependency graph is three objects deep; a DI framework would
 * be more machinery than the app has moving parts.
 */
object Graph {

    lateinit var settings: SieveSettings
        private set

    lateinit var blocklists: BlocklistRepository
        private set

    fun install(context: Context) {
        if (::settings.isInitialized) return
        val application = context.applicationContext
        settings = SieveSettings(application)
        blocklists = BlocklistRepository(application, settings)
    }

    /** For entry points such as receivers, where the Application may not have run yet. */
    fun ensure(context: Context) = install(context)
}
