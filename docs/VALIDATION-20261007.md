# Provisional validation: 2026-10-07

Snapshot: upstream `fa1ad1107638a5fd8d5b98e112b8d116b866938f`, adapter
`2.0.109-fa1ad110-compat6`. Publication of this provisional state was explicitly
requested after the outstanding Messenger read-receipt issue was reported.

## Confirmed

- A framework pulled from the current ColorOS 16 / Android 16 device was used
  for the device test, retaining its existing assistant changes.
- The patched device boots, and app namespaces see the matching framework assets.
- Messenger 581.0.0.49.91 starts without the previously observed native crash.
- All eight combinations of the three Messenger switches produce the expected
  installed/disabled hook states, with the app process remaining alive.
- Gmail's Hide ads switch produces disabled/applied states on the next launch.
- A disabled public YouTube dependency remains disabled when another enabled
  patch depends on it.
- A deliberately stale extracted payload is replaced with the pinned payload.
- Controller release build succeeds. App-row dialogs and Vietnamese/English
  resource selection were checked. The controller and payload hashes match in
  the Xiaomi and both Oplus toolbuilds.

## Outstanding behavior

The user reports that Messenger no longer crashes, but still sends "Seen" while
Disable read receipts is enabled. The current hook intercepts the inspected
legacy `MarkThreadsParams` read dispatch. Coverage of other outgoing receipt paths
has not been established. Read-receipt suppression is therefore **not verified
and is reported ineffective in the user's test**.

Typing suppression and ad filtering have installed hooks; their end-to-end
behavior is not established by the switch-state checks alone. Do not interpret
an "applied" status as proof of server-visible behavior.

The current release leaves these features available for continued testing; it
does not mark the read-receipt issue fixed. No private account or message data is
included in this report.
