package com.dekstream.tv.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Password-protected backup file of the setup (same contents as device sync), for moving to a device
 * on another network or keeping a copy. AES-256-GCM with a key stretched from the password.
 */
object Backup {
    private const val ROUNDS = 210_000

    suspend fun save(c: Context, uri: Uri, password: String, includeLogins: Boolean) = withContext(Dispatchers.IO) {
        val salt = SecureBox.randomBytes(16)
        val key = SecureBox.keyFrom(password, salt, bytes = 32, rounds = ROUNDS)
        val data = SecureBox.seal(key, DeviceSync.export(c, includeLogins).toString().toByteArray())
        val file = JSONObject().put("app", "dektv-backup").put("v", 1).put("from", DeviceSync.deviceName(c))
            .put("created", System.currentTimeMillis()).put("salt", SecureBox.b64(salt)).put("rounds", ROUNDS).put("data", data)
        c.contentResolver.openOutputStream(uri, "wt")?.use { it.write(file.toString().toByteArray()) } ?: error("Couldn't write the file")
    }

    /** Reads and unlocks a backup; throws with a readable message. */
    suspend fun open(c: Context, uri: Uri, password: String): JSONObject = withContext(Dispatchers.IO) {
        val text = c.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } ?: error("Couldn't open the file")
        val f = runCatching { JSONObject(text) }.getOrNull()?.takeIf { it.optString("app") == "dektv-backup" }
            ?: error("That isn't a DekTV backup")
        val key = SecureBox.keyFrom(password, SecureBox.unb64(f.getString("salt")), bytes = 32, rounds = f.optInt("rounds", ROUNDS))
        val plain = SecureBox.tryOpen(key, f.getString("data")) ?: error("Wrong password")
        JSONObject(String(plain)).also { if (it.optString("app") != "dektv") error("That isn't a DekTV backup") }
    }
}
