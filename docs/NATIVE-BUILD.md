# Native engine used by compat5

The legacy AliuHook binary cannot be paired directly with a newly built LSPlant:
both must be compiled against the same LSPlant header and C++ runtime. The
tested pair uses LSPlant `6ac2280d8d88e14f0864c26ac97c1dc96713bde1`, AliuHook
`1f9855353a322dd6c3df8aac07be46469f2f81dd`, NDK `30.0.16248370`, CMake
`3.31.6`, and Dobby `1.2`. The Android device test runs API 37, arm64.

Use an existing Linux Android SDK (WSL is suitable). Clone the two upstream
repositories, check out these commits, and initialize AliuHook's `xz` submodule
and LSPlant's `lsplant/src/main/jni/external/dex_builder` submodule recursively.
Apply `compat/native/lsplant-abi.patch` and `aliuhook-cmake.patch` in their
respective repositories. Only the two ARM architectures are packaged.

Build LSPlant with `ANDROID_HOME="$SDK" ./gradlew :lsplant:assembleRelease`.
The shared libraries are under
`lsplant/build/intermediates/cmake/release/obj/<ABI>/liblsplant.so`.
Extract the Dobby 1.2 AAR from its Maven Central coordinate
`io.github.vvb2060.ndk:dobby:1.2` into `$DOBBY`.

For each ABI (`arm64-v8a`, `armeabi-v7a`), configure and build AliuHook:

```bash
"$SDK/cmake/3.31.6/bin/cmake" -S "$ALIU/core/src/main/cpp" -B "$BUILD/$ABI" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$SDK/cmake/3.31.6/bin/ninja" \
  -DCMAKE_TOOLCHAIN_FILE="$SDK/ndk/30.0.16248370/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-23 -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release \
  -DLSPLANT_INCLUDE="$LSPLANT/lsplant/src/main/jni/include" \
  -DLSPLANT_LIBRARY="$LSPLANT/lsplant/build/intermediates/cmake/release/obj/$ABI/liblsplant.so" \
  -DDOBBY_INCLUDE="$DOBBY/prefab/modules/dobby/include" \
  -DDOBBY_LIBRARY="$DOBBY/prefab/modules/dobby/libs/android.$ABI/libdobby.a"
"$SDK/cmake/3.31.6/bin/cmake" --build "$BUILD/$ABI"
```

Copy `liblsplant.so`, `libaliuhook.so`, and the matching NDK `libc++_shared.so`
to the toolbuild payload ABI directories. Strip unneeded symbols with the NDK
`llvm-strip`. Keep local unstripped build outputs for crash analysis. Obtain
`libdexkit.so` from the newly built NexAlloy APK using `package-payload.py`.
Regenerate and verify the nine-file payload manifest after all native replacements.

LSPlant is LGPL-3.0; AliuHook is OSL-3.0 and includes Apache-2.0 Xposed code.
Dobby and XZ retain their upstream licenses. Corresponding source, pinned
commits, local modifications, and these build instructions must accompany any
redistribution; see [NOTICE.md](../NOTICE.md).
