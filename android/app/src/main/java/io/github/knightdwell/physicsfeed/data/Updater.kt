package io.github.knightdwell.physicsfeed.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.knightdwell.physicsfeed.BuildConfig
import org.json.JSONObject
import java.io.File

/** In-app updates from the GitHub releases of knight-dwell/physics-feed (an .apk attached to each release). */
object Updater {
    const val REPO = "knight-dwell/physics-feed"

    data class Release(val version: String, val notes: String, val apkUrl: String)

    private fun parts(v: String) = v.trimStart('v', 'V').split(".", "-").mapNotNull { it.toIntOrNull() }

    fun isNewer(remote: String, local: String = BuildConfig.VERSION_NAME): Boolean {
        val a = parts(remote); val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** The newest release that has an APK, or null. */
    fun latest(): Release? {
        val o = JSONObject(Fetcher.get("https://api.github.com/repos/$REPO/releases/latest", accept = "application/vnd.github+json"))
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) {
                return Release(o.optString("tag_name"), o.optString("body").take(600), a.optString("browser_download_url"))
            }
        }
        return null
    }

    fun download(ctx: Context, r: Release): File {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val f = File(dir, "physics-feed-${r.version}.apk")
        f.writeBytes(Fetcher.getBytes(r.apkUrl))
        return f
    }

    /** Hand the APK to Android's installer (asks once to allow installs from Physics Feed). */
    fun install(ctx: Context, apk: File) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
