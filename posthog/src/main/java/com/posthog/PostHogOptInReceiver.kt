package com.posthog

/**
 * Optional capability an integration can implement to be notified when the user opts back in via
 * [PostHogInterface.optIn].
 *
 * Kept separate from [PostHogIntegration] so the public integration contract is unchanged: adding a
 * method to [PostHogIntegration] would leave it abstract in the JVM interface (emitted through
 * `DefaultImpls`), breaking recompilation of Java implementations and risking `AbstractMethodError`
 * for already-compiled ones. Integrations opt in by also implementing this interface; the push
 * integration uses it to refetch the device token and re-register, since a prior logout unregister
 * cleared it and opt-in alone would leave the device unsubscribed until the next app launch.
 */
public interface PostHogOptInReceiver {
    public fun onOptIn()
}
