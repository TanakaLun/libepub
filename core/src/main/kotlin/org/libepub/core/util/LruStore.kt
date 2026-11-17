package org.libepub.core.util

/**
 * Small access-ordered LRU map. Not thread-safe; the reader owns one instance
 * per session and calls it from a single thread.
 */
class LruStore<K : Any, V : Any>(private val maxEntries: Int = 8) {

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val map = object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean =
            size > maxEntries
    }

    val size: Int get() = map.size

    fun get(key: K): V? = map[key]

    fun put(key: K, value: V): V? = map.put(key, value)

    fun remove(key: K): V? = map.remove(key)

    fun clear() = map.clear()

    /** Keys in least-recently-used order (oldest first). */
    fun keys(): List<K> = map.keys.toList()

    fun values(): List<V> = map.values.toList()
}
