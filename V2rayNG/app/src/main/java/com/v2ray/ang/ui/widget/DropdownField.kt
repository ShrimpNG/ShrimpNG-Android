package com.v2ray.ang.ui.widget

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.google.android.material.R as MaterialR
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.v2ray.ang.R

/**
 * A Material 3 exposed dropdown menu that stands in for the legacy [android.widget.Spinner]:
 * an outlined field with its label inside, opening a menu of fixed choices.
 *
 * It keeps the slice of the Spinner API the editors were written against — [selectedItemPosition],
 * [setSelection], [onItemSelectedListener], [adapter] and `app:entries` — so swapping the view
 * type is the whole migration. Like a Spinner, it always has a selection (the first item until
 * told otherwise), and the listener hears about a selection only when it changes — once after
 * being attached, then on every change, programmatic or not. Re-picking the item already shown
 * stays silent, which matters: the editors' listeners reload fields from the saved profile.
 */
class DropdownField @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = MaterialR.attr.textInputOutlinedExposedDropdownMenuStyle,
) : TextInputLayout(context, attrs, defStyleAttr) {

    private val input = MaterialAutoCompleteTextView(getContext()).apply {
        inputType = InputType.TYPE_NULL
        isSingleLine = true
    }

    private var items: List<CharSequence> = emptyList()

    var selectedItemPosition: Int = AdapterView.INVALID_POSITION
        private set

    val selectedItem: CharSequence? get() = items.getOrNull(selectedItemPosition)

    val count: Int get() = items.size

    private var lastDispatched: Int = AdapterView.INVALID_POSITION

    /**
     * Called with a null parent and view: no caller here ever looked at either. A Spinner reports
     * its initial selection after the first layout, so this does the same, posted.
     */
    var onItemSelectedListener: AdapterView.OnItemSelectedListener? = null
        set(value) {
            field = value
            lastDispatched = AdapterView.INVALID_POSITION
            post { dispatchSelection() }
        }

    /**
     * Accepts the ArrayAdapters the editors used to hand to their Spinners; only the items are
     * read from it, the menu always draws them with the Material dropdown item.
     */
    var adapter: ArrayAdapter<*>? = null
        set(value) {
            field = value
            setItems(value?.let { a -> (0 until a.count).map { a.getItem(it).toString() } }.orEmpty())
        }

    init {
        endIconMode = END_ICON_DROPDOWN_MENU
        addView(input, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        context.obtainStyledAttributes(attrs, R.styleable.DropdownField).use { a ->
            a.getTextArray(R.styleable.DropdownField_entries)?.let { setItems(it.toList()) }
        }

        input.setOnItemClickListener { _, _, position, _ ->
            selectedItemPosition = position
            dispatchSelection()
        }
    }

    fun setItems(newItems: List<CharSequence>) {
        items = newItems
        input.setSimpleItems(newItems.map { it.toString() }.toTypedArray())
        setSelection(if (newItems.isEmpty()) AdapterView.INVALID_POSITION else selectedItemPosition.coerceIn(0, newItems.size - 1))
    }

    /**
     * An out-of-range position (the editors pass `arrayFind(...)`, which is -1 for a value they
     * don't know) falls back to the first item rather than leaving nothing selected: the save
     * path indexes arrays with [selectedItemPosition] and would throw on -1.
     */
    fun setSelection(position: Int) {
        if (items.isEmpty()) {
            selectedItemPosition = AdapterView.INVALID_POSITION
            input.setText("", false)
            return
        }
        @Suppress("NAME_SHADOWING")
        val position = if (position in items.indices) position else 0
        selectedItemPosition = position
        // false: don't filter the menu down to the item just shown.
        input.setText(items[position], false)
        dispatchSelection()
    }

    private fun dispatchSelection() {
        val listener = onItemSelectedListener ?: return
        val position = selectedItemPosition
        if (position == AdapterView.INVALID_POSITION || position == lastDispatched) return
        lastDispatched = position
        listener.onItemSelected(null, null, position, position.toLong())
    }

    // No setEnabled override: TextInputLayout already disables its children, and its constructor
    // calls setEnabled before this class's properties exist — an override touching [input] there
    // crashed every screen with a dropdown on inflation.
}
