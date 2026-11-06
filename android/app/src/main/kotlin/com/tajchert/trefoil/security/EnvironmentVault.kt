package com.tajchert.trefoil.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.tajchert.trefoil.core.model.SavedEnvironment
import com.tajchert.trefoil.core.model.SshHostKey
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Keystore AES-GCM pattern adapted from Flocket's KeystoreSecretCipher (Apache-2.0). */
class EnvironmentVault(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "environments.v1"))
    private val hostKeysFile = AtomicFile(File(context.noBackupFilesDir, "ssh-host-keys.v1"))
    private val hostKeysSerializer = ListSerializer(SshHostKey.serializer())
    private val alias = "trefoil.environments.v1"
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SavedEnvironment.serializer())
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    @Synchronized fun read(): List<SavedEnvironment> = readEncrypted(file)?.let { json.decodeFromString(serializer, it) } ?: emptyList()
    @Synchronized fun readHostKeys(): List<SshHostKey> = readEncrypted(hostKeysFile)?.let { json.decodeFromString(hostKeysSerializer, it) } ?: emptyList()
    @Synchronized fun writeHostKeys(keys: List<SshHostKey>) = writeEncrypted(hostKeysFile, json.encodeToString(hostKeysSerializer, keys))
    private fun readEncrypted(source: AtomicFile): String? {
        if (!source.baseFile.exists()) return null
        val (nonce, encrypted) = SecretEnvelope.decode(source.readFully())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, nonce))
        return cipher.doFinal(encrypted).decodeToString()
    }
    @Synchronized fun write(environments: List<SavedEnvironment>) = writeEncrypted(file, json.encodeToString(serializer, environments))
    private fun writeEncrypted(destination: AtomicFile, plaintext: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = SecretEnvelope.encode(cipher.iv, cipher.doFinal(plaintext.encodeToByteArray()))
        val stream = destination.startWrite()
        try { stream.write(bytes); destination.finishWrite(stream) } catch (e: Exception) { destination.failWrite(stream); throw e }
    }
}
