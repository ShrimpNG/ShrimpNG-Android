package com.v2ray.ang.handler

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.UpdateManifest
import com.v2ray.ang.receiver.UpdateInstallReceiver
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * In-app updates from our own release manifest on ng.shrimp.fish.
 *
 * The manifest lists one APK per build we publish; a client only ever takes the APK of its own
 * build (see [buildId]), since the builds share a package and a signing key but differ in what
 * they enable on a first launch — the disguise's launcher alias above all.
 *
 * An APK is installed only after its size, SHA-256, package name, version and signing certificate
 * have all been checked. Without the user's confirmation only when the connection can come back
 * by itself afterwards ([canInstallSilently]): replacing the package kills the VPN process, and
 * [com.v2ray.ang.receiver.PackageReplacedReceiver] starts it again on the new version.
 */
object AppUpdateManager {

    /** Universal APKs carry the ABI as `1_000_000 * abi + versionCode` (see app/build.gradle.kts). */
    private const val ABI_VERSION_STEP = 1_000_000L
    private const val DIR = "updates"
    private const val INSTALL_TASK = AppConfig.APP_UPDATE_TASK + "_install"

    /** Which of the manifest's APKs is this build's. */
    fun buildId(): String = when {
        BuildConfig.LEGACY_BUILD -> "legacy"
        BuildConfig.DISGUISE_BY_DEFAULT -> "calculator"
        else -> "standard"
    }

    /** F-Droid builds are updated by F-Droid, from its own signed APKs. */
    fun isSupported(): Boolean = !BuildConfig.APPLICATION_ID.endsWith(".fdroid")

    fun installedVersionCode(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(packageInfo(context, context.packageName, 0)) % ABI_VERSION_STEP

    // ---------------------------------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------------------------------

    fun isAutoCheck() = MmkvManager.decodeSettingsBool(AppConfig.PREF_UPDATE_AUTO_CHECK, true)
    fun isAutoDownload() = MmkvManager.decodeSettingsBool(AppConfig.PREF_UPDATE_AUTO_DOWNLOAD, true)
    fun isWifiOnly() = MmkvManager.decodeSettingsBool(AppConfig.PREF_UPDATE_WIFI_ONLY, true)
    fun isAutoInstall() = MmkvManager.decodeSettingsBool(AppConfig.PREF_UPDATE_AUTO_INSTALL, true)
    fun isBetaChannel() = MmkvManager.decodeSettingsBool(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, false)

    fun setSetting(context: Context, key: String, value: Boolean) {
        MmkvManager.encodeSettings(key, value)
        if (key == AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE) {
            // A manifest from the other channel says nothing about this one.
            MmkvManager.encodeSettings(AppConfig.PREF_UPDATE_MANIFEST, null as String?)
        }
        schedule(context)
    }

    fun lastCheck(): Long = MmkvManager.decodeSettingsLong(AppConfig.PREF_UPDATE_LAST_CHECK, 0L)

    // ---------------------------------------------------------------------------------------------
    // Manifest
    // ---------------------------------------------------------------------------------------------

    /** A newer version of this build that this device can run. */
    data class Available(val manifest: UpdateManifest, val build: UpdateManifest.Build)

    fun storedManifest(): UpdateManifest? =
        MmkvManager.decodeSettingsString(AppConfig.PREF_UPDATE_MANIFEST)
            ?.let { JsonUtil.fromJsonSafe(it, UpdateManifest::class.java) }

    fun availableUpdate(context: Context, manifest: UpdateManifest? = storedManifest()): Available? {
        manifest ?: return null
        if (manifest.versionCode <= installedVersionCode(context)) return null
        val build = manifest.builds[buildId()] ?: return null
        if (build.minSdk > Build.VERSION.SDK_INT) return null
        return Available(manifest, build)
    }

    /**
     * Fetches the manifest of the chosen channel and keeps it. Returns the update it offers, or
     * null when this is the latest version; throws when the manifest can't be had at all.
     */
    suspend fun check(context: Context): Available? = withContext(Dispatchers.IO) {
        val url = if (isBetaChannel()) AppConfig.UPDATE_MANIFEST_BETA_URL else AppConfig.UPDATE_MANIFEST_URL
        val body = viaProxyThenDirect { port ->
            HttpUtil.buildOkHttpClient(10_000, port, SettingsManager.getSocksUsername(), SettingsManager.getSocksPassword(), true)
                .newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build())
                .execute().use { response ->
                    if (!response.isSuccessful) throw HttpStatusException(response.code)
                    response.body?.string() ?: throw IOException("empty body")
                }
        }
        val manifest = JsonUtil.fromJsonSafe(body, UpdateManifest::class.java)
            ?.takeIf { isWellFormed(it) }
            ?: throw IOException("malformed update manifest")
        MmkvManager.encodeSettings(AppConfig.PREF_UPDATE_MANIFEST, body)
        MmkvManager.encodeSettings(AppConfig.PREF_UPDATE_LAST_CHECK, System.currentTimeMillis())
        availableUpdate(context, manifest)
    }

