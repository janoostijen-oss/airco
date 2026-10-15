package nl.jan.airco.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Bewaart e-mail + wachtwoord versleuteld (AES-GCM). De sleutel staat in de
 * Android Keystore en kan het toestel niet verlaten; in de app-opslag staat
 * alleen versleutelde tekst. Gebruikt om automatisch opnieuw in te loggen.
 */
class CredentialStore(context: Context) {
    data class Credentials(val username: String, val password: String)

    private val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)

    fun save(credentials: Credentials) {
        val plain = JSONObject().put("u", credentials.username).put("p", credentials.password).toString()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun load(): Credentials? {
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val data = prefs.getString(KEY_DATA, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            }
            val json = JSONObject(String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8))
            Credentials(json.getString("u"), json.getString("p"))
        } catch (e: Exception) {
            // Sleutel ongeldig geworden (bijv. na een factory reset van de keystore): opnieuw invoeren.
            clear()
            null
        }
    }

    /** Snelle check zonder te ontsleutelen. */
    fun exists(): Boolean = prefs.contains(KEY_DATA)

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "melcloud_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_IV = "iv"
        const val KEY_DATA = "data"
    }
}
