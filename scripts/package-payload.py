#!/usr/bin/env python3
"""Package an upstream release APK with a separately assembled legacy API shim.

The shim must contain only de.robv.android.xposed, external.org.apache.commons,
and io.github.nexalloy.NTNativeLoader. Obtain it from the previous pinned payload
using baksmali, then assemble those namespaces alone; never reuse its app DEX.
"""
import argparse
import copy
import os
import hashlib
from pathlib import Path
import struct
import tempfile
import zipfile


def dex_classes(data):
    string_count, string_offset = struct.unpack_from("<II", data, 56)
    type_count, type_offset = struct.unpack_from("<II", data, 64)
    class_count, class_offset = struct.unpack_from("<II", data, 96)
    strings = []
    for i in range(string_count):
        pos = struct.unpack_from("<I", data, string_offset + 4 * i)[0]
        while data[pos] & 128:
            pos += 1
        pos += 1
        strings.append(data[pos:data.index(b"\0", pos)].decode("utf-8", "replace"))
    types = [strings[struct.unpack_from("<I", data, type_offset + 4 * i)[0]]
             for i in range(type_count)]
    return {types[struct.unpack_from("<I", data, class_offset + 32 * i)[0]]
            for i in range(class_count)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream-apk", required=True, type=Path)
    parser.add_argument("--shim-dex", required=True, type=Path)
    parser.add_argument("--payload-dir", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    args = parser.parse_args()
    shim = args.shim_dex.read_bytes()
    shim_classes = dex_classes(shim)
    if not shim_classes or any(not (c.startswith("Lde/robv/android/xposed/") or
                                    c.startswith("Lexternal/org/apache/commons/") or
                                    c == "Lio/github/nexalloy/NTNativeLoader;")
                               for c in shim_classes):
        raise ValueError("Unexpected class in legacy shim")
    args.payload_dir.mkdir(parents=True, exist_ok=True)
    output = args.payload_dir / "NTNexAlloy.apk"
    with zipfile.ZipFile(args.upstream_apk) as source:
        dex_names = [n for n in source.namelist() if n.startswith("classes") and n.endswith(".dex")]
        classes = set()
        for name in dex_names:
            current = dex_classes(source.read(name))
            if current & (classes | shim_classes):
                raise ValueError("Duplicate app or compatibility class")
            classes.update(current)
        next_dex = "classes" + str(len(dex_names) + 1) + ".dex"
        # Read and validate inputs before replacing a previously working payload.
        natives = {abi: source.read(f"lib/{abi}/libdexkit.so")
                   for abi in ("arm64-v8a", "armeabi-v7a")}
        for abi in natives:
            for lib in ("libc++_shared.so", "liblsplant.so", "libaliuhook.so"):
                if not (args.payload_dir / abi / lib).is_file():
                    raise ValueError(f"Missing engine library: {abi}/{lib}")
        fd, name = tempfile.mkstemp(dir=args.payload_dir, suffix=".apk")
        os.close(fd)
        temporary = Path(name)
        try:
            with zipfile.ZipFile(temporary, "w") as target:
                for info in source.infolist():
                    if not info.filename.startswith("META-INF/"):
                        target.writestr(copy.copy(info), source.read(info.filename))
                target.writestr(next_dex, shim, compress_type=zipfile.ZIP_STORED)
            os.replace(temporary, output)
        finally:
            temporary.unlink(missing_ok=True)
        for abi, native in natives.items():
            (args.payload_dir / abi / "libdexkit.so").write_bytes(native)
    names = ["NTNexAlloy.apk"] + [f"{abi}/{lib}" for abi in ("arm64-v8a", "armeabi-v7a")
             for lib in ("libc++_shared.so", "liblsplant.so", "libaliuhook.so", "libdexkit.so")]
    args.manifest.write_text("".join(
        hashlib.sha256((args.payload_dir / name).read_bytes()).hexdigest() + "  " + name + "\n"
        for name in names), encoding="utf-8")
    print(f"Packaged {len(classes)} upstream classes and {len(shim_classes)} legacy shim classes")


if __name__ == "__main__":
    main()