    private fun isWellFormed(m: UpdateManifest): Boolean =
        m.versionCode > 0 && m.versionName.isNotBlank() && m.builds.values.all { b ->
            b.url.startsWith("https://") && b.size > 0 && b.sha256.matches(Regex("[0-9a-f]{64}"))
        }

    /** Notes of [versionCode] in the app's language, from the stored manifest or its history. */
    fun notesFor(context: Context, versionCode: Long, manifest: UpdateManifest? = storedManifest()): List<String>? {
        manifest ?: return null
        val notes = if (manifest.versionCode == versionCode) manifest.notes
        else manifest.history.firstOrNull { it.versionCode == versionCode }?.notes
        return notes?.let { localized(it) }?.takeIf { it.isNotEmpty() }
    }

    fun localized(notes: Map<String, List<String>>): List<String> {
        val lang = SettingsManager.getLocale().language.lowercase(Locale.ROOT)
        return notes[lang] ?: notes["en"] ?: notes.values.firstOrNull().orEmpty()
    }

    // ---------------------------------------------------------------------------------------------
    // Download
    // ---------------------------------------------------------------------------------------------

    /** Why an update did not go in, in the terms the Updates screen explains it. */
    enum class Reason { NETWORK, CORRUPT, SIGNATURE, INSTALL }

    class UpdateException(val reason: Reason, message: String) : IOException(message)

    /** The server answered, just not with the manifest: says something other than "no connection". */
    class HttpStatusException(val code: Int) : IOException("HTTP $code")

    sealed class DownloadState {
        data object Idle : DownloadState()
        data class Running(val done: Long, val total: Long) : DownloadState()
        data class Failed(val reason: Reason, val message: String) : DownloadState()
    }

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    /** This process's download; the background worker's runs in another and isn't seen here. */
    val download: StateFlow<DownloadState> = _download.asStateFlow()

    private fun updateDir(context: Context) = File(context.cacheDir, DIR).apply { mkdirs() }
    private fun apkFile(context: Context, versionCode: Long) = File(updateDir(context), "shrimpng-$versionCode.apk")

    /** The verified APK of [available], if it has already been downloaded. */
    fun downloadedApk(context: Context, available: Available): File? {
        val file = apkFile(context, available.manifest.versionCode)
        val recorded = MmkvManager.decodeSettingsString(AppConfig.PREF_UPDATE_DOWNLOADED)
        return file.takeIf {
            it.isFile && it.length() == available.build.size && recorded == "${available.manifest.versionCode}:${available.build.sha256}"
        }
    }

