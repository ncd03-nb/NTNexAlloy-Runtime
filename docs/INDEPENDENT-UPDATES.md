# Original APK validation — 2026-10-17

## Final design

Only the original NexAlloy-XES APK is installed. The former custom
`com.hma.nexalloy` app was uninstalled for the current user. NTManager source/APK
is unchanged. The configuration Binder/preferences service lives in ROM via the
SettingsProvider transport dispatch.

The modified embedded NTNexAlloy.apk and its DexKit copies were removed from all
three toolbuilds. The replacement engine contains 63 API/native loader classes,
with no module hook code. The initial unchanged upstream APK is included in all
three toolbuilds. New module hooks and native DexKit are loaded from the APK
that PackageManager currently selects.

## Confirmed

- HyperOS 4 China / Android 17 boots with the original-only framework test mount.
- Original APK publisher SHA-256:
  `41b88671a623ad293d06fb1e3e396aaf22cafba8948e3cdec028dc95f2f8e8ba`.
- Current upstream APK SHA-256:
  `fdbb57ccd79872af0e1ca4219dd1f7f5b1f28f7e8aa6ac30706ef338fe740a48`,
  versionCode 110, entrypoint `kj0`, UI build commit 8cc0a85.
- After removal of the custom app, the user confirmed YouTube background play
  and changing switches in the original module both work normally.
- Original-only runtime records 150 hooked methods on YouTube 21.41.525.
  Method counts alone are not considered proof of feature behavior.
- APK-only replacement was tested with two unchanged, same-signer upstream APKs:
  older SHA-256 `45adc515195ae3e07b9ffc015770e817cb3c114d7458dcc1998a44f3088614ca`
  loaded entry `jj0` (110 records), then the current APK loaded `kj0` (150 records).
  Both replacements automatically stopped the previous target processes.
  Framework SHA-256 stayed
  `f47aa7006f919120a85ea19713179d884b6df80026824e8a4aaf40aac3ddde3b`.
  The current APK was restored after the comparison.
- Untrusted shell requests for the module configuration Binder were rejected.
- Xiaomi and both OPlus toolbuilds passed asset pins, original APK hash,
  engine inventory, ZIP embedding and provider patch idempotence checks.
  Xiaomi has three runtime variants; each OPlus build has one.

## Earlier YouTube state issue

Installing YouTube 21.20.402 over 21.41.525 preserved data and did not restore
background play. The user then uninstalled/reinstalled 21.41.525 and background
play worked. The framework did not change during that step. App UID and first
install time changed; the cached background-method fingerprint stayed the same.
This supports an old app data/configuration-state issue, not a demonstrated
unsupported YouTube version or wrong DexKit method. The exact old file/key was
not identified, and app data was not automatically cleared by this implementation.

## Limits

Device validation here covers HyperOS 4/Android 17. OPlus and older Android builds
received packaging checks, not a fresh full ROM build/device test in this run.
The current framework is a temporary mount; the toolbuild changes apply on a
subsequent ROM build. Full generic modern API semantics, other apps' server-side
behavior, and future upstream API/ABI changes are not claimed by these tests.

## Removable original APK

The original APK is seeded once into /data/app after first boot, not installed as
a system/privileged package. Uninstalling it is remembered; reboot/OTA does not
restore it. The same publisher-signed APK can be installed again normally.
NTManager does not own this installation or the upstream module UI.

### Device verification, 2026-10-18

- The ROM seed was installed by SettingsProvider UID 1000 into /data/app with
  neither SYSTEM nor UPDATED_SYSTEM_APP flags.
- Normal adb uninstall succeeded. After restarting Android with the same test
  framework mounted, the package stayed absent and the persistent seed marker
  remained true. Installing the unchanged official APK again succeeded.
- The original UI now receives its configuration Binder directly, without
  extracting a native engine first. After reinstall, “Activate this module” is
  absent, Patch Selection is present, and the YouTube settings activity displays
  its feature controls (six visible in the captured viewport).
- A pre-existing boot deadlock was captured: the client package-list hook read
  Settings while system_server was installing SettingsProvider, competing with
  TrickyStoreInit. The client filter now skips system app IDs below 10000 before
  accessing Settings. Explicit caller-UID server filtering is unchanged. All five
  toolbuild NTHooks variants assembled, and Android booted with the guard.
- All three actual update scripts passed fixture checks removing the old system
  copy/metadata while retaining NTManager. ZIP asset embedding and original APK
  checksums passed in each toolbuild; all six shell scripts passed bash -n.

Final temporary device framework SHA-256: `96e61e50e421ce453299cdc64856b5e714129f9529725ccc6250abd121b06e1d`.
