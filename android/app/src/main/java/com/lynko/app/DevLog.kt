package com.lynko.app

/**
 * In-memory ring of the interesting log lines (input chain, state, drops).
 * Served at GET http://<phone>:7912/debug on debug builds so the input path
 * can be diagnosed over plain HTTP when USB/adb is not available.
 */
object DevLog {
    private const val MAX = 300
    private val ring = ArrayDeque<String>()

    fun add(msg: String) {
        synchronized(ring) {
            if (ring.size >= MAX) ring.removeFirst()
            ring.addLast(msg)
        }
    }

    fun dump(): String = synchronized(ring) { ring.joinToString("\n") }
}
