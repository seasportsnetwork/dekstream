package com.dekstream.tv.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * "Set up with your phone": DekTV serves a small form on the home network. The QR code holds the page
 * address plus a random one-time key after the '#', which browsers never send over the network. The page
 * encrypts what you type with that key, so only this TV can read it; anything else is rejected, and 5
 * bad tries close it. It stops after 10 minutes or when the screen closes.
 */
object PhoneLink {
    /** "source" = playlist details, "addons" = addon links. */
    var mode by mutableStateOf("source"); private set
    var url by mutableStateOf<String?>(null); private set
    var status by mutableStateOf<String?>(null); private set
    /** The latest thing the phone sent (consumed by the screen). */
    var received by mutableStateOf<JSONObject?>(null)

    private var server: ServerSocket? = null
    @Volatile private var failures = 0

    fun start(c: Context, mode: String) {
        stop()
        this.mode = mode
        val key = SecureBox.randomBytes(16)
        val ss = runCatching { ServerSocket(0) }.getOrNull() ?: return
        server = ss
        failures = 0
        received = null
        val ip = DeviceSync.localIp()
        url = ip?.let { "http://$it:${ss.localPort}/#${SecureBox.b64(key)}" }
        status = if (ip == null) "Connect this device to Wi-Fi to use your phone" else null
        val app = c.applicationContext
        val page = runCatching { app.assets.open("phone/index.html").readBytes() }.getOrDefault(ByteArray(0))
        val script = runCatching { app.assets.open("phone/aesgcm.js").readBytes() }.getOrDefault(ByteArray(0))
        val device = DeviceSync.deviceName(app)
        thread(name = "dektv-phone", isDaemon = true) {
            val until = System.currentTimeMillis() + 10 * 60_000
            while (!ss.isClosed && System.currentTimeMillis() < until) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching {
                        s.use { sock ->
                            sock.soTimeout = 15_000
                            val req = MiniHttp.read(sock.getInputStream()) ?: return@use
                            val out = sock.getOutputStream()
                            when {
                                req.method == "GET" && (req.path == "/" || req.path.startsWith("/?")) ->
                                    MiniHttp.send(out, 200, String(page).replace("{{MODE}}", mode).replace("{{DEVICE}}", device.replace("<", "")),
                                        "text/html; charset=utf-8")
                                req.method == "GET" && req.path == "/aesgcm.js" -> MiniHttp.send(out, 200, script, "application/javascript")
                                req.method == "POST" && req.path == "/submit" -> {
                                    if (failures >= 5) { MiniHttp.send(out, 410, "locked"); return@use }
                                    val plain = SecureBox.tryOpen(key, req.body)
                                    if (plain == null) {
                                        failures++
                                        if (failures >= 5) { status = "Closed after 5 bad tries. Reopen this screen to start again."; runCatching { ss.close() } }
                                        MiniHttp.send(out, 403, "wrong"); return@use
                                    }
                                    val o = JSONObject(String(plain))
                                    received = o
                                    status = "Got it from your phone. Check the details, then save."
                                    MiniHttp.send(out, 200, "ok")
                                }
                                else -> MiniHttp.send(out, 404, "")
                            }
                        }
                    }
                }
            }
            runCatching { ss.close() }
        }
    }

    fun stop() {
        runCatching { server?.close() }; server = null
        url = null; status = null
    }
}
