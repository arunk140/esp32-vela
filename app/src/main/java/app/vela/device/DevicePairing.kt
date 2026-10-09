package app.vela.device

import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.vela.R
import app.vela.ui.dpadAutoFocus
import app.vela.ui.dpadHighlight
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject
import java.util.concurrent.Executors

/** The QR a device shows on its pairing screen: JSON with its name `n`, Bluetooth address `m`
 *  and a six-digit code `t` that proves the phone saw this screen. */
object DeviceQr {
    private val ADDRESS = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
    private val CODE = Regex("[0-9]{4,8}")

    /** Null for anything that is not a device's pairing code. */
    fun parse(raw: String): DeviceLinkPrefs.Bond? = runCatching {
        val j = JSONObject(raw)
        val mac = j.getString("m")
        val token = j.getString("t")
        if (!ADDRESS.matches(mac) || !CODE.matches(token)) return null
        DeviceLinkPrefs.Bond(mac.uppercase(), j.optString("n").take(24), token)
    }.getOrNull()
}

/**
 * The camera, looking for a device's pairing QR. Calls [onBond] once with the first one it reads.
 * The caller holds the camera permission before showing this.
 *
 * The decoder is ML Kit's bundled barcode model, the one Google SDK in the app. It runs on the
 * phone and sends nothing anywhere.
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
internal fun DeviceQrScanner(onBond: (DeviceLinkPrefs.Bond) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val found by rememberUpdatedState(onBond)
    val dismiss by rememberUpdatedState(onDismiss)
    val preview = remember { PreviewView(context) }

    DisposableEffect(owner) {
        val worker = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
        val pending = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var done = false
        pending.addListener({
            val p = runCatching { pending.get() }.getOrNull()
            if (p == null) {
                dismiss()
                return@addListener
            }
            provider = p
            val view = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val frames = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            frames.setAnalyzer(worker) { frame ->
                val image = frame.image
                if (done || image == null) {
                    frame.close()
                    return@setAnalyzer
                }
                scanner.process(InputImage.fromMediaImage(image, frame.imageInfo.rotationDegrees))
                    .addOnSuccessListener { codes ->
                        val bond = codes.firstNotNullOfOrNull { c -> c.rawValue?.let(DeviceQr::parse) }
                        if (bond != null && !done) {
                            done = true
                            found(bond)
                        }
                    }
                    .addOnCompleteListener { frame.close() }
            }
            runCatching {
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, view, frames)
            }.onFailure { dismiss() }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            done = true
            runCatching { provider?.unbindAll() }
            scanner.close()
            worker.shutdown()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().systemBarsPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.device_scan_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.dpadAutoFocus().dpadHighlight(ButtonDefaults.shape),
                ) { Text(stringResource(android.R.string.cancel)) }
            }
        }
    }
}
