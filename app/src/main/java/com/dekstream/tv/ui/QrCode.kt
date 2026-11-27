package com.dekstream.tv.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dekstream.tv.data.PhoneLink
import org.json.JSONObject

/** A QR code drawn crisp (black on white with a quiet border). */
@Composable
fun QrImage(content: String, size: Dp, modifier: Modifier = Modifier) {
    val bmp = remember(content) {
        runCatching {
            val m = com.google.zxing.qrcode.QRCodeWriter().encode(content, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
                mapOf(com.google.zxing.EncodeHintType.MARGIN to 1, com.google.zxing.EncodeHintType.ERROR_CORRECTION to
                    com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M))
            val w = m.width; val h = m.height
            val px = IntArray(w * h) { i -> if (m.get(i % w, i / w)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
            android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
        }.getOrNull()
    }
    Box(modifier.size(size).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(8.dp)) {
        bmp?.let { Image(it, "QR code", filterQuality = FilterQuality.None, modifier = Modifier.size(size - 16.dp)) }
    }
}

/**
 * "Use your phone" panel: QR code for the phone form. [onReceived] gets what was sent
 * ({kind:"source", type, name, url, user, pass, epg, mac} or {kind:"addons", urls:[...]}).
 */
@Composable
fun PhoneSetupCard(mode: String, onReceived: (JSONObject) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    DisposableEffect(mode) {
        PhoneLink.start(ctx, mode)
        onDispose { PhoneLink.stop() }
    }
    val got = PhoneLink.received
    LaunchedEffect(got) { if (got != null) { onReceived(got); PhoneLink.received = null } }
    BentoCard(modifier) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            val url = PhoneLink.url
            if (url != null) QrImage(url, 148.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PhoneAndroid, null, tint = DS.Accent, modifier = Modifier.size(20.dp))
                    Text("  Type it on your phone", color = DS.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Text(if (mode == "addons") "Scan with your phone's camera, paste your addon links, and tap Send. They'll show up here."
                    else "Scan with your phone's camera, fill in your playlist, and tap Send. It fills in this screen for you.",
                    color = DS.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                PhoneLink.status?.let { Text(it, color = DS.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, tint = DS.TextDim, modifier = Modifier.size(13.dp))
                    Text("  Same Wi-Fi only · encrypted · works once for 10 minutes", color = DS.TextDim, fontSize = 11.sp)
                }
            }
        }
    }
}

fun hasCamera(c: Context) = c.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

/** Opens Google's QR scanner (no camera permission needed); null if cancelled or unavailable. */
fun scanQr(c: Context, onResult: (String?) -> Unit) {
    runCatching {
        val opts = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build()
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(c, opts).startScan()
            .addOnSuccessListener { onResult(it.rawValue) }
            .addOnCanceledListener { onResult(null) }
            .addOnFailureListener { onResult(null) }
    }.onFailure { onResult(null) }
}
