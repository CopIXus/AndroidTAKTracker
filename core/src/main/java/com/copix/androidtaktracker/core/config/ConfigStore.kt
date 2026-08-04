package com.copix.androidtaktracker.core.config

import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted-at-rest secret storage keyed by blob name (server tokens/passwords, cert
 * passphrases). The `app` module should wire an Android Keystore + EncryptedSharedPreferences
 * backed implementation; [ConfigStore] only depends on this interface so core stays
 * Context-free and unit-testable on plain JVM.
 */
interface EncryptedSecretStore {
    fun write(blobName: String, plaintext: String)
    fun read(blobName: String): String?
    fun delete(blobName: String)
}

/**
 * Fallback [EncryptedSecretStore] used by default and in unit tests. Encrypts with AES/GCM using
 * a key generated once and persisted alongside the blobs. This is **not** hardware-backed —
 * production Android builds must wire [EncryptedSecretStore] to Android Keystore +
 * EncryptedSharedPreferences in the `app` module (see class doc). Kept here only so ConfigStore
 * has a working, dependency-free default.
 */
class FileEncryptedSecretStore(private val secretsDir: File) : EncryptedSecretStore {
    private val keyFile: File get() = File(secretsDir, ".key")

    override fun write(blobName: String, plaintext: String) {
        secretsDir.mkdirs()
        val key = loadOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(IV_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val cipherText = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val payload = iv + cipherText
        val encoded = Base64.getEncoder().encodeToString(payload)

        val path = blobPath(blobName)
        val temp = File(path.parentFile, path.name + "." + UUID.randomUUID().toString().replace("-", "") + ".tmp")
        temp.writeText(encoded, Charsets.UTF_8)
        if (!temp.renameTo(path)) {
            path.writeText(encoded, Charsets.UTF_8)
            temp.delete()
        }
    }

    override fun read(blobName: String): String? {
        val path = blobPath(blobName)
        if (!path.exists()) return null
        return try {
            val key = loadOrCreateKey()
            val payload = Base64.getDecoder().decode(path.readText(Charsets.UTF_8))
            val iv = payload.copyOfRange(0, IV_LENGTH_BYTES)
            val cipherText = payload.copyOfRange(IV_LENGTH_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    override fun delete(blobName: String) {
        val path = blobPath(blobName)
        if (path.exists()) path.delete()
    }

    private fun blobPath(blobName: String): File = File(secretsDir, sanitizeFileName(blobName) + ".enc")

    private fun loadOrCreateKey(): SecretKey {
        secretsDir.mkdirs()
        val file = keyFile
        if (file.exists()) {
            val bytes = Base64.getDecoder().decode(file.readText(Charsets.UTF_8))
            return SecretKeySpec(bytes, "AES")
        }

        val generator = KeyGenerator.getInstance("AES")
        generator.init(256, SecureRandom())
        val key = generator.generateKey()
        val encoded = Base64.getEncoder().encodeToString(key.encoded)
        val temp = File(file.parentFile, file.name + "." + UUID.randomUUID().toString().replace("-", "") + ".tmp")
        temp.writeText(encoded, Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.writeText(encoded, Charsets.UTF_8)
            temp.delete()
        }
        return key
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH_BYTES = 12
        private const val TAG_LENGTH_BITS = 128

        internal fun sanitizeFileName(name: String): String {
            val invalid = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
            var result = name
            for (c in invalid) result = result.replace(c, '_')
            return result
        }
    }
}

/**
 * Optional helper reference for the `app` module's Android Keystore-backed implementation.
 * Not used by core directly; documents the expected wiring so app code has a template:
 *
 * ```
 * // app module:
 * val masterKey = MasterKey.Builder(context)
 *     .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
 *     .build()
 * val prefs = EncryptedSharedPreferences.create(
 *     context, "androidtaktracker_secrets", masterKey,
 *     EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
 *     EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
 * )
 * class EncryptedPrefsSecretStore(private val prefs: SharedPreferences) : EncryptedSecretStore {
 *     override fun write(blobName: String, plaintext: String) { prefs.edit().putString(blobName, plaintext).apply() }
 *     override fun read(blobName: String): String? = prefs.getString(blobName, null)
 *     override fun delete(blobName: String) { prefs.edit().remove(blobName).apply() }
 * }
 * ```
 */
object AndroidKeystoreWiringNote {
    const val RECOMMENDED_KEYSTORE_PROVIDER: String = "AndroidKeyStore"
    val supportedByJvm: Boolean = try {
        KeyStore.getInstance("AndroidKeyStore")
        true
    } catch (_: Exception) {
        false
    }
}

/** Outcome of loading config.json — distinguishes first-run, success, and corrupt parse. */
data class ConfigLoadResult(
    val config: AppConfig,
    /** True when config.json already existed on disk. */
    val fileExisted: Boolean,
    /** True when the existing file could not be parsed (corrupt backup written). */
    val loadHadError: Boolean,
    /** Path of the quarantined corrupt file, if any. */
    val corruptBackupPath: String? = null,
    /** True when no file existed and a fresh default was created (and may be saved). */
    val createdFresh: Boolean,
)

/**
 * Loads/saves [AppConfig] under a root directory (e.g. `context.filesDir`). Secrets are
 * delegated to an [EncryptedSecretStore] so core never handles Android Keystore directly.
 */
class ConfigStore(
    private val rootDir: File,
    private val secretStore: EncryptedSecretStore = FileEncryptedSecretStore(File(rootDir, "secrets")),
    private val deviceNameProvider: () -> String = { "ANDROID-TRACKER" },
) {
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val rootDirectory: File get() = rootDir
    val configFile: File get() = File(rootDir, "config.json")
    val secretsDirectory: File get() = File(rootDir, "secrets")
    val logsDirectory: File get() = File(rootDir, "logs")
    val certsDirectory: File get() = File(rootDir, "certs")
    val updatesDirectory: File get() = File(rootDir, "updates")

    fun ensureDirectories() {
        rootDir.mkdirs()
        secretsDirectory.mkdirs()
        logsDirectory.mkdirs()
        certsDirectory.mkdirs()
        updatesDirectory.mkdirs()
    }

    /** Load config; on parse failure returns a fresh in-memory config (does not overwrite the file). */
    fun load(): AppConfig = loadDetailed().config

    fun loadDetailed(): ConfigLoadResult {
        ensureDirectories()
        val file = configFile
        if (!file.exists()) {
            val fresh = AppConfig()
            fresh.ensureIdentityDefaults(deviceNameProvider())
            return ConfigLoadResult(config = fresh, fileExisted = false, loadHadError = false, createdFresh = true)
        }

        return try {
            val text = file.readText(Charsets.UTF_8)
            val config = json.decodeFromString(AppConfig.serializer(), text)
            config.ensureIdentityDefaults(deviceNameProvider())
            ConfigLoadResult(config = config, fileExisted = true, loadHadError = false, createdFresh = false)
        } catch (_: Exception) {
            var backup: String? = null
            try {
                val target = File(file.parentFile, file.name + ".corrupt-" + System.currentTimeMillis())
                file.copyTo(target, overwrite = true)
                backup = target.absolutePath
            } catch (_: Exception) {
                backup = null
            }

            val fallback = AppConfig()
            fallback.ensureIdentityDefaults(deviceNameProvider())
            ConfigLoadResult(
                config = fallback,
                fileExisted = true,
                loadHadError = true,
                corruptBackupPath = backup,
                createdFresh = false,
            )
        }
    }

    /** Deep copy via serialize/deserialize so callers never mutate the live StateFlow value in place. */
    fun deepCopy(config: AppConfig): AppConfig =
        json.decodeFromString(AppConfig.serializer(), json.encodeToString(AppConfig.serializer(), config))

    fun save(config: AppConfig) {
        ensureDirectories()
        config.ensureIdentityDefaults(deviceNameProvider())
        config.version = AppConfig.CURRENT_VERSION
        val text = json.encodeToString(AppConfig.serializer(), config)

        val target = configFile
        val temp = File(target.parentFile, target.name + "." + UUID.randomUUID().toString().replace("-", "") + ".tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.writeText(text, Charsets.UTF_8)
            temp.delete()
        }
    }

    fun writeSecret(blobName: String, plaintext: String) {
        ensureDirectories()
        secretStore.write(blobName, plaintext)
    }

    fun readSecret(blobName: String): String? = secretStore.read(blobName)

    fun deleteSecret(blobName: String) = secretStore.delete(blobName)
}
