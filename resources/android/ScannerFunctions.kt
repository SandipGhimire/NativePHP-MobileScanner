@file:androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])

package com.sandip.plugins.scanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import com.nativephp.mobile.utils.NativeActionCoordinator
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

object ScannerFunctions {

    private const val TAG = "ScannerFunctions"
    const val CAMERA_PERMISSION_REQUEST_CODE = 4272
    private const val CODE_SCANNED_EVENT = "Sandip\\Scanner\\Native\\Events\\Scanner\\CodeScanned"
    private const val CANCELLED_EVENT = "Sandip\\Scanner\\Native\\Events\\Scanner\\Cancelled"

    private const val REPEAT_DEBOUNCE_MS = 2000L

    @Volatile private var activeOverlay: ScannerOverlay? = null

    private data class PendingScan(val id: String?)

    @Volatile private var pendingScan: PendingScan? = null

    private val FORMAT_MAP: Map<String, Int> =
            mapOf(
                    "qr" to Barcode.FORMAT_QR_CODE,
                    "ean13" to Barcode.FORMAT_EAN_13,
                    "ean8" to Barcode.FORMAT_EAN_8,
                    "code128" to Barcode.FORMAT_CODE_128,
                    "code39" to Barcode.FORMAT_CODE_39,
                    "upca" to Barcode.FORMAT_UPC_A,
                    "upce" to Barcode.FORMAT_UPC_E,
            )

    private val REVERSE_FORMAT_MAP: Map<Int, String> =
            mapOf(
                    Barcode.FORMAT_QR_CODE to "qr",
                    Barcode.FORMAT_EAN_13 to "ean13",
                    Barcode.FORMAT_EAN_8 to "ean8",
                    Barcode.FORMAT_CODE_128 to "code128",
                    Barcode.FORMAT_CODE_39 to "code39",
                    Barcode.FORMAT_UPC_A to "upca",
                    Barcode.FORMAT_UPC_E to "upce",
                    Barcode.FORMAT_CODE_93 to "code93",
                    Barcode.FORMAT_CODABAR to "codabar",
                    Barcode.FORMAT_ITF to "itf",
                    Barcode.FORMAT_DATA_MATRIX to "data_matrix",
                    Barcode.FORMAT_PDF417 to "pdf417",
                    Barcode.FORMAT_AZTEC to "aztec",
            )

    private fun barcodeFormatOptions(names: List<String>): BarcodeScannerOptions {
        if (names.contains("all")) {
            return BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                    .build()
        }

        val formats = names.mapNotNull { FORMAT_MAP[it] }.distinct()
        val first = formats.firstOrNull() ?: Barcode.FORMAT_QR_CODE
        val rest = formats.drop(1).toIntArray()

        return BarcodeScannerOptions.Builder().setBarcodeFormats(first, *rest).build()
    }

    private object PermissionPrefs {
        private const val PREFS_NAME = "scanner_permission_prefs"
        private const val KEY_ASKED = "camera_permission_asked"

        fun hasAskedBefore(context: Context): Boolean =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getBoolean(KEY_ASKED, false)

