package com.v2ray.ang.ui

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Filter

/**
 * Inline `geosite:`/`geoip:` tag suggestions for the Simple-mode domain input — typing
 * "geosite:ya" suggests "geosite:YANDEX" etc. Only offers suggestions once the user has typed
 * one of those two prefixes; a plain domain gets no dropdown.
 */
class GeoTagSuggestionAdapter(
    context: Context,
    private val geositeTags: List<String>,
    private val geoipTags: List<String>,
) : ArrayAdapter<String>(context, android.R.layout.simple_dropdown_item_1line) {

    private var currentSuggestions: List<String> = emptyList()

    override fun getCount() = currentSuggestions.size

    override fun getItem(position: Int): String = currentSuggestions[position]

    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val suggestions = computeSuggestions(constraint?.toString().orEmpty())
            return FilterResults().apply {
                values = suggestions
                count = suggestions.size
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            currentSuggestions = (results?.values as? List<String>).orEmpty()
            if (currentSuggestions.isNotEmpty()) notifyDataSetChanged() else notifyDataSetInvalidated()
        }
    }

    private fun computeSuggestions(text: String): List<String> {
        val geositePrefix = "geosite:"
        val geoipPrefix = "geoip:"
        return when {
            text.startsWith(geositePrefix, ignoreCase = true) ->
                matchTags(geositeTags, text.substring(geositePrefix.length)).map { "$geositePrefix$it" }

            text.startsWith(geoipPrefix, ignoreCase = true) ->
                matchTags(geoipTags, text.substring(geoipPrefix.length)).map { "$geoipPrefix$it" }

            else -> emptyList()
        }
    }

    private fun matchTags(tags: List<String>, partial: String): List<String> {
        val needle = partial.uppercase()
        return tags.filter { it.startsWith(needle) }.sorted().take(MAX_SUGGESTIONS)
    }

    companion object {
        private const val MAX_SUGGESTIONS = 30
    }
}
