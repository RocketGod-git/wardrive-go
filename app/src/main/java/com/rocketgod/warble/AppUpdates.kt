package com.rocketgod.warble

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AppUpdates {
    private const val REPO = "RocketGod-git/ad-control"

    fun isFromPlay(ctx: Context): Boolean {
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= 30) ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
            else @Suppress("DEPRECATION") ctx.packageManager.getInstallerPackageName(ctx.packageName)
        }.getOrNull()
        return installer == "com.android.vending"
    }

    fun checkForUpdate(ctx: Context, scope: CoroutineScope, launcher: ActivityResultLauncher<IntentSenderRequest>) {
        if (isFromPlay(ctx)) {
            val mgr = AppUpdateManagerFactory.create(ctx)
            mgr.appUpdateInfo
                .addOnSuccessListener { info ->
                    if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                        info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                        runCatching {
                            mgr.startUpdateFlowForResult(
                                info, launcher, AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                            )
                        }.onFailure { toast(ctx, "Couldn't start the update.") }
                    } else toast(ctx, "You're on the latest version.")
                }
                .addOnFailureListener { toast(ctx, "Couldn't check Play for updates.") }
        } else {
            scope.launch {
                toast(ctx, "Checking for updates…")
                val latest = withContext(Dispatchers.IO) { fetchLatestStable() }
                val cur = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
                if (latest != null && isNewer(latest.first, cur)) {
                    toast(ctx, "Update ${latest.first} available — opening…")
                    runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(latest.second)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                } else toast(ctx, "You're on the latest build.")
            }
        }
    }

    fun openWatchPlayStore(ctx: Context, scope: CoroutineScope) {
        scope.launch {
            val nodes = withContext(Dispatchers.IO) {
                runCatching { Tasks.await(Wearable.getNodeClient(ctx).connectedNodes) }.getOrNull()
            }
            if (nodes.isNullOrEmpty()) { toast(ctx, "No watch connected."); return@launch }
            val helper = androidx.wear.remote.interactions.RemoteActivityHelper(ctx)
            val intent = Intent(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setData(Uri.parse("market://details?id=${ctx.packageName}"))
            for (n in nodes) runCatching { helper.startRemoteActivity(intent, n.id) }
            toast(ctx, "Opening the Play Store on your watch…")
        }
    }

    private fun fetchLatestStable(): Pair<String, String>? = runCatching {
        val c = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000; setRequestProperty("Accept", "application/vnd.github+json")
        }
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val o = JSONObject(body)
        val ver = o.optString("tag_name").removePrefix("android-v")
        val url = o.optString("html_url")
        if (ver.isBlank() || url.isBlank()) null else ver to url
    }.getOrNull()

    fun isNewer(remote: String, current: String): Boolean {
        fun parts(s: String) = s.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val r = parts(remote); val c = parts(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }; val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}