        fun markAsked(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_ASKED, true)
                    .apply()
        }
    }

    class GalleryPickerHost : Fragment() {
        private var callback: ((Uri?) -> Unit)? = null

        private val launcher =
                registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    val cb = callback
                    callback = null
                    cb?.invoke(uri)
                }

        fun pickImage(onPicked: (Uri?) -> Unit) {
            callback = onPicked
            launcher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        companion object {
            private const val TAG = "ScannerGalleryPicker"

            fun install(activity: FragmentActivity): GalleryPickerHost =
                    activity.supportFragmentManager.findFragmentByTag(TAG) as? GalleryPickerHost
                            ?: GalleryPickerHost().also {
                                activity.supportFragmentManager
                                        .beginTransaction()
                                        .add(it, TAG)
                                        .commitNow()
                            }
        }
    }

    private fun startScan(
            activity: FragmentActivity,
            prompt: String,
            continuous: Boolean,
            allowGallery: Boolean,
            formats: List<String>,
            id: String?,
    ) {
        activity.runOnUiThread {
            activeOverlay?.finish(cancelled = true)
            val overlay = ScannerOverlay(activity, prompt, continuous, allowGallery, formats, id)
            activeOverlay = overlay
            overlay.show()
        }
    }

    fun onRequestPermissionsResult(
            activity: FragmentActivity,
            requestCode: Int,
            grantResults: IntArray
    ) {
        if (requestCode != CAMERA_PERMISSION_REQUEST_CODE) return
        val pending = pendingScan ?: return
        pendingScan = null

        val granted =
                grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED

        activity.runOnUiThread {
            val payload = JSONObject()
            payload.put("reason", if (granted) "permission_required" else "permission_denied")
            if (pending.id != null) payload.put("id", pending.id)
            NativeActionCoordinator.dispatchEvent(activity, CANCELLED_EVENT, payload.toString())
        }
    }

    class Scan(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val prompt = parameters["prompt"] as? String ?: "Scan Code"
            val continuous = parameters["continuous"] as? Boolean ?: false
            val allowGallery = parameters["allowGallery"] as? Boolean ?: true
            val id = parameters["id"] as? String

            @Suppress("UNCHECKED_CAST")
            val requestedFormats =
                    (parameters["formats"] as? List<String>)?.filter { it.isNotBlank() }?.takeIf {
                        it.isNotEmpty()
                    }
                            ?: listOf("qr")

            val unknown = requestedFormats.filter { it != "all" && !FORMAT_MAP.containsKey(it) }
            if (unknown.isNotEmpty()) {
                return BridgeResponse.error(
                        "INVALID_FORMAT",
                        "Unknown barcode format(s): ${unknown.joinToString(", ")}. Valid formats are: ${(FORMAT_MAP.keys + "all").joinToString(", ")}."
                )
            }

            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) !=
                            PackageManager.PERMISSION_GRANTED
            ) {
                val askedBefore = PermissionPrefs.hasAskedBefore(activity)
                val canShowRationale =
                        ActivityCompat.shouldShowRequestPermissionRationale(
                                activity,
                                Manifest.permission.CAMERA
                        )

                if (askedBefore && !canShowRationale) {
                    return BridgeResponse.error(
                            "PERMISSION_DENIED",
                            "Camera access is denied. Enable it in Settings to use the scanner."
                    )
                }

                PermissionPrefs.markAsked(activity)
                pendingScan = PendingScan(id)

                ActivityCompat.requestPermissions(
                        activity,
                        arrayOf(Manifest.permission.CAMERA),
                        CAMERA_PERMISSION_REQUEST_CODE
                )

                return BridgeResponse.success(mapOf("permissionRequested" to true))
            }

            startScan(activity, prompt, continuous, allowGallery, requestedFormats, id)

            return BridgeResponse.success(mapOf("started" to true))
        }
    }

    class Stop(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val id = parameters["id"] as? String
            val overlay = activeOverlay

            if (overlay == null || (id != null && overlay.id != id)) {
                return BridgeResponse.success(mapOf("stopped" to false))
            }

            activity.runOnUiThread { overlay.finish(cancelled = true, reason = "stopped_by_app") }

            return BridgeResponse.success(mapOf("stopped" to true))
        }
    }

    private class ScannerOverlay(
            private val activity: FragmentActivity,
            private val prompt: String,
            private val continuous: Boolean,
            private val allowGallery: Boolean,
            private val formatNames: List<String>,
            val id: String?,
    ) {
        private val root = activity.findViewById<ViewGroup>(android.R.id.content)
        private val executor: ExecutorService = Executors.newSingleThreadExecutor()
        private val finished = AtomicBoolean(false)
        private var overlayView: FrameLayout? = null
        private var cameraProvider: ProcessCameraProvider? = null
        private var camera: Camera? = null
        private var torchOn = false
        private var scanner: BarcodeScanner? = null

        private var lastValue: String? = null
        private var lastFiredAt: Long = 0L

        private fun dp(value: Int): Int =
                (value * activity.resources.displayMetrics.density).toInt()

        fun show() {
            val previewView =
                    PreviewView(activity).apply {
                        layoutParams =
                                FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT
                                )
                    }

            val promptLabel =
                    TextView(activity).apply {
                        text = prompt
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        gravity = Gravity.CENTER
                        layoutParams =
                                FrameLayout.LayoutParams(
                                                FrameLayout.LayoutParams.MATCH_PARENT,
                                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                                Gravity.BOTTOM
                                        )
                                        .apply { bottomMargin = dp(64) }
                    }

            val closeButton =
                    TextView(activity).apply {
                        text = "✕"
                        setTextColor(Color.WHITE)
                        textSize = 20f
                        gravity = Gravity.CENTER
                        setBackgroundColor(Color.parseColor("#66000000"))
                        isClickable = true
                        layoutParams =
                                FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END)
                                        .apply {
                                            topMargin = dp(40)
                                            rightMargin = dp(20)
                                        }
                        setOnClickListener { finish(cancelled = true, reason = "user_cancelled") }
                    }

            val torchButton =
                    TextView(activity).apply {
                        text = "⚡"
                        setTextColor(Color.WHITE)
                        textSize = 18f
                        gravity = Gravity.CENTER
                        setBackgroundColor(Color.parseColor("#66000000"))
                        isClickable = true
                        visibility = android.view.View.INVISIBLE
                        layoutParams =
                                FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END)
                                        .apply {
                                            topMargin = dp(40)
                                            rightMargin = dp(74)
                                        }
                        setOnClickListener {
                            val cam = camera ?: return@setOnClickListener
                            torchOn = !torchOn
                            cam.cameraControl.enableTorch(torchOn)
                            alpha = if (torchOn) 1f else 0.6f
                        }
                    }

            val galleryButton =
                    if (!allowGallery) null
                    else
                            TextView(activity).apply {
                                text = "🖼"
                                setTextColor(Color.WHITE)
                                textSize = 18f
                                gravity = Gravity.CENTER
                                setBackgroundColor(Color.parseColor("#66000000"))
                                isClickable = true
                                layoutParams =
                                        FrameLayout.LayoutParams(
                                                        dp(44),
                                                        dp(44),
                                                        Gravity.TOP or Gravity.END
                                                )
                                                .apply {
                                                    topMargin = dp(40)
                                                    rightMargin = dp(128)
                                                }
                                setOnClickListener { pickFromGallery() }
                            }

            val overlay =
                    FrameLayout(activity).apply {
                        setBackgroundColor(Color.BLACK)
                        addView(previewView)
                        addView(promptLabel)
                        addView(closeButton)
                        addView(torchButton)
                        galleryButton?.let { addView(it) }
                        layoutParams =
                                FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT
                                )
                    }

            overlayView = overlay
            root.addView(overlay)

            val scanner = BarcodeScanning.getClient(barcodeFormatOptions(formatNames))
            this.scanner = scanner

            val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
            cameraProviderFuture.addListener(
                    {
                        val provider = cameraProviderFuture.get()
                        cameraProvider = provider

                        val preview =
                                Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                        val analysis =
                                ImageAnalysis.Builder()
                                        .setBackpressureStrategy(
                                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                                        )
                                        .build()
                                        .also {
                                            it.setAnalyzer(executor) { imageProxy ->
                                                processFrame(imageProxy, scanner)
                                            }
                                        }

                        try {
                            provider.unbindAll()
                            val boundCamera =
                                    provider.bindToLifecycle(
                                            activity,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview,
                                            analysis
                                    )
                            camera = boundCamera
                            if (boundCamera.cameraInfo.hasFlashUnit()) {
                                torchButton.visibility = android.view.View.VISIBLE
                                torchButton.alpha = 0.6f
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to bind camera", e)
                            finish(cancelled = true, reason = "camera_error")
                        }
                    },
                    ContextCompat.getMainExecutor(activity)
            )
        }

        private fun processFrame(imageProxy: ImageProxy, scanner: BarcodeScanner) {
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return
            }

            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        val barcode = barcodes.firstOrNull { !it.rawValue.isNullOrEmpty() }
                        val value = barcode?.rawValue

                        if (value != null) {
                            handleMatch(value, REVERSE_FORMAT_MAP[barcode.format] ?: "unknown")
                        }
                    }
                    .addOnFailureListener { Log.e(TAG, "Barcode scan failed", it) }
                    .addOnCompleteListener { imageProxy.close() }
        }

        private fun pickFromGallery() {
            if (!allowGallery) return
            GalleryPickerHost.install(activity).pickImage { uri ->
                if (uri == null) return@pickImage
                decodeGalleryImage(uri)
            }
        }

        private fun decodeGalleryImage(uri: Uri) {
            if (finished.get()) return
            val scanner = this.scanner ?: return

            val image =
                    try {
                        InputImage.fromFilePath(activity, uri)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load picked image", e)
                        showGalleryToast("Couldn't read that image.")
                        return
                    }

            scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        val barcode = barcodes.firstOrNull { !it.rawValue.isNullOrEmpty() }
                        val value = barcode?.rawValue

                        if (value != null) {
                            finish(
                                    cancelled = false,
                                    data = value,
                                    format = REVERSE_FORMAT_MAP[barcode.format] ?: "unknown"
                            )
                        } else {
                            showGalleryToast("No code found in that image.")
                        }
                    }
                    .addOnFailureListener {
                        Log.e(TAG, "Gallery barcode scan failed", it)
                        showGalleryToast("No code found in that image.")
                    }
        }

        private fun showGalleryToast(message: String) {
            activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show() }
        }

        private fun handleMatch(value: String, format: String) {
            if (!continuous) {
                finish(cancelled = false, data = value, format = format)
                return
            }

            val now = System.currentTimeMillis()
            if (value == lastValue && now - lastFiredAt < REPEAT_DEBOUNCE_MS) {
                return
            }
            lastValue = value
            lastFiredAt = now

            activity.runOnUiThread {
                val payload = JSONObject()
                payload.put("data", value)
                payload.put("format", format)
                if (id != null) payload.put("id", id)
                NativeActionCoordinator.dispatchEvent(
                        activity,
                        CODE_SCANNED_EVENT,
                        payload.toString()
                )
            }
        }

        fun finish(
                cancelled: Boolean,
                data: String? = null,
                format: String? = null,
                reason: String? = null
        ) {
            if (!finished.compareAndSet(false, true)) {
                return
            }

            if (activeOverlay === this) {
                activeOverlay = null
            }

            activity.runOnUiThread {
                cameraProvider?.unbindAll()
                overlayView?.let { root.removeView(it) }
                executor.shutdown()

                val payload = JSONObject()
                if (cancelled) {
                    if (reason == null) return@runOnUiThread
                    payload.put("reason", reason)
                    if (id != null) payload.put("id", id)
                    NativeActionCoordinator.dispatchEvent(
                            activity,
                            CANCELLED_EVENT,
                            payload.toString()
                    )
                } else {
                    payload.put("data", data)
                    payload.put("format", format ?: "unknown")
                    if (id != null) payload.put("id", id)
                    NativeActionCoordinator.dispatchEvent(
                            activity,
                            CODE_SCANNED_EVENT,
                            payload.toString()
                    )
                }
            }
        }
    }
}
