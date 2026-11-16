package com.fragmind.camnode.core.model

import kotlinx.serialization.Serializable
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** A device's own key pair. The private half never leaves the device that generated it. */
@Serializable
data class NodeKeyPair(val publicKey: String, val privateKey: String) {
    override fun toString(): String = "NodeKeyPair(publicKey=$publicKey, privateKey=***)"
}

/**
 * ECDSA P-256 with SHA-256: available on every Android version CamNode supports and on
 * the JVM, so phones and the desktop recorder sign and verify the same way.
 */
object NodeKeys {
    private const val ALGORITHM = "EC"
    private const val CURVE = "secp256r1"
    private const val SIGNATURE = "SHA256withECDSA"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun generate(): NodeKeyPair {
        val pair = KeyPairGenerator.getInstance(ALGORITHM).apply { initialize(ECGenParameterSpec(CURVE)) }.generateKeyPair()
        return NodeKeyPair(encoder.encodeToString(pair.public.encoded), encoder.encodeToString(pair.private.encoded))
    }

    fun sign(privateKey: String, data: ByteArray): String {
        val key = KeyFactory.getInstance(ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(decoder.decode(privateKey)))
        return encoder.encodeToString(Signature.getInstance(SIGNATURE).apply { initSign(key); update(data) }.sign())
    }

    /** False for any malformed key or signature: verification never throws on attacker input. */
    fun verify(publicKey: String, data: ByteArray, signature: String): Boolean = runCatching {
        val key = KeyFactory.getInstance(ALGORITHM).generatePublic(X509EncodedKeySpec(decoder.decode(publicKey)))
        Signature.getInstance(SIGNATURE).apply { initVerify(key); update(data) }.verify(decoder.decode(signature))
    }.getOrDefault(false)

    fun sha256(data: ByteArray): String = encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(data))
}
