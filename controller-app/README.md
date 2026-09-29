# NexAlloy XES

Standalone controller for the NexAlloy runtime embedded in the ROM framework.

## ROM placement

- APK: `/system/priv-app/NexAlloyXES/NexAlloyXES.apk`
- privileged-permission allowlist: `/system/etc/permissions/com.hma.nexalloy.xml`

The controller and framework share these `Settings.Global` keys:

- `nt_nexalloy_enabled`
- `nt_nexalloy_app_<package-name>`

Per-app keys default to enabled, preserving the behavior of earlier ROM builds. Changing a switch
stops the affected package so its next process reads the new framework setting.

## Release build

Set `HMA_KEYSTORE_PATH`, `HMA_KEYSTORE_PASSWORD`, `HMA_KEY_ALIAS`, and `HMA_KEY_PASSWORD`, then run:

```powershell
.\gradlew.bat clean :app:assembleRelease --no-daemon
```

Runtime diagnostics use logcat tag `NexAlloyXES`; framework diagnostics use `NTNexAlloy`.
