package io.lmp.copilot.infra

import java.util.concurrent.ConcurrentHashMap

/**
 * Generic thread-safe registry. Used for providers, tools, context sources, renderers,
 * redactors, exporters, and migrations — every extensibility surface in the architecture.
 */
class Registry<K : Any, V : Any>(private val name: String) {

    private val items = ConcurrentHashMap<K, V>()

    fun register(key: K, value: V): V {
        val previous = items.put(key, value)
        if (previous != null && previous !== value) {
            // Last-writer-wins, but warn — this catches double registrations.
            System.err.println("[$name] replacing entry for key=$key")
        }
        return value
    }

    fun unregister(key: K): V? = items.remove(key)

    fun get(key: K): V? = items[key]

    fun require(key: K): V = items[key] ?: error("[$name] no entry for $key")

    fun all(): List<V> = items.values.toList()

    fun keys(): Set<K> = items.keys.toSet()
}
