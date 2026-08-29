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
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
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
}
