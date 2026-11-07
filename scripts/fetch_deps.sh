#!/usr/bin/env bash
# Vendor hev-socks5-tunnel and its submodules at a pinned commit.
# The directory is git-ignored; the pinned SHA is documented in docs/VENDORED.md.
set -euo pipefail
cd "$(dirname "$0")/.."
DEST=app/src/main/cpp/hev-socks5-tunnel
SHA="${HEV_SHA:-0c6885060c27134d1bf6a26b1132b6c90f80e4a4}"

clone_with_retry() {
  local i
  for i in 1 2 3; do
    rm -rf "$DEST"
    if git clone --quiet https://github.com/heiher/hev-socks5-tunnel "$DEST"; then
      return 0
    fi
    echo "clone failed (attempt $i/3), retrying..." >&2
    sleep 10
  done
  return 1
}

clone_with_retry

git -C "$DEST" checkout -q "$SHA"
for i in 1 2 3; do
  if git -C "$DEST" submodule update --init --recursive --quiet; then
    break
  fi
  echo "submodule update failed (attempt $i/3), retrying..." >&2
  sleep 10
done

echo "hev-socks5-tunnel pinned $(git -C "$DEST" rev-parse HEAD)"
