package com.dekstream.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.dekstream.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A newer DekTV release on GitHub. */
data class UpdateInfo(val tag: String, val version: String, val notes: String, val apkUrl: String, val size: Long)

/**
 * In-app updates from the GitHub releases of seasportsnetwork/dekstream:
 * check on launch, download DekTV.apk, hand it to Android's installer.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/seasportsnetwork/dekstream/releases/latest"

    /** Set when a newer version is found (drives the update dialog). */
    var available by mutableStateOf<UpdateInfo?>(null)
    /** null = idle, 0..1 = downloading. */
    var progress by mutableStateOf<Float?>(null)
    var failure by mutableStateOf<String?>(null)
    /** Result line for Settings ("You're up to date"). */
    var status by mutableStateOf<String?>(null)
    /** The prompt was dismissed with "Later" this session. */
    var dismissed by mutableStateOf(false)

    private fun prefs(c: Context) = c.getSharedPreferences("updater", Context.MODE_PRIVATE)
    fun autoCheck(c: Context) = prefs(c).getBoolean("auto", true)
    fun setAutoCheck(c: Context, on: Boolean) = prefs(c).edit().putBoolean("auto", on).apply()
    fun skip(c: Context, tag: String) { prefs(c).edit().putString("skip", tag).apply(); available = null }
    private fun skipped(c: Context) = prefs(c).getString("skip", null)

    /** Debug/test builds ("test-12") never update themselves. */
    val supported: Boolean get() = !BuildConfig.DEBUG && parse(BuildConfig.VERSION_NAME) != null

    private fun parse(v: String): List<Int>? =
        v.removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() }

    private fun newer(a: List<Int>, b: List<Int>): Boolean {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Checks GitHub. manual = from Settings (ignores "skip this version"). */
    suspend fun check(c: Context, manual: Boolean = false): UpdateInfo? {
        if (!supported) { status = "Updates are off in test builds"; return null }
        status = "Checking…"
        val info = runCatching { fetchLatest() }.getOrElse { status = "Couldn't check for updates"; return null }
        val cur = parse(BuildConfig.VERSION_NAME)!!
        val latest = parse(info.version)
        if (latest == null || !newer(latest, cur)) { status = "You're up to date (v${BuildConfig.VERSION_NAME})"; available = null; return null }
        status = "v${info.version} is available"
        if (!manual && skipped(c) == info.tag) return null
        dismissed = false
        available = info
        return info
    }

    private suspend fun fetchLatest(): UpdateInfo = withContext(Dispatchers.IO) {
        val conn = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "DekTV/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val o = JSONObject(conn.inputStream.bufferedReader().readText())
            val assets = o.getJSONArray("assets")
            var url = ""; var size = 0L
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == "DekTV.apk") { url = a.optString("browser_download_url"); size = a.optLong("size") }
            }
            if (url.isBlank()) error("No DekTV.apk in the release")
            val tag = o.optString("tag_name")
            UpdateInfo(tag, tag.removePrefix("v"), o.optString("body").trim(), url, size)
        } finally { conn.disconnect() }
    }

    /** Downloads the APK (progress in [progress]) and opens the installer. */
    suspend fun downloadAndInstall(c: Context, info: UpdateInfo) {
        failure = null
        progress = 0f
        val file = runCatching { download(c, info) }.getOrElse {
            progress = null; failure = "Download failed: ${it.message ?: "network error"}"; return
        }
        progress = null
        install(c, file)
    }

    private suspend fun download(c: Context, info: UpdateInfo): File = withContext(Dispatchers.IO) {
        val dir = File(c.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // old downloads
        val out = File(dir, "DekTV-${info.version}.apk")
        var url = info.apkUrl
        // GitHub redirects to its file host; follow by hand so cross-host redirects always work.
        repeat(5) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 15_000; readTimeout = 30_000
                setRequestProperty("User-Agent", "DekTV/${BuildConfig.VERSION_NAME}")
            }
            val code = conn.responseCode
            if (code in 300..399) { url = conn.getHeaderField("Location") ?: error("Bad redirect"); conn.disconnect(); return@repeat }
            if (code != 200) error("HTTP $code")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.size
            conn.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024); var done = 0L; var n: Int
                    while (input.read(buf).also { n = it } >= 0) {
                        o.write(buf, 0, n); done += n
                        if (total > 0) progress = (done.toFloat() / total).coerceIn(0f, 1f)
                    }
                }
            }
            conn.disconnect()
            return@withContext out
        }
        error("Too many redirects")
    }

    /** True when Android still needs "Install unknown apps" allowed for DekTV. */
    fun needsInstallPermission(c: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !c.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(c: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private fun install(c: Context, file: File) {
        if (needsInstallPermission(c)) {
            failure = "Allow DekTV to install apps, then press Install again."
            openInstallPermission(c)
            return
        }
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.updates", file)
        runCatching {
            c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { failure = "Couldn't open the installer: ${it.message}" }
    }
}
