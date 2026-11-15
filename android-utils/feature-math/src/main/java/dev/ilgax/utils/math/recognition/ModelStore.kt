package dev.ilgax.utils.math.recognition

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** A model folder on disk: bundled (copied from assets) or imported. */
data class InstalledModel(val id: String, val dir: File, val manifest: ModelManifest, val bundled: Boolean) {
    val name get() = manifest.name
    val sizeBytes: Long get() = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

/** Imported models live in subfolders of [root]; the bundled one is registered by the caller. Pure java.io. */
class ModelStore(val root: File) {
    /** Imported models; folders with an invalid model.json are skipped. */
    fun list(): List<InstalledModel> {
        val dirs = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.sortedBy { it.name } ?: return emptyList()
        return dirs.mapNotNull { d ->
            try { InstalledModel(d.name, d, ModelManifest.load(d), bundled = false) } catch (e: ManifestException) { null }
        }
    }

    fun delete(id: String): Boolean {
        val d = File(root, id)
        if (id.isBlank() || id.contains('/') || id.contains("..") || !d.isDirectory) return false
        return d.deleteRecursively()
    }

    /**
     * Unpacks a zip holding model.json and its files (at the top level or inside one folder), validates it
     * and installs it. Throws [ManifestException] with a readable message and leaves nothing behind on failure.
     */
    fun importZip(input: InputStream): InstalledModel {
        root.mkdirs()
        val tmp = File(root, ".import-${System.nanoTime()}")
        tmp.mkdirs()
        try {
            unpack(input, tmp)
            val src = when {
                File(tmp, ModelManifest.FILE_NAME).isFile -> tmp
                else -> tmp.listFiles()?.singleOrNull { it.isDirectory && File(it, ModelManifest.FILE_NAME).isFile }
                    ?: throw ManifestException("The zip has no model.json (at the top level or in a single folder)")
            }
            val manifest = ModelManifest.load(src)
            try {
                TokenizerDecoder.parse(File(src, manifest.tokenizer).readText())
            } catch (e: ManifestException) {
                throw e
            } catch (e: Exception) {
                throw ManifestException("tokenizer.json could not be read (${e.message})")
            }
            for (f in listOf(manifest.encoder, manifest.decoder)) {
                if (File(src, f).length() < 16) throw ManifestException("\"$f\" is empty or too small to be an ONNX model")
            }
            val id = uniqueId(slug(manifest.name))
            val target = File(root, id)
            if (!src.renameTo(target)) {
                src.copyRecursively(target)
            }
            return InstalledModel(id, target, ModelManifest.load(target), bundled = false)
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun unpack(input: InputStream, dest: File) {
        var total = 0L
        var count = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name.startsWith("__MACOSX/") || e.name.substringAfterLast('/').startsWith("._")) continue
                val out = File(dest, e.name).canonicalFile
                if (!out.path.startsWith(dest.canonicalPath + File.separator)) throw ManifestException("The zip contains an unsafe path: ${e.name}")
                if (e.isDirectory) { out.mkdirs(); continue }
                if (++count > MAX_FILES) throw ManifestException("The zip has too many files")
                out.parentFile?.mkdirs()
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw ManifestException("The zip is larger than ${MAX_BYTES / (1024 * 1024)} MB unpacked")
                        o.write(buf, 0, n)
                    }
                }
            }
        }
        if (count == 0) throw ManifestException("The zip is empty")
    }

    private fun uniqueId(base: String): String {
        var id = base
        var n = 2
        while (File(root, id).exists() || id == BUNDLED_ID) id = "$base-${n++}"
        return id
    }

    companion object {
        const val BUNDLED_ID = "bundled"
        private const val MAX_FILES = 64
        private const val MAX_BYTES = 1_500L * 1024 * 1024

        fun slug(name: String): String =
            name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "model" }.take(48)
    }
}
