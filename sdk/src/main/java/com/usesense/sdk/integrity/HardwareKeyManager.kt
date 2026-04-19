package com.usesense.sdk.integrity

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore

/**
 * LiveSense v4 hardware-backed EC P-256 key manager.
 *
 * Phase 1 ticket A-1.
 *
 * Prefers StrongBox (`setIsStrongBoxBacked(true)`); falls back to TEE
 * when StrongBox is unavailable. Key lives only in the keystore;
 * `exportPublicKeySpki()` returns the X.509/SPKI DER encoding of the
 * public key so the server can verify signatures with standard libs.
 *
 * TODO: device-verify on Pixel 7 (StrongBox present), Samsung A54
 * (TEE only), mid-tier Xiaomi (TEE only).
 */
class HardwareKeyManager(
    private val context: Context,
    sessionId: String
) {
    private val keyAlias = "com.usesense.v4.signing.$sessionId"

    private var cachedTier: String? = null

    fun getOrGenerateSigningKey(): KeyPair {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (ks.containsAlias(keyAlias)) {
            val priv = ks.getKey(keyAlias, null)
            val pub = ks.getCertificate(keyAlias).publicKey
            return KeyPair(pub, priv as java.security.PrivateKey)
        }
        return generate()
    }

    fun exportPublicKeySpki(): ByteArray {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        require(ks.containsAlias(keyAlias)) { "v4 signing key not initialised" }
        // getCertificate().publicKey.encoded returns SPKI DER for EC keys
        // when the provider is AndroidKeyStore.
        return ks.getCertificate(keyAlias).publicKey.encoded
    }

    fun attestationTier(): String {
        cachedTier?.let { return it }
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val entry = (ks.getEntry(keyAlias, null) as? KeyStore.PrivateKeyEntry) ?: return "tee"
        val factory = java.security.KeyFactory.getInstance(entry.privateKey.algorithm, KEYSTORE_PROVIDER)
        val info = try {
            factory.getKeySpec(entry.privateKey, android.security.keystore.KeyInfo::class.java)
        } catch (_: Exception) {
            null
        }
        val tier = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info?.securityLevel == android.security.keystore.KeyProperties.SECURITY_LEVEL_STRONGBOX -> "strongbox"
            info?.isInsideSecureHardware == true -> "tee"
            else -> "software"
        }
        cachedTier = tier
        return tier
    }

    fun delete() {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (ks.containsAlias(keyAlias)) ks.deleteEntry(keyAlias)
        cachedTier = null
    }

    private fun generate(): KeyPair {
        val purposes = KeyProperties.PURPOSE_SIGN
        val base = KeyGenParameterSpec.Builder(keyAlias, purposes)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)

        val strongboxBuilder = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                base.setIsStrongBoxBacked(true)
            } else {
                base
            }
        } catch (e: NoSuchMethodError) {
            base
        }

        // TODO: device-verify on a device without StrongBox (Samsung A54)
        // that the fallback executes cleanly.
        return try {
            createKey(strongboxBuilder.build()).also { cachedTier = "strongbox" }
        } catch (e: Exception) {
            Log.w(TAG, "StrongBox unavailable, falling back to TEE: ${e.message}")
            createKey(base.build()).also { cachedTier = "tee" }
        }
    }

    private fun createKey(spec: KeyGenParameterSpec): KeyPair {
        val gen = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            KEYSTORE_PROVIDER
        )
        gen.initialize(spec)
        return gen.generateKeyPair()
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TAG = "UseSense.V4Key"
    }
}
