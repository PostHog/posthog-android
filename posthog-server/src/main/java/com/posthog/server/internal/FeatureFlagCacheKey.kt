package com.posthog.server.internal

/**
 * Cache key for feature flag requests based on the parameters used in the API call
 */
internal data class FeatureFlagCacheKey(
    val distinctId: String?,
    val groups: Map<String, String>?,
    val personProperties: Map<String, Any?>?,
    val groupProperties: Map<String, Map<String, Any?>>?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FeatureFlagCacheKey) return false

        if (distinctId != other.distinctId) return false
        if (groups != other.groups) return false
        if (personProperties != other.personProperties) return false
        if (groupProperties != other.groupProperties) return false

        return true
    }

    override fun hashCode(): Int {
        var result = distinctId?.hashCode() ?: 0
        result = 31 * result + (groups?.hashCode() ?: 0)
        result = 31 * result + (personProperties?.hashCode() ?: 0)
        result = 31 * result + (groupProperties?.hashCode() ?: 0)
        return result
    }
}
