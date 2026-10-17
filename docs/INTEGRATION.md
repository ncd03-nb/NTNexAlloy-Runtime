# Original APK integration

## Framework archive

```text
ntmanager/NTXposedEngine.dex
ntmanager/NTModernBridge.dex
ntmanager/NexAlloyXES.apk  # unchanged seed APK, not a system package
ntmanager/arm64-v8a/libc++_shared.so
ntmanager/arm64-v8a/liblsplant.so
ntmanager/arm64-v8a/libaliuhook.so
ntmanager/armeabi-v7a/libc++_shared.so
ntmanager/armeabi-v7a/liblsplant.so
ntmanager/armeabi-v7a/libaliuhook.so
```

Do not include NTNexAlloy.apk or a pinned DexKit library. The engine DEX may
contain only `de.robv.android.xposed`, `external.org.apache.commons`, and
`io.github.nexalloy.NTNativeLoader`. No modified module hook classes are allowed.

Place the unchanged official APK at the existing toolbuild path
`app/NexAlloyXES/NexAlloyXES.apk`; its package is
`io.github.chsbuffer.revancedxposed`, not `com.hma.nexalloy`. This upstream APK
needs no custom privileged-permissions file. Do not copy it into app/priv-app.
Embedding verifies the seed APK against release.properties and places its original
bytes inside framework.jar, outside PackageManager's system package scan.

After boot completion, the ROM settings service installs that signed APK once
through PackageInstaller into /data/app as an ordinary user app. A persistent
marker in SettingsProvider device-protected preferences records a successful
seed (or an existing/previously removed package). Later reboots and OTA updates
never reinstall a user-removed module, replace an installed version, or downgrade
it. A factory reset clears the marker and allows the initial seed again. Failed
initial installation is logged and may retry after a later system-server restart.
The installer runs on its own thread and does not delay boot. Users can uninstall
normally and reinstall/update the official APK manually without changing ROM.
The bridge and its preferences remain in ROM, independently of the removable UI.

## Bootstrap and configuration

Call `NexAlloyRuntime.init(context)` after the app Context is available and before
Application.onCreate. Do not gate this call on the old controller Settings.Global
keys. Runtime reads the signed APK's metadata/scope and limits hooks to its main
target processes. Missing module/service fails closed without loading a legacy module.

Run `patch-provider-transport.py` against the decompiled framework tree. It adds a
dispatch after Android's incoming-authority validation. Only `nt.nexalloy.*` calls
on the real `com.android.providers.settings` provider are handled. Other calls
keep their original route. The patch is idempotent and rejects an unknown layout.

The original APK receives a Binder through libxposed's public `XposedProvider`
`SendBinder` protocol. The module UI obtains this Binder directly from the ROM
service before any native engine/cache extraction; a freshly reinstalled UI must
not depend on target-app engine assets. Private helper/entrypoint names may be
obfuscated by R8.
Module entrypoints are read from `META-INF/xposed/java_init.list`.

The ROM service stores scope and preferences in SettingsProvider's device-protected
private storage. It validates the original publisher certificate and caller UID
before allowing configuration changes. A target app can read only its scoped
preferences; cross-user access is rejected. Certificate/package discovery uses
the host identity, while authorization retains the original Binder caller UID.
No NTManager or custom controller component is used.

Preference changes restart the affected target. Replacing the module APK restarts
scoped targets so their next process loads the new sourceDir, entrypoint and
APK-specific native cache. Hook replacement inside a live process is not attempted.

## Build

Use `build-modern-bridge.py --help` for the Android SDK, official libxposed API and
interface 102 classes.jar, compile-only legacy API 82, and D8 arguments. The script
updates the bridge digest in Java and metadata. Keep Apache-2.0 dependency notices
with redistributed bridge assets.

Validate/package the engine with `package-engine.py`, export runtime smali, and
sync the identical runtime/bridge/manifest across all toolbuild variants. Embedding
must reject mismatched hashes and delete obsolete legacy payload/DexKit entries
from already patched frameworks. A stock framework needs the provider dispatch
as well as the application initialization hook.

Compatible module updates require only the original APK. A future module requiring
an unsupported API, ABI or engine capability may still require an engine update.
Class-initializer hooks and remote module files are not implemented. The current
upstream primarily uses the legacy hook API plus modern preferences/invokers;
the adapter is not advertised as a general-purpose replacement for libxposed.

## Device testing

Pull the currently running framework as the baseline. Preserve unrelated patches.
For temporary bind mounts, set owner 0:0, mode 0644, SELinux system_file before
restarting Android. App root-manager profiles must expose the mounted framework
to the original module, SettingsProvider and target apps. Root is not required
by those apps. A temporary test mount does not survive a full reboot.
