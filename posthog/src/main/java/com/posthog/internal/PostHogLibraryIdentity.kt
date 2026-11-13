package com.posthog.internal

private const val LIB = "\$lib"
private const val LIB_VERSION = "\$lib_version"

/**
 * Fills `$lib` and `$lib_version` from [sdkInfo] as a pair, only where the event doesn't already
 * name its library, so an event never pairs another library's name with this SDK's version:
 * - no `$lib` (or one that isn't a non-empty string): sets both, replacing a lone `$lib_version`
 * - `$lib` is this SDK's name and `$lib_version` is unset: sets `$lib_version`
 * - otherwise: leaves both as they are
 */
internal fun fillLibraryIdentity(
    props: MutableMap<String, Any>,
    sdkInfo: Map<String, Any>,
) {
    val ownLib = (sdkInfo[LIB] as? String)?.takeIf { it.isNotEmpty() } ?: return
    val ownVersion = (sdkInfo[LIB_VERSION] as? String)?.takeIf { it.isNotEmpty() }
    val lib = (props[LIB] as? String)?.takeIf { it.isNotEmpty() }
    val version = (props[LIB_VERSION] as? String)?.takeIf { it.isNotEmpty() }

    when {
        lib == null -> {
            props[LIB] = ownLib
            if (ownVersion != null) props[LIB_VERSION] = ownVersion else props.remove(LIB_VERSION)
        }
        lib == ownLib && version == null && ownVersion != null -> props[LIB_VERSION] = ownVersion
    }
}
