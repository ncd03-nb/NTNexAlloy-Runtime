# NTNexAlloy Runtime

Open-source Android framework adapter for loading a pinned NexAlloy XES payload directly from
`/system/framework/framework.jar`, without requiring an LSPosed manager or root service at
runtime.

This repository contains the NothingsVN runtime adapter, the standalone NexAlloy XES controller
app, and integration documentation. It does **not** contain NexAlloy's hook payload, native
libraries, patches, or other upstream binaries. Obtain those artifacts from the upstream projects
and comply with their licenses.

## Upstream projects and authors

- **Original project:** [NexAlloy/NexAlloy](https://github.com/NexAlloy/NexAlloy), created and
  maintained by **ChsBuffer** ([@chsbuffer](https://github.com/chsbuffer)).
- **XES fork used by this integration:**
  [gnadgnaoh/NexAlloy-XES](https://github.com/gnadgnaoh/NexAlloy-XES), maintained by
  **XES SEX** ([@gnadgnaoh](https://github.com/gnadgnaoh)).
- The upstream history also includes work by **Fioren**
  ([@FiorenMas](https://github.com/FiorenMas)) and **Moeta Yuko**
  ([@moetayuko](https://github.com/moetayuko)).

See [NOTICE.md](NOTICE.md) for detailed attribution and licensing boundaries.

## What is open here

- `NexAlloyRuntime.java`: process filter, per-app switches, payload extraction, class-loader setup,
  native loader invocation, and Xposed callback bridge.
- `controller-app/`: standalone `com.hma.nexalloy` Android app that owns the global and per-app
  runtime controls formerly hosted by NT Manager, plus per-feature app dialogs.
- `scripts/export-smali.sh`: reproducible Java → DEX → smali export for ROM toolbuilds.
- `docs/INTEGRATION.md`: framework and payload layout contract.
- `metadata/payload-manifest.sha256`: hashes of the tested pinned payload, without redistributing
  the payload itself.

## Current compatibility snapshot

- Upstream default branch: `root`, commit `fa1ad1107638a5fd8d5b98e112b8d116b866938f` (2026-10-05)
- Payload version: `2.0.109-fa1ad110-compat6` (`versionCode 109`, legacy framework entrypoint)
- Runtime API: `1`
- Android framework target: API 37
- Architectures: `arm64-v8a`, `armeabi-v7a`
- Google Photos is deliberately excluded because NT Manager already provides that feature.

This snapshot is provisional. On ColorOS 16, Messenger 581.0.0.49.91 starts
without the previously observed crash, but the user still observes outgoing read
receipts with "Disable read receipts" enabled. An installed hook or an "applied"
status does not prove that the server-side receipt was blocked. See
[validation notes](docs/VALIDATION-20261007.md) for the current limits.

## Export smali

Requirements: JDK 11+, Android SDK with a platform `android.jar` and D8, plus a baksmali fat JAR.

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
export BAKSMALI_JAR=/path/to/baksmali-fat.jar
bash scripts/export-smali.sh
```

The generated file is written to:

```text
build/smali/android/security/ntmanager/NexAlloyRuntime.smali
```

## Build the standalone controller

Tap an installed app row to open individual feature switches. The adjacent app
switch still enables or disables that app. Feature titles and descriptions follow Android's system locale:
Vietnamese resources are included, with English as the default fallback. Localized
display text never changes the upstream-name hashes used by the framework.

Regenerate the catalog and locale resources after updating the pinned source:

```powershell
python scripts/export-feature-catalog.py --source <patched-upstream-checkout> --output controller-app/app/src/main/assets/nt-patch-catalog.json --version 2.0.109-fa1ad110-compat6 --resource-root controller-app/app/src/main/res --translations-dir controller-app/translations
```

All public features must have a translation entry. Package-specific descriptions
remain separate; the resource keep file preserves dynamically resolved strings in
release builds. Add future translations to `controller-app/translations/<locale>.json`
and the corresponding Android UI strings under `values-<locale>`.

The controller targets Android 13+ and must be installed as a privileged system app to write the
shared `Settings.Global` contract without a development-time ADB grant.

```powershell
cd controller-app
$env:HMA_KEYSTORE_PATH = "C:\path\to\release.jks"
$env:HMA_KEYSTORE_PASSWORD = "..."
$env:HMA_KEY_ALIAS = "..."
$env:HMA_KEY_PASSWORD = "..."
.\gradlew.bat clean :app:assembleRelease --no-daemon
```

ROM placement:

```text
/system/priv-app/NexAlloyXES/NexAlloyXES.apk
/system/etc/permissions/com.hma.nexalloy.xml
```

## Important

- This is an independent ROM integration adapter, not an official NexAlloy project.
- Do not report adapter-specific issues to NexAlloy, Morphe, ReVanced, or their developers.
- The global enable key is `Settings.Global.nt_nexalloy_enabled`.
- Per-app keys use `Settings.Global.nt_nexalloy_app_<packageName>` and default to enabled.
- A new upstream release does not hot-swap a running ROM. Update the pinned payload and framework
  together in a compatible ROM/OTA build.

## ⭐ Credits

[DexKit](https://luckypray.org/DexKit/en/): a high-performance dex runtime parsing library.

[Morphe](https://morphe.software): Transform Your Android Apps.

[ReVanced](https://revanced.app): Continuing the legacy of Vanced at
[revanced.app](https://revanced.app).

[Zalo Patch](https://github.com/amarinne/zalo-patch): Zalo customization module for LSPosed.

## ❤️ Special Thanks

- **[Nguyen Trong Hieu](https://t.me/trangkyanh17)**: for sponsoring and supporting the project
  from day one.
- **Allen Chang**: for testing and reporting issues to improve the Facebook patch code.
- **[FiorenMas](https://github.com/FiorenMas/)**: for bringing SexAlloy to non-root devices.

## License

GPL-3.0-only. See [LICENSE](LICENSE). Upstream NexAlloy and NexAlloy-XES are also distributed
under GPL-3.0; their respective copyright remains with their authors and contributors.
