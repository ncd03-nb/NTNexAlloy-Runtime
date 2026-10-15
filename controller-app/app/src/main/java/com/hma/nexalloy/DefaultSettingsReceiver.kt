package com.hma.nexalloy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/** Seed the ROM default once; an explicit user choice always wins on later boots. */
class DefaultSettingsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_BOOT_COMPLETED) return
        runCatching {
            val resolver = context.contentResolver
            if (Settings.Global.getString(resolver, NexAlloyStore.SETTINGS_KEY) == null) {
                val saved = Settings.Global.putInt(resolver, NexAlloyStore.SETTINGS_KEY, 1)
                Log.i("NexAlloyXES", "first boot default enabled saved=$saved")
            }
        }.onFailure { Log.e("NexAlloyXES", "Cannot initialize first boot default", it) }
    }
}
