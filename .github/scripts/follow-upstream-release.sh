#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0
set -euo pipefail

upstream=Asterisk4Magisk/AsteriskMETA
fail() { echo "::error::$*" >&2; exit 1; }

release="$(gh api "repos/$upstream/releases/latest")"
if ! jq -e '.draft == false and .prerelease == false' <<< "$release" >/dev/null; then
  echo "Upstream has no eligible formal release."
  exit 0
fi
upstream_tag="$(jq -er '.tag_name' <<< "$release")"
if [[ ! "$upstream_tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
  fail "Cannot use upstream release tag $upstream_tag. Expected vMAJOR.MINOR.PATCH."
fi
upstream_version="${upstream_tag#v}"
if jq -en --arg version "$upstream_version" '($version | split(".") | map(tonumber)) < [1,3,1]' >/dev/null; then
  echo "Upstream latest is $upstream_tag. Automatic releases start at v1.3.1. No tag or release created."
  exit 0
fi
fork_tag="$upstream_tag-r.1"

published="$(gh api --paginate --slurp "repos/$GITHUB_REPOSITORY/releases?per_page=100")"
if jq -e --arg tag "$fork_tag" --arg version "$upstream_version" '
  ($version | split(".") | map(tonumber)) as $target |
  any(.[][]; .draft == false and .prerelease == false and
    (.tag_name == $tag or
      (.tag_name | capture("^v(?<base>[0-9]+\\.[0-9]+\\.[0-9]+)-r\\.[1-9][0-9]*$").base |
      split(".") | map(tonumber) | . >= $target)))
' <<< "$published" >/dev/null; then
  echo "Fork already published $fork_tag, another revision, or a later stable version."
  exit 0
fi

fork_source="$(git rev-parse HEAD)"
git fetch --no-tags "https://github.com/$upstream.git" \
  '+refs/heads/main:refs/remotes/upstream/main' \
  "refs/tags/$upstream_tag:refs/upstream-release/$upstream_tag"
upstream_commit="$(git rev-parse --verify "refs/upstream-release/$upstream_tag^{commit}")"
base="$(git merge-base --all "$fork_source" refs/remotes/upstream/main)" ||
  fail "Cannot find the common base of fork main and upstream main."
[[ "$base" != *$'\n'* ]] || fail "Fork and upstream have multiple merge bases."
git merge-base --is-ancestor eb8da386a006c1ac5a61ff9055d8dc1a1a056914 "$base" ||
  fail "The common base predates the original fork point. Confirm upstream history before releasing."
source_version="$(git show "$upstream_commit:buildSrc/src/main/kotlin/BuildConfig.kt" |
  awk -F '"' '/^[[:space:]]*const val VERSION_NAME = "/ { print $2; exit }')"
[[ "$source_version" == "$upstream_version" ]] ||
  fail "Upstream $upstream_tag points to $upstream_commit with source version '$source_version'."

work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
if git show-ref --verify --quiet "refs/tags/$fork_tag"; then
  candidate="$(git rev-parse "refs/tags/$fork_tag^{commit}")"
  [[ "$(git rev-parse "$candidate^1")" == "$upstream_commit" ]] &&
    git show -s --format=%B "$candidate" | grep -Fx "Upstream-release: $upstream_tag" >/dev/null ||
    fail "Existing tag $fork_tag does not identify the expected upstream source."
  git merge-base --is-ancestor "$candidate^2" "$fork_source" ||
    fail "Existing tag $fork_tag has an unexpected fork source parent."
  echo "Reusing existing tag $fork_tag at $candidate."
else
  git diff --binary --full-index "$base" "$fork_source" > "$work/fork.patch"
  [[ -s "$work/fork.patch" ]] || fail "The fork overlay is empty."
  git switch --detach "$upstream_commit"
  git apply --3way --index "$work/fork.patch" ||
    fail "Fork changes conflict with upstream $upstream_tag. No tag was created."
  sed 's/^asteriskMetaForkRevision=.*/asteriskMetaForkRevision=1/' gradle.properties > "$work/gradle.properties"
  cat "$work/gradle.properties" > gradle.properties
  grep -Fxq 'asteriskMetaForkRevision=1' gradle.properties ||
    fail "Fork revision configuration is missing from the release tree."
  git add gradle.properties
  if ! grep -Fq 'AppUpdateSection()' app/src/main/kotlin/features/about/AboutPage.kt ||
      ! grep -Fq 'LocalActivityResultRegistryOwner provides activity' app/src/main/kotlin/features/updater/AppUpdateSection.kt; then
    fail "Release tree is missing the updater entry or the About crash fix."
  fi
  tree="$(git write-tree)"
  candidate="$(git -c user.name='github-actions[bot]' \
    -c user.email='41898282+github-actions[bot]@users.noreply.github.com' \
    commit-tree "$tree" -p "$upstream_commit" -p "$fork_source" \
    -m "Prepare AsteriskMETA $fork_tag" -m "Upstream-release: $upstream_tag")"
fi

version="$(git show "$candidate:buildSrc/src/main/kotlin/BuildConfig.kt" |
  awk -F '"' '/^[[:space:]]*const val VERSION_NAME = "/ { print $2; exit }')"
[[ "$version" == "$upstream_version" ]] || fail "Fork changes altered the upstream source version."
code="$(git rev-list --count "$candidate")"
floor="$(bash "$GITHUB_WORKSPACE/.github/scripts/fork-version-floor.sh")"
(( code > floor && code <= 2100000000 )) ||
  fail "Candidate versionCode $code must exceed the signed test build floor $floor and fit Android's versionCode limit."
echo "Upstream $upstream_tag at $upstream_commit; fork $fork_tag at $candidate; versionCode $code (floor $floor)."
if [[ "$CHECK_ONLY" != false ]]; then
  echo "Check only. No tag or release created."
  exit 0
fi
if ! git show-ref --verify --quiet "refs/tags/$fork_tag"; then
  git push origin "$candidate:refs/tags/$fork_tag"
fi
printf 'release_tag=%s\n' "$fork_tag" >> "$GITHUB_OUTPUT"
