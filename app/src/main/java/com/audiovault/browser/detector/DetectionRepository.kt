package com.audiovault.browser.detector

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lista en memoria (por sesión del navegador) de todo lo detectado en las pestañas. */
class DetectionRepository {
    private val map = LinkedHashMap<String, DetectedMedia>()
    private val _items = MutableStateFlow<List<DetectedMedia>>(emptyList())
    val items: StateFlow<List<DetectedMedia>> = _items.asStateFlow()

    @Synchronized
    fun get(id: String): DetectedMedia? = map[id]

    @Synchronized
    fun upsert(id: String, create: () -> DetectedMedia, update: (DetectedMedia) -> DetectedMedia = { it }): DetectedMedia {
        val cur = map[id]
        val next = if (cur == null) create() else update(cur).copy(updatedAt = System.currentTimeMillis())
        map[id] = next
        publish()
        return next
    }

    @Synchronized
    fun update(id: String, f: (DetectedMedia) -> DetectedMedia): DetectedMedia? {
        val cur = map[id] ?: return null
        val next = f(cur).copy(updatedAt = System.currentTimeMillis())
        map[id] = next
        publish()
        return next
    }

    @Synchronized
    fun setStatus(id: String, status: AcquireStatus) {
        update(id) { it.copy(status = status) }
    }

    @Synchronized
    fun forTab(tabId: String): List<DetectedMedia> = map.values.filter { it.tabId == tabId }

    @Synchronized
    fun updateWhere(pred: (DetectedMedia) -> Boolean, f: (DetectedMedia) -> DetectedMedia) {
        var changed = false
        for ((k, v) in map.entries.toList()) if (pred(v)) { map[k] = f(v); changed = true }
        if (changed) publish()
    }

    @Synchronized
    fun clearTab(tabId: String) {
        map.entries.removeAll { it.value.tabId == tabId }
        publish()
    }

    @Synchronized
    fun remove(id: String) {
        map.remove(id); publish()
    }

    @Synchronized
    fun clearAll() {
        map.clear(); publish()
    }

    private fun publish() {
        _items.value = map.values.sortedByDescending { it.updatedAt }
    }
}
