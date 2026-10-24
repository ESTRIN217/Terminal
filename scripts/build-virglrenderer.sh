#!/usr/bin/env bash
#
# build-virglrenderer.sh — compiles the native VirGL server (spec 013, RF-1).
#
# Produces app/src/main/assets/arm64-v8a/virgl_test_server: an aarch64 binary
# built with the repo's own NDK (the proot pipeline convention), statically
# linked against libepoxy/libdrm and depending only on Android system libs
# (libc, liblog, libm, libdl). Nothing is downloaded at app runtime: the Debian
# rootfs (spec DebianInstaller) stays the only downloaded artifact, and the
# pipeline itself re-downloads only these pinned tarballs in /tmp.
#
# Pins (constitución #5 — nothing without a pin):
#   virglrenderer  tag virglrenderer-1.2.0, commit 500b41d5c8638f9b80dd558f4044f3301c7457a4
#   libepoxy       tag 1.5.10 (GitHub mirror: the freedesktop GitLab anon archive
#                  URLs redirect to a sign-in page, so a mirror is required)
#   libdrm         2.4.120 (dri.freedesktop.org release tarball)
#
# SHA-256 of the pinned tarballs:
#   70e39532f272531abf38e5ff69ec24fa8006e5919300e5a3b1256b37dc5e2050  virglrenderer-1.2.0.tar.gz
#   a7ced37f4102b745ac86d6a70a9da399cc139ff168ba6b8002b4d8d43c900c15  libepoxy-1.5.10.tar.gz
#   3bf55363f76c7250946441ab51d3a6cc0ae518055c0ff017324ab76cdefb327a  libdrm-2.4.120.tar.xz
#
# Source modifications, all documented and visible in scripts/virgl-patches/:
#   001-egl-without-gbm-meson.patch           EGL availability on Android (no Mesa gbm)
#   002-egl-android-surfaceless-winsys.patch  EGL init surfaceless without GBM (vrend_winsys.c)
# plus scripts/ndk-shims/include/ for two Android-internal headers that the NDK
# does not ship (log/log.h, cutils/properties.h), placed on the include path by
# the cross file below.
#
# Requirements on the host: curl, tar, xz, meson 1.x, ninja, pkg-config, python3
# with the yaml module (Debian: meson python3-yaml), Android NDK 30.0.14904198
# via ANDROID_NDK_ROOT (or ANDROID_SDK_ROOT/ndk/<version>).
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${VIRGL_BUILD_WORK:-/tmp/terminal-virgl-build}"
PREFIX="$WORK/prefix"
BUILDDIR="$WORK/build"
SRC="$WORK/src"
ASSETS_OUT="$REPO_ROOT/app/src/main/assets/arm64-v8a/virgl_test_server"

VIRGL_TARBALL="virglrenderer-1.2.0.tar.gz"
VIRGL_URL="https://gitlab.freedesktop.org/virgl/virglrenderer/-/archive/virglrenderer-1.2.0/virglrenderer-1.2.0.tar.gz"
VIRGL_SHA256="70e39532f272531abf38e5ff69ec24fa8006e5919300e5a3b1256b37dc5e2050"
VIRGL_DIRNAME="virglrenderer-virglrenderer-1.2.0-500b41d5c8638f9b80dd558f4044f3301c7457a4"

EPOXY_TARBALL="libepoxy-1.5.10.tar.gz"
EPOXY_URLS=(
    "https://github.com/kraj/libepoxy/archive/refs/tags/1.5.10.tar.gz"
    "https://github.com/anholt/libepoxy/archive/refs/tags/1.5.10.tar.gz"
)
EPOXY_SHA256="a7ced37f4102b745ac86d6a70a9da399cc139ff168ba6b8002b4d8d43c900c15"
EPOXY_DIRNAME="libepoxy-1.5.10"

DRM_TARBALL="libdrm-2.4.120.tar.xz"
DRM_URL="https://dri.freedesktop.org/libdrm/libdrm-2.4.120.tar.xz"
DRM_SHA256="3bf55363f76c7250946441ab51d3a6cc0ae518055c0ff017324ab76cdefb327a"
DRM_DIRNAME="libdrm-2.4.120"

