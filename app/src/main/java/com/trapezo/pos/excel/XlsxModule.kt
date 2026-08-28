package com.trapezo.pos.excel

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Real OOXML (.xlsx) reader/writer — no fake CSV masquerading.
 *
 * Reader supports: shared strings, inline strings, number/bool cells, styles-based
 * date conversion, multiple sheets, full column-reference parsing ("BC23" -> col).
 * Writer produces minimal spec-compliant workbooks readable by Excel/LibreOffice/WPS.
 */
object XlsxModule {

    // ---------- public result types ----------
    class SheetRef(val name: String, val entryPath: String)

    class ReadResult(
        val sheetNames: List<String>,
        val activeSheet: String,
        val headers: List<String>,
        /** Row values keyed by normalized header (trimmed). Missing cells are absent/empty string. */
        val rows: List<Map<String, String>>,
        val skippedRows: Int
    )

    sealed class CellVal {
        data class Txt(val s: String) : CellVal()
        data class N(val v: Double) : CellVal()
        data class I(val v: Long) : CellVal()
    }

    class WriteSheet(val name: String, val rows: List<List<CellVal>>)

    // ---------- helpers ----------
    private val COL_LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    fun colLetters(index0: Int): String {
        var n = index0
        val sb = StringBuilder()
        while (n >= 0) {
            sb.insert(0, COL_LETTERS[n % 26])
            n = n / 26 - 1
        }
        return sb.toString()
    }

    fun lettersIndex(letters: String): Int {
        var n = 0
        for (c in letters.uppercase(Locale.US)) {
            if (c !in 'A'..'Z') continue
            n = n * 26 + (c - 'A' + 1)
        }
        return n - 1
    }

    private fun cellRefSplit(ref: String): Pair<Int, Int>? {
        var i = 0
        val sb = StringBuilder()
        while (i < ref.length && ref[i].isLetter()) { sb.append(ref[i]); i++ }
        if (sb.isEmpty()) return null
        val row = ref.substring(i).toIntOrNull() ?: return null
        return Pair(lettersIndex(sb.toString()), row - 1) // col0, row0
    }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch == '\'' -> sb.append("&apos;")
                ch == '\n' -> sb.append("&#10;")
                ch == '\r' -> {}
                ch.code < 0x20 -> {} // drop other control chars
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun stripNs(tagAttrs: String, attr: String): String? {
        // matches plain attr or namespaced like r:id
        val m = Regex("(?:^|\\s)(?:[A-Za-z0-9]+:)?$attr=\"([^\"]*)\"").find(tagAttrs)
        return m?.groupValues?.get(1)
    }

