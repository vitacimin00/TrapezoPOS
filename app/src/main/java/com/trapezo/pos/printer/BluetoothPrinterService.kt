package com.trapezo.pos.printer

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Classic Bluetooth SPP transport for common ESC/POS thermal printers.
 * It does not pretend a print succeeded: connection/write failures are returned
 * to the caller so the UI can offer the PDF/share fallback.
 */
class BluetoothPrinterService(private val context: Context) {

    data class PairedPrinter(val name: String, val address: String)
    sealed class Result<out T> {
        data class Success<T>(val value: T) : Result<T>()
        data class Error(val message: String) : Result<Nothing>()
    }

    private fun canConnect(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission", "DEPRECATION")
    fun pairedPrinters(): Result<List<PairedPrinter>> {
        if (!canConnect()) return Result.Error("Izin Bluetooth diperlukan untuk membaca printer yang dipasangkan")
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return Result.Error("Perangkat ini tidak mendukung Bluetooth")
        if (!adapter.isEnabled) return Result.Error("Bluetooth belum aktif")
        return try {
            Result.Success(
                adapter.bondedDevices
                    .map { PairedPrinter(it.name ?: "Printer Bluetooth", it.address) }
                    .sortedBy { it.name.lowercase() }
            )
        } catch (e: Exception) {
            Result.Error("Gagal membaca printer Bluetooth: ${e.message}")
        }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    suspend fun print(address: String, payload: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        if (address.isBlank()) return@withContext Result.Error("Belum ada printer yang dipilih")
        if (!canConnect()) return@withContext Result.Error("Izin Bluetooth diperlukan untuk mencetak")
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@withContext Result.Error("Perangkat ini tidak mendukung Bluetooth")
        if (!adapter.isEnabled) return@withContext Result.Error("Bluetooth belum aktif")
        try {
            val device = adapter.bondedDevices.firstOrNull { it.address.equals(address, ignoreCase = true) }
                ?: return@withContext Result.Error("Printer $address tidak ditemukan dalam perangkat yang dipasangkan")
            adapter.cancelDiscovery()
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            try {
                socket.connect()
                socket.outputStream.use { out ->
                    out.write(payload)
                    out.flush()
                }
                Result.Success(Unit)
            } finally {
                try { socket.close() } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            Result.Error("Cetak gagal: ${e.message ?: "koneksi Bluetooth bermasalah"}")
        }
    }

    suspend fun testPrint(address: String): Result<Unit> = print(
        address,
        byteArrayOf(0x1B, 0x40) +
            "TRAPEZO POS\nTEST PRINT BERHASIL\n\n\n".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x1D, 0x56, 0x00)
    )

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
