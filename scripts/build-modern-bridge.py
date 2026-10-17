#!/usr/bin/env python3
"""Build the ROM-side API bridge; never repack the original NexAlloy APK.

Inputs are the official libxposed API 102 classes.jar and legacy Xposed API 82
compile stubs. The latter is compile-only; the ROM's engine provides it.
"""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android-jar', type=Path, required=True)
    parser.add_argument('--api-jar', type=Path, required=True)
    parser.add_argument('--interface-jar', type=Path, required=True)
    parser.add_argument('--legacy-api', type=Path, required=True)
    parser.add_argument('--d8-jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/modern')
    args = parser.parse_args()
    for path in (args.android_jar, args.api_jar, args.interface_jar, args.legacy_api, args.d8_jar):
        if not path.is_file():
            parser.error(f'Missing dependency: {path}')
    classes = args.output / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    sources = sorted((ROOT / 'modern-src').rglob('*.java')) + sorted((ROOT / 'shared-src').rglob('*.java'))
    import os
    cp = os.pathsep.join(str(p.resolve()) for p in (args.android_jar, args.api_jar, args.interface_jar, args.legacy_api))
    subprocess.run(['javac', '--release', '17', '-cp', cp, '-d', str(classes), *map(str, sources)], check=True)
    jar = args.output / 'bridge-classes.jar'
    subprocess.run(['jar', 'cf', str(jar), '-C', str(classes), '.'], check=True)
    subprocess.run(['java', '-cp', str(args.d8_jar), 'com.android.tools.r8.D8',
                    '--release', '--min-api', '33', '--lib', str(args.android_jar),
                    '--classpath', str(args.legacy_api), '--output', str(args.output),
                    str(jar), str(args.api_jar), str(args.interface_jar)], check=True)
    dex = (args.output / 'classes.dex').read_bytes()
    output = args.output / 'NTModernBridge.dex'
    output.write_bytes(dex)
    digest = hashlib.sha256(dex).hexdigest()
    source = ROOT / 'src/android/security/ntmanager/NexAlloyRuntime.java'
    text, count = re.subn(r'(BRIDGE_DIGEST = ")[^"]+(";)', rf'\g<1>{digest}\2', source.read_text(encoding='utf-8'))
    assert count == 1
    source.write_text(text, encoding='utf-8')
    manifest = ROOT / 'metadata/payload-manifest.sha256'
    lines = [line for line in manifest.read_text().splitlines() if not line.endswith('  NTModernBridge.dex')]
    manifest.write_text('\n'.join(lines + [digest + '  NTModernBridge.dex']) + '\n')
    print(f'{digest}  {output}')


if __name__ == '__main__':
    main()
