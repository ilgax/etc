package dev.ilgax.utils.math

import dev.ilgax.utils.math.recognition.Json
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Corrections log for later fine-tuning: one folder per solve session with the crop PNG and a meta.json
 * (original and corrected LaTeX). Plain files in app-private storage; nothing is sent anywhere.
 */
class CorrectionsStore(private val root: File) {
    /** Writes (or replaces) the entry for [sessionId]. */
    fun save(sessionId: String, png: ByteArray, original: String, corrected: String, model: String, timeMillis: Long) {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]+")))
        val dir = File(root, sessionId)
        dir.mkdirs()
        File(dir, IMAGE).writeBytes(png)
        File(dir, META).writeText(meta(sessionId, original, corrected, model, timeMillis))
    }

    fun count(): Int = root.listFiles { f -> f.isDirectory && File(f, META).isFile }?.size ?: 0

    fun deleteAll() {
        root.deleteRecursively()
    }

    /** Zip with `<id>/crop.png`, `<id>/meta.json` and a `corrections.jsonl` index (one JSON object per line). */
    fun exportZip(out: OutputStream): Int {
        val dirs = root.listFiles { f -> f.isDirectory && File(f, META).isFile }?.sortedBy { it.name } ?: emptyList()
        ZipOutputStream(out).use { zip ->
            for (d in dirs) {
                for (name in listOf(IMAGE, META)) {
                    val f = File(d, name)
                    if (!f.isFile) continue
                    zip.putNextEntry(ZipEntry("${d.name}/$name"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            zip.putNextEntry(ZipEntry("corrections.jsonl"))
            zip.write(dirs.joinToString("") { File(it, META).readText().trim().replace("\n", " ") + "\n" }.toByteArray())
            zip.closeEntry()
        }
        return dirs.size
    }

    private fun meta(id: String, original: String, corrected: String, model: String, time: Long) =
        "{\"id\":${Json.quote(id)},\"image\":${Json.quote("$id/$IMAGE")},\"original\":${Json.quote(original)}," +
            "\"corrected\":${Json.quote(corrected)},\"model\":${Json.quote(model)},\"time\":$time}"

    companion object {
        const val IMAGE = "crop.png"
        const val META = "meta.json"
    }
}
