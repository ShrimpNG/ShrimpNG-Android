package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityAppIconBinding
import com.v2ray.ang.enums.AppIconVariant
import com.v2ray.ang.handler.AppIconManager
import com.v2ray.ang.handler.DecoyManager
import com.v2ray.ang.handler.EmojiStyle
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.ThemeManager

/**
 * Themes & icons: the colour palette, light/dark and AMOLED (see [ThemeManager]), then the app's
 * launcher identity — see [AppIconVariant]. Applying an icon warns first, because swapping the
 * enabled launcher alias makes the system rebuild the shortcut and, on a fair number of OEM ROMs,
 * kill the app while doing it.
 */
class AppIconActivity : BaseActivity() {

    private val binding by lazy { ActivityAppIconBinding.inflate(layoutInflater) }
    private lateinit var adapter: AppIconAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(
            binding.root,
            showHomeAsUp = true,
            title = getString(R.string.theme_and_icons_title),
        )

        setUpColors()
        setUpEmoji()

        adapter = AppIconAdapter { variant -> confirmAndApply(variant) }
        binding.recyclerIcons.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerIcons.adapter = adapter
        adapter.setSelected(AppIconManager.current())

        setUpDecoyControls()
        renderDecoySection(AppIconManager.current())
    }

    private fun setUpColors() {
        val paletteAdapter = ThemePaletteAdapter { palette ->
            ThemeManager.setPalette(palette)
            // Overlays apply before onCreate; BaseActivity.onResume brings the rest of the stack along.
            recreate()
        }
        val paletteLayout = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.recyclerPalettes.layoutManager = paletteLayout
        // Recreation after a pick should keep that swatch in view, including the new presets.
        paletteLayout.scrollToPositionWithOffset(ThemeManager.Palette.available.indexOf(ThemeManager.palette()),
            resources.getDimensionPixelSize(R.dimen.padding_spacing_dp12))
        binding.recyclerPalettes.adapter = paletteAdapter
        binding.tvPaletteName.text = getString(ThemeManager.palette().label)

        val mode = MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0")
        binding.toggleThemeMode.check(
            when (mode) {
                "1" -> R.id.btn_theme_light
                "2" -> R.id.btn_theme_dark
                else -> R.id.btn_theme_auto
            }
        )
        binding.toggleThemeMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val value = when (checkedId) {
                R.id.btn_theme_light -> "1"
                R.id.btn_theme_dark -> "2"
                else -> "0"
            }
            if (value == MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0")) return@addOnButtonCheckedListener
            MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, value)
            // AppCompat recreates every activity itself when the default night mode changes.
            SettingsManager.setNightMode()
        }

        renderAmoled(mode ?: "0")
        binding.rowAmoled.setOnClickListener {
            ThemeManager.setAmoled(!ThemeManager.isAmoled())
            recreate()
        }
    }

    /** AMOLED only does anything while the theme is dark, so it is greyed out under "Light". */
    /**
     * EmojiCompat is set up once per process, so a change waits for a restart; the row offering it
     * shows only while the choice differs from what is on screen.
     */
    private fun setUpEmoji() {
        binding.toggleEmojiStyle.check(
            if (EmojiStyle.current() == EmojiStyle.Style.APPLE) R.id.btn_emoji_apple else R.id.btn_emoji_system
        )
        renderEmojiRestart()
        binding.toggleEmojiStyle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            EmojiStyle.set(if (checkedId == R.id.btn_emoji_apple) EmojiStyle.Style.APPLE else EmojiStyle.Style.SYSTEM)
            renderEmojiRestart()
        }
        binding.btnEmojiRestart.setOnClickListener { restartApp() }
    }

    private fun renderEmojiRestart() {
        binding.rowEmojiRestart.visibility =
            if (EmojiStyle.current() != EmojiStyle.active) View.VISIBLE else View.GONE
        val error = when {
            EmojiStyle.active != EmojiStyle.Style.APPLE -> null
            EmojiStyle.loadError != null -> EmojiStyle.loadError
            !EmojiStyle.loaderRan -> getString(R.string.theme_emoji_not_started)
            else -> null
        }
        binding.tvEmojiError.visibility = if (error != null) View.VISIBLE else View.GONE
        binding.tvEmojiError.text = error?.let { getString(R.string.theme_emoji_load_failed, it) }
    }

    /** Back to this screen in a fresh process: Главное underneath, as if navigated here. */
    private fun restartApp() {
        startActivities(
            arrayOf(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                Intent(this, AppIconActivity::class.java),
            )
        )
        Runtime.getRuntime().exit(0)
    }

    private fun renderAmoled(mode: String) {
        val applicable = mode != "1"
        binding.switchAmoled.isChecked = ThemeManager.isAmoled()
        binding.rowAmoled.isEnabled = applicable
        binding.switchAmoled.isEnabled = applicable
        binding.tvAmoledTitle.isEnabled = applicable
        binding.tvAmoledTitle.alpha = if (applicable) 1f else 0.38f
        binding.tvAmoledSummary.alpha = if (applicable) 1f else 0.38f
        binding.tvAmoledSummary.setText(
            if (applicable) R.string.theme_amoled_summary else R.string.theme_amoled_needs_dark
        )
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
        MaterialAlertDialogBuilder(this)
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
