package lt.oranges.orangchat.feature.transfer
import lt.oranges.orangchat.util.AppStrings
import lt.oranges.orangchat.R
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import lt.oranges.orangchat.crypto.E2ee
import lt.oranges.orangchat.crypto.E2eeQr
import lt.oranges.orangchat.ui.components.ButtonVariant
import lt.oranges.orangchat.ui.components.OrangButton
import lt.oranges.orangchat.ui.theme.OrangTheme

@Composable
fun TransferQrScanner(
    expectInvitation: Boolean,
    onScanned: (String) -> Unit,
    onCancel: () -> Unit,
) = OrangQrScanner(
    purpose = if (expectInvitation) QrScanPurpose.TRANSFER_INVITATION else QrScanPurpose.TRANSFER_DEVICE,
    onScanned = onScanned,
    onCancel = onCancel,
)

@Composable
fun ContactQrScanner(
    onScanned: (String) -> Unit,
    onCancel: () -> Unit,
) = OrangQrScanner(
    purpose = QrScanPurpose.CONTACT_VERIFY,
    onScanned = onScanned,
    onCancel = onCancel,
)

private enum class QrScanPurpose {
    TRANSFER_INVITATION,
    TRANSFER_DEVICE,
    CONTACT_VERIFY,
}

@Composable
private fun OrangQrScanner(
    purpose: QrScanPurpose,
    onScanned: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val c = OrangTheme.colors
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var scanError by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (!granted) scanError = AppStrings.get(context, R.string.catalog_camera_access_is_needed_to_scan_the_dcfe064f)
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permission.launch(Manifest.permission.CAMERA)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            when (purpose) {
                QrScanPurpose.TRANSFER_INVITATION ->
                    AppStrings.get(context, R.string.catalog_point_this_phone_at_the_qr_code_cea07275)
                QrScanPurpose.TRANSFER_DEVICE ->
                    AppStrings.get(context, R.string.catalog_point_this_phone_at_the_transfer_code_d6baae81)
                QrScanPurpose.CONTACT_VERIFY ->
                    AppStrings.get(context, R.string.catalog_ask_them_to_show_their_verification_qr_630e919c)
            },
            color = c.inkSecondary,
            fontSize = 14.sp,
        )

        if (hasPermission) {
            val previewView = remember {
                PreviewView(context).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            }
            val delivered = remember { AtomicBoolean(false) }
            val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }

            DisposableEffect(lifecycleOwner, purpose) {
                val providerFuture = ProcessCameraProvider.getInstance(context)
                var provider: ProcessCameraProvider? = null
                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(
                            analyzerExecutor,
                            TransferQrAnalyzer { raw ->
                                val validationError = validateQrCode(raw, purpose)
                                ContextCompat.getMainExecutor(context).execute {
                                    if (validationError == null && delivered.compareAndSet(false, true)) {
                                        onScanned(raw.trim())
                                    } else if (validationError != null) {
                                        scanError = validationError
                                    }
                                }
                            },
                        )
                    }
                val startCamera = Runnable {
                    runCatching {
                        provider = providerFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        provider?.unbindAll()
                        provider?.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analyzer,
                        )
                    }.onFailure {
                        scanError = AppStrings.get(context, R.string.catalog_the_camera_could_not_start_you_can_a181859b)
                    }
                }
                providerFuture.addListener(startCamera, ContextCompat.getMainExecutor(context))
                onDispose {
                    analyzer.clearAnalyzer()
                    provider?.unbindAll()
                    analyzerExecutor.shutdown()
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black),
            ) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize(),
                )
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val side = size.minDimension * 0.68f
                    val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f)
                    drawRoundRect(
                        color = Color.White,
                        topLeft = topLeft,
                        size = Size(side, side),
                        cornerRadius = CornerRadius(28f, 28f),
                        style = Stroke(width = 6f),
                    )
                }
                Text(
                    AppStrings.get(context, R.string.catalog_hold_steady_scanning_happens_automatically_ae832ed2),
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.surface2, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    AppStrings.get(context, R.string.catalog_allow_camera_access_to_scan_without_leaving_ca7cd21f),
                    color = c.inkSecondary,
                    fontSize = 14.sp,
                )
                OrangButton(
                    text = AppStrings.get(context, R.string.catalog_allow_camera_1dfb1b87),
                    onClick = { permission.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OrangButton(
                    text = AppStrings.get(context, R.string.catalog_open_app_permissions_893246fb),
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    },
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        scanError?.let { Text(it, color = c.danger, fontSize = 13.sp) }
        OrangButton(
            text = AppStrings.get(context, R.string.catalog_cancel_scanning_f1f3d169),
            onClick = onCancel,
            variant = ButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

internal fun validateTransferCode(raw: String, expectInvitation: Boolean): String? {
    return validateQrCode(
        raw,
        if (expectInvitation) QrScanPurpose.TRANSFER_INVITATION else QrScanPurpose.TRANSFER_DEVICE,
    )
}

internal fun validateContactCode(raw: String): String? =
    validateQrCode(raw, QrScanPurpose.CONTACT_VERIFY)

private fun validateQrCode(raw: String, purpose: QrScanPurpose): String? {
    if (purpose == QrScanPurpose.CONTACT_VERIFY) {
        if (E2eeQr.kindOf(raw) != E2ee.QrKind.CONTACT_VERIFY) {
            return "That is not an OrangChat contact-verification code."
        }
        return runCatching { E2eeQr.decodeContactVerify(raw) }
            .exceptionOrNull()
            ?.message
    }
    if (E2eeQr.kindOf(raw) != E2ee.QrKind.DEVICE_TRANSFER) {
        return "That is not an OrangChat device-transfer code."
    }
    val expectInvitation = purpose == QrScanPurpose.TRANSFER_INVITATION
    return runCatching {
        if (expectInvitation) {
            E2eeQr.decodeDeviceTransferInvite(raw)
        } else {
            E2eeQr.decodeDeviceTransfer(raw)
        }
    }.exceptionOrNull()?.let {
        val isInvitation = E2eeQr.isDeviceTransferInvite(raw)
        if (!expectInvitation && isInvitation) {
            "This code is for the phone being added. Scan it on the new phone."
        } else if (expectInvitation && !isInvitation) {
            "This code must be scanned by an already-authorized device. Scan the QR shown on your PC."
        } else {
            it.message ?: "This device-transfer code is invalid."
        }
    }
}

private class TransferQrAnalyzer(
    private val onQrCode: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    override fun analyze(image: ImageProxy) {
        try {
            val luma = contiguousLuma(image)
            val source = PlanarYUVLuminanceSource(
                luma,
                image.width,
                image.height,
                0,
                0,
                image.width,
                image.height,
                false,
            )
            val result = runCatching {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            }.getOrNull()
            result?.text?.takeIf(String::isNotBlank)?.let(onQrCode)
        } finally {
            reader.reset()
            image.close()
        }
    }
}

private fun contiguousLuma(image: ImageProxy): ByteArray {
    val plane = image.planes[0]
    val buffer = plane.buffer.duplicate()
    val base = buffer.position()
    val output = ByteArray(image.width * image.height)
    for (row in 0 until image.height) {
        val rowStart = base + row * plane.rowStride
        if (plane.pixelStride == 1) {
            buffer.position(rowStart)
            buffer.get(output, row * image.width, image.width)
        } else {
            for (column in 0 until image.width) {
                output[row * image.width + column] =
                    buffer.get(rowStart + column * plane.pixelStride)
            }
        }
    }
    return output
}
