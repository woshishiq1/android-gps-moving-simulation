package io.github.mwarevn.fakegps.utils

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import io.github.mwarevn.fakegps.R
import io.github.mwarevn.fakegps.ui.MapActivity

/**
 * Foreground service that monitors which apps are actively accessing location.
 * Shows a notification when an app starts using location with a "Block" action.
 *
 * Uses AppOpsManager.startWatchingActive() on API 29+ for real-time callbacks.
 * Falls back to polling AppOpsManager on older APIs.
 */
class LocationAccessMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "location_monitor_channel"
        const val NOTIFICATION_ID_BASE = 5000
        const val FOREGROUND_NOTIFICATION_ID = 4999
        const val ACTION_BLOCK_APP = "io.github.mwarevn.fakegps.ACTION_BLOCK_LOCATION_APP"
        const val ACTION_UNBLOCK_APP = "io.github.mwarevn.fakegps.ACTION_UNBLOCK_LOCATION_APP"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
    }

    private val binder = MonitorBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val activeApps = mutableSetOf<String>()
    private var appOpsCallback: Any? = null // Store the callback reference for cleanup

    inner class MonitorBinder : Binder() {
        fun getService(): LocationAccessMonitorService = this@LocationAccessMonitorService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BLOCK_APP -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                if (pkg != null) {
                    PrefManager.addBlockedApp(pkg)
                    // Dismiss the notification for this app
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.cancel(pkg.hashCode() + NOTIFICATION_ID_BASE)
                    activeApps.remove(pkg)
                }
                return START_STICKY
            }
            ACTION_UNBLOCK_APP -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                if (pkg != null) {
                    PrefManager.removeBlockedApp(pkg)
                }
                return START_STICKY
            }
        }

        // Start foreground with persistent notification
        startForegroundMonitor()
        startMonitoring()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Access Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Giám sát quyền truy cập vị trí"
            }
            val highChannel = NotificationChannel(
                "${CHANNEL_ID}_alert",
                "Location Access Alert",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Cảnh báo khi ứng dụng truy cập vị trí"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            manager.createNotificationChannel(highChannel)
        }
    }

    private fun startForegroundMonitor() {
        val intent = Intent(this, MapActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_monitor)
            .setContentTitle("Location Monitor Active")
            .setContentText("Đang giám sát quyền truy cập vị trí")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        startForeground(FOREGROUND_NOTIFICATION_ID, notification)
    }

    private fun startMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startWatchingActiveApi29()
        } else {
            startPollingFallback()
        }
    }

    /**
     * API 29+ real-time monitoring using AppOpsManager.startWatchingActive()
     */
    private fun startWatchingActiveApi29() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val ops = arrayOf(
                AppOpsManager.OPSTR_FINE_LOCATION,
                AppOpsManager.OPSTR_COARSE_LOCATION
            )

            val callback = AppOpsManager.OnOpActiveChangedListener { op, uid, packageName, active ->
                handler.post {
                    if (packageName == this.packageName) return@post // Ignore ourselves
                    if (active) {
                        if (activeApps.add(packageName)) {
                            showLocationAccessNotification(packageName)
                        }
                    } else {
                        if (activeApps.remove(packageName)) {
                            val manager = getSystemService(NotificationManager::class.java)
                            manager.cancel(packageName.hashCode() + NOTIFICATION_ID_BASE)
                        }
                    }
                }
            }

            appOpsCallback = callback
            appOps.startWatchingActive(ops, { it.run() }, callback)
        } catch (e: Exception) {
            // Fallback to polling if permission not granted
            startPollingFallback()
        }
    }

    /**
     * Polling fallback for API < 29
     */
    private val pollingRunnable = object : Runnable {
        override fun run() {
            checkLocationAccessApps()
            handler.postDelayed(this, 3000L)
        }
    }

    private fun startPollingFallback() {
        handler.post(pollingRunnable)
    }

    @Suppress("DEPRECATION")
    private fun checkLocationAccessApps() {
        try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val pm = packageManager
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            
            val currentActive = mutableSetOf<String>()
            
            for (app in installedApps) {
                if (app.packageName == this.packageName) continue
                try {
                    val mode = appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_FINE_LOCATION,
                        app.uid,
                        app.packageName
                    )
                    if (mode == AppOpsManager.MODE_ALLOWED) {
                        // Check if recently used (within the last 5 seconds)
                        currentActive.add(app.packageName)
                    }
                } catch (e: Exception) { /* skip */ }
            }
            
            // Show notification for newly active apps
            for (pkg in currentActive) {
                if (activeApps.add(pkg)) {
                    showLocationAccessNotification(pkg)
                }
            }
            // Remove notifications for no longer active
            for (pkg in activeApps.toSet()) {
                if (pkg !in currentActive) {
                    activeApps.remove(pkg)
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.cancel(pkg.hashCode() + NOTIFICATION_ID_BASE)
                }
            }
        } catch (e: Exception) { /* silently fail */ }
    }

    private fun showLocationAccessNotification(packageName: String) {
        val pm = packageManager
        val appName = try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName
        }

        val isBlocked = PrefManager.blockedLocationApps.contains(packageName)

        // Block action
        val blockIntent = Intent(this, LocationAccessMonitorService::class.java).apply {
            action = ACTION_BLOCK_APP
            putExtra(EXTRA_PACKAGE_NAME, packageName)
        }
        val blockPI = PendingIntent.getService(
            this, packageName.hashCode(),
            blockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Unblock action
        val unblockIntent = Intent(this, LocationAccessMonitorService::class.java).apply {
            action = ACTION_UNBLOCK_APP
            putExtra(EXTRA_PACKAGE_NAME, packageName)
        }
        val unblockPI = PendingIntent.getService(
            this, packageName.hashCode() + 1,
            unblockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "${CHANNEL_ID}_alert")
            .setSmallIcon(R.drawable.ic_monitor)
            .setContentTitle("📍 $appName đang truy cập vị trí")
            .setContentText(packageName)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (isBlocked) {
                    addAction(R.drawable.ic_check_circle, "Bỏ chặn", unblockPI)
                } else {
                    addAction(R.drawable.ic_stop, "Chặn", blockPI)
                }
            }
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(packageName.hashCode() + NOTIFICATION_ID_BASE, notification)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        
        // Stop watching
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && appOpsCallback != null) {
            try {
                val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                appOps.stopWatchingActive(appOpsCallback as AppOpsManager.OnOpActiveChangedListener)
            } catch (e: Exception) { }
        }
        
        // Clear alert notifications
        val manager = getSystemService(NotificationManager::class.java)
        for (pkg in activeApps) {
            manager.cancel(pkg.hashCode() + NOTIFICATION_ID_BASE)
        }
        activeApps.clear()
        
        super.onDestroy()
    }
}
