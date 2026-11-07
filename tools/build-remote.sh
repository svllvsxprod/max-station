#!/bin/sh
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
mkdir -p "$repo/out"
"${ZIG:-zig}" cc -target aarch64-linux-musl -static -Os -s -Wall -Wextra -Werror \
  "$repo/remote/remote-hold.c" -o "$repo/out/max-station-remote"
