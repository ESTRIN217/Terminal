#!/bin/sh
# Rebuild the tracked prebuilt (app/src/main/assets/debian/termux-linkfix.so).
# Needs: aarch64 glibc cross toolchain (e.g. Debian/Ubuntu gcc-aarch64-linux-gnu).
# Usage: ./build.sh   (run from this directory)
set -eu
CC="${CC:-aarch64-linux-gnu-gcc}"
SRC="$(dirname "$0")/linkfix.c"
OUT="$(dirname "$0")/../../../main/assets/debian/termux-linkfix.so"
mkdir -p "$(dirname "$OUT")"
"$CC" -shared -fPIC -O2 -Wall -Wextra -o "$OUT" "$SRC"
echo "--- file ---"
file "$OUT"
echo "--- NEEDED (must be only libc.so.6) ---"
readelf -d "$OUT" | grep -E 'NEEDED|TEXTREL' || true
echo "--- newest GLIBC version refs (must fit trixie glibc 2.41) ---"
readelf --version-info "$OUT" 2>/dev/null | grep -o 'GLIBC_[0-9.]*' | sort -Vu | tail -3 || true
