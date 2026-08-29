package com.trapezo.pos.backup

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Versioned Trapezo backup PACKAGE — a ZIP-compatible container built with the Java/Kotlin
 * standard library only. It carries the Room database plus the managed product photos and
 * store logo referenced by that database, so a restore no longer loses media.
 *
 * Layout:
 *   manifest.properties
 *   database/trapezo_pos.db
 *   media/product_photos/<basename>…
 *   media/store_media/<basename>…
 *
 * Security contract (backed by [reads]): no path traversal, no absolute paths, no duplicate
 * logical entries, bounded entry counts and decompressed byte counts, a mandatory valid
 * manifest, and a mandatory database entry.
 */
object BackupPackage {
    const val MANIFEST_NAME = "manifest.properties"
    const val DB_ENTRY = "database/trapezo_pos.db"
    const val PHOTO_DIR = "media/product_photos"
    const val LOGO_DIR = "media/store_media"
    const val FORMAT = "TRAPEZO_POS_BACKUP"
    const val FORMAT_VERSION = 1

    /** Absolute upper bound on entries in a package. */
    const val MAX_ENTRIES = 4096

    /** Upper bound on a single extracted media entry. */
    const val MAX_MEDIA_ENTRY_BYTES = 64L * 1024 * 1024

    /** Upper bound on the manifest entry. */
    const val MAX_MANIFEST_BYTES = 64 * 1024

    /** Upper bound on the sum of extracted media bytes. */
    const val MAX_MEDIA_TOTAL_BYTES = 512L * 1024 * 1024

    class BuildException(message: String) : Exception(message)
    class ParseException(message: String) : Exception(message)

    data class Manifest(
        val format: String,
        val formatVersion: Int,
        val schemaVersion: Int,
        val createdAt: Long
    )

    /** Result of a validated extraction. */
    data class Content(
        val manifest: Manifest,
        val dbFile: File,
        val photos: Map<String, File>,      // basename -> extracted file
        val logos: Map<String, File>        // basename -> extracted file
    )

    /** A media file to embed, with its logical entry name. */
    data class MediaFile(val file: File, val entryName: String)

    fun manifestText(schemaVersion: Int, now: Long): String = buildString {
        append("format=$FORMAT\n")
        append("formatVersion=$FORMAT_VERSION\n")
        append("schemaVersion=$schemaVersion\n")
        append("createdAt=$now\n")
    }

