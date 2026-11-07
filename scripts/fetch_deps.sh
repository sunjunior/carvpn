#!/usr/bin/env bash
# Unpack the vendored hev-socks5-tunnel source tree.
#
# The dependency is shipped as a tarball under third_party/ so builds do not
# depend on the upstream repository history (upstream has rewritten history,
# which makes pinned commit SHAs unreadable). See docs/VENDORED.md.
set -euo pipefail
cd "$(dirname "$0")/.."

DEST=app/src/main/cpp/hev-socks5-tunnel
TARBALL=third_party/hev-socks5-tunnel-0c68850.tar.gz

if [ ! -f "$TARBALL" ]; then
  echo "missing $TARBALL" >&2
  exit 1
fi

rm -rf "$DEST"
mkdir -p app/src/main/cpp
tar -xzf "$TARBALL" -C app/src/main/cpp

if [ ! -f "$DEST/src/hev-jni.c" ]; then
  echo "unexpected tarball layout: $DEST/src/hev-jni.c not found" >&2
  exit 1
fi

echo "hev-socks5-tunnel unpacked from $TARBALL"
