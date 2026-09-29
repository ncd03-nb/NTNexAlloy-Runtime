package com.hma.nexalloy

import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("NexAlloyXES", "standalone controller started package=$packageName")
        setContent {
            val dark = isSystemInDarkTheme()
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(Color.Transparent.toArgb(), Color.Transparent.toArgb()) { dark },
                navigationBarStyle = SystemBarStyle.auto(Color.Transparent.toArgb(), Color.Transparent.toArgb()) { dark },
            )
            NexAlloyTheme(dark) { NexAlloyApp() }
        }
    }
}

@Composable
private fun NexAlloyTheme(dark: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = runCatching {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }.getOrElse { if (dark) darkColorScheme() else lightColorScheme() }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun NexAlloyApp() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(NexAlloyStore.isEnabled(context)) }

    Surface(Modifier.fillMaxSize(), color = colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 12.dp),
                )
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = colorScheme.primaryContainer),
                    shape = RoundedCornerShape(26.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.master), style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.master_sub),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colorScheme.onPrimaryContainer,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = enabled, onCheckedChange = { next ->
                            if (NexAlloyStore.setEnabled(context, next)) {
                                enabled = NexAlloyStore.isEnabled(context)
                                NexAlloyStore.forceStop(context, NexAlloyStore.apps.map { it.packageName })
                            } else {
                                Toast.makeText(context, R.string.write_failed, Toast.LENGTH_LONG).show()
                            }
                        })
                    }
                }
            }
            item {
                Text(
                    text = stringResource(R.string.supported_apps),
                    style = MaterialTheme.typography.titleLarge,
                    color = colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 2.dp),
                )
            }
            items(NexAlloyStore.apps, key = { it.packageName }) { app ->
                SupportedAppCard(app)
            }
        }
    }
}

@Composable
private fun SupportedAppCard(app: NexAlloyStore.SupportedApp) {
    val context = LocalContext.current
    val installed = remember(app.packageName) {
        runCatching { context.packageManager.getApplicationInfo(app.packageName, 0) }.isSuccess
    }
    val icon = remember(app.packageName, installed) {
        if (installed) runCatching { context.packageManager.getApplicationIcon(app.packageName) }.getOrNull() else null
    }
    var appEnabled by remember(app.packageName) {
        mutableStateOf(NexAlloyStore.isAppEnabled(context, app.packageName))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(icon)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(app.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(if (installed) R.string.installed else R.string.not_installed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (installed) colorScheme.primary else colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (installed) {
                Switch(checked = appEnabled, onCheckedChange = { next ->
                    if (NexAlloyStore.setAppEnabled(context, app.packageName, next)) {
                        appEnabled = NexAlloyStore.isAppEnabled(context, app.packageName)
                        NexAlloyStore.forceStop(context, listOf(app.packageName))
                    } else {
                        Toast.makeText(context, R.string.write_failed, Toast.LENGTH_LONG).show()
                    }
                })
            } else {
                Button(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.downloadUrl)))
                    }
                }) { Text(stringResource(R.string.download)) }
            }
        }
    }
}

@Composable
private fun AppIcon(icon: Drawable?) {
    if (icon == null) {
        androidx.compose.material3.Icon(
            imageVector = Icons.Rounded.Apps,
            contentDescription = null,
            tint = colorScheme.primary,
            modifier = Modifier.size(52.dp).padding(8.dp),
        )
    } else {
        val bitmap = remember(icon) { icon.toBitmap(width = 128, height = 128).asImageBitmap() }
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)),
        )
    }
}
