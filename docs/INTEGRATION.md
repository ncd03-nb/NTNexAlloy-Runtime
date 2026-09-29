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

The supported-package allowlist is compiled into `NexAlloyRuntime`. Update the controller app and
runtime together when changing that list.

## Updating upstream

1. Select a stable release of `gnadgnaoh/NexAlloy-XES`.
2. Review its GPL source changes and supported application list.
3. Build or obtain the release artifacts from that release.
4. Update the payload version and runtime directory in `NexAlloyRuntime.java`.
5. Update `metadata/release.properties` and regenerate the SHA-256 manifest.
6. Export smali and run the complete framework/services patcher on the target ROM framework.
7. Test on the matching ROM before publishing an OTA/toolbuild update.

The update is intentionally ROM-coupled; this project does not implement hot replacement of a
framework-resident hook payload.
