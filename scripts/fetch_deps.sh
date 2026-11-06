#!/usr/bin/env bash
# vendor 依赖：hev-socks5-tunnel（含子模块；gitignore 不入库；锚定 SHA 见 docs/VENDORED.md）
set -euo pipefail
cd "$(dirname "$0")/.."
DEST=app/src/main/cpp/hev-socks5-tunnel
SHA="${HEV_SHA:-0c6885060c27134d1bf6a26b1132b6c90f80e4a4}"   # 默认 = VENDORED.md 锚定版本
rm -rf "$DEST"
git clone https://github.com/heiher/hev-socks5-tunnel "$DEST"
git -C "$DEST" checkout -q "$SHA"
git -C "$DEST" submodule update --init --recursive
echo "hev-socks5-tunnel pinned $(git -C "$DEST" rev-parse HEAD)"
