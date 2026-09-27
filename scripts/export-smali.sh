#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
BAKSMALI="${BAKSMALI_JAR:-$ROOT/tools/baksmali.jar}"
SOURCE="$ROOT/src/android/security/ntmanager/NexAlloyRuntime.java"
WORK="$ROOT/build/runtime"
OUT="${1:-$ROOT/build/smali}"

if [[ -z "$SDK_ROOT" ]]; then
  echo "ERROR: set ANDROID_SDK_ROOT or ANDROID_HOME." >&2
  exit 1
fi

ANDROID_JAR="$(find "$SDK_ROOT/platforms" -mindepth 2 -maxdepth 2 -name android.jar | sort -V | tail -1)"
D8_JAR="$(find "$SDK_ROOT/build-tools" -mindepth 3 -maxdepth 3 -path '*/lib/d8.jar' | sort -V | tail -1)"

for required in "$SOURCE" "$ANDROID_JAR" "$D8_JAR" "$BAKSMALI"; do
  if [[ ! -f "$required" ]]; then
    echo "ERROR: required file missing: $required" >&2
    exit 1
  fi
done

rm -rf "$WORK" "$OUT"
mkdir -p "$WORK/classes" "$OUT"

javac -source 11 -target 11 -classpath "$ANDROID_JAR" -d "$WORK/classes" "$SOURCE"
java -cp "$D8_JAR" com.android.tools.r8.D8 \
  --min-api 36 \
  --output "$WORK" \
  "$WORK/classes/android/security/ntmanager/NexAlloyRuntime.class"
java -jar "$BAKSMALI" disassemble "$WORK/classes.dex" -o "$OUT"

RESULT="$OUT/android/security/ntmanager/NexAlloyRuntime.smali"
[[ -s "$RESULT" ]] || { echo "ERROR: smali export failed." >&2; exit 1; }
echo "NexAlloy runtime smali exported to: $RESULT"
