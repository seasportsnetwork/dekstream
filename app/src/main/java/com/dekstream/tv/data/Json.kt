package com.dekstream.tv.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Xtream panels are loose with types: numbers arrive as strings, nulls as "", etc. */
internal fun JSONObject.s(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = opt(key)?.toString()?.trim() ?: return null
    return v.ifEmpty { null }
}

internal fun JSONObject.i(key: String, def: Int = 0): Int =
    s(key)?.toDoubleOrNull()?.toInt() ?: def

internal fun JSONObject.l(key: String): Long? = s(key)?.toDoubleOrNull()?.toLong()

internal fun JSONObject.bool(key: String): Boolean {
    val v = s(key) ?: return false
    return v == "1" || v.equals("true", true)
}

internal inline fun JSONArray.objects(block: (JSONObject) -> Unit) {
    for (idx in 0 until length()) {
        val o = optJSONObject(idx) ?: continue
        block(o)
    }
}

internal fun parseArray(body: String): JSONArray {
    val t = body.trim()
    return if (t.startsWith("[")) JSONArray(t) else JSONArray()
}

internal fun parseObject(body: String): JSONObject {
    val t = body.trim()
    return if (t.startsWith("{")) JSONObject(t) else JSONObject()
}

/** EPG titles/descriptions are usually base64; fall back to raw text if not. */
internal fun maybeB64(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return try {
        val decoded = String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8)
        if (decoded.any { it == '�' || (it.isISOControl() && it != '\n' && it != '\r' && it != '\t') }) raw else decoded
    } catch (_: IllegalArgumentException) {
        raw
    }
}
