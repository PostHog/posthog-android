package com.posthog.internal

import com.posthog.PostHogInternal

/**
 * An Interface that reads the static and dynamic context
 * For example, screen's metrics, app's name and version, device details, connectivity status
 */
@PostHogInternal
public interface PostHogContext {
    public fun getStaticContext(): Map<String, Any>

    public fun getDynamicContext(): Map<String, Any>

    public fun getSdkInfo(): Map<String, Any>
}

/**
 * Returns person properties context by extracting relevant properties from static context.
 * This centralizes the logic for determining which properties should be used as person properties.
 */
@PostHogInternal
public fun PostHogContext.personPropertiesContext(): Map<String, Any> {
    val sdkInfo = getSdkInfo()
    val staticCtx = getStaticContext()
    val personProperties = mutableMapOf<String, Any>()

    // App information
    staticCtx["\$app_version"]?.let { personProperties["\$app_version"] = it }
    staticCtx["\$app_build"]?.let { personProperties["\$app_build"] = it }
    staticCtx["\$app_namespace"]?.let { personProperties["\$app_namespace"] = it }

    // Operating system information
    staticCtx["\$os_name"]?.let { personProperties["\$os_name"] = it }
    staticCtx["\$os_version"]?.let { personProperties["\$os_version"] = it }

    // Device information
    staticCtx["\$device_type"]?.let { personProperties["\$device_type"] = it }

    // SDK information
    sdkInfo["\$lib"]?.let { personProperties["\$lib"] = it }
    sdkInfo["\$lib_version"]?.let { personProperties["\$lib_version"] = it }

    return personProperties
}
