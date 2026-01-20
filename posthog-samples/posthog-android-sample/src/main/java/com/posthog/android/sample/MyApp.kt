package com.posthog.android.sample

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.StrictMode
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.OnCompleteListener
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.posthog.PostHogOnFeatureFlags
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.sample.R

class MyApp : Application() {
    private var isFirebaseAvailable = false
    
    override fun onCreate() {
        super.onCreate()

        enableStrictMode()
        
        // Initialize Firebase if google-services.json is present
        // If not available, Firebase features will be disabled gracefully
        isFirebaseAvailable = initializeFirebase()
        
        // Ensure notification channel is created early (before any notifications arrive)
        // Only create if Firebase is available
        if (isFirebaseAvailable) {
            createNotificationChannelEarly()
        }

        // Use a default API key for the sample app
        // In production, you should use your actual PostHog API key
        val apiKey = "phc_crqS8UmHy8fAhZRjNTqPr5HGsAnZEsEGsSRs8S9vKy4" // Default for local testing
        
        // For local development, you can override the host
        // Note: For Android emulator, use "http://10.0.2.2:8000" instead of "http://localhost:8000"
        // For physical device, use your development machine's IP address (e.g., "http://192.168.1.100:8000")
        val host = "http://10.0.2.2:8000" // Optional: override host for local testing
        
        val config = PostHogAndroidConfig(apiKey, host = host).apply {
                debug = true
                flushAt = 1
                captureDeepLinks = false
                captureApplicationLifecycleEvents = false
                captureScreenViews = false
                sessionReplay = true
                preloadFeatureFlags = true
                sendFeatureFlagEvent = false
                onFeatureFlags = PostHogOnFeatureFlags { print("feature flags loaded") }
                addBeforeSend { event ->
                    if (event.event == "test_event") {
                        null
                    } else {
                        event
                    }
                }
                sessionReplayConfig.maskAllTextInputs = true
                sessionReplayConfig.maskAllImages = false
                sessionReplayConfig.captureLogcat = false
                sessionReplayConfig.screenshot = true
                surveys = false
                errorTrackingConfig.autoCapture = false
            }
        PostHogAndroid.setup(this, config)
        
        // Register FCM token with PostHog (only if Firebase is available)
        // Note: onNewToken() in PostHogFirebaseMessagingService will be called automatically
        // by Firebase when tokens are refreshed, but we need to manually fetch on first install
        if (isFirebaseAvailable) {
            Log.d(TAG, "FCM initializing token registration")
            registerFCMToken()
        } else {
            Log.i(TAG, "FCM token registration skipped - Firebase not available")
        }
    }
    
    /**
     * Initialize Firebase if google-services.json is present.
     * @return true if Firebase was successfully initialized, false otherwise
     */
    private fun initializeFirebase(): Boolean {
        return try {
            // Check if Firebase is already initialized
            val apps = FirebaseApp.getApps(this)
            if (apps.isEmpty()) {
                Log.d(TAG, "FCM Firebase not initialized, initializing now")
                FirebaseApp.initializeApp(this)
                Log.d(TAG, "FCM Firebase initialized successfully")
                true
            } else {
                Log.d(TAG, "FCM Firebase already initialized (${apps.size} app(s))")
                true
            }
        } catch (e: Exception) {
            // google-services.json is likely missing - Firebase features will be disabled
            Log.w(TAG, "FCM Firebase not available (google-services.json may be missing): ${e.message}")
            Log.i(TAG, "FCM Push notification features will be disabled. Add google-services.json to enable them.")
            false
        }
    }
    
    private fun registerFCMToken() {
        try {
            Log.d(TAG, "FCM requesting token from FirebaseMessaging")
            FirebaseMessaging.getInstance().token.addOnCompleteListener(OnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.w(TAG, "FCM fetching registration token failed", task.exception)
                    task.exception?.let {
                        Log.w(TAG, "FCM error details: ${it.message}", it)
                    }
                    return@OnCompleteListener
                }

                // Get new FCM registration token
                val token = task.result
                if (token.isNullOrBlank()) {
                    Log.w(TAG, "FCM received null or blank token")
                    return@OnCompleteListener
                }
                
                Log.d(TAG, "FCM registration token retrieved: $token")
                Log.d(TAG, "FCM registering token with PostHog")

                // Register token with PostHog
                val success = PostHogAndroid.registerPushToken(token)
                if (success) {
                    Log.d(TAG, "FCM token successfully registered with PostHog")
                } else {
                    Log.e(TAG, "FCM failed to register token with PostHog")
                }
            })
        } catch (e: Exception) {
            // Firebase might not be initialized or available
            Log.w(TAG, "FCM Firebase Messaging not available: ${e.message}", e)
        }
    }

    /**
     * Create notification channel early in Application onCreate
     * This ensures the channel exists before any notifications arrive
     */
    private fun createNotificationChannelEarly() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = getString(R.string.default_notification_channel_id)
            val channelName = getString(R.string.default_notification_channel_name)
            val channelDescription = getString(R.string.default_notification_channel_description)
            val importance = NotificationManager.IMPORTANCE_HIGH
            
            val channel = NotificationChannel(channelId, channelName, importance).apply {
                description = channelDescription
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            
            val notificationManager = ContextCompat.getSystemService(this, NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
            Log.d(TAG, "FCM Notification channel created early in Application onCreate: $channelId")
        }
    }

    private fun enableStrictMode() {
        if (BuildConfig.DEBUG) {
            val threadPolicyBuilder = StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog()
            val vmPolicyBuilder = StrictMode.VmPolicy.Builder().detectAll().penaltyLog()

            StrictMode.setThreadPolicy(threadPolicyBuilder.build())
            StrictMode.setVmPolicy(vmPolicyBuilder.build())
        }
    }
    
    companion object {
        private const val TAG = "MyApp"
    }
}
