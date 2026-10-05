package com.v2ray.ang.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemPrivacySectionBinding
import com.v2ray.ang.databinding.SheetPrivacyBinding

/**
 * The privacy notice, shown in the app instead of linking out to a policy page.
 *
 * Every statement here describes what the code actually does; when the behaviour changes — a new
 * request, a new header, a new permission — the matching section has to change with it.
 */
class PrivacySheet : BottomSheetDialogFragment() {

    private class Section(@DrawableRes val icon: Int, @StringRes val title: Int, @StringRes val body: Int)

    private val sections = listOf(
        Section(R.drawable.ic_visibility_off_24dp, R.string.privacy_none_title, R.string.privacy_none_body),
        Section(R.drawable.ic_cloud_download_24dp, R.string.privacy_subs_title, R.string.privacy_subs_body),
        Section(R.drawable.ic_speed_24dp, R.string.privacy_tests_title, R.string.privacy_tests_body),
        Section(R.drawable.ic_dns_24dp, R.string.privacy_dns_title, R.string.privacy_dns_body),
        Section(R.drawable.ic_check_update_24dp, R.string.privacy_downloads_title, R.string.privacy_downloads_body),
        Section(R.drawable.ic_key_24dp, R.string.privacy_permissions_title, R.string.privacy_permissions_body),
        Section(R.drawable.ic_smartphone_24dp, R.string.privacy_storage_title, R.string.privacy_storage_body),
        Section(R.drawable.ic_language_24dp, R.string.privacy_links_title, R.string.privacy_links_body),
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = SheetPrivacyBinding.inflate(inflater, container, false)
        sections.forEach { section ->
            ItemPrivacySectionBinding.inflate(inflater, binding.containerSections, true).apply {
                ivIcon.setImageResource(section.icon)
                tvTitle.setText(section.title)
                tvBody.setText(section.body)
            }
        }
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        // A long read: open fully rather than peeking at the first section.
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }
}
