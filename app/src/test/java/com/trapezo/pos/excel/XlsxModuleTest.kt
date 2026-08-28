package com.trapezo.pos.excel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XlsxModuleTest {
    @Test fun formulaRows_followRetainedRowsAcrossBlankRows() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            put("xl/workbook.xml", "<workbook><sheets><sheet name=\"product\" r:id=\"rId1\"/></sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", "<Relationships><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>")
            put("xl/worksheets/sheet1.xml", "<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>name</t></is></c></row><row r=\"2\"/><row r=\"3\"><c r=\"A3\" t=\"inlineStr\"><is><t>safe</t></is></c></row><row r=\"4\"/><row r=\"5\"><c r=\"A5\"><f>1+1</f><v>2</v></c></row></sheetData></worksheet>")
        }
        val decoded = XlsxModule.read(ByteArrayInputStream(out.toByteArray()), "product")
        assertEquals(2, decoded.rows.size)
        assertEquals(setOf(1), decoded.formulaRows)
    }

    @Test fun writeThenRead_createsRealXlsxWorkbookWithProductSheetAndCells() {
        val out = ByteArrayOutputStream()
        XlsxModule.write(
            out,
            listOf(
                XlsxModule.WriteSheet(
                    "product",
                    listOf(
                        listOf(XlsxModule.CellVal.Txt("name"), XlsxModule.CellVal.Txt("sell_price")),
                        listOf(XlsxModule.CellVal.Txt("Kopi Arabika"), XlsxModule.CellVal.I(25_000))
                    )
                )
            )
        )
        val bytes = out.toByteArray()

        // OOXML is a ZIP package, never fake CSV text with a .xlsx suffix.
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                names += entry.name
                entry = zin.nextEntry
            }
        }
        assertTrue(names.contains("[Content_Types].xml"))
        assertTrue(names.contains("xl/workbook.xml"))
        assertTrue(names.contains("xl/worksheets/sheet1.xml"))

        val decoded = XlsxModule.read(ByteArrayInputStream(bytes), "product")
        assertEquals("product", decoded.activeSheet)
        assertEquals(listOf("name", "sell_price"), decoded.headers)
        assertEquals(1, decoded.rows.size)
        assertEquals("Kopi Arabika", decoded.rows.single()["name"])
        assertEquals("25000", decoded.rows.single()["sell_price"])
    }

    @Test fun productTemplate_hasExactFortyNineHeadersAndNoProductRows() {
        val out = ByteArrayOutputStream()
        // The public template is produced by ProductExcelService in Android UI;
        // this contract asserts the prescribed workbook header list itself.
        XlsxModule.write(
            out,
            listOf(XlsxModule.WriteSheet("product", listOf(ProductExcelService.HEADERS.map { XlsxModule.CellVal.Txt(it) })))
        )
        val decoded = XlsxModule.read(ByteArrayInputStream(out.toByteArray()), "product")
        assertEquals(49, decoded.headers.size)
        assertEquals(ProductExcelService.HEADERS, decoded.headers)
        assertTrue(decoded.rows.isEmpty())
    }
}
