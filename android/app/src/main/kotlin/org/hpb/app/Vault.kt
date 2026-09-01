package org.hpb.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Everything secret this worker holds, the Android half of the same contract
 * the iOS vault implements.
 *
 * Backed by the Android Keystore rather than plain preferences, because the
 * worker key *is* this worker's identity: losing it orphans every claim and any
 * unpaid earnings. Settings that are not secrets — the relay list, the payout
 * address — stay in ordinary preferences.
 */
class Vault(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun workerKey(): String? = prefs.getString(WORKER_KEY, null)

    fun storeWorkerKey(hex: String) = prefs.edit().putString(WORKER_KEY, hex).apply()

    fun toolCredential(tool: String): String? = prefs.getString(credentialKey(tool), null)

    fun storeToolCredential(tool: String, token: String) =
        prefs.edit().putString(credentialKey(tool), token).apply()

    fun clearToolCredential(tool: String) = prefs.edit().remove(credentialKey(tool)).apply()

    /** The tools this worker can verify results for — what it may declare. */
    fun tools(): List<String> = prefs.all.keys
        .filter { it.startsWith(CREDENTIAL_PREFIX) }
        .map { it.removePrefix(CREDENTIAL_PREFIX) }
        .sorted()

    private fun credentialKey(tool: String) = "$CREDENTIAL_PREFIX$tool"

    private companion object {
        const val FILE = "hpb-vault"
        const val WORKER_KEY = "worker.key"
        const val CREDENTIAL_PREFIX = "tool."
    }
}
