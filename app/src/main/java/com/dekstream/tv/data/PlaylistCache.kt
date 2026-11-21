package com.dekstream.tv.data

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * The playlist saved on the device (compact binary), so DekTV opens without waiting for the provider.
 * It's refreshed from the provider in the background on every launch.
 */
class PlaylistCache(root: File, sourceId: String) {
    private val dir = File(root, "playlist/" + sourceId.replace(Regex("[^A-Za-z0-9_-]"), "_")).apply { mkdirs() }
    private val version = 1

    private fun file(name: String) = File(dir, "$name.bin")

    fun saveChannels(list: List<Channel>) = write("channels") { o ->
        o.writeInt(list.size)
        for (c in list) {
            o.writeInt(c.streamId); o.writeInt(c.num); o.writeUTF(c.name); o.str(c.icon); o.writeUTF(c.categoryId)
            o.str(c.epgChannelId); o.writeBoolean(c.hasArchive); o.writeInt(c.archiveDays); o.str(c.url); o.str(c.catchupType); o.str(c.catchupSource)
        }
    }

    fun loadChannels(): List<Channel>? = read("channels") { i ->
        val n = i.readInt()
        ArrayList<Channel>(n).apply {
            repeat(n) {
                add(Channel(i.readInt(), i.readInt(), i.readUTF(), i.str(), i.readUTF(), i.str(), i.readBoolean(), i.readInt(), i.str(), i.str(), i.str()))
            }
        }
    }

    fun saveCategories(name: String, list: List<Category>) = write(name) { o ->
        o.writeInt(list.size); for (c in list) { o.writeUTF(c.id); o.writeUTF(c.name) }
    }

    fun loadCategories(name: String): List<Category>? = read(name) { i ->
        val n = i.readInt(); List(n) { Category(i.readUTF(), i.readUTF()) }
    }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    private fun write(name: String, block: (DataOutputStream) -> Unit) {
        runCatching {
            val tmp = File(dir, "$name.tmp")
            DataOutputStream(BufferedOutputStream(tmp.outputStream(), 256 * 1024)).use { o -> o.writeInt(version); block(o) }
            tmp.renameTo(file(name))
        }
    }

    private fun <T> read(name: String, block: (DataInputStream) -> T): T? {
        val f = file(name)
        if (!f.exists()) return null
        return runCatching {
            DataInputStream(BufferedInputStream(f.inputStream(), 256 * 1024)).use { i ->
                if (i.readInt() != version) null else block(i)
            }
        }.getOrNull()
    }

    private fun DataOutputStream.str(s: String?) { writeBoolean(s != null); if (s != null) writeUTF(s.take(16_000)) }
    private fun DataInputStream.str(): String? = if (readBoolean()) readUTF() else null
}
