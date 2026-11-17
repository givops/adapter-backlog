#!/usr/bin/env bash
set -euo pipefail
adapter_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# Separate copies and Maven caches prevent concurrent SDK tasks changing build inputs.
cache=${GIVOPS_MAVEN_REPOSITORY:-"$adapter_root/.m2"}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
sdk_source=${GIVOPS_SDK_SOURCE:-}
if [[ -n "$sdk_source" ]]; then
  tar -C "$sdk_source" --exclude=.git --exclude=target --exclude=.m2 --exclude=.sdk -cf - . | tar -C "$work" -xf -
elif [[ -n "${GIVOPS_SDK_ARCHIVE:-}" ]]; then
  expected=${GIVOPS_SDK_ARCHIVE_SHA256:?Set GIVOPS_SDK_ARCHIVE_SHA256 to the reviewed archive hash}
  [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || { echo 'Invalid SDK archive hash.' >&2; exit 1; }
  actual=$(sha256sum "$GIVOPS_SDK_ARCHIVE" | cut -d ' ' -f 1)
  [[ "$actual" == "$expected" ]] || { echo 'SDK archive hash mismatch.' >&2; exit 1; }
  # A source archive is a trusted, reviewed build input. Never accept user-uploaded archives here.
  tar -xzf "$GIVOPS_SDK_ARCHIVE" -C "$work"
else
  revision=$(cat "$adapter_root/sdk-revision.txt")
  [[ "$revision" =~ ^[0-9a-f]{40}$ ]] || {
    echo 'sdk-revision.txt must contain the reviewed 40-character public SDK commit.' >&2; exit 1;
  }
  GIT_TERMINAL_PROMPT=0 git clone --quiet https://github.com/givops/adapter-sdk.git "$work"
  git -C "$work" fetch --quiet origin "$revision"
  git -C "$work" checkout --quiet --detach "$revision"
  [[ $(git -C "$work" rev-parse HEAD) == "$revision" ]]
fi
# Epoch-zero archive mtimes can make Maven omit resources whose output does not exist.
find "$work" -type f -exec touch {} +
mvn -B -Dmaven.repo.local="$cache" -f "$work/pom.xml" clean install
mvn -B -Dmaven.repo.local="$cache" -f "$adapter_root/pom.xml" clean verify
