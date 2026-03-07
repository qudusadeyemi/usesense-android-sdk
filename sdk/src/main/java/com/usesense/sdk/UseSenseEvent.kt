package com.usesense.sdk

enum class EventType {
    SESSION_CREATED,
    PERMISSIONS_REQUESTED,
    PERMISSIONS_GRANTED,
    PERMISSIONS_DENIED,
    CAPTURE_STARTED,
    FRAME_CAPTURED,
    CAPTURE_COMPLETED,
    AUDIO_RECORD_STARTED,
    AUDIO_RECORD_COMPLETED,
    CHALLENGE_STARTED,
    CHALLENGE_COMPLETED,
    UPLOAD_STARTED,
    UPLOAD_PROGRESS,
    UPLOAD_COMPLETED,
    COMPLETE_STARTED,
    DECISION_RECEIVED,
    IMAGE_QUALITY_CHECK,
    ERROR,
}

data class UseSenseEvent(
    val type: EventType,
    val timestamp: Long = System.currentTimeMillis(),
    val data: Map<String, Any?>? = null,
)

typealias EventCallback = (UseSenseEvent) -> Unit

/**
 * Thread-safe event emitter for SDK lifecycle events.
 */
class UseSenseEventEmitter {
    private val listeners = mutableListOf<EventCallback>()

    fun addListener(callback: EventCallback): () -> Unit {
        synchronized(listeners) { listeners.add(callback) }
        return { synchronized(listeners) { listeners.remove(callback) } }
    }

    fun emit(type: EventType, data: Map<String, Any?>? = null) {
        val event = UseSenseEvent(type = type, data = data)
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it(event) }
    }

    fun clear() {
        synchronized(listeners) { listeners.clear() }
    }
}
