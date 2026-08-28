package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityUrlSchemeBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.DecoyManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Entry point for `shrimpng://` (and the legacy `v2rayng://`) links and for plain-text shares.
 *
 * Two link shapes are accepted, both under either scheme:
 * - `shrimpng://install-sub?url=<encoded url>` — the original query form.
 * - `shrimpng://add/<url>` — the url as the path, which is easier to write by hand.
 *
 * `add` / `sub` are aliases of `install-sub`; `install-config` imports a single config instead.
 */
class UrlSchemeActivity : BaseActivity() {
    private val binding by lazy { ActivityUrlSchemeBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        // The import has to finish before this activity does: it runs on lifecycleScope, which is
        // cancelled on destroy. Finishing first used to kill the subscription fetch mid-flight,
        // leaving a bare "import sub" entry with no servers until the user hit refresh by hand.
        lifecycleScope.launch {
            try {
                handleIntent()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Error processing URL scheme", e)
                toastError(R.string.toast_failure)
            }
            openApp()
            finish()
        }
    }

    private suspend fun handleIntent() {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if ("text/plain" == intent.type) {
                    importTarget(intent.getStringExtra(Intent.EXTRA_TEXT), null)
                }
            }

            Intent.ACTION_VIEW -> {
                val uri: Uri? = intent.data
                when (uri?.host) {
                    "install-config", "install-sub", "add", "sub" ->
                        importTarget(extractTarget(uri), uri.fragment)

                    else -> toastError(R.string.toast_failure)
                }
            }
        }
    }

    /**
     * Reads the target from whichever form the link used. The query parameter wins when both are
     * present, since that is the form older links use.
     *
     * [Uri.getQueryParameter] already percent-decodes, so no further decoding happens here — a
     * second pass would turn a legitimate `+` inside a subscription URL into a space.
     */
    private fun extractTarget(uri: Uri): String? {
        uri.getQueryParameter("url")?.takeIf { it.isNotBlank() }?.let { return it }

        // Path form: everything after the host, with the leading slash dropped. Handles both a
        // percent-encoded url and a bare one; the regex restores "https://" for the case where
        // something along the way (a chat client, a browser) normalised the double slash to one.
        val path = uri.encodedPath?.removePrefix("/").orEmpty()
        if (path.isBlank()) return null
        val decoded = Uri.decode(path)
        return Regex("^(https?):/(?!/)", RegexOption.IGNORE_CASE).replace(decoded) { "${it.groupValues[1]}://" }
    }

    private suspend fun importTarget(uriString: String?, fragment: String?) {
        if (uriString.isNullOrEmpty()) return

        var target = uriString
        if (Uri.parse(target)?.fragment.isNullOrEmpty() && !fragment.isNullOrEmpty()) {
            target += "#${fragment}"
        }

        val (count, countSub) = withContext(Dispatchers.IO) {
            AngConfigManager.importBatchConfig(target, "", false)
        }
        if (count + countSub > 0) {
            toast(R.string.import_subscription_success)
        } else {
            toast(R.string.import_subscription_failure)
        }
    }

    /**
     * Honours the disguise like every other way in — opening the client straight from a link
     * would walk past the decoy that the launcher icon and the tile both respect.
     */
    private fun openApp() {
        val target = if (DecoyManager.isActive()) DecoyCalculatorActivity::class.java else MainActivity::class.java
        startActivity(Intent(this, target))
    }
}
