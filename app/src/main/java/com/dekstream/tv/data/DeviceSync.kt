package com.dekstream.tv.data

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Copies DekTV's setup (playlists, profiles, favorites, teams, channel edits, appearance) from one
 * device to another on the home network. The sender shows a 6-digit code; the receiver finds it
 * (Wi-Fi discovery) or types its address, enters the code, and replaces its own setup.
 */
object DeviceSync {
    private const val SERVICE_TYPE = "_dektv._tcp."

    /** Settings files that belong to the setup (not device-only things like updates or stream health). */
    private fun isSetupFile(name: String) =
        name == "dekstream_global" || name.startsWith("ds_") || name.startsWith("dsp_") ||
            name == "channel_memory" || name == "game_alarms"

    fun deviceName(c: Context): String =
        runCatching { Settings.Global.getString(c.contentResolver, "device_name") }.getOrNull()?.ifBlank { null }
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    // ---------------- Export / import ----------------

    fun export(c: Context): JSONObject {
        val dir = File(c.filesDir.parentFile, "shared_prefs")
        val files = JSONObject()
        dir.listFiles()?.map { it.name.removeSuffix(".xml") }?.filter(::isSetupFile)?.forEach { name ->
            val o = JSONObject()
            c.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                val e = JSONObject()
                when (v) {
                    is String -> e.put("t", "s").put("v", v)
                    is Boolean -> e.put("t", "b").put("v", v)
                    is Int -> e.put("t", "i").put("v", v)
                    is Long -> e.put("t", "l").put("v", v)
                    is Float -> e.put("t", "f").put("v", v.toDouble())
                    is Set<*> -> e.put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                    else -> return@forEach
                }
                o.put(k, e)
            }
            files.put(name, o)
        }
        return JSONObject().put("app", "dektv").put("v", 1).put("from", deviceName(c)).put("files", files)
    }

    /** What the payload holds, for the confirm screen. */
    fun summary(payload: JSONObject): String {
        val files = payload.optJSONObject("files") ?: return "Nothing to copy"
        val global = files.optJSONObject("dekstream_global")
        fun count(key: String) = runCatching { JSONArray(global?.optJSONObject(key)?.optString("v") ?: "[]").length() }.getOrDefault(0)
        val profiles = count("profiles"); val sources = count("sources")
        var favs = 0
        files.keys().forEach { n ->
            if (n.startsWith("ds_")) favs += files.getJSONObject(n).optJSONObject("favs")?.optString("v")?.split(',')?.count { it.isNotBlank() } ?: 0
        }
        return listOfNotNull(
            if (sources > 0) "$sources playlist${if (sources == 1) "" else "s"}" else null,
            if (profiles > 0) "$profiles profile${if (profiles == 1) "" else "s"}" else null,
            if (favs > 0) "$favs favorite channels" else null,
            "teams, channel edits and appearance",
        ).joinToString(", ")
    }

    /** Replaces this device's setup with the payload, then restarts DekTV. */
    fun importAndRestart(c: Context, payload: JSONObject) {
        val files = payload.getJSONObject("files")
        val dir = File(c.filesDir.parentFile, "shared_prefs")
        // Drop the current setup files first so profiles/sources that don't exist on the sender go away.
        dir.listFiles()?.map { it.name.removeSuffix(".xml") }?.filter(::isSetupFile)?.forEach { name ->
            c.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        files.keys().forEach { name ->
            if (!isSetupFile(name)) return@forEach
            val o = files.getJSONObject(name)
            val ed = c.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            o.keys().forEach { k ->
                val e = o.getJSONObject(k)
                when (e.optString("t")) {
                    "s" -> ed.putString(k, e.getString("v"))
                    "b" -> ed.putBoolean(k, e.getBoolean("v"))
                    "i" -> ed.putInt(k, e.getInt("v"))
                    "l" -> ed.putLong(k, e.getLong("v"))
                    "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                    "ss" -> ed.putStringSet(k, e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                }
            }
            ed.commit()
        }
        // Saved playlists belong to the old setup.
        File(c.filesDir, "playlist").deleteRecursively()
        val launch = c.packageManager.getLaunchIntentForPackage(c.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launch != null) c.startActivity(launch)
        exitProcess(0)
    }

    // ---------------- Sending ----------------

    var sendCode by mutableStateOf<String?>(null)
    var sendAddress by mutableStateOf<String?>(null)
    var sendStatus by mutableStateOf<String?>(null)
    private var server: ServerSocket? = null
    private var regListener: NsdManager.RegistrationListener? = null

    fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }.getOrNull()

    fun startSending(c: Context) {
        stopSending(c)
        val code = (100000..999999).random().toString()
        val ss = ServerSocket(0)
        server = ss
        sendCode = code
        sendAddress = localIp()?.let { "$it:${ss.localPort}" }
        sendStatus = "Waiting for the other device…"
        if (com.dekstream.tv.BuildConfig.DEBUG) android.util.Log.i("DekTV", "SYNC code=$code port=${ss.localPort}")
        val app = c.applicationContext
        thread(name = "dektv-sync", isDaemon = true) {
            val until = System.currentTimeMillis() + 10 * 60_000
            while (!ss.isClosed && System.currentTimeMillis() < until) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                runCatching { handle(app, s, code) }
            }
            runCatching { ss.close() }
        }
        // Announce on the network so the other device can find this one without typing an address.
        runCatching {
            val nsd = c.getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply { serviceName = "DekTV · ${deviceName(c)}"; serviceType = SERVICE_TYPE; port = ss.localPort }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            regListener = l
        }
    }

    private fun handle(c: Context, s: Socket, code: String) {
        s.use { sock ->
            sock.soTimeout = 10_000
            val reader = sock.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break } // skip headers
            val path = requestLine.split(' ').getOrNull(1) ?: ""
            val given = Regex("code=(\\d+)").find(path)?.groupValues?.get(1)
            val out = sock.getOutputStream()
            if (!path.startsWith("/sync") || given != code) {
                out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
                sendStatus = "Someone tried a wrong code"
                return
            }
            val body = export(c).toString().toByteArray()
            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body); out.flush()
            sendStatus = "Sent to ${sock.inetAddress.hostAddress}"
        }
    }

    fun stopSending(c: Context) {
        runCatching { server?.close() }; server = null
        regListener?.let { l -> runCatching { (c.getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(l) } }
        regListener = null
        sendCode = null; sendAddress = null; sendStatus = null
    }

    // ---------------- Receiving ----------------

    data class Found(val name: String, val address: String)
    val found = mutableStateListOf<Found>()
    private var discovery: NsdManager.DiscoveryListener? = null

    fun startDiscovery(c: Context) {
        stopDiscovery(c)
        found.clear()
        val nsd = c.getSystemService(Context.NSD_SERVICE) as NsdManager
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(i: NsdServiceInfo) { found.removeAll { it.name == i.serviceName } }
            override fun onServiceFound(i: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                runCatching {
                    nsd.resolveService(i, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(i: NsdServiceInfo, e: Int) {}
                        override fun onServiceResolved(r: NsdServiceInfo) {
                            val host = r.host?.hostAddress ?: return
                            val f = Found(r.serviceName.removePrefix("DekTV · "), "$host:${r.port}")
                            if (found.none { it.address == f.address }) found += f
                        }
                    })
                }
            }
        }
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l); discovery = l }
    }

    fun stopDiscovery(c: Context) {
        discovery?.let { l -> runCatching { (c.getSystemService(Context.NSD_SERVICE) as NsdManager).stopServiceDiscovery(l) } }
        discovery = null
    }

    /** Fetches the sender's setup. Throws with a readable message on failure. */
    suspend fun fetch(address: String, code: String): JSONObject = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        val url = "http://${address.trim().removePrefix("http://")}/sync?code=${code.trim()}"
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            when {
                r.code == 403 -> error("Wrong code")
                !r.isSuccessful -> error("The other device answered ${r.code}")
            }
            val o = JSONObject(r.body?.string() ?: error("Empty reply"))
            if (o.optString("app") != "dektv") error("That isn't DekTV")
            o
        }
    }
}