    /**
     * Downloads, resuming a partial file, and verifies the APK of [available]. Every process
     * downloads into the same file, so they take turns.
     */
    suspend fun download(context: Context, available: Available): File = withContext(Dispatchers.IO) {
        downloadedApk(context, available)?.let { return@withContext it }
        // The read loop blocks: it checks this between chunks so that Cancel stops it.
        val job = currentCoroutineContext()[Job]
        try {
            withDirLock(context) {
                downloadedApk(context, available) ?: downloadLocked(context, available) { job?.isActive == false }
            }.also { _download.value = DownloadState.Idle }
        } catch (e: CancellationException) {
            // Left for the next attempt to resume; nothing went wrong.
            _download.value = DownloadState.Idle
            throw e
        } catch (e: Exception) {
            val reason = (e as? UpdateException)?.reason ?: Reason.NETWORK
            _download.value = DownloadState.Failed(reason, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    private fun downloadLocked(context: Context, available: Available, cancelled: () -> Boolean): File {
        val versionCode = available.manifest.versionCode
        val build = available.build
        val target = apkFile(context, versionCode)
        val partial = File(target.path + ".part")
        updateDir(context).listFiles()?.filter { it != partial && !it.name.startsWith(".") }?.forEach { it.delete() }
        MmkvManager.encodeSettings(AppConfig.PREF_UPDATE_DOWNLOADED, null as String?)

        // A file already complete was cut off before its checks, not before its end.
        if (partial.length() != build.size) viaProxyThenDirect { port -> fetch(build, partial, port, cancelled) }

        if (partial.length() != build.size) {
            partial.delete()
            throw UpdateException(Reason.CORRUPT, "size mismatch")
        }
        if (sha256(partial) != build.sha256) {
            partial.delete()
            throw UpdateException(Reason.CORRUPT, "checksum mismatch")
        }
        verifyArchive(context, partial, versionCode)?.let { problem ->
            partial.delete()
            throw UpdateException(Reason.SIGNATURE, problem)
        }
        if (!partial.renameTo(target)) throw IOException("could not store the update")
        MmkvManager.encodeSettings(AppConfig.PREF_UPDATE_DOWNLOADED, "$versionCode:${build.sha256}")
        LogUtil.i(AppConfig.TAG, "AppUpdate: ${available.manifest.versionName} downloaded")
        return target
    }

    private fun fetch(build: UpdateManifest.Build, partial: File, port: Int, cancelled: () -> Boolean) {
        val have = partial.length().takeIf { it in 1 until build.size } ?: run { partial.delete(); 0L }
        val client = HttpUtil.buildOkHttpClient(30_000, port, SettingsManager.getSocksUsername(), SettingsManager.getSocksPassword(), true)
        val request = Request.Builder().url(build.url)
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
        client.newCall(request).execute().use { response ->
            val append = when (response.code) {
                206 -> have > 0
                200 -> false
                else -> throw IOException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("empty body")
            var done = if (append) have else 0L
            _download.value = DownloadState.Running(done, build.size)
            FileOutputStream(partial, append).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        if (cancelled()) throw CancellationException("download cancelled")
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (done > build.size) throw UpdateException(Reason.CORRUPT, "larger than announced")
                        if (done - lastReport >= 256 * 1024) {
                            lastReport = done
                            _download.value = DownloadState.Running(done, build.size)
                        }
                    }
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Why [file] must not be installed, or null when it is this app, at [versionCode], signed
     * with the key the installed app is signed with. Android checks the key on install as well;
     * doing it first turns its bare "app not installed" into a reason.
     */
    fun verifyArchive(context: Context, file: File, versionCode: Long): String? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = archiveInfo(pm, file, flags) ?: return "not an APK"
        if (archive.packageName != context.packageName) return "wrong package ${archive.packageName}"
        if (PackageInfoCompat.getLongVersionCode(archive) % ABI_VERSION_STEP != versionCode) return "wrong version"
        val installed = packageInfo(context, context.packageName, flags)
        if (signatures(archive) != signatures(installed) || signatures(installed).isEmpty()) return "signed with a different key"
        return null
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, file: File, flags: Int): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else pm.getPackageArchiveInfo(file.path, flags)

    @Suppress("DEPRECATION")
    private fun packageInfo(context: Context, name: String, flags: Int): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(name, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else context.packageManager.getPackageInfo(name, flags)

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            info.signatures
        }
        return signatures.orEmpty().filterNotNull().mapTo(HashSet()) { it.toCharsString() }
    }

    // ---------------------------------------------------------------------------------------------
    // Install
    // ---------------------------------------------------------------------------------------------

    /** Whether Android lets this app install packages at all ("Install unknown apps"). */
    fun canRequestInstalls(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    private fun wasConnected() = MmkvManager.decodeSettingsBool(AppConfig.PREF_CORE_ACTIVE, false)

    /**
     * Whether a connection that is up now could be started again on the new version without the
     * user: a VPN needs its consent still in place (another VPN app taking over revokes it), a
     * proxy-only or root mode needs nothing.
     */
    fun canRestoreConnection(context: Context): Boolean {
        if (!wasConnected()) return true
        if (MmkvManager.getSelectServer().isNullOrEmpty()) return false
        return !SettingsManager.isVpnMode() || VpnService.prepare(context) == null
    }

    /**
     * Whether an update may go in without asking: the user allows it, Android 12+ lets an app
     * update itself without a prompt, the "Install unknown apps" permission is in place, and the
     * connection, if there is one, will come back by itself.
     */
    fun canInstallSilently(context: Context): Boolean =
        isAutoInstall() && Build.VERSION.SDK_INT >= 31 && canRequestInstalls(context) && canRestoreConnection(context)

    /**
     * Hands [file] to the package installer. [interactive] is a user standing at the Updates
     * screen: Android's confirmation is shown straight away. Otherwise a session that turns out
     * to need confirmation becomes a notification (see [UpdateInstallReceiver]).
     */
    @SuppressLint("RequestInstallPackagesPolicy")
    fun install(context: Context, file: File, versionCode: Long, interactive: Boolean) {
        val silent = !interactive && canInstallSilently(context)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(
                    if (silent) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                    else PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                )
            }
        }
        // Read back by PackageReplacedReceiver on the new version; cleared if the install fails.
        MmkvManager.encodeSettings(AppConfig.PREF_UPDATE_RECONNECT, wasConnected() && canRestoreConnection(context))
        MmkvManager.encodeSettings(AppConfig.PREF_UPDATE_WHATS_NEW, versionCode)

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = Intent(context, UpdateInstallReceiver::class.java)
                .putExtra(UpdateInstallReceiver.EXTRA_INTERACTIVE, interactive)
                .putExtra(UpdateInstallReceiver.EXTRA_VERSION_CODE, versionCode)
            // Mutable: the installer fills in the status and, when it needs one, the confirmation.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, sessionId, callback, flags).intentSender)
        }
        LogUtil.i(AppConfig.TAG, "AppUpdate: install of $versionCode committed (silent=$silent)")
    }

    /** The install did not happen: nothing is to be reconnected or announced on next start. */
    fun onInstallFailed(message: String?) {
        MmkvManager.encodeSettings(AppConfig.PREF_UPDATE_RECONNECT, false)
        MmkvManager.encodeSettings(AppConfig.PREF_UPDATE_WHATS_NEW, 0L)
        _download.value = message?.let { DownloadState.Failed(Reason.INSTALL, it) } ?: DownloadState.Idle
    }

    /** On the new version's first start: the downloaded APK has done its job. */
    fun onPackageReplaced(context: Context) {
        updateDir(context).listFiles()?.filter { !it.name.startsWith(".") }?.forEach { it.delete() }
        MmkvManager.encodeSettings(AppConfig.PREF_UPDATE_DOWNLOADED, null as String?)
    }

    /**
     * Notes to show once after an update: the version this app now runs, if it was installed
     * from here or announced in a manifest we hold. Consumed by the call.
     */
    fun takeWhatsNew(context: Context): Pair<String, List<String>>? {
        val installed = installedVersionCode(context)
        val pending = MmkvManager.decodeSettingsLong(AppConfig.PREF_UPDATE_WHATS_NEW, 0L)
        if (pending == 0L) return null
        MmkvManager.encodeSettings(AppConfig.PREF_UPDATE_WHATS_NEW, 0L)
        if (pending != installed) return null
        val manifest = storedManifest() ?: return null
        val name = if (manifest.versionCode == installed) manifest.versionName
        else manifest.history.firstOrNull { it.versionCode == installed }?.versionName ?: return null
        return notesFor(context, installed, manifest)?.let { name to it }
    }

    // ---------------------------------------------------------------------------------------------
    // Background
    // ---------------------------------------------------------------------------------------------

    /** The daily check, in line with the setting. KEEP, as every app start calls this. */
    fun schedule(context: Context) {
        val workManager = RemoteWorkManager.getInstance(context)
        if (!isSupported() || !isAutoCheck()) {
            workManager.cancelUniqueWork(AppConfig.APP_UPDATE_TASK)
            return
        }
        val request = PeriodicWorkRequestBuilder<CheckTask>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(AppConfig.APP_UPDATE_TASK)
            .build()
        workManager.enqueueUniquePeriodicWork(AppConfig.APP_UPDATE_TASK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** A silent install while the phone is in use would cut the connection under the user. */
    private fun scheduleIdleInstall(context: Context) {
        val request = OneTimeWorkRequestBuilder<InstallTask>()
            .setConstraints(Constraints.Builder().setRequiresDeviceIdle(true).build())
            .addTag(AppConfig.APP_UPDATE_TASK)
            .build()
        RemoteWorkManager.getInstance(context).enqueueUniqueWork(INSTALL_TASK, ExistingWorkPolicy.KEEP, request)
    }

    private fun isUnmetered(context: Context): Boolean =
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == false

    private fun isInteractive(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive != false

    class CheckTask(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            if (!isSupported() || !isAutoCheck()) return Result.success()
            val available = try {
                check(applicationContext)
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "AppUpdate: check failed: ${e.message}")
                return Result.retry()
            } ?: return Result.success()

            var apk = downloadedApk(applicationContext, available)
            if (apk == null && isAutoDownload() && (!isWifiOnly() || isUnmetered(applicationContext))) {
                apk = runCatching { download(applicationContext, available) }
                    .onFailure { LogUtil.w(AppConfig.TAG, "AppUpdate: download failed: ${it.message}") }
                    .getOrNull()
            }
            when {
                apk != null && canInstallSilently(applicationContext) ->
                    if (isInteractive(applicationContext)) scheduleIdleInstall(applicationContext)
                    else install(applicationContext, apk, available.manifest.versionCode, interactive = false)
                else -> AppUpdateNotifier.notifyAvailable(applicationContext, available, ready = apk != null)
            }
            return Result.success()
        }
    }

    class InstallTask(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val available = availableUpdate(applicationContext) ?: return Result.success()
            val apk = downloadedApk(applicationContext, available) ?: return Result.success()
            if (canInstallSilently(applicationContext) && !isInteractive(applicationContext)) {
                install(applicationContext, apk, available.manifest.versionCode, interactive = false)
            } else {
                AppUpdateNotifier.notifyAvailable(applicationContext, available, ready = true)
            }
            return Result.success()
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** The manifest and APK through the running core first (where our site is blocked), then directly. */
    private fun <T> viaProxyThenDirect(block: (port: Int) -> T): T {
        val ports = if (wasConnected()) listOf(SettingsManager.getHttpPort(), 0) else listOf(0)
        var last: Exception? = null
        for (port in ports) {
            try {
                return block(port)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("unreachable")
    }

    private fun <T> withDirLock(context: Context, block: () -> T): T = synchronized(this) {
        RandomAccessFile(File(updateDir(context), ".lock"), "rw").use { raf ->
            raf.channel.lock().use { block() }
        }
    }
}
