package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemAppIconBinding
import com.v2ray.ang.enums.AppIconVariant
import com.v2ray.ang.ui.widget.IconShape

/**
 * Grid of selectable launcher identities. The active one is marked with the same primary outline
 * the server list uses for the selected server, so "selected" reads the same way app-wide.
 */
class AppIconAdapter(
    private val onPick: (AppIconVariant) -> Unit,
) : RecyclerView.Adapter<AppIconAdapter.ViewHolder>() {

    private val variants = AppIconVariant.entries
    private var selected: AppIconVariant = AppIconVariant.DEFAULT

    @SuppressLint("NotifyDataSetChanged")
    fun setSelected(variant: AppIconVariant) {
        selected = variant
        notifyDataSetChanged()
    }

    override fun getItemCount() = variants.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppIconBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(variants[position])
    }

    inner class ViewHolder(private val binding: ItemAppIconBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(variant: AppIconVariant) {
            val context = binding.root.context
            binding.ivIconPreview.setImageDrawable(IconShape.shaped(context, variant.previewIconRes, 56))
            binding.tvIconLabel.text = context.getString(variant.pickerLabelRes)

            val isSelected = variant == selected
            binding.cardRoot.strokeWidth = if (isSelected) {
                context.resources.getDimensionPixelSize(R.dimen.subscription_card_active_stroke)
            } else {
                0
            }
            if (isSelected) {
                binding.cardRoot.strokeColor =
                    MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary)
            }

            // Must be the inner row, not the card: the row is clickable in the layout (it owns the
            // ripple), so it swallows the touch and a listener on the card would never fire.
            binding.layoutIconRow.setOnClickListener {
                if (variant != selected) onPick(variant)
            }
        }
    }
}
