package io.github.mwarevn.movingsimulation.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.mwarevn.movingsimulation.R
import io.github.mwarevn.movingsimulation.ui.MapActivity

/**
 * Foreground Service to keep navigation running when app is in background
 * Prevents Android from killing the app process during navigation
 */
class NavigationForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "navigation_foreground_service_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP_NAVIGATION = "action_stop_navigation"

        /**
         * Start the foreground service
         */
        fun start(context: Context, speedKmh: Double, distanceKm: Double) {
            val intent = Intent(context, NavigationForegroundService::class.java).apply {
                putExtra("speed_kmh", speedKmh)
                putExtra("distance_km", distanceKm)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop the foreground service
         */
        fun stop(context: Context) {
            val intent = Intent(context, NavigationForegroundService::class.java)
            context.stopService(intent)
        }

        /**
         * Update notification with current navigation info
         * CRITICAL: This must be callable from background thread
         * Uses startService (not startForegroundService) since service is already running
         */
        fun updateNotification(context: Context, speedKmh: Double, distanceKm: Double, traveledKm: Double) {
            try {
                val intent = Intent(context, NavigationForegroundService::class.java).apply {
                    action = "update"
                    putExtra("speed_kmh", speedKmh)
                    putExtra("distance_km", distanceKm)
                    putExtra("traveled_km", traveledKm)
                }
                // Use startService for updates (service is already foreground)
                // This is more efficient than startForegroundService for frequent updates
                context.startService(intent)
            } catch (e: Exception) {
                android.util.Log.e("NavigationForegroundService", "Failed to update notification: ${e.message}", e)
            }
        }
    }

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        android.util.Log.d("NavigationForegroundService", "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "update" -> {
                // Update notification
                val speedKmh = intent.getDoubleExtra("speed_kmh", 0.0)
                val distanceKm = intent.getDoubleExtra("distance_km", 0.0)
                val traveledKm = intent.getDoubleExtra("traveled_km", 0.0)
                updateNotification(speedKmh, distanceKm, traveledKm)
            }
            ACTION_STOP_NAVIGATION -> {
                // Stop navigation and service
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(Service.STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Start foreground service
                val speedKmh = intent?.getDoubleExtra("speed_kmh", 0.0) ?: 0.0
                val distanceKm = intent?.getDoubleExtra("distance_km", 0.0) ?: 0.0
                val notification = createNotification(speedKmh, distanceKm, 0.0)
                startForeground(NOTIFICATION_ID, notification)
                android.util.Log.d("NavigationForegroundService", "Foreground service started")
            }
        }
        
        // Return START_STICKY to restart service if killed by system
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("NavigationForegroundService", "Service destroyed")
    }

    /**
     * Create notification channel for Android O+
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Navigation Service",
                NotificationManager.IMPORTANCE_LOW // Low priority to not disturb user
            ).apply {
                description = "Keeps navigation running in background"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Create notification for foreground service
     */
    private fun createNotification(
        speedKmh: Double,
        distanceKm: Double,
        traveledKm: Double
    ): Notification {
        // Intent to open app when notification is clicked
        val openAppIntent = Intent(this, MapActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent to stop navigation
        val stopIntent = Intent(this, NavigationForegroundService::class.java).apply {
            action = ACTION_STOP_NAVIGATION
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val speedText = "${speedKmh.toInt()} km/h"
        val progressText = if (distanceKm > 0) {
            "${String.format("%.2f", traveledKm)}km / ${String.format("%.2f", distanceKm)}km"
        } else {
            "Đang di chuyển..."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Đang điều hướng")
            .setContentText("$speedText • $progressText")
            .setSmallIcon(R.drawable.ic_navigation)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                R.drawable.ic_navigation,
                "Dừng",
                stopPendingIntent
            )
            .setOngoing(true) // Cannot be dismissed by swiping
            .setPriority(NotificationCompat.PRIORITY_LOW) // Low priority
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    /**
     * Update notification with current navigation info
     */
    private fun updateNotification(
        speedKmh: Double,
        distanceKm: Double,
        traveledKm: Double
    ) {
        val notification = createNotification(speedKmh, distanceKm, traveledKm)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}

