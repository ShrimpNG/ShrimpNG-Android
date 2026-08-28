package com.v2ray.ang.ui

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityAppIconBinding
import com.v2ray.ang.enums.AppIconVariant
import com.v2ray.ang.handler.AppIconManager
import com.v2ray.ang.handler.DecoyManager

/**
 * Picker for the app's launcher identity — see [AppIconVariant]. Applying one warns first,
 * because swapping the enabled launcher alias makes the system rebuild the shortcut and, on a
 * fair number of OEM ROMs, kill the app while doing it.
 */
class AppIconActivity : BaseActivity() {

    private val binding by lazy { ActivityAppIconBinding.inflate(layoutInflater) }
    private lateinit var adapter: AppIconAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(
            binding.root,
            showHomeAsUp = true,
            title = getString(R.string.shrimp_app_icon_title),
        )

        adapter = AppIconAdapter { variant -> confirmAndApply(variant) }
        binding.recyclerIcons.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerIcons.adapter = adapter
        adapter.setSelected(AppIconManager.current())

        setUpDecoyControls()
        renderDecoySection(AppIconManager.current())
    }

    private fun setUpDecoyControls() {
        binding.switchDecoy.setOnCheckedChangeListener { _, checked ->
            DecoyManager.setEnabled(checked)
            renderDecoyFields(checked)
        }

        binding.etDecoyExpression.doAfterTextChanged { text ->
            val typed = text?.toString().orEmpty()
            if (typed.isBlank()) {
                // Blank falls back to the default rather than locking the user out of their own app.
                binding.tilDecoyExpression.error = null
                return@doAfterTextChanged
            }
            binding.tilDecoyExpression.error = if (DecoyManager.setUnlockExpression(typed)) {
                null
            } else {
                getString(R.string.shrimp_decoy_expression_invalid)
            }
        }
    }

    /** The decoy only makes sense behind the Calculator disguise. */
    private fun renderDecoySection(variant: AppIconVariant) {
        val applicable = variant == AppIconVariant.CALCULATOR
        binding.cardDecoy.visibility = if (applicable) android.view.View.VISIBLE else android.view.View.GONE
        if (!applicable) return

        binding.switchDecoy.isChecked = DecoyManager.isEnabled()
        binding.etDecoyExpression.setText(DecoyManager.unlockExpression())
        renderDecoyFields(DecoyManager.isEnabled())
    }

    private fun renderDecoyFields(enabled: Boolean) {
        val visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
        binding.tilDecoyExpression.visibility = visibility
        binding.tvDecoyWarning.visibility = visibility
    }

    private fun confirmAndApply(variant: AppIconVariant) {
        AlertDialog.Builder(this)
            .setTitle(R.string.shrimp_app_icon_warning_title)
            .setMessage(R.string.shrimp_app_icon_warning_message)
            .setPositiveButton(R.string.shrimp_app_icon_warning_confirm) { _, _ ->
                AppIconManager.apply(applicationContext, variant)
                adapter.setSelected(variant)
                renderDecoySection(variant)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
