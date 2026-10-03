package com.cortinadev.dogmatix.ui.screens.sources.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.screens.settings.PillButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR codes for the source list: drawing them, and reading them back from a photo. */
object QrCodes {

    fun bitmap(content: String, size: Int = 720): Bitmap {
        val matrix = QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
        )
        val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix.get(i % matrix.width, i / matrix.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }

    /** The text of the QR code in the picture at [uri], or null when none can be read. */
    fun read(context: Context, uri: Uri): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
        val reader = MultiFormatReader().apply { setHints(hints) }
        runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text }.getOrNull()
            ?: runCatching { reader.decodeWithState(BinaryBitmap(GlobalHistogramBinarizer(source))).text }.getOrNull()
    }.getOrNull()
}

/** The source list as QR codes, one at a time when it needs several. */
@Composable
fun QrShowDialog(parts: List<String>, onDismiss: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    val bitmap = remember(index, parts) { QrCodes.bitmap(parts[index]).asImageBitmap() }
    val closeFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.qr_show_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.qr_show_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.background(Color.White).padding(8.dp)) {
                    Image(bitmap, contentDescription = null, filterQuality = FilterQuality.None, modifier = Modifier.size(280.dp))
                }
                if (parts.size > 1) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("‹") { index = (index - 1 + parts.size) % parts.size }
                    Text(stringResource(R.string.qr_part, index + 1, parts.size), style = MaterialTheme.typography.titleMedium)
                    PillButton("›") { index = (index + 1) % parts.size }
                }
            }
        },
        confirmButton = { DialogButton(text = stringResource(R.string.dialog_close), onClick = onDismiss, initialFocus = closeFocus) }
    )
}

/** Share menu of the Sources screen: as a file, as QR codes, or read QR codes from photos. */
@Composable
fun ShareSourcesDialog(onFile: () -> Unit, onShowQr: () -> Unit, onReadQrCamera: () -> Unit, onReadQrPictures: () -> Unit, onDismiss: () -> Unit) {
    val fileFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_sources_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.share_sources_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DialogButton(text = stringResource(R.string.share_sources_file), onClick = onFile, initialFocus = fileFocus)
                DialogButton(text = stringResource(R.string.share_sources_qr), onClick = onShowQr)
                DialogButton(text = stringResource(R.string.share_sources_read_camera), onClick = onReadQrCamera)
                DialogButton(text = stringResource(R.string.share_sources_read_pictures), onClick = onReadQrPictures)
            }
        },
        confirmButton = { DialogButton(text = stringResource(R.string.dialog_close), onClick = onDismiss) }
    )
}
