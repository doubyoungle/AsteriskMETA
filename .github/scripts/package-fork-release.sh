#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0
set -euo pipefail

: "${RELEASE_KEYSTORE_BASE64:?Missing RELEASE_KEYSTORE_BASE64}"
: "${RELEASE_KEYSTORE_PASSWORD:?Missing RELEASE_KEYSTORE_PASSWORD}"
: "${RELEASE_KEY_ALIAS:?Missing RELEASE_KEY_ALIAS}"
export RELEASE_KEY_PASSWORD="${RELEASE_KEY_PASSWORD:-$RELEASE_KEYSTORE_PASSWORD}"

version="${GITHUB_REF_NAME#v}"
version_code="$(git rev-list --count HEAD)"
build_tools_version="$(sed -n 's/^[[:space:]]*const val BUILD_TOOLS_VERSION = "\([^"]*\)".*/\1/p' buildSrc/src/main/kotlin/BuildConfig.kt)"
tools="$ANDROID_HOME/build-tools/$build_tools_version"
if [[ ! -x "$tools/apksigner" ]]; then
  android --no-metrics --sdk="$ANDROID_HOME" sdk install "build-tools/$build_tools_version"
fi

umask 077
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
printf '%s' "$RELEASE_KEYSTORE_BASE64" | base64 --decode > "$work/release.keystore"
mkdir -p release-artifacts

shopt -s nullglob
for core in mihomo meow; do
  suffix=
  if [[ "$core" == meow ]]; then suffix=-meow; fi
  for apk in build-apks/release-"$core"/*.apk; do
    badging="$("$tools/aapt2" dump badging "$apk")"
    package="$(sed -n "s/^package: name='\([^']*\)' versionCode='\([^']*\)' versionName='\([^']*\)'.*/\1 \2 \3/p" <<< "$badging")"
    if [[ "$package" != "org.asterisk.zcc.ameta $version_code $version" ]]; then
      echo "::error::Unexpected package or version in $apk"
      exit 1
    fi
    case "${apk##*/}" in
      *-arm64-v8a-*) abi=arm64-v8a ;;
      *-armeabi-v7a-*) abi=armeabi-v7a ;;
      *-x86_64-*) abi=x86_64 ;;
      *-x86-*) abi=x86 ;;
      *-universal-*) abi=universal ;;
      *) echo "::error::Unable to identify ABI for $apk"; exit 1 ;;
    esac
    output="release-artifacts/AsteriskMETA_${version}${suffix}-${version_code}-${abi}.apk"
    if [[ -e "$output" ]]; then
      echo "::error::Duplicate release asset $output"
      exit 1
    fi
    "$tools/zipalign" -P 16 -f 4 "$apk" "$work/aligned.apk"
    "$tools/apksigner" sign \
      --ks "$work/release.keystore" --ks-key-alias "$RELEASE_KEY_ALIAS" \
      --ks-pass env:RELEASE_KEYSTORE_PASSWORD --key-pass env:RELEASE_KEY_PASSWORD \
      --out "$output" "$work/aligned.apk"
    "$tools/apksigner" verify "$output"
  done
  for abi in arm64-v8a armeabi-v7a x86 x86_64 universal; do
    test -s "release-artifacts/AsteriskMETA_${version}${suffix}-${version_code}-${abi}.apk"
  done
done
rm -f release-artifacts/*.idsig

python3 - "$version_code" <<'PY'
import hashlib
import json
import os
import sys
from pathlib import Path

directory = Path("release-artifacts")
tag = os.environ["GITHUB_REF_NAME"]
download_base = f'https://github.com/{os.environ["GITHUB_REPOSITORY"]}/releases/download/{tag}'
assets = []
for apk in sorted(directory.glob("*.apk")):
    with apk.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    assets.append({
        "name": apk.name,
        "size": apk.stat().st_size,
        "digest": f"sha256:{digest}",
        "browser_download_url": f"{download_base}/{apk.name}",
    })
manifest = {"tag_name": tag, "versionCode": int(sys.argv[1]), "assets": assets}
(directory / "update-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
PY
