package com.v2ray.ang.ui

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.motion.MotionUtils
import com.google.android.material.shape.MaterialShapeDrawable
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemRecyclerBypassListBinding
import com.v2ray.ang.dto.AppInfo
import com.v2ray.ang.ui.widget.SegmentedTiles
import com.google.android.material.R as MaterialR

/** One grouped app list for VPN filtering and the routing-rule picker. Selection storage is
 * supplied by the caller: global VPN settings save immediately; a rule returns its own result. */
class AppSelectorAdapter(
    private val header: View,
    private val isSelected: (String) -> Boolean,
    private val onToggle: (String) -> Unit,
) : RecyclerView.Adapter<AppSelectorAdapter.BaseViewHolder>() {
    var apps: List<AppInfo> = emptyList()
        private set

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun submitList(newApps: List<AppInfo>) {
        apps = newApps
        notifyDataSetChanged()
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun refreshSelection() = notifyDataSetChanged()

    override fun getItemCount() = apps.size + 1
    override fun getItemViewType(position: Int) = if (position == 0) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        if (viewType == 0) {
            (header.parent as? ViewGroup)?.removeView(header)
            header.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            return BaseViewHolder(header)
        }
        return AppViewHolder(ItemRecyclerBypassListBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        if (holder is AppViewHolder) holder.bind(apps[position - 1], position - 1)
    }

    override fun onViewRecycled(holder: BaseViewHolder) {
        if (holder is AppViewHolder) holder.cancelAnimation()
        super.onViewRecycled(holder)
    }

    open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    inner class AppViewHolder(private val binding: ItemRecyclerBypassListBinding) : BaseViewHolder(binding.root) {
        private lateinit var app: AppInfo
        private var colorAnimator: ValueAnimator? = null

        init {
            itemView.setOnClickListener {
                onToggle(app.packageName)
                renderSelection(animate = true)
            }
            ViewCompat.setAccessibilityDelegate(itemView, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.CheckBox"
                    info.isCheckable = true
                    info.isChecked = isSelected(app.packageName)
                }
            })
        }

        fun bind(item: AppInfo, index: Int) {
            cancelAnimation()
            app = item
            binding.icon.setImageDrawable(item.appIcon)
            binding.name.text = item.appName
            binding.packageName.text = if (item.isSystemApp) {
                itemView.context.getString(R.string.per_app_system_app, item.packageName)
            } else item.packageName
            (itemView.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.topMargin = if (index == 0) 0 else (2 * itemView.resources.displayMetrics.density).toInt()
                itemView.layoutParams = it
            }
            itemView.background = SegmentedTiles.background(itemView, index == 0, index == apps.lastIndex)
            renderSelection(animate = false)
        }

        private fun renderSelection(animate: Boolean) {
            val selected = isSelected(app.packageName)
            binding.checkBox.isChecked = selected
            val fill = (itemView.background as RippleDrawable).getDrawable(0) as MaterialShapeDrawable
            val target = MaterialColors.getColor(itemView,
                if (selected) MaterialR.attr.colorSecondaryContainer else MaterialR.attr.colorSurfaceContainerLow)
            cancelAnimation()
            if (animate && ValueAnimator.areAnimatorsEnabled()) {
                colorAnimator = ValueAnimator.ofArgb(fill.fillColor!!.defaultColor, target).apply {
                    duration = MotionUtils.resolveThemeDuration(itemView.context, MaterialR.attr.motionDurationShort3, 150).toLong()
                    addUpdateListener { fill.fillColor = ColorStateList.valueOf(it.animatedValue as Int) }
                    start()
                }
            } else fill.fillColor = ColorStateList.valueOf(target)
            binding.name.setTextColor(MaterialColors.getColor(itemView,
                if (selected) MaterialR.attr.colorOnSecondaryContainer else MaterialR.attr.colorOnSurface))
            binding.packageName.setTextColor(MaterialColors.getColor(itemView,
                if (selected) MaterialR.attr.colorOnSecondaryContainer else MaterialR.attr.colorOnSurfaceVariant))
        }

        fun cancelAnimation() {
            colorAnimator?.cancel()
            colorAnimator = null
        }
    }
}
