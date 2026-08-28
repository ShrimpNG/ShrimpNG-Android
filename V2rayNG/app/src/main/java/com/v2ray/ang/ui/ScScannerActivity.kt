package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.R
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.DecoyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScScannerActivity : HelperBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_none)
        importQRcode()
    }

    private fun importQRcode() {
        launchQRCodeScanner { scanResult ->
            if (scanResult == null) {
                finish()
                return@launchQRCodeScanner
            }
            // Importing a subscription fetches it over the network, so it cannot run on the main
            // thread — and this activity must not finish until it is done, or the fetch dies with
            // the scope and leaves a subscription with no servers in it.
            lifecycleScope.launch {
                val (count, countSub) = withContext(Dispatchers.IO) {
                    AngConfigManager.importBatchConfig(scanResult, "", false)
                }
                if (count + countSub > 0) {
                    toastSuccess(R.string.toast_success)
                } else {
                    toastError(R.string.toast_failure)
                }
                // Honours the disguise, like every other entry point into the app.
                val target = if (DecoyManager.isActive()) DecoyCalculatorActivity::class.java else MainActivity::class.java
                startActivity(Intent(this@ScScannerActivity, target))
                finish()
            }
        }
    }
}
