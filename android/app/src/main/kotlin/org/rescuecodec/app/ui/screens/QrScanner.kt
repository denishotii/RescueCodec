// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.rescuecodec.app.R
import org.rescuecodec.app.ui.Permissions
import org.rescuecodec.app.ui.components.BigButton
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen QR scanner for Meshtastic channel QR codes. CameraX shows the picture and ZXing
 * (bundled, no network) reads the code. Nothing leaves the phone.
 */
@Composable
fun QrScannerDialog(onResult: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(Permissions.granted(context, Manifest.permission.CAMERA)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted) {
                CameraPreview(onResult)
            } else {
                Column(Modifier.align(Alignment.Center).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.qr_need_camera), color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    BigButton(stringResource(R.string.action_allow), { launcher.launch(Manifest.permission.CAMERA) })
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (granted) Text(stringResource(R.string.qr_point), color = Color.White, style = MaterialTheme.typography.titleMedium)
                BigButton(stringResource(R.string.action_cancel), onClose, Modifier.fillMaxWidth(), outlined = true, container = Color.White)
            }
        }
    }
}

@Composable
private fun CameraPreview(onResult: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val done = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { image ->
                    val text = decode(reader, image)
                    image.close()
                    if (text != null && done.compareAndSet(false, true)) {
                        ContextCompat.getMainExecutor(ctx).execute { onResult(text) }
                    }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(ctx))
            view
        },
    )
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }
}

private fun decode(reader: MultiFormatReader, image: ImageProxy): String? {
    val plane = image.planes[0]
    val w = image.width
    val h = image.height
    val buf = plane.buffer
    val stride = plane.rowStride
    val data = ByteArray(w * h)
    for (row in 0 until h) {
        buf.position(row * stride)
        buf.get(data, row * w, w)
    }
    val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
    return try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (e: Exception) {
        null
    } finally {
        reader.reset()
    }
}