log() { printf '[build-virglrenderer] %s\n' "$*"; }
die() { printf '[build-virglrenderer] ERROR: %s\n' "$*" >&2; exit 1; }

for tool in curl tar xz meson ninja pkg-config python3; do
    command -v "$tool" >/dev/null 2>&1 || die "missing tool: $tool (Debian: apt install meson python3-yaml ninja-build pkg-config curl tar xz-utils)"
done
python3 -c 'import yaml' 2>/dev/null || die "python3 needs the yaml module (Debian: apt install python3-yaml)"

# NDK 30.0.14904198 (AGENTS.md): env var first, then the default SDK layout.
NDK="${ANDROID_NDK_ROOT:-${ANDROID_SDK_ROOT:-/root/android-sdk}/ndk/30.0.14904198}"
[ -d "$NDK" ] || die "NDK not found at \"$NDK\" (set ANDROID_NDK_ROOT)"
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -x "$CC/aarch64-linux-android24-clang" ] || die "$CC/aarch64-linux-android24-clang missing"

log "work dir: $WORK"
mkdir -p "$WORK/pinned" "$SRC" "$PREFIX" "$BUILDDIR"

# fetch_pin <name> <outfile> sha256 url...
fetch_pin() {
    local name="$1" out="$2" sha="$3"; shift 3
    if [ -f "$out" ]; then
        log "already pinned: $(basename "$out")"
    else
        local url
        for url in "$@"; do
            log "downloading $name from $url"
            if curl -fsL --max-time 300 -o "$out" "$url"; then
                if echo "$sha  $out" | sha256sum -c - >/dev/null 2>&1; then
                    log "sha256 verified: $(basename "$out")"
                    return 0
                fi
                rm -f "$out"
                log "sha256 mismatch on $url, trying next mirror"
            else
                log "download failed on $url, trying next mirror"
            fi
        done
        die "no mirror served a tarball with the pinned sha256 for $name"
    fi
}

fetch_pin libepoxy      "$WORK/pinned/$EPOXY_TARBALL"  "$EPOXY_SHA256"     "${EPOXY_URLS[@]}"
fetch_pin libdrm        "$WORK/pinned/$DRM_TARBALL"    "$DRM_SHA256"       "$DRM_URL"
fetch_pin virglrenderer "$WORK/pinned/$VIRGL_TARBALL"  "$VIRGL_SHA256"     "$VIRGL_URL"

# extract_once <tarball> <dirname>
extract_once() {
    local tarball="$1" dirname="$2"
    if [ ! -d "$SRC/$dirname" ]; then
        log "extracting $(basename "$tarball")"
        case "$tarball" in
            *.tar.gz) tar xzf "$tarball" -C "$SRC" ;;
            *.tar.xz) tar xJf "$tarball" -C "$SRC" ;;
            *) die "unknown tarball type: $tarball" ;;
        esac
    fi
    [ -d "$SRC/$dirname" ] || die "missing extracted dir $dirname"
}
extract_once "$WORK/pinned/$EPOXY_TARBALL" "$EPOXY_DIRNAME"
extract_once "$WORK/pinned/$DRM_TARBALL"   "$DRM_DIRNAME"
extract_once "$WORK/pinned/$VIRGL_TARBALL" "$VIRGL_DIRNAME"

# Apply the two documented patches (idempotent: the marker text is the change).
VIRGL_SRC="$SRC/$VIRGL_DIRNAME"
apply_patch() { # relative-file patch-file
    local rel="$1" patch="$2"
    if grep -q "ESTRIN217 virgl-android fork (spec 013 T6" "$VIRGL_SRC/$rel"; then
        log "already patched: $rel"
        return 0
    fi
    (cd "$VIRGL_SRC" && git apply --unsafe-paths "$patch") \
        || (cd "$VIRGL_SRC" && patch -u -p0 -i "$patch") \
        || die "failed to apply $(basename "$patch") on $rel"
}
apply_patch meson.build               "$REPO_ROOT/scripts/virgl-patches/001-egl-without-gbm-meson.patch"
apply_patch src/vrend/vrend_winsys.c  "$REPO_ROOT/scripts/virgl-patches/002-egl-android-surfaceless-winsys.patch"