    fun parseManifest(text: String): Manifest {
        val map = HashMap<String, String>()
        text.lineSequence().forEach { line ->
            val idx = line.indexOf('=')
            if (idx > 0) map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        return Manifest(
            format = map["format"] ?: "",
            formatVersion = map["formatVersion"]?.toIntOrNull() ?: -1,
            schemaVersion = map["schemaVersion"]?.toIntOrNull() ?: -1,
            createdAt = map["createdAt"]?.toLongOrNull() ?: 0L
        )
    }

    /** Streams a package to [out]. Throws [BuildException] when a referenced file is missing. */
    fun write(
        out: OutputStream,
        dbFile: File,
        photos: List<File>,
        logos: List<File>,
        schemaVersion: Int,
        now: Long
    ) {
        val seen = HashSet<String>()
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_NAME))
            zip.write(manifestText(schemaVersion, now).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            fun put(name: String, file: File) {
                if (!seen.add(name)) throw BuildException("Entri arsip duplikat: $name")
                if (!file.exists() || !file.isFile) {
                    throw BuildException("File yang akan dibackup tidak ditemukan: ${file.name}")
                }
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }

            put(DB_ENTRY, dbFile)
            photos.forEach { put("$PHOTO_DIR/${it.name}", it) }
            logos.forEach { put("$LOGO_DIR/${it.name}", it) }
        }
    }

    /**
     * Extracts and validates a package from [source] into [stagingDir] (which is cleared
     * first). Throws [ParseException] with a user-facing message on any violation.
     */
    fun read(source: File, stagingDir: File, maxDbBytes: Long): Content {
        stagingDir.mkdirs()
        stagingDir.listFiles()?.forEach { it.deleteRecursively() }

        val photos = linkedMapOf<String, File>()
        val logos = linkedMapOf<String, File>()
        var manifest: Manifest? = null
        val dbFile = File(stagingDir, DB_ENTRY)
        var entryCount = 0
        var mediaTotal = 0L

        fun extract(inp: InputStream, dest: File, maxBytes: Long): Long {
            dest.parentFile?.mkdirs()
            var total = 0L
            val buf = ByteArray(64 * 1024)
            dest.outputStream().use { out ->
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw ParseException("Entri arsip melebihi batas ukuran")
                    out.write(buf, 0, n)
                }
            }
            return total
        }

        try {
            ZipInputStream(BufferedInputStream(source.inputStream())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_ENTRIES) throw ParseException("Jumlah entri arsip melebihi batas")
                    val name = entry.name
                    if (name.contains("..") || name.startsWith("/") || name.startsWith("\\") ||
                        name.contains(":") || name.contains('\\')
                    ) {
                        throw ParseException("Entri arsip tidak valid: $name")
                    }
                    when {
                        name == MANIFEST_NAME -> {
                            val baos = java.io.ByteArrayOutputStream()
                            val buf = ByteArray(4096)
                            var total = 0
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > MAX_MANIFEST_BYTES) throw ParseException("Manifest arsip terlalu besar")
                                baos.write(buf, 0, n)
                            }
                            manifest = parseManifest(baos.toString("UTF-8"))
                        }
                        name == DB_ENTRY -> extract(zip, dbFile, maxDbBytes)
                        name.startsWith("$PHOTO_DIR/") -> {
                            val base = prefixBase(name, PHOTO_DIR)
                            val f = File(stagingDir, "$PHOTO_DIR/$base")
                            mediaTotal += extract(zip, f, MAX_MEDIA_ENTRY_BYTES)
                            if (mediaTotal > MAX_MEDIA_TOTAL_BYTES) throw ParseException("Total media arsip melebihi batas")
                            if (photos.put(base, f) != null) throw ParseException("Entri media duplikat: $base")
                        }
                        name.startsWith("$LOGO_DIR/") -> {
                            val base = prefixBase(name, LOGO_DIR)
                            val f = File(stagingDir, "$LOGO_DIR/$base")
                            mediaTotal += extract(zip, f, MAX_MEDIA_ENTRY_BYTES)
                            if (mediaTotal > MAX_MEDIA_TOTAL_BYTES) throw ParseException("Total media arsip melebihi batas")
                            if (logos.put(base, f) != null) throw ParseException("Entri media duplikat: $base")
                        }
                        else -> throw ParseException("Entri arsip tidak dikenal: $name")
                    }
                }
            }
        } catch (e: ParseException) {
            throw e
        } catch (e: java.util.zip.ZipException) {
            // e.g. a duplicate entry name — the archive stream rejects it natively.
            throw ParseException("Arsip backup rusak atau tidak valid")
        } catch (e: java.io.IOException) {
            throw ParseException("Arsip backup rusak atau tidak dapat dibaca")
        }

        val m = manifest ?: throw ParseException("Manifest backup tidak ditemukan")
        if (m.format != FORMAT) throw ParseException("File ini bukan arsip backup Trapezo POS")
        if (m.formatVersion != FORMAT_VERSION) throw ParseException("Versi format backup ($m.formatVersion) tidak didukung")
        if (!dbFile.exists() || dbFile.length() == 0L) throw ParseException("Entri database tidak ditemukan")
        return Content(m, dbFile, photos, logos)
    }

    /** Returns the basename after a known directory prefix, rejecting extra path components. */
    private fun prefixBase(name: String, dir: String): String {
        val suffix = name.removePrefix("$dir/")
        if (suffix.isEmpty() || suffix.contains('/') || suffix.contains('\\') || suffix == "..") {
            throw ParseException("Entri media tidak valid: $name")
        }
        return suffix
    }
}
