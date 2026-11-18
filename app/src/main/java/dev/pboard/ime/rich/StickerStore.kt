package dev.pboard.ime.rich

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The user's own stickers: images imported from the system photo picker, stored app-private under
 * `files/stickers/<pack>/<id>.<ext>` with an `index.json` (packs in order, stickers in order, recently sent).
 * Nothing is downloaded or uploaded; the directory is excluded from backups like all pboard data. Static images are
 * re-encoded to PNG at most [MAX_SIDE] px on their longer side (what messaging apps expect from a sticker); GIFs are
 * kept as they are so they stay animated.
 */
class StickerStore(context: Context) {
    data class Sticker(val pack: String, val file: File, val mime: String) {
        val id get() = "$pack/${file.name}"
    }

    data class Pack(val id: String, val name: String, val stickers: List<Sticker>)

    private val root = File(context.applicationContext.filesDir, DIR)
    private val index = File(root, "index.json")
    private val resolver = context.applicationContext.contentResolver

    @Synchronized
    fun packs(): List<Pack> {
        val o = readIndex()
        val packs = o.optJSONArray("packs") ?: return emptyList()
        return (0 until packs.length()).mapNotNull { i ->
            val p = packs.getJSONObject(i)
            val id = p.getString("id")
            val items = p.optJSONArray("stickers") ?: JSONArray()
            val stickers = (0 until items.length()).mapNotNull { k ->
                val name = items.getString(k)
                File(File(root, id), name).takeIf { it.isFile }?.let { Sticker(id, it, mimeOf(name)) }
            }
            Pack(id, p.optString("name", "My stickers"), stickers)
        }
    }

    /** Recently sent stickers, newest first. */
    @Synchronized
    fun recents(): List<Sticker> {
        val o = readIndex()
        val r = o.optJSONArray("recents") ?: return emptyList()
        return (0 until r.length()).mapNotNull { i ->
            val (pack, name) = r.getString(i).split('/', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            File(File(root, pack), name).takeIf { it.isFile }?.let { Sticker(pack, it, mimeOf(name)) }
        }
    }

    @Synchronized
    fun addRecent(s: Sticker) {
        val o = readIndex()
        val old = o.optJSONArray("recents") ?: JSONArray()
        val list = ArrayList<String>()
        list += s.id
        for (i in 0 until old.length()) old.getString(i).takeIf { it != s.id }?.let { list += it }
        o.put("recents", JSONArray(list.take(MAX_RECENTS)))
        writeIndex(o)
    }

    /** Copies picked images into [packId] (created as "My stickers" if new); returns how many were imported. */
    @Synchronized
    fun import(uris: List<Uri>, packId: String = DEFAULT_PACK, packName: String = "My stickers"): Int {
        val dir = File(root, packId).apply { mkdirs() }
        val added = ArrayList<String>()
        for (uri in uris) {
            val name = runCatching { importOne(uri, dir) }.getOrNull() ?: continue
            added += name
        }
        if (added.isEmpty()) return 0
        val o = readIndex()
        val packs = o.optJSONArray("packs") ?: JSONArray().also { o.put("packs", it) }
        var pack: JSONObject? = null
        for (i in 0 until packs.length()) if (packs.getJSONObject(i).getString("id") == packId) pack = packs.getJSONObject(i)
        if (pack == null) {
            pack = JSONObject().put("id", packId).put("name", packName).put("stickers", JSONArray())
            packs.put(pack)
        }
        val items = pack.getJSONArray("stickers")
        for (n in added) items.put(n)
        writeIndex(o)
        return added.size
    }

    @Synchronized
    fun delete(s: Sticker) {
        s.file.delete()
        val o = readIndex()
        val packs = o.optJSONArray("packs") ?: return
        for (i in 0 until packs.length()) {
            val p = packs.getJSONObject(i)
            if (p.getString("id") != s.pack) continue
            val items = p.getJSONArray("stickers")
            val keep = (0 until items.length()).map { items.getString(it) }.filter { it != s.file.name }
            p.put("stickers", JSONArray(keep))
        }
        val r = o.optJSONArray("recents")
        if (r != null) o.put("recents", JSONArray((0 until r.length()).map { r.getString(it) }.filter { it != s.id }))
        writeIndex(o)
    }

    private fun importOne(uri: Uri, dir: File): String? {
        val type = resolver.getType(uri).orEmpty()
        val id = UUID.randomUUID().toString().substring(0, 12)
        if (type == "image/gif") {
            val out = File(dir, "$id.gif")
            resolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { o -> if (input.copyTo(o) > MAX_GIF_BYTES) { out.delete(); return null } }
            } ?: return null
            return out.name
        }
        val bmp = decode(uri) ?: return null
        val scaled = scale(bmp)
        val out = File(dir, "$id.png")
        out.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return out.name
    }

    private fun decode(uri: Uri): Bitmap? =
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { dec, info, _ ->
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val (w, h) = info.size.width to info.size.height
                val s = MAX_SIDE.toFloat() / maxOf(w, h)
                if (s < 1f) dec.setTargetSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
            }
        } else {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }

    private fun scale(b: Bitmap): Bitmap {
        val s = MAX_SIDE.toFloat() / maxOf(b.width, b.height)
        return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    private fun readIndex(): JSONObject = runCatching { JSONObject(index.readText()) }.getOrElse { JSONObject() }

    private fun writeIndex(o: JSONObject) {
        root.mkdirs()
        val tmp = File(root, "index.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(index)
    }

    companion object {
        const val DIR = "stickers"
        const val DEFAULT_PACK = "mine"
        const val MAX_SIDE = 512
        const val MAX_RECENTS = 24
        const val MAX_GIF_BYTES = 8L * 1024 * 1024

        fun mimeOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "image/png"
        }
    }
}
