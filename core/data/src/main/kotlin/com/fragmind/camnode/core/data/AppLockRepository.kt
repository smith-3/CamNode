package com.fragmind.camnode.core.data

import com.fragmind.camnode.core.model.AppDisguise
import com.fragmind.camnode.core.model.PinCheck
import kotlinx.coroutines.flow.Flow

/** What the app shows at launch: whether a PIN guards it and which disguise it wears. */
data class AppLockConfig(val enabled: Boolean = false, val disguise: AppDisguise = AppDisguise.NONE, val iconStyle: Int = 0)

interface AppLockRepository {
    val config: Flow<AppLockConfig>

    /** Turns the lock on. False when [disguise] can't take this PIN through its own controls. */
    suspend fun enable(pin: String, disguise: AppDisguise, iconStyle: Int = 0): Boolean

    /** Changing how the app is disguised, icon included, requires the current PIN. */
    suspend fun changeDisguise(pin: String, disguise: AppDisguise, iconStyle: Int = 0): PinCheck

    suspend fun disable(pin: String): PinCheck

    /** Throttled: repeated failures lock out for longer and longer, across restarts. */
    suspend fun verify(pin: String): PinCheck
}
