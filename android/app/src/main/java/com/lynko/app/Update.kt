package com.lynko.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub-releases updater, mirroring the desktop one. One shared result so the
 * settings row and the launch prompt never disagree, and the open dialog is not
 * duplicated by a second check.
 */
object Update {
    data class Release(
        val version: String,
        val notes: String,
        val page: String,
        val apk: String,
        val published: String,
    )

    private const val API = "https://api.github.com/repos/ErfanBagheri404/Lynko/releases?per_page=30"

    @Volatile var latest: Release? = null
        private set

    /** Newest release seen, even when it is not newer than this build. */
    @Volatile var newest = ""
        private set

    @Volatile var busy = false
        private set

    @Volatile var failed = false
        private set

    @Volatile var checked = false
        private set

    @Volatile var error = ""
        private set

    var onChange: (() -> Unit)? = null

    fun current(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""

    /** Never throws: failures land in [failed]/[error] and the row says so. */
    fun check(ctx: Context, done: ((Release?) -> Unit)? = null) {
        if (busy) return
        busy = true
        failed = false
        error = ""
        onChange?.invoke()
        val app = ctx.applicationContext
        Thread {
            var found: Release? = null
            try {
                found = pick(JSONArray(fetch(API)), current(app))
            } catch (e: Exception) {
                failed = true
                error = e.message ?: e.javaClass.simpleName
            }
            latest = found
            checked = true
            busy = false
            Handler(Looper.getMainLooper()).post {
                onChange?.invoke()
                done?.invoke(found)
            }
        }.start()
    }

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Lynko-Android/${Build.VERSION.RELEASE}")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("GitHub API returned ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun pick(list: JSONArray, cur: String): Release? {
        var best: org.json.JSONObject? = null
        var bestVer = ""
        for (i in 0 until list.length()) {
            val r = list.optJSONObject(i) ?: continue
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val tag = r.optString("tag_name").trim().trimStart('v')
            if (semver(tag) == null) continue
            if (best == null || semver(tag)!! > semver(bestVer)!!) {
                best = r
                bestVer = tag
            }
        }
        if (best == null) throw java.io.IOException("No releases found")
        newest = bestVer
        val curV = semver(cur)
        if (curV == null || semver(bestVer)!! <= curV) return null
        val assets = best.optJSONArray("assets") ?: JSONArray()
        return Release(
            version = bestVer,
            notes = best.optString("body").trim(),
            page = best.optString("html_url", "https://github.com/ErfanBagheri404/Lynko/releases"),
            apk = apkFor(assets),
            published = best.optString("published_at").take(10),
        )
    }

    /** Universal first, then this phone's ABI; any APK beats the release page. */
    private fun apkFor(assets: JSONArray): String {
        val names = mutableListOf<Pair<String, String>>()
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val n = a.optString("name")
            if (!n.endsWith(".apk", ignoreCase = true)) continue
            names += n to a.optString("browser_download_url")
        }
        val abis = Build.SUPPORTED_ABIS + "universal"
        return abis.firstNotNullOfOrNull { abi ->
            names.firstOrNull { it.first.contains("-android-$abi") }?.second
        } ?: names.firstOrNull()?.second ?: ""
    }

    private fun semver(s: String): IntArray? {
        val core = s.trim().trimStart('v').substringBefore('-')
        val p = core.split('.')
        if (p.size < 3) return null
        val maj = p[0].toIntOrNull() ?: return null
        val min = p[1].toIntOrNull() ?: return null
        val pat = p[2].toIntOrNull() ?: return null
        return intArrayOf(maj, min, pat)
    }

    private operator fun IntArray.compareTo(o: IntArray): Int {
        for (i in 0 until 3) if (this[i] != o[i]) return this[i] - o[i]
        return 0
    }

    /**
     * Fetch the APK, then hand it to the system installer. Progress arrives on
     * the main thread; the caller owns the dialog it is written into.
     */
    fun download(activity: Activity, r: Release, progress: (Int) -> Unit, fail: (String) -> Unit) {
        val apk = File(activity.cacheDir, "lynko-${r.version}.apk")
        Thread {
            try {
                val c = URL(r.apk).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.instanceFollowRedirects = true
                if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
                val total = c.contentLength.toLong()
                c.inputStream.use { ins ->
                    apk.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var got = 0L
                        val ui = Handler(Looper.getMainLooper())
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            if (total > 0) {
                                val pct = (got * 100 / total).toInt()
                                ui.post { progress(pct) }
                            }
                        }
                    }
                }
                apk.setReadable(true, false)
                Handler(Looper.getMainLooper()).post { install(activity, apk) }
            } catch (e: Exception) {
                fail(e.message ?: e.javaClass.simpleName)
            }
        }.start()
    }

    private fun install(activity: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(activity, R.style.Theme_Lynko_Dialog)
                .setTitle(R.string.update_title)
                .setMessage(R.string.update_allow_sources)
                .setPositiveButton(R.string.update_open_settings) { _, _ ->
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}"),
                        ),
                    )
                }
                .setNegativeButton(R.string.update_later, null)
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(i)
    }
}
