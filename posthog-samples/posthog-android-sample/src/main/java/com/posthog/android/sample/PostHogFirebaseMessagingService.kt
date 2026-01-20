package com.posthog.android.sample

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.posthog.android.PostHogAndroid
import com.posthog.android.sample.R

/**
 * Firebase Cloud Messaging service for handling push notifications
 * and registering FCM tokens with PostHog
 */
class PostHogFirebaseMessagingService : FirebaseMessagingService() {

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FCM service created")
        try {
            createNotificationChannel()
        } catch (e: Exception) {
            Log.w(TAG, "FCM failed to create notification channel: ${e.message}", e)
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM onNewToken called automatically by Firebase")
        Log.d(TAG, "FCM token refreshed: $token")
        
        if (token.isBlank()) {
            Log.w(TAG, "FCM token is blank, skipping registration")
            return
        }
        
        // Register the new token with PostHog
        Log.d(TAG, "FCM registering token with PostHog")
        val success = PostHogAndroid.registerPushToken(token)
        if (success) {
            Log.d(TAG, "FCM token successfully registered with PostHog")
        } else {
            Log.e(TAG, "FCM failed to register token with PostHog")
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM message received: ${remoteMessage.messageId}")
        Log.d(TAG, "FCM From: ${remoteMessage.from}")
        Log.d(TAG, "FCM Message data payload: ${remoteMessage.data}")
        Log.d(TAG, "FCM Has notification payload: ${remoteMessage.notification != null}")
        
        // When app is in foreground, onMessageReceived is called for both notification and data messages
        // When app is in background:
        //   - Messages with notification payload: Android auto-displays, onMessageReceived is NOT called
        //   - Messages with data-only payload: onMessageReceived IS called, we must create notification
        
        var title: String? = null
        var body: String? = null
        
        // Check if message contains a notification payload
        remoteMessage.notification?.let { notification ->
            title = notification.title
            body = notification.body
            Log.d(TAG, "FCM Message Notification Title: $title")
            Log.d(TAG, "FCM Message Notification Body: $body")
        } ?: run {
            // No notification payload - check data payload for title/body
            title = remoteMessage.data["title"]
            body = remoteMessage.data["body"] ?: remoteMessage.data["message"]
            Log.d(TAG, "FCM Data-only message - Title from data: $title, Body from data: $body")
        }
        
        // Display notification if we have title or body
        if (!title.isNullOrBlank() || !body.isNullOrBlank()) {
            sendNotification(title ?: "PostHog Notification", body ?: "")
        } else {
            Log.w(TAG, "FCM Message has no title or body to display")
        }
        
        // Process additional data payload if present
        if (remoteMessage.data.isNotEmpty()) {
            Log.d(TAG, "FCM Message data payload keys: ${remoteMessage.data.keys}")
            // You can process the data payload here
            // For example, extract custom data and handle it accordingly
        }
    }

    /**
     * Create notification channel for Android 8.0+
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = getString(R.string.default_notification_channel_id)
            val channelName = getString(R.string.default_notification_channel_name)
            val channelDescription = getString(R.string.default_notification_channel_description)
            // Use HIGH importance to ensure notifications are shown even when app is in background
            val importance = NotificationManager.IMPORTANCE_HIGH
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Check if channel already exists
            val existingChannel = notificationManager.getNotificationChannel(channelId)
            if (existingChannel != null) {
                Log.d(TAG, "FCM Notification channel already exists: $channelId")
                // Verify channel importance
                if (existingChannel.importance != importance) {
                    Log.w(TAG, "FCM Notification channel has different importance: ${existingChannel.importance}, expected: $importance")
                }
                return
            }
            
            val channel = NotificationChannel(channelId, channelName, importance).apply {
                description = channelDescription
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "FCM Notification channel created: $channelId with importance: $importance")
            
            // Verify channel was created successfully
            val createdChannel = notificationManager.getNotificationChannel(channelId)
            if (createdChannel != null) {
                Log.d(TAG, "FCM Notification channel verified - importance: ${createdChannel.importance}, enabled: ${createdChannel.importance != NotificationManager.IMPORTANCE_NONE}")
            } else {
                Log.e(TAG, "FCM Notification channel creation failed - channel not found after creation")
            }
        }
    }

    /**
     * Display notification when a message is received
     */
    private fun sendNotification(title: String, messageBody: String) {
        try {
            val intent = Intent(this, com.posthog.android.sample.NormalActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            
            val channelId = getString(R.string.default_notification_channel_id)
            
            // Use app icon for notification - fallback to system icon if not available
            val smallIcon = try {
                // Try to use the app's launcher icon
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getApplicationInfo(
                        packageName,
                        PackageManager.ApplicationInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getApplicationInfo(packageName, 0)
                }
                val appIcon = appInfo.icon
                if (appIcon != 0) appIcon else android.R.drawable.ic_dialog_info
            } catch (e: Exception) {
                android.R.drawable.ic_dialog_info
            }
            
            val notificationBuilder = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(smallIcon)
                .setContentTitle(title)
                .setContentText(messageBody)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH) // Ensure notification is shown
                .setDefaults(NotificationCompat.DEFAULT_ALL) // Sound, vibration, etc.
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Show on lock screen
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Verify notification channel exists and is enabled
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = notificationManager.getNotificationChannel(channelId)
                if (channel == null) {
                    Log.e(TAG, "FCM Notification channel does not exist: $channelId - creating now")
                    createNotificationChannel()
                } else {
                    Log.d(TAG, "FCM Notification channel exists: $channelId, importance: ${channel.importance}, enabled: ${channel.importance != NotificationManager.IMPORTANCE_NONE}")
                    if (channel.importance == NotificationManager.IMPORTANCE_NONE) {
                        Log.w(TAG, "FCM Notification channel is disabled by user")
                        return
                    }
                }
            }
            
            // Check if notifications are enabled (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (!notificationManager.areNotificationsEnabled()) {
                    Log.w(TAG, "FCM Notifications are disabled by user - cannot display notification")
                    return
                }
            }
            
            // Use a unique ID for each notification
            val notificationId = System.currentTimeMillis().toInt()
            notificationManager.notify(notificationId, notificationBuilder.build())
            Log.d(TAG, "FCM Notification displayed successfully: $title - $messageBody (ID: $notificationId)")
        } catch (e: Exception) {
            Log.e(TAG, "FCM Failed to display notification: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "PostHogFCMService"
    }
}
