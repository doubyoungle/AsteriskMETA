#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0

import json
import os
import re
import subprocess
import sys
from pathlib import Path

from fork_release_policy import (
    ANDROID_MAX_VERSION_CODE, distribution_floor, latest_stable, upstream_version_code,
)

UPSTREAM = "Asterisk4Magisk/AsteriskMETA"
FIRST_TRACKED_TAG = "v1.3.1"
REVISION_PROPERTY = "asteriskMetaForkRevision"
INITIAL_VERSION_CODE = 624
FORK_POINT = "eb8da386a006c1ac5a61ff9055d8dc1a1a056914"
PROJECT_CONFIG = "buildSrc/src/main/kotlin/BuildConfig.kt"
STABLE = r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
FORK = rf"({STABLE})-r\.[1-9][0-9]*"


def run(*args, data=None):
    return subprocess.run(args, input=data, text=True, check=True, stdout=subprocess.PIPE).stdout.strip()


def releases(repo):
    pages = json.loads(run("gh", "api", "--paginate", "--slurp", f"repos/{repo}/releases?per_page=100"))
    return [release for page in pages for release in page]


def version(tag):
    return tuple(int(part) for part in tag[1:].split("."))


def was_published(upstream_tag, fork_releases):
    for release in fork_releases:
        if release["draft"]:
            continue
        tag = release["tag_name"]
        if tag == f"{upstream_tag}-r.1":
            return True
        if release["prerelease"]:
            continue
        match = re.fullmatch(FORK, tag)
        if match and version(match[1]) >= version(upstream_tag):
            return True
    return False


def official_tag_commit(upstream_tag):
    git_ref = json.loads(run("gh", "api", f"repos/{UPSTREAM}/git/ref/tags/{upstream_tag}"))["object"]
    while git_ref["type"] == "tag":
        git_ref = json.loads(run("gh", "api", f"repos/{UPSTREAM}/git/tags/{git_ref['sha']}"))["object"]
    if git_ref["type"] != "commit":
        raise ValueError(f"Upstream tag {upstream_tag} does not resolve to a commit")
    return git_ref["sha"]


def minimum_version_code():
    tracked_test_floor = int(run("bash", str(Path(__file__).with_name("fork-version-floor.sh"))))
    return max(INITIAL_VERSION_CODE, tracked_test_floor)


def release_candidate(upstream_release, fork_releases, repo):
    upstream_tag = upstream_release["tag_name"]
    fork_tag = f"{upstream_tag}-r.1"
    fork_source = run("git", "rev-parse", "HEAD")
    run("git", "fetch", "--no-tags", f"https://github.com/{UPSTREAM}.git",
        "+refs/heads/main:refs/remotes/upstream/main",
        f"+refs/tags/{upstream_tag}:refs/upstream-release/{upstream_tag}")
    upstream_commit = run("git", "rev-parse", "--verify", f"refs/upstream-release/{upstream_tag}^{{commit}}")
    if official_tag_commit(upstream_tag) != upstream_commit:
        raise ValueError(f"GitHub and Git disagree on the commit for {upstream_tag}")
    config = run("git", "show", f"{upstream_commit}:{PROJECT_CONFIG}")
    match = re.search(r'^\s*const val VERSION_NAME = "([^"]+)"', config, re.MULTILINE)
    if not match or match[1] != upstream_tag[1:]:
        raise ValueError(f"{upstream_tag} resolves to {upstream_commit}, whose source version is {match[1] if match else 'missing'}")

    minimum = upstream_version_code(upstream_release, upstream_commit)
    floor = distribution_floor(repo, fork_releases, max(minimum, minimum_version_code()))
    ref = f"refs/tags/{fork_tag}"
    existing = run("git", "ls-remote", "--tags", "origin", ref)
    overlay_source = fork_source
    if existing:
        run("git", "fetch", "--no-tags", "origin", f"{ref}:refs/fork-release/{fork_tag}")
        candidate = run("git", "rev-parse", f"refs/fork-release/{fork_tag}^{{commit}}")
        parents = run("git", "show", "-s", "--format=%P", candidate).split()
        if len(parents) != 2 or parents[0] != upstream_commit:
            raise ValueError(f"Existing {fork_tag} does not have the expected upstream first parent")
        overlay_source = parents[1]
        if subprocess.run(["git", "merge-base", "--is-ancestor", overlay_source, fork_source]).returncode:
            raise ValueError(f"Existing {fork_tag} has a fork parent outside the current fork history")

    bases = run("git", "merge-base", "--all", overlay_source, "refs/remotes/upstream/main").splitlines()
    if len(bases) != 1:
        raise ValueError("Cannot identify a unique fork overlay base")
    if subprocess.run(["git", "merge-base", "--is-ancestor", FORK_POINT, bases[0]]).returncode:
        raise ValueError("Fork overlay base predates the original fork point. Confirm upstream history.")
    overlay = subprocess.check_output(["git", "diff", "--binary", "--full-index", bases[0], overlay_source])
    if not overlay:
        raise ValueError("Fork overlay is empty")
    run("git", "switch", "--detach", upstream_commit)
    if subprocess.run(["git", "apply", "--3way", "--index"], input=overlay).returncode:
        raise ValueError(f"Fork changes conflict with {upstream_tag}")
    match = re.search(r'^\s*const val VERSION_NAME = "([^"]+)"', Path(PROJECT_CONFIG).read_text(), re.MULTILINE)
    if not match or match[1] != upstream_tag[1:]:
        raise ValueError("Fork overlay changed the upstream source version")
    properties = Path("gradle.properties")
    contents = properties.read_text()
    revision = rf"^{REVISION_PROPERTY}=.*$"
    if re.search(revision, contents, re.MULTILINE):
        contents = re.sub(revision, f"{REVISION_PROPERTY}=1", contents, flags=re.MULTILINE)
    else:
        contents = f"{contents}\n{REVISION_PROPERTY}=1\n"
    properties.write_text(contents)
    run("git", "add", "gradle.properties")
    if "AppUpdateSection()" not in Path("app/src/main/kotlin/features/about/AboutPage.kt").read_text():
        raise ValueError("Fork overlay lost the app updater entry")
    updater = Path("app/src/main/kotlin/features/updater/AppUpdateSection.kt")
    if "LocalActivityResultRegistryOwner provides activity" not in updater.read_text():
        raise ValueError("Fork overlay lost the About updater crash fix")
    tree = run("git", "write-tree")

    if existing:
        if run("git", "rev-parse", f"{candidate}^{{tree}}") != tree:
            raise ValueError(f"Existing {fork_tag} differs from its recorded fork overlay. Inspect the failed release tag.")
        print(f"Reusing {fork_tag} at {candidate}", flush=True)
    else:
        candidate = run(
            "git", "-c", "user.name=github-actions[bot]",
            "-c", "user.email=41898282+github-actions[bot]@users.noreply.github.com",
            "commit-tree", tree, "-p", upstream_commit, "-p", fork_source,
            "-m", f"Prepare {UPSTREAM.split('/')[1]} {fork_tag}", "-m", f"Upstream-release: {upstream_tag}",
        )
    code = int(run("git", "rev-list", "--count", candidate))
    print(f"Upstream tag {upstream_tag} -> {upstream_commit}; upstream main {run('git', 'rev-parse', 'refs/remotes/upstream/main')}")
    print(f"Fork {fork_tag} -> {candidate}; versionCode {code}")

    require_higher(code, floor)
    print(f"versionCode {code} exceeds distribution floor {floor}.")
    return fork_tag, candidate, upstream_commit, code, existing