# Cross file: API-24 wrapper toolchain, static dependency libraries, and the
# repo shim headers on the include path (the NDK lacks log/log.h and
# cutils/properties.h; see scripts/ndk-shims/include for the exact mapping).
SHIM="$REPO_ROOT/scripts/ndk-shims/include"
CROSS="$BUILDDIR/cross-aarch64.ini"
cat > "$CROSS" <<EOF
[binaries]
c = '$CC/aarch64-linux-android24-clang'
cpp = '$CC/aarch64-linux-android24-clang++'
ar = '$CC/llvm-ar'
strip = '$CC/llvm-strip'
pkg-config = '$(command -v pkg-config)'

[host_machine]
system = 'linux'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[paths]
prefix = '$PREFIX'

[built-in options]
c_args = ['-fPIC', '-Os', '-I$SHIM']
cpp_args = ['-fPIC', '-Os', '-I$SHIM']
c_link_args = ['-fPIC', '-llog']
default_library = 'static'
EOF

# build_install <name> <srcdir> [meson-args...]
build_install() {
    local name="$1" srcdir="$2"; shift 2
    if [ -f "$BUILDDIR/$name/build.ninja" ]; then
        log "reusing configured $name"
    else
        log "configuring $name"
        PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig" meson setup "$BUILDDIR/$name" "$srcdir" \
            --cross-file "$CROSS" "$@" > "$BUILDDIR/$name-setup.log" 2>&1 \
            || { tail -30 "$BUILDDIR/$name-setup.log"; die "meson setup failed for $name"; }
    fi
    ninja -C "$BUILDDIR/$name" > "$BUILDDIR/$name-build.log" 2>&1 \
        || { tail -40 "$BUILDDIR/$name-build.log"; die "ninja failed for $name"; }
    ninja -C "$BUILDDIR/$name" install > "$BUILDDIR/$name-install.log" 2>&1 \
        || { tail -30 "$BUILDDIR/$name-install.log"; die "ninja install failed for $name"; }
    log "installed $name into $PREFIX"
}

build_install epoxy         "$SRC/$EPOXY_DIRNAME"  -Dtests=false -Ddocs=false -Dglx=no
build_install libdrm        "$SRC/$DRM_DIRNAME"    -Damdgpu=disabled -Dradeon=disabled -Dnouveau=disabled \
                            -Dvmwgfx=disabled -Dfreedreno=disabled -Detnaviv=disabled -Dintel=disabled -Dtests=false
build_install virglrenderer "$VIRGL_SRC"           -Dplatforms=egl -Dvideo=false -Dtracing=none \
                            -Dtests=false -Dvenus=false -Dcheck-gl-errors=false

BIN="$BUILDDIR/virglrenderer/vtest/virgl_test_server"
[ -x "$BIN" ] || die "expected binary not produced: $BIN"

log "checking dynamic dependencies (Android system libraries only)"
NEEDED="$("$CC/llvm-readelf" -d "$BIN" | grep NEEDED | awk -F'[][]' '{print $2}' | sort -u)"
log "NEEDED: $(echo "$NEEDED" | tr '\n' ' ')"
while IFS= read -r lib; do
    [ -z "$lib" ] && continue
    case "$lib" in
        libc.so|libm.so|libdl.so|liblog.so) ;;
        *) die "unexpected dynamic dependency on Android: $lib" ;;
    esac
done <<< "$NEEDED"

log "stripping and publishing to assets"
"$CC/llvm-strip" "$BIN"
mkdir -p "$(dirname "$ASSETS_OUT")"
cp "$BIN" "$ASSETS_OUT"
chmod 0644 "$ASSETS_OUT"
"$CC/llvm-readelf" -h "$ASSETS_OUT" | grep -q "Machine:.*AArch64" || die "published asset is not AArch64"

log "done: $ASSETS_OUT ($(wc -c < "$ASSETS_OUT" | sed 's/^ *//') bytes)"
log "next: ./gradlew assembleDebug && unzip -l <apk> | grep virgl_test_server"
