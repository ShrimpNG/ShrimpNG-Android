package com.v2ray.ang.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.v2ray.ang.R
import com.v2ray.ang.databinding.DialogAddKeyBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.util.QRCodeDecoder
import com.v2ray.ang.util.Utils
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A single "paste or scan a key" entry point replacing the old per-protocol manual-import menu
 * items — [MainActivity.importBatchConfig] (already used by the clipboard/QR import paths)
 * already auto-detects whether the text is a subscription link or one or more individual server
 * URIs, so this sheet doesn't need to ask the user which kind of key they have.
 *
 * The camera card scans inline via CameraX + ZXing rather than launching [ScannerActivity]'s
 * whole-screen Quickie flow — Quickie has no embeddable scanning view, only a separate Activity.
 */
class AddKeyBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogAddKeyBinding? = null
    private val binding get() = _binding!!

    private val ownerActivity: MainActivity
        get() = requireActivity() as MainActivity

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    private val decoded = AtomicBoolean(false)

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else ownerActivity.toast(R.string.toast_permission_denied)
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogAddKeyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.btnEnableCamera.setOnClickListener {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }

        binding.tilKeyInput.setEndIconOnClickListener {
            val clipboard = Utils.getClipboard(ownerActivity)
            if (clipboard.isNotBlank()) {
                binding.etKeyInput.setText(clipboard)
            }
        }

        binding.btnContinue.setOnClickListener {
            val key = binding.etKeyInput.text?.toString()?.trim().orEmpty()
            if (key.isEmpty()) {
                ownerActivity.toast(R.string.shrimp_add_key_empty)
                return@setOnClickListener
            }
            ownerActivity.importBatchConfig(key)
            dismiss()
        }

        if (hasCameraPermission()) startCamera() else showPlaceholder()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(ownerActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun showPlaceholder() {
        binding.previewCamera.visibility = View.GONE
        binding.layoutCameraPlaceholder.visibility = View.VISIBLE
    }

    private fun showPreview() {
        binding.previewCamera.visibility = View.VISIBLE
        binding.layoutCameraPlaceholder.visibility = View.GONE
    }

    private fun startCamera() {
        showPreview()
        val providerFuture = ProcessCameraProvider.getInstance(ownerActivity)
        providerFuture.addListener({
            if (_binding == null) return@addListener
            val provider = providerFuture.get()
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.previewCamera.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor!!, ::analyzeFrame) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                showPlaceholder()
            }
        }, ContextCompat.getMainExecutor(ownerActivity))
    }

    /** Runs on [cameraExecutor]; decodes the Y (luminance) plane directly, no RGB conversion needed. */
    private fun analyzeFrame(image: ImageProxy) {
        if (decoded.get()) {
            image.close()
            return
        }
        try {
            val yPlane = image.planes[0]
            val data = ByteArray(yPlane.buffer.remaining())
            yPlane.buffer.get(data)
            val source = PlanarYUVLuminanceSource(
                data, yPlane.rowStride, image.height, 0, 0, image.width, image.height, false
            )
            val result = try {
                MultiFormatReader().apply { setHints(QRCodeDecoder.HINTS) }
                    .decode(BinaryBitmap(HybridBinarizer(source)))
            } catch (e: NotFoundException) {
                null
            }
            if (result != null && decoded.compareAndSet(false, true)) {
                val text = result.text
                ownerActivity.runOnUiThread {
                    if (_binding != null) {
                        ownerActivity.importBatchConfig(text)
                        dismiss()
                    }
                }
            }
        } catch (e: Exception) {
            // Malformed or unreadable frame — just try the next one.
        } finally {
            image.close()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraProvider?.unbindAll()
        cameraProvider = null
        cameraExecutor?.shutdown()
        cameraExecutor = null
        _binding = null
    }
}
