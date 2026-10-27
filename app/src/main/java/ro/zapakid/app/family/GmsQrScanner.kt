package ro.zapakid.app.family

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await

/**
 * Google's code scanner (Play services): the camera runs in Google's process, so ZapaKid needs no camera
 * permission and never sees the camera image, only the decoded QR text.
 */
class GmsQrScanner(private val context: Context) : QrScanner {
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    override suspend fun scan(): String? {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        return try {
            GmsBarcodeScanning.getClient(context, options).startScan().await().rawValue
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // our own cancellation goes on
            null // the kid closed the scanner (the Task was cancelled)
        } catch (e: Exception) {
            null
        }
    }
}
