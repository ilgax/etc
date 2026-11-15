package dev.ilgax.tabremote.tablet

import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import java.security.KeyStore
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Holds the long-term HMAC key inside the Android Keystore; the raw key is never persisted. */
class KeyVault(private val alias: String = "tabremote-hmac") {
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun save(key: ByteArray) {
        val entry = KeyStore.SecretKeyEntry(SecretKeySpec(key, "HmacSHA256"))
        val protection = KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        store().setEntry(alias, entry, protection)
    }

    fun has(): Boolean = try { store().containsAlias(alias) } catch (e: Exception) { false }

    /** Returns null when no key exists. Throws on keystore failure. */
    fun mac(data: ByteArray): ByteArray? {
        val key = store().getKey(alias, null) ?: return null
        return Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(data)
    }

    fun delete() {
        try {
            store().takeIf { it.containsAlias(alias) }?.deleteEntry(alias)
        } catch (_: Exception) {
        }
    }
}
