package com.v2ray.ang.ui.widget

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.EditTextPreferenceDialogFragmentCompat
import androidx.preference.ListPreference
import androidx.preference.ListPreferenceDialogFragmentCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceDialogFragmentCompat
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.v2ray.ang.R

/**
 * A settings screen in the Android 16 / Material 3 Expressive grouped style: each category is a
 * stack of separate tiles with large outer corners and small inner ones, under a section label
 * styled like the Settings hub's. Switch rows use a Material switch, and the value dialogs are
 * Material dialogs.
 *
 * Purely presentational — the preference XML, keys, classes and listeners are untouched. The
 * layouts are swapped in code when the screen is set, so a preference added to the XML later is
 * styled without anyone having to remember a layout attribute.
 */
abstract class GroupedPreferenceFragment : PreferenceFragmentCompat() {

    override fun setPreferenceScreen(preferenceScreen: PreferenceScreen?) {
        preferenceScreen?.let { applyGroupedLayouts(it) }
        super.setPreferenceScreen(preferenceScreen)
    }

    private fun applyGroupedLayouts(group: PreferenceGroup) {
        for (i in 0 until group.preferenceCount) {
            val pref = group.getPreference(i)
            pref.isIconSpaceReserved = false
            when (pref) {
                is PreferenceCategory -> pref.layoutResource = R.layout.preference_category_m3
                else -> pref.layoutResource = R.layout.preference_m3
            }
            if (pref is CheckBoxPreference) {
                pref.widgetLayoutResource = R.layout.preference_widget_material_switch
            }
            if (pref is EditTextPreference) {
                pref.dialogLayoutResource = R.layout.preference_dialog_edittext_m3
            }
            if (pref is PreferenceGroup) {
                applyGroupedLayouts(pref)
            }
        }
    }

    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> =
        SegmentedPreferenceAdapter(preferenceScreen)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // The tiles' gaps do the separating; a divider line would cut across the rounded corners.
        setDivider(null)
        listView.apply {
            clipToPadding = false
            // Room below the last group, plus the gesture bar when the list is drawn under it.
            setOnApplyWindowInsetsListener { v, insets ->
                val bottom = WindowInsetsCompat.toWindowInsetsCompat(insets, v)
                    .getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottom + v.dp(24))
                insets
            }
            requestApplyInsets()
        }
    }

    @Suppress("DEPRECATION") // setTargetFragment is how preference dialogs find their preference.
    override fun onDisplayPreferenceDialog(preference: Preference) {
        val dialog: PreferenceDialogFragmentCompat = when (preference) {
            is EditTextPreference -> MaterialEditTextPreferenceDialog()
            is ListPreference -> MaterialListPreferenceDialog()
            else -> return super.onDisplayPreferenceDialog(preference)
        }
        if (parentFragmentManager.findFragmentByTag(DIALOG_TAG) != null) return
        dialog.arguments = bundleOf(ARG_KEY to preference.key)
        dialog.setTargetFragment(this, 0)
        dialog.show(parentFragmentManager, DIALOG_TAG)
    }

    /**
     * Rounds each tile according to where it sits in its group and spaces the groups. Recomputed
     * on every bind: hiding or showing a preference rebinds the whole list, so a tile that becomes
     * the last of its group picks up the large bottom corners then.
     */
    private class SegmentedPreferenceAdapter(group: PreferenceGroup) : PreferenceGroupAdapter(group) {

        override fun onBindViewHolder(holder: PreferenceViewHolder, position: Int) {
            super.onBindViewHolder(holder, position)
            val pref = getItem(position) ?: return
            val view = holder.itemView
            val prev = getItem(position - 1)
            val next = getItem(position + 1)

            val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val side = view.dp(16)

            if (pref is PreferenceCategory) {
                lp.setMargins(side, if (prev == null) view.dp(8) else view.dp(16), side, 0)
                view.layoutParams = lp
                view.background = null
                return
            }

            // A group is a run of tiles under the same parent; a category label ends one.
            val first = prev == null || prev is PreferenceCategory || prev.parent != pref.parent
            val last = next == null || next is PreferenceCategory || next.parent != pref.parent
            val top = when {
                prev == null -> view.dp(8)
                prev is PreferenceCategory -> 0
                first -> view.dp(24)
                else -> view.dp(2)
            }
            lp.setMargins(side, top, side, 0)
            view.layoutParams = lp
            view.background = SegmentedTiles.background(view, first, last)
        }
    }

    /** The library's dialog, built with [MaterialAlertDialogBuilder] instead of AppCompat's. */
    class MaterialEditTextPreferenceDialog : EditTextPreferenceDialogFragmentCompat() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
            buildMaterialDialog(
                fragment = this,
                createView = { onCreateDialogView(it) },
                bindView = { onBindDialogView(it) },
                prepare = { onPrepareDialogBuilder(it) },
                showIme = true,
            )
    }

    /** The library's dialog, built with [MaterialAlertDialogBuilder] instead of AppCompat's. */
    class MaterialListPreferenceDialog : ListPreferenceDialogFragmentCompat() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
            buildMaterialDialog(
                fragment = this,
                createView = { onCreateDialogView(it) },
                bindView = { onBindDialogView(it) },
                prepare = { onPrepareDialogBuilder(it) },
                showIme = false,
            )
    }

    private companion object {
        /** The tag PreferenceFragmentCompat uses, so its own duplicate-dialog guard still holds. */
        const val DIALOG_TAG = "androidx.preference.PreferenceFragment.DIALOG"

        /** PreferenceDialogFragmentCompat.ARG_KEY, which is protected. */
        const val ARG_KEY = "key"

        /**
         * Mirrors PreferenceDialogFragmentCompat.onCreateDialog step for step, with a Material
         * builder: that method news up an AppCompat builder itself, so there is no hook to swap
         * it. The fragment is the button listener, which is how it learns that OK was pressed.
         */
        fun buildMaterialDialog(
            fragment: PreferenceDialogFragmentCompat,
            createView: (Context) -> View?,
            bindView: (View) -> Unit,
            prepare: (AlertDialog.Builder) -> Unit,
            showIme: Boolean,
        ): Dialog {
            val context = fragment.requireContext()
            val preference = fragment.preference
            val builder = MaterialAlertDialogBuilder(context)
                .setTitle(preference.dialogTitle)
                .setIcon(preference.dialogIcon)
                .setPositiveButton(preference.positiveButtonText, fragment)
                .setNegativeButton(preference.negativeButtonText, fragment)

            val content = createView(context)
            if (content != null) {
                bindView(content)
                builder.setView(content)
            } else {
                builder.setMessage(preference.dialogMessage)
            }
            prepare(builder)

            val dialog = builder.create()
            if (showIme) {
                dialog.window?.let { WindowCompat.getInsetsController(it, it.decorView).show(WindowInsetsCompat.Type.ime()) }
            }
            return dialog
        }
    }
}

private fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
