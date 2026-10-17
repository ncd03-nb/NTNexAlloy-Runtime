#!/usr/bin/env python3
"""Package only the legacy API/native loader shim, never modified module hooks."""
import argparse
import hashlib
from pathlib import Path
import shutil
from importlib.util import spec_from_file_location, module_from_spec


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--shim-dex', type=Path, required=True)
    parser.add_argument('--bridge-dex', type=Path, required=True)
    parser.add_argument('--payload-dir', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, required=True)
    args = parser.parse_args()
    spec = spec_from_file_location('dex_inventory', Path(__file__).with_name('package-payload.py'))
    inventory = module_from_spec(spec); spec.loader.exec_module(inventory)
    data = args.shim_dex.read_bytes()
    classes = inventory.dex_classes(data)
    if not classes or any(not (c.startswith('Lde/robv/android/xposed/') or
                               c.startswith('Lexternal/org/apache/commons/') or
                               c == 'Lio/github/nexalloy/NTNativeLoader;') for c in classes):
        raise ValueError('Engine contains module hook/application code')
    args.payload_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(args.shim_dex, args.payload_dir / 'NTXposedEngine.dex')
    shutil.copy2(args.bridge_dex, args.payload_dir / 'NTModernBridge.dex')
    names = ['NTXposedEngine.dex', 'NTModernBridge.dex'] + [f'{abi}/{lib}'
             for abi in ('arm64-v8a', 'armeabi-v7a')
             for lib in ('libc++_shared.so', 'liblsplant.so', 'libaliuhook.so')]
    args.manifest.write_text(''.join(hashlib.sha256((args.payload_dir / name).read_bytes()).hexdigest()
                                   + '  ' + name + '\n' for name in names), encoding='utf-8')
    print(f'Engine-only package verified: {len(classes)} API/loader classes, {len(names)} assets')


if __name__ == '__main__':
    main()
