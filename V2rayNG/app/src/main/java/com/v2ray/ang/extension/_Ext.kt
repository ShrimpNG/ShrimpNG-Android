package com.v2ray.ang.extension

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.v2ray.ang.AngApplication
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.SettingsManager
import java.io.Serializable
import java.net.URI
import java.util.Locale

val Context.v2RayApplication: AngApplication?
    get() = applicationContext as? AngApplication

/**
 * Resolve a string in the language chosen for the app, not the system's.
 *
 * Public because it is not only toasts: anything that builds its own text from a Context that is
 * not an activity — the Application object, a plain Service — hits the same thing.
 *
 * Only activities and the core services install the locale override, in attachBaseContext. Toasts
 * are also raised from the Application context and from plain services, and those came out in the
 * system language while the rest of the app was translated — which read as a missing translation
 * rather than as the wrong context.
 *
 * The configuration is copied rather than adjusted in place: the one held by Resources is shared,
 * and editing it would change the locale for everything drawn from that Resources object.
 */
fun Context.localizedString(message: Int, vararg args: Any): String {
    val locale = SettingsManager.getLocale()
    if (locale.language == resources.configuration.locales[0].language) return getString(message, *args)
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    return createConfigurationContext(config).getString(message, *args)
}

private fun Context.localized(message: Int): CharSequence {
    val locale = SettingsManager.getLocale()
    if (locale.language == resources.configuration.locales[0].language) return getString(message)
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    return createConfigurationContext(config).getString(message)
}

/**
 * Shows a toast message.
 *
 * Deliberately the platform toast rather than a custom view. Since Android 12 the system draws
 * text toasts itself, labelled with the app's icon and name, while custom views are deprecated and
 * are dropped outright when posted from the background — which is exactly where several of these
 * are raised from (the VPN service, the tile, the widget).
 *
 * @param message The resource ID of the message to show.
 */
fun Context.toast(message: Int) {
    Toast.makeText(this, localized(message), Toast.LENGTH_SHORT).show()
}

/**
 * Shows a toast message with the given text.
 *
 * @param message The text of the message to show.
 */
fun Context.toast(message: CharSequence) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

/**
 * Shows a toast message.
 *
 * Success and failure no longer differ in colour — the system toast has no such variants. The
 * separate names are kept so call sites still say which they mean, and so a future change can act
 * on the distinction in one place.
 *
 * @param message The resource ID of the message to show.
 */
fun Context.toastSuccess(message: Int) {
    Toast.makeText(this, localized(message), Toast.LENGTH_SHORT).show()
}

/**
 * Shows a toast message with the given text.
 *
 * @param message The text of the message to show.
 */
fun Context.toastSuccess(message: CharSequence) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

/**
 * Shows a toast message. Held on screen longer than a success: an error usually carries a reason
 * worth reading.
 *
 * @param message The resource ID of the message to show.
 */
fun Context.toastError(message: Int) {
    Toast.makeText(this, localized(message), Toast.LENGTH_LONG).show()
}

/**
 * Shows a toast message with the given text.
 *
 * @param message The text of the message to show.
 */
fun Context.toastError(message: CharSequence) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

const val THRESHOLD = 1000L
const val DIVISOR = 1024.0

/**
 * Converts a Long value to a speed string.
 *
 * @return The speed string.
 */
fun Long.toSpeedString(): String = this.toTrafficString() + "/s"

/**
 * Converts a Long value to a traffic string.
 *
 * @return The traffic string.
 */
fun Long.toTrafficString(): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
    var size = this.toDouble()
    var unitIndex = 0
    while (size >= THRESHOLD && unitIndex < units.size - 1) {
        size /= DIVISOR
        unitIndex++
    }
    return String.format(Locale.getDefault(), "%.1f %s", size, units[unitIndex])
}

val URI.idnHost: String
    get() = host?.replace("[", "")?.replace("]", "").orEmpty()

/**
 * Removes all whitespace from the string.
 *
 * @return The string without whitespace.
 */
fun String?.removeWhiteSpace(): String? = this?.replace(" ", "")

/**
 * Returns null if the string is null or blank, otherwise returns the string itself.
 *
 * @return The string or null.
 */
fun String?.nullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

/**
 * Converts the string to a Long value, or returns 0 if the conversion fails.
 *
 * @return The Long value.
 */
fun String.toLongEx(): Long = toLongOrNull() ?: 0

/**
 * Listens for package changes and executes a callback when a change occurs.
 *
 * @param onetime Whether to unregister the receiver after the first callback.
 * @param callback The callback to execute when a package change occurs.
 * @return The BroadcastReceiver that was registered.
 */
fun Context.listenForPackageChanges(onetime: Boolean = true, callback: () -> Unit) =
    object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            callback()
            if (onetime) context.unregisterReceiver(this)
        }
    }.apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(this, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(this, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            })
        }
    }

/**
 * Retrieves a serializable object from the Bundle.
 *
 * @param key The key of the serializable object.
 * @return The serializable object, or null if not found.
 */
inline fun <reified T : Serializable> Bundle.serializable(key: String): T? = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getSerializable(key, T::class.java)
    else -> @Suppress("DEPRECATION") getSerializable(key) as? T
}

/**
 * Retrieves a serializable object from the Intent.
 *
 * @param key The key of the serializable object.
 * @return The serializable object, or null if not found.
 */
inline fun <reified T : Serializable> Intent.serializable(key: String): T? = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getSerializableExtra(key, T::class.java)
    else -> @Suppress("DEPRECATION") getSerializableExtra(key) as? T
}

/**
 * Checks if the CharSequence is not null and not empty.
 *
 * @return True if the CharSequence is not null and not empty, false otherwise.
 */
fun CharSequence?.isNotNullEmpty(): Boolean = !this.isNullOrBlank()

fun String.concatUrl(vararg paths: String): String {
    val builder = StringBuilder(this.trimEnd('/'))

    paths.forEach { path ->
        val trimmedPath = path.trim('/')
        if (trimmedPath.isNotEmpty()) {
            builder.append('/').append(trimmedPath)
        }
    }

    return builder.toString()
}

/**
 * Helper function to match text either by Regex or literal string.
 */
fun String.matchesPattern(regex: Regex?, keyword: String?, ignoreCase: Boolean = true): Boolean {
    if (keyword.isNullOrEmpty()) {
        return true
    }
    return regex?.containsMatchIn(this)
        ?: this.contains(keyword, ignoreCase = ignoreCase)
}

/**
 * Checks if the config type is a group type (PolicyGroup or ProxyChain).
 *
 * @return True if the config type is PolicyGroup or ProxyChain, false otherwise.
 */
fun EConfigType.isGroupType(): Boolean {
    return this == EConfigType.POLICYGROUP || this == EConfigType.PROXYCHAIN
}

/**
 * Checks if the config type is a complex type (Custom, PolicyGroup, or ProxyChain).
 *
 * @return True if the config type is Custom, PolicyGroup, or ProxyChain, false otherwise.
 */
fun EConfigType.isComplexType(): Boolean {
    return this == EConfigType.CUSTOM || this == EConfigType.POLICYGROUP || this == EConfigType.PROXYCHAIN
}
