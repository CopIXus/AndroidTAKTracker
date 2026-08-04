package com.copix.androidtaktracker.config

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.copix.androidtaktracker.core.config.EncryptedSecretStore

class AndroidEncryptedSecretStore(context: Context) : EncryptedSecretStore {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "att_secrets",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun write(blobName: String, plaintext: String) {
        prefs.edit().putString(blobName, plaintext).apply()
    }

    override fun read(blobName: String): String? = prefs.getString(blobName, null)

    override fun delete(blobName: String) {
        prefs.edit().remove(blobName).apply()
    }
}
