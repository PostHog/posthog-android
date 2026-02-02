package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.PostHogInternal
import com.posthog.PostHogPushTokenCallback
import com.posthog.PostHogPushTokenError
import java.io.IOException
import java.util.concurrent.ExecutorService

private const val ONE_DAY_IN_MILLIS = 24 * 60 * 60 * 1000L

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
        fcmProjectId: String,
        distinctId: String,
        preferences: PostHogPreferences,
        callback: PostHogPushTokenCallback?,
    ) {
        if (token.isBlank()) {
            pushTokenExecutor.executeSafely { callback?.onComplete(PostHogPushTokenError.BLANK_TOKEN, null) }
            return
        }

        if (fcmProjectId.isBlank()) {
            pushTokenExecutor.executeSafely { callback?.onComplete(PostHogPushTokenError.BLANK_FIREBASE_PROJECT_ID, null) }
            return
        }

        synchronized(pushTokenLock) {
            val storedToken = preferences.getValue(PostHogPreferences.FCM_TOKEN) as? String
            val lastUpdated = preferences.getValue(PostHogPreferences.FCM_TOKEN_LAST_UPDATED) as? Long ?: 0L
            val currentTime = config.dateProvider.currentDate().time

            val tokenChanged = storedToken != token
            val shouldUpdate =
                tokenChanged || (currentTime - lastUpdated >= ONE_DAY_IN_MILLIS)

            if (!shouldUpdate) {
                pushTokenExecutor.executeSafely { callback?.onComplete(null, null) }
                return
            }
            // Do not persist here; persist only after successful API response to avoid race
            // where registerStoredTokenIfExists could send a token that never registered.
        }

        pushTokenExecutor.executeSafely {
            try {
                api.registerPushSubscription(distinctId, token, fcmProjectId)
                synchronized(pushTokenLock) {
                    preferences.setValue(PostHogPreferences.FCM_TOKEN, token)
                    preferences.setValue(PostHogPreferences.FCM_TOKEN_LAST_UPDATED, config.dateProvider.currentDate().time)
                    preferences.setValue(PostHogPreferences.FCM_PROJECT_ID, fcmProjectId)
                }
                callback?.onComplete(null, null)
            } catch (e: PostHogApiError) {
                config.logger.log("Push token registration failed: ${e.message} (code: ${e.statusCode})")
                clearStoredPushToken(preferences)
                callback?.onComplete(pushTokenErrorFromApiError(e), e)
            } catch (e: Throwable) {
                config.logger.log("Push token registration failed: ${e.message ?: e.javaClass.simpleName}")
                clearStoredPushToken(preferences)
                callback?.onComplete(pushTokenErrorFromThrowable(e), e)
            }
        }
    }

    private fun clearStoredPushToken(preferences: PostHogPreferences) {
        synchronized(pushTokenLock) {
            preferences.remove(PostHogPreferences.FCM_TOKEN)
            preferences.remove(PostHogPreferences.FCM_TOKEN_LAST_UPDATED)
            preferences.remove(PostHogPreferences.FCM_PROJECT_ID)
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
        val storedFcmProjectId: String?

        synchronized(pushTokenLock) {
            storedToken = preferences.getValue(PostHogPreferences.FCM_TOKEN) as? String
            storedFcmProjectId = preferences.getValue(PostHogPreferences.FCM_PROJECT_ID) as? String
        }

        if (storedToken.isNullOrBlank() || storedFcmProjectId.isNullOrBlank()) {
            // Clear inconsistent state
            if (!storedToken.isNullOrBlank() && storedFcmProjectId.isNullOrBlank()) {
                clearStoredPushToken(preferences)
            }
            return
        }

        if (distinctId.isBlank()) {
            return
        }

        pushTokenExecutor.executeSafely {
            try {
                api.registerPushSubscription(distinctId, storedToken, storedFcmProjectId)
            } catch (e: PostHogApiError) {
                config.logger.log("Push token re-registration failed: ${e.message} (code: ${e.statusCode})")
            } catch (e: Throwable) {
                config.logger.log("Push token re-registration failed: ${e.message ?: e.javaClass.simpleName}")
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
        if (e is IOException) {
            PostHogPushTokenError.NETWORK_ERROR
        } else {
            PostHogPushTokenError.OTHER
        }
}
