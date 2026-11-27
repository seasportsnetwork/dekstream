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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
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

    fun export(c: Context, includeLogins: Boolean = true): JSONObject {
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
            if (name == "dekstream_global") {
                o.remove("parentPin") // a PIN never leaves the device
                o.optJSONObject("profiles")?.let { e -> e.put("v", stripPins(e.optString("v"))) }
                if (!includeLogins) o.optJSONObject("sources")?.let { e -> e.put("v", stripLogins(e.optString("v"))) }
            }
            files.put(name, o)
        }
        return JSONObject().put("app", "dektv").put("v", 1).put("from", deviceName(c)).put("files", files)
    }

    private fun stripPins(json: String): String = runCatching {
        val a = JSONArray(json); for (i in 0 until a.length()) a.getJSONObject(i).put("pin", ""); a.toString()
    }.getOrDefault(json)

    /** Blanks usernames, passwords and MACs (and logins inside playlist links). */
    private fun stripLogins(json: String): String = runCatching {
        val a = JSONArray(json)
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            o.put("user", ""); o.put("pass", ""); o.put("mac", ""); o.put("extraLogins", "")
            o.put("url", o.optString("url").replace(Regex("([?&](username|password|user|pass)=)[^&]*", RegexOption.IGNORE_CASE), "$1"))
        }
        a.toString()
    }.getOrDefault(json)

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
        val keepPin = c.getSharedPreferences("dekstream_global", Context.MODE_PRIVATE).getString("parentPin", null)
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
        if (!keepPin.isNullOrBlank()) c.getSharedPreferences("dekstream_global", Context.MODE_PRIVATE).edit().putString("parentPin", keepPin).commit()
        // Saved playlists belong to the old setup.
        File(c.filesDir, "playlist").deleteRecursively()
        val launch = c.packageManager.getLaunchIntentForPackage(c.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launch != null) c.startActivity(launch)
        exitProcess(0)
    }

    // ---------------- Sending ----------------
    //
    // Security: the sender shows a QR code holding a random one-time key, plus a 10-digit code for devices
    // without a camera. The receiver must prove it has one of them (an encrypted request), the person at
    // the sender presses Allow, and the setup goes back encrypted with that key. 5 wrong tries end the
    // session; one successful copy ends it; it closes after 10 minutes or when the screen is left.

    /** Someone asking to copy this device's setup, waiting for Allow / Don't allow. */
    class Pending(val from: String) { internal val answer = java.util.concurrent.LinkedBlockingQueue<Boolean>(1) }

    var sendCode by mutableStateOf<String?>(null)      // 10 digits
    var sendQr by mutableStateOf<String?>(null)        // dektv://sync?a=ip:port&k=key&s=sid
    var sendAddress by mutableStateOf<String?>(null)
    var sendStatus by mutableStateOf<String?>(null)
    var pending by mutableStateOf<Pending?>(null)
    var includeLogins by mutableStateOf(true)
    private var server: ServerSocket? = null
    private var regListener: NsdManager.RegistrationListener? = null
    @Volatile private var failures = 0

    fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }.getOrNull()

    fun answer(allow: Boolean) { pending?.answer?.offer(allow); pending = null }

    fun startSending(c: Context) {
        stopSending(c)
        val rnd = java.security.SecureRandom()
        val code = (1..10).map { rnd.nextInt(10) }.joinToString("")
        val qrKey = SecureBox.randomBytes(16)
        val sid = SecureBox.randomBytes(12)
        val ss = ServerSocket(0)
        server = ss
        failures = 0
        sendCode = code
        sendAddress = localIp()?.let { "$it:${ss.localPort}" }
        sendQr = sendAddress?.let { "dektv://sync?a=$it&k=${SecureBox.b64(qrKey)}&s=${SecureBox.b64(sid)}" }
        sendStatus = "Waiting for the other device…"
        if (com.dekstream.tv.BuildConfig.DEBUG) android.util.Log.i("DekTV", "SYNC code=$code port=${ss.localPort}")
        val app = c.applicationContext
        thread(name = "dektv-sync", isDaemon = true) {
            val codeKey = SecureBox.keyFrom(code, sid)
            val until = System.currentTimeMillis() + 10 * 60_000
            while (!ss.isClosed && System.currentTimeMillis() < until) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(app, s, sid, qrKey, codeKey, ss) } }
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

    private fun handle(c: Context, s: Socket, sid: ByteArray, qrKey: ByteArray, codeKey: ByteArray, ss: ServerSocket) {
        s.use { sock ->
            sock.soTimeout = 15_000
            val req = MiniHttp.read(sock.getInputStream()) ?: return
            val out = sock.getOutputStream()
            when {
                req.path.startsWith("/hello") -> MiniHttp.send(out, 200,
                    JSONObject().put("app", "dektv").put("v", 2).put("name", deviceName(c)).put("sid", SecureBox.b64(sid)).toString(), "application/json")
                req.method == "POST" && req.path.startsWith("/pull") -> {
                    if (failures >= 5) { MiniHttp.send(out, 410, "locked"); return }
                    val (key, plain) = SecureBox.tryOpen(qrKey, req.body)?.let { qrKey to it }
                        ?: SecureBox.tryOpen(codeKey, req.body)?.let { codeKey to it }
                        ?: run {
                            failures++
                            if (failures >= 5) {
                                sendStatus = "Stopped after 5 wrong codes. Leave and come back to start again."
                                runCatching { ss.close() }
                            } else sendStatus = "Someone tried a wrong code (${5 - failures} tries left)"
                            MiniHttp.send(out, 403, "wrong"); return
                        }
                    val from = runCatching { JSONObject(String(plain)).optString("name") }.getOrNull()?.take(60)?.ifBlank { null } ?: "Another device"
                    if (pending != null) { MiniHttp.send(out, 408, "busy"); return }
                    val p = Pending(from)
                    pending = p
                    sendStatus = "$from wants to copy this setup"
                    sock.soTimeout = 0
                    val ok = p.answer.poll(120, java.util.concurrent.TimeUnit.SECONDS)
                    if (pending === p) pending = null
                    when (ok) {
                        true -> {
                            val body = SecureBox.seal(key, export(c, includeLogins).toString().toByteArray())
                            MiniHttp.send(out, 200, body)
                            sendStatus = "Sent to $from"
                            runCatching { ss.close() } // single use
                        }
                        false -> { MiniHttp.send(out, 403, "declined"); sendStatus = "You didn't allow $from" }
                        null -> { MiniHttp.send(out, 408, "timeout"); sendStatus = "No answer, so nothing was sent" }
                    }
                }
                else -> MiniHttp.send(out, 404, "")
            }
        }
    }

    fun stopSending(c: Context) {
        pending?.answer?.offer(false); pending = null
        runCatching { server?.close() }; server = null
        regListener?.let { l -> runCatching { (c.getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(l) } }
        regListener = null
        sendCode = null; sendQr = null; sendAddress = null; sendStatus = null
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
            override fun onServiceLost(i: NsdServiceInfo) { found.removeAll { it.name == i.serviceName.removePrefix("DekTV · ") } }
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

    /** What a scanned sync QR code holds. */
    data class QrInfo(val address: String, val key: ByteArray)
    fun parseQr(text: String): QrInfo? = runCatching {
        val u = android.net.Uri.parse(text.trim())
        if (u.scheme != "dektv" || u.host != "sync") return null
        QrInfo(u.getQueryParameter("a")!!, SecureBox.unb64(u.getQueryParameter("k")!!))
    }.getOrNull()

    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(150, TimeUnit.SECONDS).build()
    private fun base(address: String) = "http://${address.trim().removePrefix("http://").trimEnd('/')}"

    /** Asks with the 10-digit code typed from the other screen. */
    suspend fun fetchWithCode(c: Context, address: String, code: String): JSONObject = withContext(Dispatchers.IO) {
        val hello = client.newCall(Request.Builder().url(base(address) + "/hello").build()).execute().use { r ->
            if (!r.isSuccessful) error("That device isn't sending right now")
            JSONObject(r.body?.string() ?: error("Empty reply"))
        }
        if (hello.optString("app") != "dektv") error("That isn't DekTV")
        if (hello.optInt("v") < 2) error("Update DekTV on the other device first")
        pull(c, address, SecureBox.keyFrom(code.filter(Char::isDigit), SecureBox.unb64(hello.getString("sid"))))
    }

    /** Asks with the key from a scanned QR code. */
    suspend fun fetchWithQr(c: Context, qr: QrInfo): JSONObject = withContext(Dispatchers.IO) { pull(c, qr.address, qr.key) }

    private fun pull(c: Context, address: String, key: ByteArray): JSONObject {
        val ask = SecureBox.seal(key, JSONObject().put("name", deviceName(c)).toString().toByteArray())
        val req = Request.Builder().url(base(address) + "/pull")
            .post(ask.toRequestBody("text/plain".toMediaTypeOrNull())).build()
        client.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            when {
                r.code == 403 && body == "declined" -> error("The other device didn't allow it")
                r.code == 403 -> error("Wrong code")
                r.code == 410 -> error("Too many wrong codes. Start sending again on the other device.")
                r.code == 408 -> error("Nobody answered on the other device")
                !r.isSuccessful -> error("The other device answered ${r.code}")
            }
            val o = JSONObject(String(SecureBox.open(key, body)))
            if (o.optString("app") != "dektv") error("That isn't DekTV")
            return o
        }
    }
}
