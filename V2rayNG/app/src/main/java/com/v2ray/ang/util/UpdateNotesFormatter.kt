package com.v2ray.ang.util

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan

/**
 * Release notes as the manifest carries them: one change per line, and group titles ("Fixes")
 * as lines marked "# " (see local/publish-release.py). Changes get a bullet, titles none.
 */
object UpdateNotesFormatter {

    private const val HEADING = "# "

    /** For the Updates screen and the "What's new" dialog: titles in bold, a gap before each. */
    fun styled(lines: List<String>): CharSequence {
        val out = SpannableStringBuilder()
        lines.forEach { line ->
            if (out.isNotEmpty()) out.append('\n')
            if (line.startsWith(HEADING)) {
                if (out.isNotEmpty()) out.append('\n')
                val start = out.length
                out.append(line.removePrefix(HEADING))
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                out.append("•  ").append(line)
            }
        }
        return out
    }

    /** For a notification, which has no room for styling: titles as plain lines. */
    fun plain(lines: List<String>): String =
        lines.joinToString("\n") { if (it.startsWith(HEADING)) it.removePrefix(HEADING) + ":" else "• $it" }
}
