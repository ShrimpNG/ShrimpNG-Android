package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.v2ray.ang.R
import com.v2ray.ang.databinding.SheetShareSubscriptionBinding
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.QRCodeDecoder
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A subscription's link, made easy to move to another device: as a QR code to scan, copied, or
 * sent through the share sheet. Opened from Главное's subscription card and from Подписки.
 */
class ShareSubscriptionSheet : BottomSheetDialogFragment() {

    private var _binding: SheetShareSubscriptionBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetShareSubscriptionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val sub = MmkvManager.decodeSubscription(requireArguments().getString(ARG_SUB_ID).orEmpty())
        val url = sub?.url.orEmpty()
        if (url.isBlank()) {
            dismiss()
            return
        }
        binding.tvTitle.text = sub?.remarks?.ifBlank { null } ?: getString(R.string.share_sub_untitled)
        binding.tvUrl.text = url

        viewLifecycleOwner.lifecycleScope.launch {
            val qr = withContext(Dispatchers.Default) { QRCodeDecoder.createQRCode(url, 720) }
            _binding?.ivQr?.setImageBitmap(qr)
        }

        binding.btnCopy.setOnClickListener {
            Utils.setClipboard(requireContext(), url)
            requireContext().toastSuccess(R.string.share_sub_copied)
        }
        binding.btnSend.setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
            startActivity(Intent.createChooser(send, binding.tvTitle.text))
        }
    }

    override fun onStart() {
        super.onStart()
        // The QR code is the point: open fully rather than peeking at the title.
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_SUB_ID = "sub_id"

        fun newInstance(subId: String) = ShareSubscriptionSheet().apply {
            arguments = Bundle().apply { putString(ARG_SUB_ID, subId) }
        }
    }
}
