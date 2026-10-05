package com.v2ray.ang.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.v2ray.ang.R
import com.v2ray.ang.databinding.SheetAddSubscriptionBinding
import com.v2ray.ang.fmt.WireguardFmt
import java.net.URI

/**
 * Opened by the "Add" chip at the end of Главное's subscription row: take what's on the
 * clipboard, scan or paste in the Add key sheet, or fill in the subscription form by hand.
 */
class AddSubscriptionSheet : BottomSheetDialogFragment() {

    private var _binding: SheetAddSubscriptionBinding? = null
    private val binding get() = _binding!!

    private val ownerActivity: MainActivity
        get() = requireActivity() as MainActivity

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetAddSubscriptionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Read once, up front: the row shows what it would import, so nothing is a surprise.
        // (Android announces the read with its own "pasted from clipboard" toast.)
        val clip = readClipboard(requireContext())
        val preview = clip?.let { describe(it) }
        binding.cardClipboard.isEnabled = clip != null
        binding.cardClipboard.alpha = if (clip != null) 1f else 0.5f
        binding.tvClipboardSummary.text = preview ?: getString(R.string.add_sub_clipboard_empty)
        binding.cardClipboard.setOnClickListener {
            clip ?: return@setOnClickListener
            ownerActivity.importBatchConfig(clip)
            dismiss()
        }

        binding.cardScan.setOnClickListener {
            AddKeyBottomSheet().show(parentFragmentManager, "AddKeyBottomSheet")
            dismiss()
        }

        binding.cardManual.setOnClickListener {
            startActivity(Intent(requireContext(), SubEditActivity::class.java))
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun readClipboard(context: Context): String? = try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    /**
     * A short, recognisable line for the clipboard row: "vless · host", the host of an http(s)
     * subscription link, "WireGuard", or the first line of anything else — never the whole text,
     * which may be a long key.
     */
    private fun describe(text: String): String {
        if (WireguardFmt.isWireguardConfFile(text)) return "WireGuard"
        val firstLine = text.lineSequence().first().trim()
        val scheme = firstLine.substringBefore("://", missingDelimiterValue = "")
        if (scheme.isNotEmpty() && scheme.all { it.isLetterOrDigit() || it in "+.-" }) {
            val host = runCatching { URI(firstLine).host }.getOrNull()
                ?: firstLine.substringAfter("://").substringAfter('@').substringBefore('/').substringBefore('?')
            val lines = text.lineSequence().count { it.isNotBlank() }
            val more = if (lines > 1) " +${lines - 1}" else ""
            return when (scheme.lowercase()) {
                "http", "https" -> host
                else -> "${scheme.lowercase()} · $host$more"
            }
        }
        return if (firstLine.length > 40) firstLine.take(40) + "…" else firstLine
    }
}
