package com.posthog.server

internal fun <K, V> MutableMap<K, V>?.putBuilderValue(
    key: K,
    value: V,
): MutableMap<K, V> = (this ?: mutableMapOf()).apply { put(key, value) }

internal fun <K, V> MutableMap<K, V>?.putBuilderValues(values: Map<K, V>): MutableMap<K, V> =
    (this ?: mutableMapOf()).apply { putAll(values) }

internal fun <T> MutableList<T>?.addBuilderValues(values: List<T>): MutableList<T> = (this ?: mutableListOf()).apply { addAll(values) }

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