    // ============================ READER ============================
    /**
     * Reads the workbook from stream (consumed fully once). If preferSheet is given,
     * that sheet is parsed when present, otherwise the first sheet.
     */
    fun read(stream: InputStream, preferSheet: String? = null): ReadResult {
        // Load zip entries to memory (import files are small)
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(stream.buffered()).use { zin ->
            var e: ZipEntry? = zin.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val name = e.name.replace('\\', '/')
                    if (!name.startsWith("xl/media/") && !name.startsWith("xl/drawings/")) {
                        entries[name] = zin.readBytes()
                    }
                }
                e = zin.nextEntry
            }
        }

        val wbXml = entries["xl/workbook.xml"]?.toString(StandardCharsets.UTF_8)
            ?: throw IllegalArgumentException("Bukan file .xlsx yang valid (workbook.xml tidak ditemukan)")

        // sheet name -> rel id
        val sheetRid = LinkedHashMap<String, String>()
        Regex("<sheet[^>]*>").findAll(wbXml).forEach { m ->
            val tag = m.value
            val nm = stripNs(tag, "name")
            val rid = stripNs(tag, "id") ?: stripNs(tag, "sid")
            if (nm != null && rid != null) sheetRid[nm] = rid
        }
        if (sheetRid.isEmpty()) throw IllegalArgumentException("Workbook tidak memiliki sheet")

        // rels: rid -> target path
        val relsXml = entries["xl/_rels/workbook.xml.rels"]?.toString(StandardCharsets.UTF_8) ?: ""
        val ridTarget = HashMap<String, String>()
        Regex("<Relationship[^>]*>").findAll(relsXml).forEach { m ->
            val tag = m.value
            val id = stripNs(tag, "Id")
            val tgt = stripNs(tag, "Target")
            if (id != null && tgt != null) {
                ridTarget[id] = if (tgt.startsWith("/")) tgt.removePrefix("/") else "xl/" + tgt.removePrefix("./")
            }
        }

        val orderedSheets = sheetRid.entries.mapNotNull { (nm, rid) ->
            ridTarget[rid]?.let { SheetRef(nm, it) }
        }
        val chosen = orderedSheets.firstOrNull { it.name.equals(preferSheet, ignoreCase = true) } ?: orderedSheets.first()
        val sheetXml = entries[chosen.entryPath]?.toString(StandardCharsets.UTF_8)
            ?: throw IllegalArgumentException("Sheet '${chosen.name}' tidak dapat dibaca")

        // shared strings
        val shared = ArrayList<String>()
        entries["xl/sharedStrings.xml"]?.let { bytes ->
            val s = bytes.toString(StandardCharsets.UTF_8)
            Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(s).forEach { si ->
                val ts = Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(si.groupValues[1]).joinToString("") { unesc(it.groupValues[1]) }
                shared.add(ts)
            }
        }

        // styles: which xf indexes are dates (numFmt 14-22 or custom containing d/m/y/h)
        val dateStyles = HashSet<Int>()
        entries["xl/styles.xml"]?.let { bytes ->
            val s = bytes.toString(StandardCharsets.UTF_8)
            val fmtIds = HashMap<Int, String>() // numFmtId -> code
            Regex("<numFmt[^>]*/?>").findAll(s).forEach { m ->
                val id = stripNs(m.value, "numFmtId")?.toIntOrNull() ?: return@forEach
                val code = stripNs(m.value, "formatCode") ?: ""
                fmtIds[id] = code
            }
            Regex("<cellXfs[^>]*>(.*?)</cellXfs>", RegexOption.DOT_MATCHES_ALL).findAll(s).forEach { block ->
                var idx = 0
                Regex("<xf[^>]*/?>").findAll(block.groupValues[1]).forEach { xm ->
                    val fid = stripNs(xm.value, "numFmtId")?.toIntOrNull() ?: 0
                    val builtinDate = fid in 14..22 || fid in 45..47
                    val code = fmtIds[fid] ?: ""
                    val customDate = code.contains('d') || code.contains('m') && code.contains('/') ||
                        code.contains('y') || code.contains('h') && code.contains(':')
                    if (builtinDate || customDate) dateStyles.add(idx)
                    idx++
                }
            }
        }

        // parse rows
        data class RawCell(var col: Int, var value: String)

        val outRows = ArrayList<List<String?>>()
        var maxCols = 0
        val epoch1904 = wbXml.contains("date1904=\"1\"") || wbXml.contains("date1904=\"true\"")

        Regex("<row[^>]*>(.*?)</row>|<row[^>]*/>", RegexOption.DOT_MATCHES_ALL).findAll(sheetXml).forEach { rm ->
            val inner = rm.groupValues.getOrElse(1) { "" }
            if (inner.isEmpty()) return@forEach
            val cells = ArrayList<RawCell>()
            Regex("<c ([^>]*?)/>|<c ([^>]*?)>(.*?)</c>", RegexOption.DOT_MATCHES_ALL).findAll(inner).forEach { cm ->
                val attrs = cm.groupValues[2].ifEmpty { cm.groupValues[1] }
                val body = if (cm.groupValues[3].isEmpty()) "" else cm.groupValues[3]
                val rref = stripNs(attrs, "r") ?: return@forEach
                val colRow = cellRefSplit(rref) ?: return@forEach
                val type = stripNs(attrs, "t") ?: "n"
                val styleIdx = stripNs(attrs, "s")?.toIntOrNull() ?: 0

                var text: String? = null
                when (type) {
                    "s" -> {
                        val v = Regex("<v[^>]*>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
                        text = v?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                    }
                    "inlineStr" -> {
                        text = Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                            .findAll(body).joinToString("") { unesc(it.groupValues[1]) }
                    }
                    "str" -> {
                        text = unesc(Regex("<v[^>]*>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1) ?: "")
                    }
                    "b" -> {
                        val v = Regex("<v[^>]*>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
                        text = if (v == "1") "TRUE" else "FALSE"
                    }
                    else -> { // numeric
                        val v = Regex("<v[^>]*>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
                        if (v != null) {
                            text = if (styleIdx in dateStyles) {
                                formatExcelDate(v.toDoubleOrNull(), epoch1904)
                            } else normalizeNumber(v)
                        }
                    }
                }
                if (body.isEmpty() && cm.groupValues.size >= 2 && cm.groupValues[1].isNotEmpty()) {
                    text = "" // self-closed empty cell keeps grid alignment
                }
                if (text != null) cells.add(RawCell(colRow.first, text))
            }
            if (cells.isNotEmpty()) {
                val lastCol = cells.maxOf { it.col }
                if (lastCol + 1 > maxCols) maxCols = lastCol + 1
                val arr = arrayOfNulls<String>(lastCol + 1)
                for (c in cells) arr[c.col] = c.value
                outRows.add(arr.toList())
            }
        }

        // First non-empty row = header
        val headerIdx = outRows.indexOfFirst { r -> r.any { !it.isNullOrBlank() } }
        if (headerIdx < 0) throw IllegalArgumentException("File Excel kosong / tidak ada baris data")

        val headers = outRows[headerIdx].map { (it ?: "").trim() }
        val usedHeaders = headers.mapIndexed { i, h -> h.ifBlank { "kolom_${i + 1}" } }

        val dataRows = ArrayList<Map<String, String>>()
        for (i in (headerIdx + 1) until outRows.size) {
            val r = outRows[i]
            if (r.all { it.isNullOrBlank() }) continue
            val m = HashMap<String, String>()
            for (c in 0 until minOf(r.size, usedHeaders.size)) {
                m[usedHeaders[c]] = (r[c] ?: "").trim()
            }
            dataRows.add(m)
        }
        return ReadResult(orderedSheets.map { it.name }, chosen.name, usedHeaders, dataRows, outRows.size - headerIdx - 1)
    }

    private fun unesc(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&#10;", "\n").replace("&amp;", "&")

    private fun normalizeNumber(v: String): String {
        val d = v.trim().toDoubleOrNull() ?: return v.trim()
        return if (d == kotlin.math.floor(d) && !d.isInfinite() && kotlin.math.abs(d) < 9.0e15) {
            d.toLong().toString()
        } else d.toString()
    }

    private fun formatExcelDate(serial: Double?, epoch1904: Boolean): String? {
        if (serial == null || serial <= 0) return null
        try {
            val daysOffset = if (epoch1904) 24107L else 25569L
            val ms = (serial * DatesUtil.MS_PER_DAY).toLong() + (daysOffset * DatesUtil.MS_PER_DAY).toLong()
            return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))
        } catch (_: Exception) {
            return serial.toString()
        }
    }

    object DatesUtil { const val MS_PER_DAY = 86_400_000.0 }

    // ============================ WRITER ============================
    fun write(out: OutputStream, sheets: List<WriteSheet>) {
        require(sheets.isNotEmpty())
        ZipOutputStream(out.buffered()).use { z ->
            fun put(name: String, content: String) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray(StandardCharsets.UTF_8))
                z.closeEntry()
            }

            val ct = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
            for (i in sheets.indices) {
                ct.append("\n<Override PartName=\"/xl/worksheets/sheet${i + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
            }
            ct.append("\n</Types>")
            put("[Content_Types].xml", ct.toString())

            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""")

            val wbs = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
            for (i in sheets.indices) {
                wbs.append("<sheet name=\"${esc(sheetTabName(sheets[i].name))}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>")
            }
            wbs.append("</sheets></workbook>")
            put("xl/workbook.xml", wbs.toString())

            val wr = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            for (i in sheets.indices) {
                wr.append("\n<Relationship Id=\"rId${i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${i + 1}.xml\"/>")
            }
            wr.append("\n</Relationships>")
            put("xl/_rels/workbook.xml.rels", wr.toString())

            put(
                "xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="1"><fill><patternFill patternType="none"/></fill></fills>
<borders count="1"><border/></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>
</styleSheet>"""
            )

            sheets.forEachIndexed { si, sh ->
                val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
                sh.rows.forEachIndexed { ri, rowVals ->
                    if (rowVals.isEmpty()) return@forEachIndexed
                    sb.append("<row r=\"${ri + 1}\">")
                    rowVals.forEachIndexed { ci, cv ->
                        val ref = "${colLetters(ci)}${ri + 1}"
                        when (cv) {
                            is CellVal.N -> sb.append("<c r=\"$ref\"><v>${trimNum(cv.v)}</v></c>")
                            is CellVal.I -> sb.append("<c r=\"$ref\"><v>${cv.v}</v></c>")
                            is CellVal.Txt -> {
                                if (cv.s.isNotEmpty()) {
                                    sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                                    sb.append(esc(cv.s))
                                    sb.append("</t></is></c>")
                                }
                            }
                        }
                    }
                    sb.append("</row>")
                }
                sb.append("</sheetData></worksheet>")
                put("xl/worksheets/sheet${si + 1}.xml", sb.toString())
            }
        }
    }

    private fun trimNum(d: Double): String =
        if (d == kotlin.math.floor(d) && !d.isInfinite() && kotlin.math.abs(d) < 9.0e15) d.toLong().toString() else d.toString()

    /** Sanitize sheet tab names (no []:*?/\ and max 31 chars). */
    private fun sheetTabName(n: String): String {
        val cleaned = n.replace(Regex("[\\[\\]:*?/\\\\]"), "_").take(31)
        return cleaned.ifBlank { "Sheet1" }
    }
}
