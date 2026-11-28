#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0

import json
import os
import re
import subprocess
import sys

ANDROID_MAX_VERSION_CODE = 2_100_000_000


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def latest_stable(upstream):
    release = json.loads(run("gh", "api", f"repos/{upstream}/releases/latest"))
    if release["draft"] or release["prerelease"]:
        raise ValueError("Upstream Latest is not a formal release")
    if not re.fullmatch(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", release["tag_name"]):
        raise ValueError(f"Unexpected official tag {release['tag_name']}; expected vX.Y.Z")
    return release


def upstream_version_code(release, commit=None):
    matches = re.findall(r"^- Version code: ([1-9][0-9]*)[ \t\r]*$", release["body"] or "", re.MULTILINE)
    if len(matches) != 1 or not any(asset["name"].endswith(".apk") for asset in release["assets"]):
        raise ValueError(f"Cannot confirm the upstream APK versionCode for {release['tag_name']}")
    code = int(matches[0])
    if not 0 < code <= ANDROID_MAX_VERSION_CODE or (
        commit is not None and code != int(run("git", "rev-list", "--count", commit))
    ):
        raise ValueError(f"Upstream APK versionCode does not match the source for {release['tag_name']}")
    return code


def distribution_floor(repo, releases, minimum):
    configured = int(os.environ.get("DISTRIBUTED_VERSION_CODE_FLOOR") or "0")
    if not 0 <= configured < ANDROID_MAX_VERSION_CODE:
        raise ValueError("DISTRIBUTED_VERSION_CODE_FLOOR is outside the Android versionCode range")
    floor = max(minimum, configured)
    for release in releases:
        apk_names = {asset["name"] for asset in release["assets"] if asset["name"].endswith(".apk")}
        if release["draft"] or not apk_names:
            continue
        tag = release["tag_name"]
        manifest = json.loads(run(
            "gh", "release", "download", tag, "--repo", repo, "--pattern", "update-manifest.json", "--output", "-"
        ))
        code = manifest["versionCode"]
        manifest_names = [asset["name"] for asset in manifest["assets"]]
        if (manifest["tag_name"] != tag or type(code) is not int
                or not 0 < code <= ANDROID_MAX_VERSION_CODE
                or len(manifest_names) != len(apk_names) or set(manifest_names) != apk_names
                or any(not re.search(rf"-{code}-(?:arm64-v8a|armeabi-v7a|x86|x86_64|universal)\.apk$", name)
                       for name in apk_names)):
            raise ValueError(f"Cannot confirm the published APK versionCode for {tag}")
        floor = max(floor, code)
    return floor


if __name__ == "__main__":
    try:
        operation, *args = sys.argv[1:]
        if operation == "latest":
            print(json.dumps(latest_stable(*args)))
        elif operation == "upstream-code":
            print(upstream_version_code(json.load(sys.stdin), *args))
        elif operation == "floor":
            repo, minimum = args
            releases = [release for page in json.load(sys.stdin) for release in page]
            print(distribution_floor(repo, releases, int(minimum)))
        else:
            raise ValueError(f"Unknown operation: {operation}")
    except (ValueError, KeyError, subprocess.CalledProcessError) as error:
        sys.exit(f"::error::{error}")
