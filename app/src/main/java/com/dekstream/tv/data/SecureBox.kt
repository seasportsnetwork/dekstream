package com.dekstream.tv.data

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption for moving a setup between devices (sync, phone setup, backup files).
 * Sealed format, shared with the phone page's script: base64url(iv[12] || ciphertext || tag[16]), AES-GCM.
 */
object SecureBox {
    private val rnd = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rnd.nextBytes(it) }

    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun unb64(s: String): ByteArray {
        val t = s.trim().replace('+', '-').replace('/', '_')
        return Base64.decode(t + "=".repeat((4 - t.length % 4) % 4), Base64.URL_SAFE or Base64.NO_WRAP)
    }

    /** Stretches a code or password into a key (slow on purpose so guessing offline is impractical). */
    fun keyFrom(secret: String, salt: ByteArray, bytes: Int = 16, rounds: Int = 150_000): ByteArray {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return f.generateSecret(PBEKeySpec(secret.toCharArray(), salt, rounds, bytes * 8)).encoded
    }

    fun seal(key: ByteArray, plain: ByteArray): String {
        val iv = randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return b64(iv + c.doFinal(plain))
    }

    /** Opens a sealed message; throws if the key is wrong or the message was changed. */
    fun open(key: ByteArray, sealed: String): ByteArray {
        val b = unb64(sealed)
        require(b.size > 28) { "Too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b, 0, 12))
        return c.doFinal(b, 12, b.size - 12)
    }

    fun tryOpen(key: ByteArray?, sealed: String): ByteArray? = if (key == null) null else runCatching { open(key, sealed) }.getOrNull()
}

/** Tiny HTTP helpers for the LAN servers (sync and phone setup). */
internal object MiniHttp {
    class Req(val method: String, val path: String, val body: String)

    fun read(input: java.io.InputStream): Req? {
        val head = StringBuilder()
        var last4 = 0
        // Read headers byte by byte up to the blank line.
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.length > 16_384) return null
        }
        val lines = head.toString().split("\r\n")
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        val len = lines.firstOrNull { it.lowercase().startsWith("content-length:") }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        if (len > 2_000_000) return null
        val body = ByteArray(len)
        var got = 0
        while (got < len) { val n = input.read(body, got, len - got); if (n < 0) break; got += n }
        return Req(parts.getOrElse(0) { "GET" }, parts.getOrElse(1) { "/" }, String(body, 0, got))
    }

    fun send(out: java.io.OutputStream, code: Int, body: ByteArray, type: String = "text/plain; charset=utf-8") {
        val status = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; 408 -> "Request Timeout"; 410 -> "Gone"; else -> "Error" }
        out.write(("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body); out.flush()
    }
    fun send(out: java.io.OutputStream, code: Int, text: String, type: String = "text/plain; charset=utf-8") = send(out, code, text.toByteArray(), type)
}
