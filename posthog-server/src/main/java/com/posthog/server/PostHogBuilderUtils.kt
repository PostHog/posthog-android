package com.posthog.server

internal fun <K, V> MutableMap<K, V>?.putBuilderValue(
    key: K,
    value: V,
): MutableMap<K, V> = (this ?: mutableMapOf()).apply { put(key, value) }

internal fun <K, V> MutableMap<K, V>?.putBuilderValues(values: Map<K, V>): MutableMap<K, V> =
    (this ?: mutableMapOf()).apply { putAll(values) }

internal fun <T> MutableList<T>?.addBuilderValues(values: List<T>): MutableList<T> = (this ?: mutableListOf()).apply { addAll(values) }

internal fun <K, V> Map<K, V>?.toBuilderMapSnapshot(): Map<K, V>? = this?.toMap()

internal fun <T> List<T>?.toBuilderListSnapshot(): List<T>? = this?.toList()

internal fun Map<String, Map<String, Any?>>?.toBuilderGroupPropertiesSnapshot(): Map<String, Map<String, Any?>>? =
    this?.mapValues { (_, properties) -> properties.toMap() }

internal fun MutableMap<String, MutableMap<String, Any?>>?.putBuilderGroupProperty(
    group: String,
    key: String,
    value: Any?,
): MutableMap<String, MutableMap<String, Any?>> =
    (this ?: mutableMapOf()).apply {
        getOrPut(group) { mutableMapOf() }[key] = value
    }

internal fun MutableMap<String, MutableMap<String, Any?>>?.putBuilderGroupProperties(
    groupProperties: Map<String, Map<String, Any?>>,
): MutableMap<String, MutableMap<String, Any?>> =
    (this ?: mutableMapOf()).apply {
        groupProperties.forEach { (group, properties) ->
            getOrPut(group) { mutableMapOf() }.putAll(properties)
        }
    }

internal fun MutableMap<String, Map<String, Any?>>?.mergeBuilderGroupProperties(
    groupProperties: Map<String, Map<String, Any?>>,
): MutableMap<String, Map<String, Any?>> =
    (this ?: mutableMapOf()).apply {
        groupProperties.forEach { (group, properties) ->
            put(group, get(group).orEmpty() + properties)
        }
    }
