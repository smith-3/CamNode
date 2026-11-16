package com.fragmind.camnode.core.model

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Persistent PIN lock state. Only a slow salted hash of the PIN is stored. Failed attempts
 * are stored too, so killing the app does not reset the throttle.
 */
@Serializable
data class AppLockState(
    val pinHash: String? = null,
    val salt: String? = null,
    val disguise: AppDisguise = AppDisguise.NONE,
    /** Which of the disguise's launcher icons is shown, 0 until [AppDisguise.iconStyles]. */
    val iconStyle: Int = 0,
    val failedAttempts: Int = 0,
    val lockedUntilMillis: Long = 0,
) {
    val enabled: Boolean get() = pinHash != null && salt != null
}

sealed interface PinCheck {
    data object Granted : PinCheck
    data class Denied(val attemptsBeforeLockout: Int) : PinCheck
    data class LockedOut(val untilMillis: Long) : PinCheck
}

object PinHash {
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun newSalt(random: SecureRandom = SecureRandom()): String = encoder.encodeToString(ByteArray(16).also(random::nextBytes))

    fun hash(pin: String, salt: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), decoder.decode(salt), ITERATIONS, KEY_BITS)
        return encoder.encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded)
    }

    fun matches(pin: String, salt: String, expected: String): Boolean =
        MessageDigest.isEqual(hash(pin, salt).toByteArray(), expected.toByteArray())
}

/**
 * Brakes guessing: every [ATTEMPTS_PER_ROUND] consecutive failures lock the app for a
 * period that doubles each round, capped at an hour. With 10 000 possible PINs this
 * turns a quick sweep into days.
 */
object PinThrottle {
    const val ATTEMPTS_PER_ROUND = 5
    private const val BASE_LOCKOUT_MILLIS = 30_000L
    private const val MAX_LOCKOUT_MILLIS = 60 * 60_000L

    fun check(state: AppLockState, pin: String, nowMillis: Long): Pair<PinCheck, AppLockState> {
        if (nowMillis < state.lockedUntilMillis) return PinCheck.LockedOut(state.lockedUntilMillis) to state
        val salt = state.salt ?: return PinCheck.Granted to state
        val hash = state.pinHash ?: return PinCheck.Granted to state
        if (PinHash.matches(pin, salt, hash)) return PinCheck.Granted to state.copy(failedAttempts = 0, lockedUntilMillis = 0)
        val failures = state.failedAttempts + 1
        val inRound = failures % ATTEMPTS_PER_ROUND
        if (inRound != 0) return PinCheck.Denied(ATTEMPTS_PER_ROUND - inRound) to state.copy(failedAttempts = failures)
        val round = failures / ATTEMPTS_PER_ROUND
        val lockout = minOf(BASE_LOCKOUT_MILLIS shl (round - 1).coerceAtMost(20), MAX_LOCKOUT_MILLIS)
        val until = nowMillis + lockout
        return PinCheck.LockedOut(until) to state.copy(failedAttempts = failures, lockedUntilMillis = until)
    }
}
