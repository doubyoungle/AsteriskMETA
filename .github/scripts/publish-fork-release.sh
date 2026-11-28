#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0
set -euo pipefail

fail() { echo "::error::$*" >&2; exit 1; }
scripts="$(dirname "$0")"
source "$scripts/release-signer.sh"
actual_version_code="$(git rev-list --count HEAD)"
[[ -z "$RELEASE_VERSION_CODE" || "$RELEASE_VERSION_CODE" == "$actual_version_code" ]] ||
  fail "Packaged commit has a different versionCode than planned."
export RELEASE_VERSION_CODE="$actual_version_code"
expected_commit="${EXPECTED_RELEASE_COMMIT:-$(git rev-parse HEAD)}"

preflight() {
  local tag_ref="refs/tags/$RELEASE_TAG" remote
  remote="$(git ls-remote origin "$tag_ref" "$tag_ref^{}" | awk 'END { print $1 }')"
  [[ "$remote" == "$expected_commit" && "$(git rev-parse HEAD)" == "$expected_commit" ]] ||
    fail "Fork release tag no longer points to the packaged commit."

  if [[ "$AUTOMATIC_RELEASE" == true ]]; then
    python3 "$scripts/follow-upstream-release.py" --publish-check
  else
    local upstream minimum published floor
    upstream="$(gh api "repos/Asterisk4Magisk/AsteriskMETA/releases/tags/${RELEASE_TAG%-r.*}")"
    jq -e '.draft == false and .prerelease == false' <<< "$upstream" >/dev/null ||
      fail "The requested upstream release is not formal."
    minimum="$(python3 "$scripts/fork_release_policy.py" upstream-code <<< "$upstream")"
    floor="$(bash "$scripts/fork-version-floor.sh")"
    if (( floor > minimum )); then minimum="$floor"; fi
    published="$(gh api --paginate --slurp "repos/$GITHUB_REPOSITORY/releases?per_page=100")"
    floor="$(python3 "$scripts/fork_release_policy.py" floor "$GITHUB_REPOSITORY" "$minimum" <<< "$published")"
    (( RELEASE_VERSION_CODE > floor && RELEASE_VERSION_CODE <= 2100000000 )) ||
      fail "Candidate versionCode $RELEASE_VERSION_CODE must exceed distribution floor $floor and fit Android's limit."
  fi
}
preflight

tag="$RELEASE_TAG"
signer="$(jq -er .signerSha256 release-artifacts/update-manifest.json)"
published="$(gh api --paginate --slurp "repos/$GITHUB_REPOSITORY/releases?per_page=100")"
prior_tags="$(jq -r '.[][] | select(.draft == false and any(.assets[]; .name | endswith(".apk"))) | .tag_name' <<< "$published")"
while IFS= read -r prior; do
  [[ -n "$prior" ]] || continue
  manifest="$(gh release download "$prior" --pattern update-manifest.json --output -)"
  prior_signer="$(jq -r '.signerSha256 // empty' <<< "$manifest")"
  if [[ -z "$prior_signer" ]] && [[ "$prior" == v1.3.0-r.1 || "$prior" == v1.3.1-r.1 ]]; then
    prior_signer="$legacy_signer"
  fi
  [[ -n "$prior_signer" ]] || fail "Cannot confirm the signer of previously distributed APKs in $prior."
  [[ "$prior_signer" == "$signer" ]] || fail "Signer differs from previously distributed APKs in $prior."
done <<< "$prior_tags"

existing="$(jq -c --arg tag "$tag" 'first(.[][] | select(.tag_name == $tag)) // empty' <<< "$published")"
if [[ -z "$existing" ]]; then
  gh release create "$tag" --verify-tag --draft --title "AsteriskMETA ${tag#v}" \
    --generate-notes --notes "Version code: $RELEASE_VERSION_CODE"
else
  jq -e '.draft == true' <<< "$existing" >/dev/null || fail "Release $tag is already published."
fi

gh release upload "$tag" release-artifacts/*.apk release-artifacts/update-manifest.json --clobber
gh release view "$tag" --json isDraft,assets > "$RUNNER_TEMP/fork-draft.json"
python3 - "$RUNNER_TEMP/fork-draft.json" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

draft = json.loads(Path(sys.argv[1]).read_text())
expected = {}
for path in Path("release-artifacts").iterdir():
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    expected[path.name] = (path.stat().st_size, f"sha256:{digest}")
uploaded = {
    asset["name"]: (asset["size"], asset["digest"])
    for asset in draft["assets"] if asset["state"] == "uploaded"
}
if not draft["isDraft"] or len(draft["assets"]) != len(expected) or uploaded != expected:
    sys.exit("::error::The draft does not contain exactly the verified APKs and the manifest.")
PY
preflight
gh release edit "$tag" --draft=false --latest
