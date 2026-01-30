package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogInternal
import com.posthog.PostHogPushTokenCallback
import com.posthog.PostHogPushTokenError
import java.io.IOException
import java.util.concurrent.ExecutorService

// TODOdin: official recommendation is to update token once per month
// unless it changed
private const val ONE_HOUR_IN_MILLIS = 60 * 60 * 1000L

/**
 * Handles FCM push token registration: validation, storage, and API calls.
 */
@PostHogInternal
public class PostHogPushTokenRegistration(
    private val config: PostHogConfig,
    private val api: PostHogApi,
    private val pushTokenExecutor: ExecutorService,
) {
    private val pushTokenLock = Any()
    public fun register(
        token: String,
        firebaseAppId: String,
        distinctId: String,
        preferences: PostHogPreferences,
        callback: PostHogPushTokenCallback?,
    ) {
        // TODOdin: Clean up logging
        config.logger.log("FCM: registerPushToken called with token length=${token.length}, firebaseAppId=$firebaseAppId")

        if (token.isBlank()) {
            config.logger.log("FCM: registerPushToken called with blank token")
            callback?.onComplete(PostHogPushTokenError.BLANK_TOKEN, null)
            return
        }

        if (firebaseAppId.isBlank()) {
            config.logger.log("FCM: registerPushToken called with blank firebaseAppId")
            callback?.onComplete(PostHogPushTokenError.BLANK_FIREBASE_APP_ID, null)
            return
        }

        synchronized(pushTokenLock) {
            val storedToken = preferences.getValue(PostHogPreferences.FCM_TOKEN) as? String
            val lastUpdated = preferences.getValue(PostHogPreferences.FCM_TOKEN_LAST_UPDATED) as? Long ?: 0L
            val currentTime = config.dateProvider.currentDate().time

            config.logger.log("FCM: Checking stored token - storedToken=${storedToken?.take(20)}..., lastUpdated=$lastUpdated, currentTime=$currentTime")

            val tokenChanged = storedToken != token
            val shouldUpdate = tokenChanged || (currentTime - lastUpdated >= ONE_HOUR_IN_MILLIS)

            config.logger.log("FCM: Token check - tokenChanged=$tokenChanged, shouldUpdate=$shouldUpdate, timeSinceLastUpdate=${currentTime - lastUpdated}ms")

            if (!shouldUpdate) {
                config.logger.log("FCM: token registration skipped: token unchanged and less than hour since last update")
                callback?.onComplete(null, null)
                return
            }

            config.logger.log("FCM: Storing new token and firebaseAppId in preferences")
            preferences.setValue(PostHogPreferences.FCM_TOKEN, token)
            preferences.setValue(PostHogPreferences.FCM_TOKEN_LAST_UPDATED, currentTime)
            preferences.setValue(PostHogPreferences.FCM_FIREBASE_APP_ID, firebaseAppId)
        }

        config.logger.log("FCM: Preparing to register push subscription - distinctId=$distinctId, token length=${token.length}, firebaseAppId=$firebaseAppId")

        pushTokenExecutor.executeSafely {
            config.logger.log("FCM: Executing push token registration on background thread")
            try {
                config.logger.log("FCM: Calling API to register push subscription")
                api.registerPushSubscription(distinctId, token, firebaseAppId)
                config.logger.log("FCM: token registered successfully")
                callback?.onComplete(null, null)
            } catch (e: PostHogApiError) {
                config.logger.log("FCM: Failed to register token: ${e.message} (code: ${e.statusCode})")
                clearStoredPushToken(preferences)
                callback?.onComplete(pushTokenErrorFromApiError(e), e)
            } catch (e: Throwable) {
                config.logger.log("FCM: Failed to register token: ${e.message ?: "Unknown error"} (${e.javaClass.simpleName})")
                clearStoredPushToken(preferences)
                callback?.onComplete(pushTokenErrorFromThrowable(e), e)
            }
        }
    }

    private fun clearStoredPushToken(preferences: PostHogPreferences) {
        synchronized(pushTokenLock) {
            config.logger.log("FCM: Clearing stored token, timestamp, and firebaseAppId due to error")
            preferences.remove(PostHogPreferences.FCM_TOKEN)
            preferences.remove(PostHogPreferences.FCM_TOKEN_LAST_UPDATED)
            preferences.remove(PostHogPreferences.FCM_FIREBASE_APP_ID)
        }
    }

    /**
     * Automatically registers stored push token when distinctId changes.
     * Called internally after identify() and reset() to ensure the push token
     * is associated with the current distinctId. Bypasses rate limiting.
     */
    public fun registerStoredTokenIfExists(
        preferences: PostHogPreferences,
        distinctId: String,
    ) {
        val storedToken: String?
        val storedFirebaseAppId: String?

        synchronized(pushTokenLock) {
            storedToken = preferences.getValue(PostHogPreferences.FCM_TOKEN) as? String
            storedFirebaseAppId = preferences.getValue(PostHogPreferences.FCM_FIREBASE_APP_ID) as? String
        }

        if (storedToken.isNullOrBlank() || storedFirebaseAppId.isNullOrBlank()) {
            config.logger.log("FCM: maybeRegisterStoredPushToken skipped - no stored token or firebaseAppId")
            return
        }

        if (distinctId.isBlank()) {
            config.logger.log("FCM: maybeRegisterStoredPushToken skipped - distinctId is blank")
            return
        }

        config.logger.log("FCM: Auto-registering stored push token after distinctId change - distinctId=$distinctId, token length=${storedToken.length}, firebaseAppId=$storedFirebaseAppId")

        pushTokenExecutor.executeSafely {
            try {
                config.logger.log("FCM: Calling API to auto-register push subscription")
                api.registerPushSubscription(distinctId, storedToken, storedFirebaseAppId)
                config.logger.log("FCM: Auto-registration successful")
            } catch (e: PostHogApiError) {
                config.logger.log("FCM: Auto-registration failed: ${e.message} (code: ${e.statusCode})")
            } catch (e: Throwable) {
                config.logger.log("FCM: Auto-registration failed: ${e.message ?: "Unknown error"} (${e.javaClass.simpleName})")
            }
        }
    }

    private fun pushTokenErrorFromApiError(e: PostHogApiError): PostHogPushTokenError =
        when (e.statusCode) {
            401 -> PostHogPushTokenError.UNAUTHORIZED
            in 500..599 -> PostHogPushTokenError.SERVER_ERROR
            else -> PostHogPushTokenError.INVALID_INPUT // 4xx client errors (400, 403, 404, etc.)
        }

    private fun pushTokenErrorFromThrowable(e: Throwable): PostHogPushTokenError =
        if (e is IOException) PostHogPushTokenError.NETWORK_ERROR
        else PostHogPushTokenError.OTHER
}
