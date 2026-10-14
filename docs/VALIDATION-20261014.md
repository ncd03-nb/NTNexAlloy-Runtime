# Release b61974f validation

## Provenance and packaging

- Release: `gnadgnaoh/NexAlloy-XES` v1.0, published 2026-10-14 09:10:43 UTC.
- Release APK: versionName 2.0.110, versionCode 110; embedded commit `b61974f`.
- Source pin: `b61974fdea5b1b5a944657072c0a01d6426bb41e`.
- The published tag points to an older commit; the release APK's commit and
  current root commit `6b188252` have identical source trees.
- Adapter/cache version: `2.0.110-b61974f-compat1`.
- Controller: `com.hma.nexalloy` 1.2.0 (10004), HMA certificate matching the
  installed controller; 152 public features for 18 apps, Vietnamese and English.
- Upstream and controller release builds passed. Payload assembly rejects
  duplicate classes and includes only the 63 existing legacy shim classes.
- All nine asset digests match both runtime source copies and toolbuild exports.

## Module test

Used `framework.jar` freshly pulled from the connected OPPO Android 16 / SDK 36
phone, preserving its existing assistant/framework changes. The test module
checks the original framework SHA-256 before installation.

Only `classes7.dex` and `ntmanager/NTNexAlloy.apk` changed in that framework.
The framework class set and every unrelated ZIP entry were preserved. Native
DexKit/engine libraries are byte-identical to the previous payload.

Installed `nt_nexalloy_release_test` with KernelSU/Hybrid Mount and rebooted.
Boot completed successfully. The boot classloader loaded the new runtime. The
initial app checksum mismatch was traced to KernelSU unmounting modules in app
namespaces; the user disabled that option for the four tested apps. Their
process roots then exposed the exact patched framework hash.

The controller was also installed as an HMA-signed update because PackageManager
initially retained the old system-app version metadata after the module mount.

## Results

| App | Result |
| --- | --- |
| Gmail | New runtime attached, ad hook applied; toggling off/on changes the hook status to disabled/applied after restart. Original setting restored. |
| YouTube | New runtime initialization confirmed in its runtime log; 21 public feature hooks applied, including DeArrow. No startup crash observed. |
| Messenger | All six feature hooks applied with read receipts enabled for testing. User confirmed the other side sees neither read receipts nor typing status and the app remains usable. Read-receipt protection remains enabled after the successful test. |
| Instagram 452.0.0.0.39 | New runtime attached, 22 public hooks applied, including Ghost view once. Ghost ephemeral keep fails because ephemeralVanishLocalDeleteFingerprint has no match. App remains running; this feature is not validated or claimed working. |
| Proton VPN | Latest release source and five public feature definitions included. Not installed on the connected phone, so no device runtime test. |

The legacy inbox-ad filter for Messenger is retained. An applied ad hook is not
proof of every server-delivered ad being removed. The tests above do not claim
exhaustive coverage of all 152 features or all supported app versions.

## Rollback

Disable `nt_nexalloy_release_test` in KernelSU and reboot to restore the ROM's
framework and system controller file. The controller installed under `/data/app`
is a separate app update; restoring the previous controller requires installing
the saved matching-key APK or removing its update through Android Settings.
The original framework and controller APK are retained in the local test work
directory. No SystemUI force-stop was used.
