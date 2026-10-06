#!/usr/bin/env python3
"""Export public patch switches from the pinned Kotlin source, without guessing names."""
import argparse
import hashlib
import json
from pathlib import Path
import re
from xml.sax.saxutils import escape


def android_string(value):
    return escape(value).replace('\\', '\\\\').replace("'", "\\'").replace('"', '\\"').replace('\n', '\\n')


def export_resources(apps, resource_root, translations_dir):
    """Use Android's locale fallback; IDs remain based on the upstream names."""
    locales = {"values": None}
    if translations_dir:
        for path in sorted(translations_dir.glob('*.json')):
            locales['values-' + path.stem] = json.loads(path.read_text(encoding='utf-8'))
    for directory, translation in locales.items():
        strings = {}
        for app in apps:
            package = app['packageName']
            for feature in app['features']:
                name = feature['name']
                title, description = name, feature['description']
                if translation is not None:
                    if name not in translation['features']:
                        raise ValueError(f'Missing {directory} translation: {name}')
                    title, description = translation['features'][name]
                    description = translation.get('descriptionOverrides', {}).get(package, {}).get(name, description)
                    if not feature['description']:
                        description = ''
                strings['feature_' + feature['id'] + '_title'] = title
                key = 'feature_' + package.replace('.', '_') + '_' + feature['id'] + '_description'
                strings[key] = description
        output = resource_root / directory / 'features.xml'
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' + ''.join(
            f'    <string name="{key}" formatted="false">{android_string(value)}</string>\n'
            for key, value in sorted(strings.items())) + '</resources>\n', encoding='utf-8')
    keep = resource_root / 'raw' / 'nexalloy_features_keep.xml'
    keep.parent.mkdir(parents=True, exist_ok=True)
    keep.write_text('<resources xmlns:tools="http://schemas.android.com/tools" tools:keep="@string/feature_*" />\n', encoding='utf-8')


def tokens(source):
    pattern = re.compile(r'//[^\n]*|/\*.*?\*/|""".*?"""|"(?:\\.|[^"\\])*"|[A-Za-z_][\w]*|[^\s]', re.S)
    return [m.group() for m in pattern.finditer(source)
            if not m.group().startswith(("//", "/*"))]


def string(value):
    if value.startswith('"""'):
        return value[3:-3].strip()
    if value.startswith('"'):
        return json.loads(value.replace(r'\$', '$'))
    raise ValueError(f"Expected literal patch metadata, got {value}")


def declarations(source):
    t = tokens(source)
    result = {}
    for i in range(len(t) - 5):
        if t[i] != "val":
            continue
        name = t[i + 1]
        j = i + 2
        while j < len(t) and t[j] not in ("=", "{", "val", "fun"):
            j += 1
        if j + 1 >= len(t) or t[j] != "=" or t[j + 1] not in ("patch", "arrayOf"):
            continue
        kind = t[j + 1]
        j += 2
        if t[j] == "<":
            while t[j] != ">":
                j += 1
            j += 1
        if t[j] != "(":
            continue
        start = j + 1
        depth = 1
        while depth:
            j += 1
            depth += (t[j] == "(") - (t[j] == ")")
        args = t[start:j]
        if kind == "arrayOf":
            result[name] = (kind, [v for v in args if v != ","])
        else:
            fields = {"name": "", "description": "", "defaultEnabled": True}
            for k in range(len(args) - 2):
                if args[k + 1] != "=":
                    continue
                if args[k] in ("name", "description"):
                    value = string(args[k + 2])
                    end = k + 3
                    while end + 1 < len(args) and args[end] == '+':
                        value += string(args[end + 1])
                        end += 2
                    fields[args[k]] = value
                elif args[k] == "use":
                    if args[k + 2] not in ("true", "false"):
                        raise ValueError(f"Nonliteral default for {name}")
                    fields["defaultEnabled"] = args[k + 2] == "true"
            result[name] = (kind, fields)
    for match in re.finditer(r'\bval\s+(\w+)\s*=\s*patch\s*\{', source):
        result[match[1]] = ('patch', {'name': '', 'description': '', 'defaultEnabled': True})
    return result


def factory_patches(source):
    result = {}
    for match in re.finditer(r'\bfun\s+(\w+)\s*\(', source):
        tail = source[match.end():]
        factory = re.search(r'\)\s*=\s*patch\s*\(', tail)
        boundary = re.search(r'\n(?:internal\s+)?(?:fun|val|class)\s', tail)
        if factory and (not boundary or factory.start() < boundary.start()):
            result.update(declarations('val ' + match[1] + ' = patch(' + tail[factory.end():]))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--resource-root", type=Path)
    parser.add_argument("--translations-dir", type=Path)
    args = parser.parse_args()
    root = args.source / "app/src/main/java"
    symbols = {}
    contexts = {}
    constants = {}
    for path in root.rglob("*.kt"):
        source = path.read_text(encoding="utf-8")
        package = re.search(r"^package\s+([\w.]+)", source, re.M).group(1)
        imports = {alias or full.rsplit(".", 1)[-1]: full
                   for full, alias in re.findall(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$", source, re.M)}
        declared = declarations(source)
        declared.update(factory_patches(source))
        for match in re.finditer(r'\bval\s+(\w+)\s*=\s*(\w+Patch)\s*\(', source):
            if match[1] not in declared:
                declared[match[1]] = ('factory', imports.get(match[2], package + '.' + match[2]))
        for name, kind_value in declared.items():
            full = package + "." + name
            symbols[full] = kind_value
            contexts[full] = (package, imports)
        for name, value in re.findall(r'\bconst\s+val\s+(\w+)\s*=\s*("(?:\\.|[^"\\])*")', source):
            constants[package + "." + name] = string(value)
            object_name = re.search(r'\bobject\s+(\w+)\s*\{', source)
            if object_name:
                constants[package + '.' + object_name[1] + '.' + name] = string(value)
    config = (root / "io/github/nexalloy/AppPatchInfo.kt").read_text(encoding="utf-8")
    imports = {alias or full.rsplit(".", 1)[-1]: full
               for full, alias in re.findall(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$", config, re.M)}
    apps = []
    for name, package_token, patch_symbol in re.findall(
            r'AppPatchInfo\("([^"]+)",\s*("[^"]+"|\w+),\s*(\w+)', config):
        package_name = string(package_token) if package_token.startswith('"') else constants[imports[package_token]]
        if package_name == "com.google.android.apps.photos":
            continue
        array_symbol = imports[patch_symbol]
        kind, array = symbols[array_symbol]
        assert kind == "arrayOf"
        package, local_imports = contexts[array_symbol]
        patches = []
        for patch in array:
            full = local_imports.get(patch, package + "." + patch)
            kind, metadata = symbols[full]
            while kind == 'factory':
                kind, metadata = symbols[metadata]
            assert kind == "patch", full
            if not metadata["name"] or metadata["name"].startswith("<"):
                continue
            patch_id = hashlib.sha256(metadata["name"].encode()).hexdigest()[:16]
            patches.append({"id": patch_id, **metadata})
        if len({p["id"] for p in patches}) != len(patches):
            raise ValueError(f"Duplicate feature key in {package_name}")
        if not patches:
            raise ValueError(f"No public patches exported for {package_name}")
        apps.append({"name": name, "packageName": package_name, "features": patches})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"schema": 1, "version": args.version, "apps": apps},
                                      ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.resource_root:
        export_resources(apps, args.resource_root, args.translations_dir)
    print(f"Exported {sum(len(a['features']) for a in apps)} public features for {len(apps)} apps")


if __name__ == "__main__":
    main()