def require_higher(code, floor):
    if not floor < code <= ANDROID_MAX_VERSION_CODE:
        raise ValueError(f"Candidate versionCode {code} must exceed distribution floor {floor} and fit Android's limit")


def main():
    repo = os.environ["GITHUB_REPOSITORY"]
    fork_releases = releases(repo)
    latest = latest_stable(UPSTREAM)
    latest_tag = latest["tag_name"]
    if sys.argv[1:] == ["--publish-check"]:
        tag = os.environ["RELEASE_TAG"]
        if tag != f"{latest_tag}-r.1" or was_published(latest_tag, fork_releases):
            raise ValueError(f"{tag} is no longer the latest unpublished fork candidate")
        if official_tag_commit(latest_tag) != os.environ["UPSTREAM_COMMIT"]:
            raise ValueError(f"Upstream tag {latest_tag} moved after planning")
        minimum = max(minimum_version_code(), upstream_version_code(latest))
        require_higher(int(os.environ["RELEASE_VERSION_CODE"]), distribution_floor(repo, fork_releases, minimum))
        print("Publication preflight passed.")
        return

    check_only = os.environ["CHECK_ONLY"] == "true"
    override = os.environ.get("UPSTREAM_START_TAG")
    if override and not re.fullmatch(STABLE, override):
        raise ValueError("UPSTREAM_START_TAG must be a stable tag in vX.Y.Z format")
    if override:
        start = json.loads(run("gh", "api", f"repos/{UPSTREAM}/releases/tags/{override}"))
        if start["draft"] or start["prerelease"]:
            raise ValueError("UPSTREAM_START_TAG must refer to a formal upstream release")
    if version(latest_tag) < version(override or FIRST_TRACKED_TAG) or was_published(latest_tag, fork_releases):
        print(f"No pending fork release for upstream Latest {latest_tag}.")
        return

    tag, candidate, upstream_commit, code, existing = release_candidate(latest, fork_releases, repo)
    if check_only:
        print("Check only: no remote tag or release created. Build workflow was not invoked.")
        return

    if os.environ.get("SIGNING_READY") != "true":
        raise ValueError("Release signing configuration is required before a tag can be created")
    if not existing:
        run("git", "push", "origin", f"{candidate}:refs/tags/{tag}")
    with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
        stream.write(f"release_tag={tag}\nrelease_commit={candidate}\n")
        stream.write(f"upstream_commit={upstream_commit}\nversion_code={code}\n")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, subprocess.CalledProcessError) as error:
        sys.exit(f"::error::{error}")
