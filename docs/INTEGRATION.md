# Framework integration

## Archive layout

The ROM build must place the pinned payload inside `framework.jar` using this layout:

```text
ntmanager/NTNexAlloy.apk
ntmanager/arm64-v8a/libc++_shared.so
ntmanager/arm64-v8a/liblsplant.so
ntmanager/arm64-v8a/libaliuhook.so
ntmanager/arm64-v8a/libdexkit.so
ntmanager/armeabi-v7a/libc++_shared.so
ntmanager/armeabi-v7a/liblsplant.so
ntmanager/armeabi-v7a/libaliuhook.so
ntmanager/armeabi-v7a/libdexkit.so
```

The runtime extracts the ABI-specific files into the target application's cache directory and
loads the payload with Android's `DelegateLastClassLoader`.

The embedded legacy Xposed API shim must expose
`XC_MethodHook.MethodHookParam.getObjectExtra(String)` and
`setObjectExtra(String, Object)`. Facebook hooks use these methods to carry state from the before
callback to the after callback. When the payload changes, increment `PAYLOAD_VERSION` even if the
upstream APK versionCode is unchanged; otherwise existing app caches keep the previous payload.

## Initialization

Call the runtime after a valid application `Context` is available:

```java
if (context != null && Settings.Global.getInt(
        context.getContentResolver(), "nt_nexalloy_enabled", 0) != 0) {
    NexAlloyRuntime.init(context);
}
```

Wrap the call in a broad failure boundary so an unavailable or incompatible payload cannot abort
application startup.

## Settings contract

- Global switch: `nt_nexalloy_enabled`, default `0`.
- Per-app switch: `nt_nexalloy_app_<packageName>`, default `1`.
- Per-feature switch: `nt_nexalloy_feature_<packageName>_<id>`. The ID is the first
  16 lowercase hex characters of SHA-256 of the UTF-8 upstream patch name.
  The default is the source patch `use` value; internal helpers are not exposed.
  `scripts/export-feature-catalog.py` generates the controller catalog from the
  pinned patched source. The payload reads the same setting before applying a patch.
  Changing a switch force-stops only that app so hooks are recreated on next launch.
  The app-private `nt_nexalloy_features` preferences record applied/disabled/error
  states without account identifiers or message content.
  Public dependencies respect the same per-feature switches; they cannot enable
  another public feature that the user disabled.

The supported-package allowlist is compiled into `NexAlloyRuntime`. Update the controller app and
runtime together when changing that list.

## Updating upstream

1. Pin a release or default-branch commit of `gnadgnaoh/NexAlloy-XES`.
2. Review its GPL source changes and supported application list.
3. Build or obtain the release artifacts from that release.
4. Update the payload version and runtime directory in `NexAlloyRuntime.java`.
5. Update `metadata/release.properties` and regenerate the SHA-256 manifest.
6. Export smali and run the complete framework/services patcher on the target ROM framework.
7. Test on the matching ROM before publishing an OTA/toolbuild update.

The update is intentionally ROM-coupled; this project does not implement hot replacement of a
framework-resident hook payload.


## Default-branch snapshot fa1ad110

The tested snapshot is pinned by full commit in `metadata/release.properties`.
Its APK versionCode remains 109 upstream; the runtime cache version includes
`fa1ad110-compat6` to prevent old extracted APK/native files from being reused.
`PatchExecutor` also keys its DexKit cache by the upstream commit hash.

Apply `compat/fa1ad110/legacy-entrypoint.patch` to the upstream checkout and
`legacy-swipe.patch` inside its pinned `morphe-patches` submodule before building.
The patches preserve the new split-APK DexKit path and updated YouTube fingerprint.
The snapshot references Messenger sources absent from that commit. The adapter adds
its own Messenger implementation for sponsored inbox rows, outgoing read dispatch,
and outgoing typing state. Fingerprints were inspected against Messenger 581.0.0.49.91.
Messenger is included in both the controller and framework allowlists.

Build with `gradlew :app:assembleRelease`. Assemble only the legacy namespaces from
the previous pinned payload (`de/robv/android/xposed`, `external/org/apache/commons`,
and `io/github/nexalloy/NTNativeLoader.smali`) into a separate DEX using smali API 37.
Do not copy old app classes into the new payload. `scripts/package-payload.py` rejects
unexpected shim namespaces and duplicate classes, takes DexKit native libraries from
the exact new APK, and writes SHA-256 hashes for all nine framework assets.

```powershell
python scripts/package-payload.py --upstream-apk <unsigned-release.apk> --shim-dex <compat-shim.dex> --payload-dir <toolbuild-payload/ntmanager> --manifest <toolbuild-payload/payload-manifest.sha256>
```

Copy the manifest and release metadata together. The Xiaomi toolbuild runs
`embed-nexalloy.py --framework-tree <decoded-framework>` before assembling the JAR.
That step checks all nine hashes and requires a matching runtime cache version.
For a device test, pull the currently running framework first, retain its assistant
patches, update its existing runtime class, and add the verified assets. Keep a backup.

When testing through a KernelSU module, target apps must have **Unmount modules**
disabled so `/system/framework/framework.jar` resolves to the patched archive.
The app does not need root permission. Force-stop only the test app before each retry.

The runtime pins the SHA-256 digest of every embedded APK/native library and checks
cached files before loading them. If an app sees original ROM assets through its
mount namespace, initialization stops before any native library is loaded. Update
`expectedDigest` in both runtime source copies from `payload-manifest.sha256` before
exporting smali after a payload update. The embed helper rejects mismatched pins.
Cache versions must also change when the adapter code changes. A versioned cache
directory alone does not prove that it contains the expected files.

The legacy adapter uses Android API 33+ `MethodHandles.privateLookupIn` and
`findSpecial` for superclass lifecycle/touch calls. Reflection with
`Method.invoke` dispatches virtually and re-enters the replacement hook, causing
a settings ANR. Keep the special-call adapter for YouTube, Music, and swipe controls.
