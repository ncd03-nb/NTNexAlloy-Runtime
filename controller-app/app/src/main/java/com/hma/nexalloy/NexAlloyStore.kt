package com.hma.nexalloy

import android.app.ActivityManager
import android.content.Context
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

object NexAlloyStore {
    private const val TAG = "NexAlloyXES"
    const val SETTINGS_KEY = "nt_nexalloy_enabled"
    private const val APP_SETTINGS_PREFIX = "nt_nexalloy_app_"

    data class SupportedApp(val name: String, val packageName: String) {
        val downloadUrl: String get() = "https://play.google.com/store/apps/details?id=$packageName"
    }

    data class Feature(val id: String, val name: String, val description: String, val defaultEnabled: Boolean)

    fun features(context: Context, packageName: String): List<Feature> {
        val catalog = JSONObject(context.assets.open("nt-patch-catalog.json").bufferedReader().use { it.readText() })
        val apps = catalog.getJSONArray("apps")
        for (i in 0 until apps.length()) {
            val app = apps.getJSONObject(i)
            if (app.getString("packageName") != packageName) continue
            val features = app.getJSONArray("features")
            return List(features.length()) { index ->
                val f = features.getJSONObject(index)
                val id = f.getString("id")
                val descriptionKey = "feature_${packageName.replace('.', '_')}_${id}_description"
                Feature(id, localized(context, "feature_${id}_title", f.getString("name")),
                    localized(context, descriptionKey, f.getString("description")), f.getBoolean("defaultEnabled"))
            }
        }
        return emptyList()
    }

    private fun localized(context: Context, key: String, fallback: String): String {
        val resource = context.resources.getIdentifier(key, "string", context.packageName)
        return if (resource == 0) fallback else context.getString(resource)
    }

    fun isFeatureEnabled(context: Context, packageName: String, feature: Feature): Boolean =
        read(context, featureKey(packageName, feature.id), feature.defaultEnabled)

    fun setFeatureEnabled(context: Context, packageName: String, feature: Feature, enabled: Boolean): Boolean =
        write(context, featureKey(packageName, feature.id), enabled)

    private fun featureKey(packageName: String, id: String) = "nt_nexalloy_feature_${packageName}_$id"

    val apps = listOf(
        SupportedApp("AllTrails", "com.alltrails.alltrails"),
        SupportedApp("Facebook", "com.facebook.katana"),
        SupportedApp("Gmail", "com.google.android.gm"),
        SupportedApp("Google Discover", "com.google.android.googlequicksearchbox"),
        SupportedApp("Instagram", "com.instagram.android"),
        SupportedApp("Messenger", "com.facebook.orca"),
        SupportedApp("Photomath", "com.microblink.photomath"),
        SupportedApp("Proton VPN", "ch.protonvpn.android"),
        SupportedApp("Reddit", "com.reddit.frontpage"),
        SupportedApp("SoundCloud", "com.soundcloud.android"),
        SupportedApp("Strava", "com.strava"),
        SupportedApp("Threads", "com.instagram.barcelona"),
        SupportedApp("TikTok", "com.zhiliaoapp.musically"),
        SupportedApp("TikTok Asia", "com.ss.android.ugc.trill"),
        SupportedApp("Twitter / X", "com.twitter.android"),
        SupportedApp("YouTube", "com.google.android.youtube"),
        SupportedApp("YouTube Music", "com.google.android.apps.youtube.music"),
        SupportedApp("Zalo", "com.zing.zalo"),
    )

    fun isEnabled(context: Context): Boolean = read(context, SETTINGS_KEY, false)

    fun setEnabled(context: Context, enabled: Boolean): Boolean =
        write(context, SETTINGS_KEY, enabled).also { ok ->
            Log.i(TAG, "master requested=$enabled stored=${isEnabled(context)} ok=$ok")
        }

    fun isAppEnabled(context: Context, packageName: String): Boolean =
        read(context, appKey(packageName), true)

    fun setAppEnabled(context: Context, packageName: String, enabled: Boolean): Boolean =
        write(context, appKey(packageName), enabled).also { ok ->
            Log.i(TAG, "package=$packageName requested=$enabled stored=${isAppEnabled(context, packageName)} ok=$ok")
        }

    fun forceStop(context: Context, packages: Collection<String>) {
        val appContext = context.applicationContext
        Thread({
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            packages.distinct().forEach { packageName ->
                val stopped = runCatching {
                    ActivityManager::class.java
                        .getMethod("forceStopPackage", String::class.java)
                        .invoke(am, packageName)
                    true
                }.getOrElse {
                    runCatching { am.killBackgroundProcesses(packageName); true }.getOrDefault(false)
                }
                Log.i(TAG, "forceStop package=$packageName ok=$stopped")
            }
        }, "nexalloy-force-stop").start()
    }

    private fun read(context: Context, key: String, default: Boolean): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, key, if (default) 1 else 0) != 0
    }.getOrDefault(default)

    private fun write(context: Context, key: String, value: Boolean): Boolean = runCatching {
        Settings.Global.putInt(context.contentResolver, key, if (value) 1 else 0)
    }.getOrElse {
        Log.e(TAG, "Settings.Global write failed key=$key", it)
        false
    }

    private fun appKey(packageName: String) = APP_SETTINGS_PREFIX + packageName
}
