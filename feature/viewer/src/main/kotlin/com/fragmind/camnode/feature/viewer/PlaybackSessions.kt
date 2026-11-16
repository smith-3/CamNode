package com.fragmind.camnode.feature.viewer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal interface PlaybackConnection {
    fun start()
    fun stop()
    fun setAudible(audible: Boolean)
    fun release()
}

/** Main-thread owner of connections shared by the camera grid and detail. */
internal class PlaybackSessions<C : PlaybackConnection>(
    private val scope: CoroutineScope,
    private val create: (String) -> C,
    private val retentionMillis: Long = 5_000L,
) {
    private class Entry<C>(val connection: C) {
        val viewers = mutableMapOf<Any, Boolean>()
        var expiry: Job? = null
    }

    class Lease<C>(
        val connection: C,
        private val update: (Boolean) -> Unit,
        private val detach: () -> Unit,
    ) {
        fun setMuted(muted: Boolean) = update(muted)
        fun close() = detach()
    }

    private val entries = mutableMapOf<String, Entry<C>>()
    private var foreground = true

    fun acquire(url: String, muted: Boolean): Lease<C> {
        val entry = entries.getOrPut(url) {
            Entry(create(url)).also { if (foreground) it.connection.start() }
        }
        entry.expiry?.cancel()
        entry.expiry = null
        val viewer = Any()
        entry.viewers[viewer] = muted
        updateAudio(entry)
        return Lease(
            connection = entry.connection,
            update = { value ->
                if (entries[url] === entry && viewer in entry.viewers) {
                    entry.viewers[viewer] = value
                    updateAudio(entry)
                }
            },
            detach = {
                if (entries[url] === entry && entry.viewers.remove(viewer) != null) {
                    updateAudio(entry)
                    if (entry.viewers.isEmpty()) {
                        entry.expiry = scope.launch {
                            delay(retentionMillis)
                            entries.remove(url)
                            entry.connection.release()
                        }
                    }
                }
            },
        )
    }

    fun setForeground(active: Boolean) {
        if (foreground == active) return
        foreground = active
        entries.values.forEach { entry ->
            updateAudio(entry)
            if (active) entry.connection.start() else entry.connection.stop()
        }
    }

    fun clear() {
        entries.values.forEach {
            it.expiry?.cancel()
            it.connection.release()
        }
        entries.clear()
    }

    private fun updateAudio(entry: Entry<C>) {
        entry.connection.setAudible(foreground && entry.viewers.values.any { muted -> !muted })
    }
}
