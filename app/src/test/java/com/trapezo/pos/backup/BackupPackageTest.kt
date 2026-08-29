package com.trapezo.pos.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Pure JVM tests for the package archive's safety boundaries. */
class BackupPackageTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun writeZip(file: File, entries: Map<String, ByteArray>) {
        writeZipEntries(file, entries.map { it.key to it.value })
    }

    /**
     * List-based writer so duplicate logical entry names CAN be produced.
     *
     * `ZipOutputStream` refuses to emit a duplicate name, so the archive is assembled by hand
     * from raw STORED local-file records plus a matching central directory. That is exactly the
     * shape a malicious package would take.
     */
    private fun writeZipEntries(file: File, entries: List<Pair<String, ByteArray>>) {
        val out = java.io.ByteArrayOutputStream()
        data class Rec(val name: ByteArray, val crc: Long, val size: Int, val offset: Int)
        val recs = mutableListOf<Rec>()

        fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        fun le32(v: Long) = byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
        )

        for ((name, bytes) in entries) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val crc = java.util.zip.CRC32().apply { update(bytes) }.value
            val offset = out.size()
            out.write(le32(0x04034b50))              // local file header signature
            out.write(le16(20)); out.write(le16(0))  // version, flags
            out.write(le16(0))                        // method: STORED
            out.write(le16(0)); out.write(le16(0))    // time, date
            out.write(le32(crc))
            out.write(le32(bytes.size.toLong()))      // compressed size
            out.write(le32(bytes.size.toLong()))      // uncompressed size
            out.write(le16(nameBytes.size)); out.write(le16(0))
            out.write(nameBytes)
            out.write(bytes)
            recs += Rec(nameBytes, crc, bytes.size, offset)
        }

        val cdStart = out.size()
        for (r in recs) {
            out.write(le32(0x02014b50))              // central directory signature
            out.write(le16(20)); out.write(le16(20)); out.write(le16(0))
            out.write(le16(0))                        // STORED
            out.write(le16(0)); out.write(le16(0))
            out.write(le32(r.crc))
            out.write(le32(r.size.toLong())); out.write(le32(r.size.toLong()))
            out.write(le16(r.name.size)); out.write(le16(0)); out.write(le16(0))
            out.write(le16(0)); out.write(le16(0)); out.write(le32(0))
            out.write(le32(r.offset.toLong()))
            out.write(r.name)
        }
        val cdSize = out.size() - cdStart
        out.write(le32(0x06054b50))                  // end of central directory
        out.write(le16(0)); out.write(le16(0))
        out.write(le16(recs.size)); out.write(le16(recs.size))
        out.write(le32(cdSize.toLong())); out.write(le32(cdStart.toLong()))
        out.write(le16(0))

        file.writeBytes(out.toByteArray())
    }

    private fun validManifest() = BackupPackage.manifestText(schemaVersion = 5, now = 123L).toByteArray()
    private fun sqliteBytes() = "SQLite format 3\u0000".toByteArray() + ByteArray(64) { 1 }

    private fun expectParseFailure(entries: Map<String, ByteArray>, messageContains: String? = null) {
        val src = tmp.newFile("pkg.trpz")
        writeZip(src, entries)
        try {
            BackupPackage.read(src, tmp.newFolder("stage"), 512L * 1024 * 1024)
            fail("expected ParseException")
        } catch (e: BackupPackage.ParseException) {
            if (messageContains != null) {
                assertTrue("expected '$messageContains' in '${e.message}'", e.message?.contains(messageContains) == true)
            }
        }
    }

    @Test fun roundTrip_writesAndReadsManifestDbAndMedia() {
        val db = tmp.newFile("trapezo_pos.db"); db.writeBytes(sqliteBytes())
        val photo = tmp.newFile("photo.jpg"); photo.writeBytes("JPEGDATA".toByteArray())
        val logo = tmp.newFile("logo.png"); logo.writeBytes("PNGDATA".toByteArray())

        val out = tmp.newFile("backup.trpz")
        out.outputStream().use { BackupPackage.write(it, db, listOf(photo), listOf(logo), 5, 123L) }

        val content = BackupPackage.read(out, tmp.newFolder("stage"), 512L * 1024 * 1024)
        assertEquals(BackupPackage.FORMAT, content.manifest.format)
        assertEquals(1, content.manifest.formatVersion)
        assertEquals(5, content.manifest.schemaVersion)
        assertEquals(123L, content.manifest.createdAt)
        assertTrue(content.dbFile.exists())
        assertEquals("photo.jpg", content.photos.keys.single())
        assertEquals("logo.png", content.logos.keys.single())
        assertEquals("JPEGDATA", String(content.photos.values.single().readBytes()))
        assertEquals("PNGDATA", String(content.logos.values.single().readBytes()))
    }

    @Test fun read_rejectsMissingManifest() = expectParseFailure(
        mapOf(BackupPackage.DB_ENTRY to sqliteBytes()), "Manifest"
    )

    @Test fun read_rejectsUnknownFormatManifest() {
        val bad = "format=OTHER\nformatVersion=1\nschemaVersion=5\ncreatedAt=1\n".toByteArray()
        expectParseFailure(mapOf(BackupPackage.MANIFEST_NAME to bad, BackupPackage.DB_ENTRY to sqliteBytes()), "arsip backup Trapezo")
    }

    @Test fun read_rejectsUnsupportedFormatVersion() {
        val bad = "format=${BackupPackage.FORMAT}\nformatVersion=99\nschemaVersion=5\ncreatedAt=1\n".toByteArray()
        expectParseFailure(mapOf(BackupPackage.MANIFEST_NAME to bad, BackupPackage.DB_ENTRY to sqliteBytes()), "tidak didukung")
    }

    @Test fun read_rejectsMissingDatabaseEntry() {
        expectParseFailure(mapOf(BackupPackage.MANIFEST_NAME to validManifest()), "database")
    }

    @Test fun read_rejectsUnknownTopLevelEntry() {
        expectParseFailure(
            mapOf(
                BackupPackage.MANIFEST_NAME to validManifest(),
                BackupPackage.DB_ENTRY to sqliteBytes(),
                "evil/thing" to "x".toByteArray()
            ),
            "tidak dikenal"
        )
    }

    @Test fun read_rejectsPathTraversalEntry() {
        expectParseFailure(
            mapOf(
                BackupPackage.MANIFEST_NAME to validManifest(),
                BackupPackage.DB_ENTRY to sqliteBytes(),
                "../evil" to "x".toByteArray()
            ),
            "tidak valid"
        )
    }

    @Test fun build_failsWhenReferencedTeardownFileMissing() {
        val db = tmp.newFile("trapezo_pos.db"); db.writeBytes(sqliteBytes())
        val missing = File(tmp.root, "missing.jpg")  // does not exist
        try {
            tmp.newFile("out.trpz").outputStream().use {
                BackupPackage.write(it, db, listOf(missing), emptyList(), 5, 1L)
            }
            fail("expected BuildException")
        } catch (e: BackupPackage.BuildException) {
            assertTrue(e.message?.contains("tidak ditemukan") == true)
        }
    }

    // ---- Revision 01: duplicate protected entries ----

    private fun expectDuplicateRejected(entries: List<Pair<String, ByteArray>>) {
        val src = tmp.newFile("dup-${System.nanoTime()}.trpz")
        writeZipEntries(src, entries)
        try {
            BackupPackage.read(src, tmp.newFolder("stage-${System.nanoTime()}"), 512L * 1024 * 1024)
            fail("expected duplicate entry rejection")
        } catch (e: BackupPackage.ParseException) {
            assertTrue(
                "unexpected message: ${e.message}",
                e.message?.contains("duplikat") == true
            )
        }
    }

    @Test fun read_rejectsDuplicateManifestEntry() = expectDuplicateRejected(
        listOf(
            BackupPackage.MANIFEST_NAME to validManifest(),
            BackupPackage.MANIFEST_NAME to validManifest(),
            BackupPackage.DB_ENTRY to sqliteBytes()
        )
    )

    @Test fun read_rejectsDuplicateDatabaseEntry() = expectDuplicateRejected(
        listOf(
            BackupPackage.MANIFEST_NAME to validManifest(),
            BackupPackage.DB_ENTRY to sqliteBytes(),
            BackupPackage.DB_ENTRY to sqliteBytes()
        )
    )

    @Test fun read_rejectsDuplicateMediaEntry() = expectDuplicateRejected(
        listOf(
            BackupPackage.MANIFEST_NAME to validManifest(),
            BackupPackage.DB_ENTRY to sqliteBytes(),
            "${BackupPackage.PHOTO_DIR}/product_1.jpg" to "a".toByteArray(),
            "${BackupPackage.PHOTO_DIR}/product_1.jpg" to "b".toByteArray()
        )
    )

    @Test fun read_rejectsDirectoryEntry() {
        val src = tmp.newFile("dir.trpz")
        writeZipEntries(
            src,
            listOf(
                BackupPackage.MANIFEST_NAME to validManifest(),
                BackupPackage.DB_ENTRY to sqliteBytes(),
                "${BackupPackage.PHOTO_DIR}/" to ByteArray(0)
            )
        )
        try {
            BackupPackage.read(src, tmp.newFolder("stage-dir"), 512L * 1024 * 1024)
            fail("expected directory entry rejection")
        } catch (e: BackupPackage.ParseException) {
            assertTrue(e.message?.contains("tidak dikenal") == true)
        }
    }

    // ---- Revision 01: writer-side limits must match reader-side limits ----

    /** Creates a sparse file of [size] bytes without allocating it in RAM. */
    private fun sparse(name: String, size: Long): File {
        val f = tmp.newFile(name)
        java.io.RandomAccessFile(f, "rw").use { it.setLength(size) }
        return f
    }

    @Test fun write_rejectsMediaEntryOverPerEntryLimit() {
        val db = tmp.newFile("db-entry.db"); db.writeBytes(sqliteBytes())
        val huge = sparse("huge.jpg", BackupPackage.MAX_MEDIA_ENTRY_BYTES + 1)
        try {
            File(tmp.root, "out-entry.trpz").outputStream().use {
                BackupPackage.write(it, db, listOf(huge), emptyList(), 5, 1L)
            }
            fail("expected per-entry limit rejection")
        } catch (e: BackupPackage.BuildException) {
            assertTrue("unexpected: ${e.message}", e.message?.contains("terlalu besar") == true)
        }
    }

    @Test fun write_rejectsMediaTotalOverTotalLimit() {
        val db = tmp.newFile("db-total.db"); db.writeBytes(sqliteBytes())
        // Several sparse files, each within the per-entry bound, summing past the total bound.
        val each = BackupPackage.MAX_MEDIA_ENTRY_BYTES
        val count = (BackupPackage.MAX_MEDIA_TOTAL_BYTES / each).toInt() + 1
        val files = (0 until count).map { sparse("part_$it.jpg", each) }
        try {
            File(tmp.root, "out-total.trpz").outputStream().use {
                BackupPackage.write(it, db, files, emptyList(), 5, 1L)
            }
            fail("expected total limit rejection")
        } catch (e: BackupPackage.BuildException) {
            assertTrue("unexpected: ${e.message}", e.message?.contains("Total ukuran media") == true)
        }
    }

    @Test fun write_acceptsMediaWithinLimits() {
        val db = tmp.newFile("db-ok.db"); db.writeBytes(sqliteBytes())
        val photo = tmp.newFile("product_ok.jpg"); photo.writeBytes(ByteArray(2048) { 7 })
        val out = File(tmp.root, "out-ok.trpz")
        out.outputStream().use { BackupPackage.write(it, db, listOf(photo), emptyList(), 5, 1L) }
        val content = BackupPackage.read(out, tmp.newFolder("stage-ok"), 512L * 1024 * 1024)
        assertEquals(1, content.photos.size)
        assertTrue(content.dbFile.exists())
    }
}
