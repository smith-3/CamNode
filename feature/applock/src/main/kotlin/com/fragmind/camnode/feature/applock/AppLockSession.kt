package com.fragmind.camnode.feature.applock

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the PIN was entered in this run. The app locks again after spending more than
 * [GRACE_MILLIS] in the background; the grace keeps a share sheet or permission prompt
 * from asking for the PIN again.
 */
@Singleton
class AppLockSession @Inject constructor() : DefaultLifecycleObserver {
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()
    private var stoppedAt: Long? = null
    private var observing = false

    /** Must run on the main thread; called from the gate's ViewModel. */
    fun observeProcess() {
        if (observing) return
        observing = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    fun unlock() {
        _unlocked.value = true
    }

    fun lock() {
        _unlocked.value = false
    }

    override fun onStop(owner: LifecycleOwner) {
        stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart(owner: LifecycleOwner) {
        val stopped = stoppedAt ?: return
        if (SystemClock.elapsedRealtime() - stopped > GRACE_MILLIS) lock()
        stoppedAt = null
    }

    private companion object {
        const val GRACE_MILLIS = 15_000L
    }
}
