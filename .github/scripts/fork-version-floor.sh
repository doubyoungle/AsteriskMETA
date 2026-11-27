#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0
set -euo pipefail

# 624 is the highest signed test build before the tracker was introduced.
floor=624
git fetch --no-tags origin '+refs/heads/*:refs/remotes/origin/*'
while IFS= read -r ref; do
  count="$(git rev-list --count "$ref")"
  if (( count > floor )); then floor="$count"; fi
done < <(git for-each-ref --format='%(refname)' refs/remotes/origin)
printf '%s\n' "$floor"
