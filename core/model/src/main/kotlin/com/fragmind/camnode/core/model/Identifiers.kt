package com.fragmind.camnode.core.model

import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

@JvmInline
@Serializable
value class NodeId(val value: String) {
    init {
        require(value.isNotBlank()) { "NodeId must not be blank" }
    }

    companion object {
        fun random(): NodeId = NodeId(UUID.randomUUID().toString())
    }
}

@JvmInline
@Serializable
value class HouseholdId(val value: String) {
    init {
        require(value.isNotBlank()) { "HouseholdId must not be blank" }
    }

    companion object {
        fun random(): HouseholdId = HouseholdId(UUID.randomUUID().toString())
    }
}

/**
 * Shared credential of a household. Every member presents it to the control API
 * (bearer) and to RTSP (basic auth). Base64url keeps it safe inside URLs and QR codes.
 */
@JvmInline
@Serializable
value class HouseholdSecret(val value: String) {
    init {
        require(value.length >= MIN_LENGTH && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "HouseholdSecret must be at least $MIN_LENGTH base64url characters"
        }
    }

    override fun toString(): String = "HouseholdSecret(***)"

    companion object {
        private const val MIN_LENGTH = 16
        private const val RANDOM_BYTES = 24

        fun generate(random: SecureRandom = SecureRandom()): HouseholdSecret {
            val bytes = ByteArray(RANDOM_BYTES).also(random::nextBytes)
            return HouseholdSecret(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        }
    }
}
