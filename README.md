# NTNexAlloy Runtime

ROM-integrated engine for the unmodified official [NexAlloy-XES APK](https://github.com/gnadgnaoh/NexAlloy-XES).
Install an upstream APK update to update app hooks; the framework does not need to
be rebuilt for a compatible module release. The original APK owns the feature UI. It is installed once as a removable user
app; uninstalling it does not make the ROM restore it on later boots.

There is no custom NexAlloy controller APK in the ROM and no dependency on NTManager.
The historical `controller-app/` and `compat/` sources document the retired
integration and are not packaged by current toolbuilds.

## Runtime

- `src/`: framework bootstrap, signed APK discovery, cache verification and native loader.
- `modern-src/`: API adapter and ROM settings service.
- `shared-src/`: upstream certificate, metadata and IPC contract.
- `scripts/build-modern-bridge.py`: builds API/IPC DEX from libxposed API/interface 102.
- `scripts/package-engine.py`: validates the 63-class legacy API/native loader shim.
- `scripts/patch-provider-transport.py`: adds the namespaced settings service dispatch.
- `metadata/`: engine asset pins and the initial unchanged upstream APK provenance.

The engine/API shim and six ABI libraries are fixed in framework.jar. The bundled
original APK is only a first-boot seed outside the system app scan. Active module
hook code and DexKit come from the installed original APK. There is no legacy
modified module fallback and no simultaneous second hook payload.

See [integration](docs/INTEGRATION.md) and [validation](docs/INDEPENDENT-UPDATES.md).

## Upstream authors and licensing

- Original project: [NexAlloy/NexAlloy](https://github.com/NexAlloy/NexAlloy),
  **ChsBuffer** ([@chsbuffer](https://github.com/chsbuffer)).
- XES fork: [gnadgnaoh/NexAlloy-XES](https://github.com/gnadgnaoh/NexAlloy-XES),
  **XES SEX** ([@gnadgnaoh](https://github.com/gnadgnaoh)).
- Upstream history also credits Fioren and Moeta Yuko.

The NothingsVN adapter is GPL-3.0-only. Upstream APK, native libraries, legacy API
shim and libxposed dependencies retain their own licenses. This source repository
does not redistribute their binary artifacts; obtain and validate them separately.
See [NOTICE.md](NOTICE.md).
