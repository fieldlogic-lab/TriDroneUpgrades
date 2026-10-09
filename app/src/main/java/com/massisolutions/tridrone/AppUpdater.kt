package com.massisolutions.tridrone

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Private-release-agnostic updater. Feed and APK must be HTTPS and under operator control.
 * Android always presents the platform installer; the app never installs silently.
 */
object AppUpdater {
    private const val PREFS = "tridrone_updates"
    private const val KEY = "feed_url"
    private const val DEFAULT_FEED = "https://github.com/fieldlogic-lab/TriDroneUpgrades/releases/download/dev-latest/latest.json"

    fun open(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        val current = prefs.getString(KEY, DEFAULT_FEED) ?: DEFAULT_FEED
        val input = EditText(activity).apply {
            setSingleLine()
            hint = DEFAULT_FEED
            setText(current)
            setPadding(24, 22, 24, 22)
        }
        AlertDialog.Builder(activity).setTitle("TriDrone app updates")
            .setMessage("The official development update feed is preconfigured. Tap Check now to download the newest signed build.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Save") { _, _ ->
                val url = input.text.toString().trim()
                if (validHttps(url)) {
                    prefs.edit().putString(KEY, url).apply()
                    Toast.makeText(activity, "Update feed saved", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(activity, "HTTPS URL required", Toast.LENGTH_LONG).show()
            }
            .setPositiveButton("Check now") { _, _ ->
                val url = input.text.toString().trim()
                if (!validHttps(url)) {
                    Toast.makeText(activity, "HTTPS URL required", Toast.LENGTH_LONG).show()
                } else {
                    prefs.edit().putString(KEY, url).apply()
                    check(activity, url)
                }
            }.show()
    }

    private fun validHttps(value: String): Boolean = try {
        val u = URL(value)
        u.protocol.equals("https", true) && !u.host.isNullOrBlank() && u.userInfo == null
    } catch (_: Exception) { false }

    /** Follow GitHub Release 302 redirects without permitting HTTPS downgrade or loops. */
    private fun openHttps(url: String): HttpURLConnection {
        var current = URL(url)
        require(validHttps(current.toString())) { "HTTPS URL required" }
        repeat(8) {
            val connection = current.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                require(!location.isNullOrBlank()) { "Redirect missing Location" }
                val next = URL(current, location)
                require(validHttps(next.toString())) { "Unsafe update redirect" }
                current = next
            } else {
                return connection
            }
        }
        error("Too many update redirects")
    }

    private fun check(activity: Activity, feed: String) {
        Toast.makeText(activity, "Checking for updates…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val connection = openHttps(feed)
                val json = try {
                    if (connection.responseCode != 200) error("Update feed HTTP ${connection.responseCode}")
                    JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                } finally { connection.disconnect() }
                val version = json.getInt("versionCode")
                val apkUrl = json.getString("apkUrl")
                val sha256 = json.getString("sha256").lowercase()
                require(validHttps(apkUrl) && sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid update metadata" }
                val installed = if (Build.VERSION.SDK_INT >= 28)
                    activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode
                else @Suppress("DEPRECATION") activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode.toLong()
                activity.runOnUiThread {
                    if (version.toLong() <= installed) {
                        AlertDialog.Builder(activity).setTitle("Up to date")
                            .setMessage("Installed build: $installed. Latest published build: $version.")
                            .setPositiveButton("OK", null).show()
                    } else AlertDialog.Builder(activity).setTitle("TriDrone update available")
                        .setMessage("Installed build $installed → build $version. Download and open Android installer?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Download") { _, _ -> download(activity, apkUrl, sha256) }
                        .show()
                }
            } catch (e: Exception) {
                activity.runOnUiThread { Toast.makeText(activity, "Update check failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun download(activity: Activity, apkUrl: String, expected: String) {
        Toast.makeText(activity, "Downloading update…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val c = openHttps(apkUrl)
                val file = File(activity.cacheDir, "tridrone-update.apk")
                val digest = MessageDigest.getInstance("SHA-256")
                try {
                    val conn = c
                    if (conn.responseCode != 200) error("APK HTTP ${conn.responseCode}")
                    require(conn.contentLengthLong in 1..100_000_000) { "Invalid APK size" }
                    conn.inputStream.use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(32768)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= 100_000_000) { "APK too large" }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                } finally { c.disconnect() }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                require(actual == expected) { "SHA-256 verification failed" }
                activity.runOnUiThread {
                    if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
                        AlertDialog.Builder(activity).setTitle("Allow app updates")
                            .setMessage("Android must allow TriDrone to install updates from this source. After enabling it, tap Check for Updates again.")
                            .setPositiveButton("Settings") { _, _ ->
                                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:${activity.packageName}")))
                            }.setNegativeButton("Cancel", null).show()
                    } else {
                        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
                        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread { Toast.makeText(activity, "Update failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
}
