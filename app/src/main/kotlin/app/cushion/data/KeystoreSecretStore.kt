package app.cushion.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.cushion.core.auth.SecretStore
import app.cushion.core.auth.StoredSession
import app.cushion.core.auth.decodeSession
import app.cushion.core.auth.encodeSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the app secret and tokens in a file encrypted with AES-256-GCM. The key lives
 * in Android Keystore and can't leave the device, and the file sits in the no-backup
 * directory, so credentials never reach a cloud backup.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val file = File(context.noBackupFilesDir, "schwab.session")

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override suspend fun read(): StoredSession? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        try {
            val bytes = file.readBytes()
            val iv = bytes.copyOfRange(0, IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            val plain = cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
            decodeSession(String(plain, Charsets.UTF_8))
        } catch (_: Exception) {
            // An unreadable file (for example, a key lost on restore) means starting over.
            file.delete()
            null
        }
    }

    override suspend fun write(session: StoredSession?) = withContext(Dispatchers.IO) {
        if (session == null) {
            file.delete()
            return@withContext
        }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(encodeSession(session).toByteArray(Charsets.UTF_8))
        // Write then rename, so a crash mid-write can't destroy a good session.
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeBytes(sealed)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
        Unit
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "cushion.session.v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
