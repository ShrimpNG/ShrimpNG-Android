package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityDecoyCalculatorBinding
import com.v2ray.ang.handler.AppIconManager
import com.v2ray.ang.handler.DecoyManager
import com.v2ray.ang.util.SimpleExpression
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The face the Calculator disguise shows: a calculator that genuinely calculates, so poking at it
 * reveals nothing. Entering [DecoyManager.unlockExpression] and pressing `=` opens the real client
 * instead of computing a result.
 *
 * The Calculator launcher alias targets this activity rather than [MainActivity]; when the decoy is
 * switched off it forwards straight through, before any of its own UI is drawn.
 */
class DecoyCalculatorActivity : BaseActivity() {

    private val binding by lazy { ActivityDecoyCalculatorBinding.inflate(layoutInflater) }

    /** What the user has typed so far, in keypad glyphs. */
    private var expression = ""

    private var titleTapCount = 0
    private var firstTitleTapAt = 0L

    private companion object {
        const val RESET_TAP_COUNT = 10
        const val RESET_WINDOW_MS = 10_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!DecoyManager.isActive()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        // Carries the disguise's own name, so the screen looks like the app the launcher promised.
        setContentViewWithToolbar(
            binding.root,
            showHomeAsUp = false,
            title = getString(AppIconManager.current().labelRes),
        )
        wireKeypad()
        wireTitleReset()
        render()
    }

    /**
     * Tapping the title [RESET_TAP_COUNT] times inside [RESET_WINDOW_MS] restores the default
     * unlock expression. Deliberately silent — no toast, no counter, no visual change — because
     * any feedback would tell a bystander that the title does something.
     */
    private fun wireTitleReset() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar) ?: return
        val titleView = (0 until toolbar.childCount)
            .map { toolbar.getChildAt(it) }
            .filterIsInstance<TextView>()
            .firstOrNull { it.text == toolbar.title }
            ?: return

        titleView.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - firstTitleTapAt > RESET_WINDOW_MS) {
                firstTitleTapAt = now
                titleTapCount = 0
            }
            titleTapCount++
            if (titleTapCount >= RESET_TAP_COUNT) {
                titleTapCount = 0
                firstTitleTapAt = 0L
                DecoyManager.resetUnlockExpression()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the client must not leave the unlock expression sitting on screen.
        if (expression.isNotEmpty()) {
            expression = ""
            render()
        }
    }

    /** Keys declare what they insert via android:tag, so the layout stays the single source. */
    private fun wireKeypad() {
        forEachKey(binding.layoutKeypad) { key ->
            val tag = key.tag as? String ?: return@forEachKey
            key.setOnClickListener { onKey(tag) }
        }
    }

    private fun forEachKey(root: ViewGroup, action: (View) -> Unit) {
        for (i in 0 until root.childCount) {
            when (val child = root.getChildAt(i)) {
                is ViewGroup -> forEachKey(child, action)
                is TextView -> action(child)
            }
        }
    }

    private fun onKey(tag: String) {
        when (tag) {
            "AC" -> expression = ""
            "C" -> expression = expression.dropLast(1)
            "=" -> {
                if (DecoyManager.matchesUnlock(expression)) {
                    expression = ""
                    render()
                    startActivity(Intent(this, MainActivity::class.java))
                    return
                }
                SimpleExpression.evaluate(expression)?.let { expression = plain(it) }
            }

            else -> expression += tag
        }
        render()
    }

    /**
     * Grouping happens here and nowhere else: [expression] itself stays in bare digits, so the
     * separators can never leak into the parser or into the unlock comparison.
     */
    private fun render() {
        binding.tvExpression.text = SimpleExpression.withGroupSeparators(expression)
        binding.tvResult.text = SimpleExpression.evaluate(expression)
            ?.let { SimpleExpression.withGroupSeparators(plain(it)) }
            .orEmpty()
    }

    /** Trims float noise so 1984×1984 reads as 3936256, not 3936256.0000000005. */
    private fun plain(value: Double): String =
        BigDecimal(value).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
