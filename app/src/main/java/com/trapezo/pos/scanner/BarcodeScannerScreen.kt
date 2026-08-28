package com.trapezo.pos.scanner

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage

/** Full-screen camera scanner. It returns one decoded barcode then the caller closes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarcodeScannerScreen(onBarcode: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
            Icon(Icons.Default.FlashlightOn, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Izin kamera diperlukan untuk memindai barcode.", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("IZINKAN KAMERA") }
            Button(onClick = onDismiss) { Text("KEMBALI") }
        }
    } else {
        val lifecycleOwner = LocalLifecycleOwner.current
        val previewView = remember { PreviewView(context).also { it.scaleType = PreviewView.ScaleType.FILL_CENTER } }
        val scanner = remember { BarcodeScanning.getClient() }
        val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
        var consumed by remember { mutableStateOf(false) }

        DisposableEffect(lifecycleOwner) {
            val providerFuture = ProcessCameraProvider.getInstance(context)
            val listener = Runnable {
                try {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis.setAnalyzer(mainExecutor) { imageProxy ->
                        val image = imageProxy.image
                        if (image == null) { imageProxy.close(); return@setAnalyzer }
                        val input = InputImage.fromMediaImage(image, imageProxy.imageInfo.rotationDegrees)
                        scanner.process(input)
                            .addOnSuccessListener { codes ->
                                val raw = codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                                if (!consumed && raw != null) {
                                    consumed = true
                                    onBarcode(raw)
                                }
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (_: Exception) {
                    // UI remains visible; user can close and use keyboard-wedge scanning instead.
                }
            }
            providerFuture.addListener(listener, mainExecutor)
            onDispose {
                try { providerFuture.get().unbindAll() } catch (_: Exception) { }
                scanner.close()
            }
        }

        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            TopAppBar(title = { Text("Scan Barcode", color = Color.White) }, navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Tutup", tint = Color.White) } }, modifier = Modifier.background(Color(0x55000000)))
            Card(modifier = Modifier.align(Alignment.BottomCenter).padding(22.dp), shape = RoundedCornerShape(16.dp)) {
                Text("Arahkan kamera ke barcode produk", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
