package com.controletv.phone

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import okio.Buffer
import okio.ForwardingSink
import okio.ForwardingSource
import okio.Sink
import okio.Source
import okio.sink
import okio.source
import org.json.JSONArray
import org.json.JSONObject

/**
 * File manager for the TV's shared storage (/sdcard: Download, Movies, APKs…), run from the
 * phone over ADB. Listing and small operations use shell commands; copies use their own
 * ADB connection and report progress.
 */
class TvFiles(private val context: Context, private val relay: () -> Relay?) {

    private fun shell(command: String): String {
        val r = relay() ?: throw IllegalStateException("O controle completo (ADB) precisa estar ativo")
        if (r.state != Relay.State.OK) throw IllegalStateException("O controle completo (ADB) precisa estar ativo")
        return r.shell(command)
    }

    /** Folder contents (folders first) plus free space, as JSON. */
    fun list(path: String): JSONObject {
        val dir = checked(path)
        // One line per entry: type|size|mtime|name (hidden files left out)
        val out = shell("cd ${q(dir)} 2>/dev/null && stat -c '%F|%s|%Y|%n' * 2>/dev/null")
        val entries = out.lines().mapNotNull { line ->
            val parts = line.split('|', limit = 4)
            if (parts.size < 4 || parts[3].isEmpty() || parts[3] == "*") return@mapNotNull null
            JSONObject()
                .put("name", parts[3])
                .put("dir", parts[0].contains("directory"))
                .put("size", parts[1].toLongOrNull() ?: 0)
                .put("mtime", (parts[2].toLongOrNull() ?: 0) * 1000)
        }.sortedWith(compareBy({ !it.getBoolean("dir") }, { it.getString("name").lowercase() }))
        return JSONObject().put("path", dir).put("entries", JSONArray(entries)).put("space", space())
    }

    private fun space(): JSONObject {
        // df -k: Filesystem 1K-blocks Used Available Use% Mounted-on
        val line = shell("df -k /sdcard 2>/dev/null").lines().drop(1).firstOrNull { it.isNotBlank() } ?: return JSONObject()
        val cols = line.trim().split(Regex("\\s+"))
        val total = cols.getOrNull(1)?.toLongOrNull() ?: 0
        val free = cols.getOrNull(3)?.toLongOrNull() ?: 0
        return JSONObject().put("total", total * 1024).put("free", free * 1024)
    }

    fun delete(path: String) {
        val target = checked(path)
        if (target.count { it == '/' } < 3) throw IllegalArgumentException("Por segurança, não dá para apagar essa pasta")
        expectQuiet(shell("rm -rf ${q(target)} 2>&1"))
    }

    fun rename(path: String, newName: String) {
        if (newName.isBlank() || '/' in newName) throw IllegalArgumentException("Nome inválido")
        val target = checked(path)
        expectQuiet(shell("mv ${q(target)} ${q(target.substringBeforeLast('/') + "/" + newName)} 2>&1"))
    }

    fun mkdir(parent: String, name: String) {
        if (name.isBlank() || '/' in name) throw IllegalArgumentException("Nome inválido")
        expectQuiet(shell("mkdir -p ${q(checked(parent) + "/" + name)} 2>&1"))
    }

    fun installApk(path: String): String {
        val out = shell("pm install -r ${q(checked(path))} 2>&1").trim()
        if (!out.contains("Success")) throw IllegalStateException("Falhou: $out")
        return out
    }

    /** Opens any file with whatever TV app handles its type. */
    fun open(path: String, mime: String) {
        val out = shell("am start -a android.intent.action.VIEW -d ${q("file://" + checked(path))} -t ${q(mime)} 2>&1")
        if (out.contains("Error")) throw IllegalStateException("Nenhum app da TV abre esse arquivo")
    }

    /** Lets the Controle TV player read the TV's storage, so local videos can play there. */
    fun allowPlayerToRead() {
        runCatching { shell("pm grant com.controletv android.permission.READ_EXTERNAL_STORAGE") }
    }

    /** Phone -> TV, from a picked file. */
    fun upload(uri: Uri, folder: String, progress: (Long, Long) -> Unit): String {
        val name = displayName(uri)
        val size = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) else -1
        } ?: -1
        val remote = checked(folder) + "/" + name
        relay()!!.transferConnection().use { adb ->
            val input = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Não deu para ler o arquivo")
            adb.push(counting(input.source(), size, progress), remote, 420 /* 0644 */, System.currentTimeMillis())
        }
        return name
    }

    /** TV -> phone, into the phone's Downloads folder. */
    fun download(path: String, size: Long, progress: (Long, Long) -> Unit): String {
        val remote = checked(path)
        val name = remote.substringAfterLast('/')
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Controle TV")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("Sem espaço no celular?")
        try {
            relay()!!.transferConnection().use { adb ->
                resolver.openOutputStream(item)!!.use { out -> adb.pull(counting(out.sink(), size, progress), remote) }
            }
            resolver.update(item, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(item, null, null)
            throw e
        }
        return "Downloads/Controle TV/$name"
    }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }?.replace('/', '_') ?: "arquivo"

    private fun counting(source: Source, total: Long, progress: (Long, Long) -> Unit): Source {
        var done = 0L
        var reported = 0L
        return object : ForwardingSource(source) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val n = super.read(sink, byteCount)
                if (n > 0) {
                    done += n
                    if (done - reported > 512 * 1024) { reported = done; progress(done, total) }
                }
                return n
            }
        }
    }

    private fun counting(sink: Sink, total: Long, progress: (Long, Long) -> Unit): Sink {
        var done = 0L
        var reported = 0L
        return object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                done += byteCount
                if (done - reported > 512 * 1024) { reported = done; progress(done, total) }
            }
        }
    }

    /** Only the shared storage, and no ".." tricks. */
    private fun checked(path: String): String {
        val clean = path.trim().trimEnd('/').ifEmpty { ROOT }
        if (!(clean == ROOT || clean.startsWith("$ROOT/")) || "/../" in "$clean/" || '\n' in clean) {
            throw IllegalArgumentException("Pasta fora do armazenamento da TV")
        }
        return clean
    }

    private fun expectQuiet(output: String) {
        if (output.isNotBlank()) throw IllegalStateException(output.trim().lines().first())
    }

    private fun q(text: String) = "'" + text.replace("'", "'\\''") + "'"

    companion object {
        const val ROOT = "/sdcard"
    }
}
