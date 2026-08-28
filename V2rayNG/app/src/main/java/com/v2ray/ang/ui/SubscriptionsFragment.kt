package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.FragmentSubscriptionsBinding
import com.v2ray.ang.databinding.ItemQrcodeBinding
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.DragReorderCallback
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.QRCodeDecoder
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import com.v2ray.ang.viewmodel.SubscriptionsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Подписки: manage the subscription list (add/edit/remove/share, reorder, hide-from-Home,
 * update-all). No server rows here. Picking which subscription is *active* happens on Главное
 * now (quick-switch chips), not here.
 */
class SubscriptionsFragment : BaseFragment<FragmentSubscriptionsBinding>() {

    private val ownerActivity: MainActivity
        get() = requireActivity() as MainActivity
    private val viewModel: SubscriptionsViewModel by viewModels()
    private val mainViewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: SubscriptionRowAdapter

    private val shareMethod: Array<out String> by lazy {
        resources.getStringArray(R.array.share_sub_method)
    }

    private var itemTouchHelper: androidx.recyclerview.widget.ItemTouchHelper? = null

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentSubscriptionsBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = SubscriptionRowAdapter(RowListener())
        binding.recyclerSubscriptions.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerSubscriptions.adapter = adapter
        attachDragReorder()

        binding.btnEmptyAdd.setOnClickListener { AddKeyBottomSheet().show(childFragmentManager, "AddKeyBottomSheet") }
        binding.fabAddSubscription.setOnClickListener { AddKeyBottomSheet().show(childFragmentManager, "AddKeyBottomSheet") }

        refreshData()
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refreshData() {
        viewModel.reload()
        val items = viewModel.getAll()
        adapter.submitList(items)
        binding.layoutEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private inner class RowListener : SubscriptionRowAdapter.Listener {
        override fun onRefresh(subId: String) {
            refreshSubscription(subId)
        }

        override fun onMore(subId: String) {
            showMoreMenu(subId)
        }

        override fun onToggleHidden(subId: String) {
            val subItem = MmkvManager.decodeSubscription(subId) ?: return
            subItem.hiddenFromHome = !subItem.hiddenFromHome
            viewModel.update(subId, subItem)
            if (subItem.hiddenFromHome && subId == mainViewModel.subscriptionId) {
                // The active subscription just got hidden — Главное falls back to another one.
                mainViewModel.subscriptionIdChanged("")
            }
            mainViewModel.notifySubscriptionsChanged()
            refreshData()
        }

        override fun onStartDrag(viewHolder: RecyclerView.ViewHolder) {
            itemTouchHelper?.startDrag(viewHolder)
        }

        override fun onReordered() {
            // The adapter only ever shows the *visible* subscriptions (e.g. an empty default
            // "Server list" is filtered out) — persist its new order for those, but keep any
            // guid missing from that set (i.e. filtered-out ones) appended after, so they aren't
            // silently dropped from storage by a reorder that never touched them.
            val visibleGuidsInNewOrder = adapter.currentGuids()
            val hiddenGuids = MmkvManager.decodeSubsList().filter { it !in visibleGuidsInNewOrder }
            MmkvManager.encodeSubsList((visibleGuidsInNewOrder + hiddenGuids).toMutableList())
            viewModel.reload()
            mainViewModel.notifySubscriptionsChanged()
        }
    }

    private fun refreshSubscription(subId: String) {
        val subItem = MmkvManager.decodeSubscription(subId) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                AngConfigManager.updateConfigViaSub(SubscriptionCache(subId, subItem))
            }
            when {
                result.successCount > 0 -> {
                    ownerActivity.toastSuccess(getString(R.string.title_update_config_count, result.configCount))
                    if (subId == mainViewModel.subscriptionId) mainViewModel.reloadServerList()
                }
                result.failureCount > 0 -> ownerActivity.toastError(R.string.toast_failure)
            }
            refreshData()
        }
    }

    private fun showMoreMenu(subId: String) {
        val subItem = MmkvManager.decodeSubscription(subId) ?: return
        val options = arrayOf(
            getString(R.string.menu_item_edit_config),
            getString(R.string.title_configuration_share),
            getString(R.string.menu_item_del_config),
        )
        AlertDialog.Builder(ownerActivity).setItems(options) { _, i ->
            when (i) {
                0 -> editSubscription(subId)
                1 -> shareSubscription(subItem.url)
                2 -> removeSubscription(subId)
            }
        }.show()
    }

    private fun attachDragReorder() {
        itemTouchHelper = androidx.recyclerview.widget.ItemTouchHelper(DragReorderCallback(adapter)).apply {
            attachToRecyclerView(binding.recyclerSubscriptions)
        }
    }

    private fun editSubscription(subId: String) {
        ownerActivity.requestActivityLauncher.launch(
            Intent(ownerActivity, SubEditActivity::class.java).putExtra("subId", subId)
        )
    }

    private fun removeSubscription(subId: String) {
        val removeAction = {
            viewModel.remove(subId)
            if (mainViewModel.subscriptionId == subId) {
                mainViewModel.subscriptionIdChanged("")
            }
            mainViewModel.notifySubscriptionsChanged()
            refreshData()
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_CONFIRM_REMOVE)) {
            AlertDialog.Builder(ownerActivity)
                .setMessage(R.string.del_config_comfirm)
                .setPositiveButton(android.R.string.ok) { _, _ -> removeAction() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            removeAction()
        }
    }

    private fun shareSubscription(url: String) {
        AlertDialog.Builder(ownerActivity)
            .setItems(shareMethod.asList().toTypedArray()) { _, i ->
                try {
                    when (i) {
                        0 -> {
                            val ivBinding = ItemQrcodeBinding.inflate(LayoutInflater.from(ownerActivity))
                            ivBinding.ivQcode.setImageBitmap(QRCodeDecoder.createQRCode(url))
                            AlertDialog.Builder(ownerActivity).setView(ivBinding.root).show()
                        }

                        1 -> Utils.setClipboard(ownerActivity, url)
                        else -> ownerActivity.toast("else")
                    }
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Share subscription failed", e)
                }
            }.show()
    }

}
