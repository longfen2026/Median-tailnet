#!/usr/bin/env bash
set -euo pipefail

readonly REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
readonly SOURCE_ROOT="$REPO_ROOT/third_party/libtailscale"
readonly OUTPUT_ROOT="${OUTPUT_ROOT:-$REPO_ROOT/build/libtailscale}"
readonly PATCH_FILE="$REPO_ROOT/tools/patches/libtailscale-android-network.patch"
readonly COMMIT='59d4bb82744915815178e0f0776d60026a397ee7'
readonly EXPECTED_GO='go1.25.5'
readonly EXPECTED_NDK='28.2.13676358'

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

require_command git
require_command go
GO_VERSION="$(go version | awk '{print $3}')"
[[ "$GO_VERSION" == "$EXPECTED_GO" ]] || {
  echo "Expected $EXPECTED_GO, found $GO_VERSION" >&2
  exit 1
}

NDK_ROOT="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
[[ -n "$NDK_ROOT" && -d "$NDK_ROOT" && "${NDK_ROOT##*/}" == "$EXPECTED_NDK" ]] || {
  echo "Expected Android NDK $EXPECTED_NDK via ANDROID_NDK_ROOT or ANDROID_NDK_HOME" >&2
  exit 1
}
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
[[ -d "$TOOLCHAIN" ]] || {
  echo "Android LLVM toolchain is unavailable: $TOOLCHAIN" >&2
  exit 1
}

if [[ ! -d "$SOURCE_ROOT/.git" ]]; then
  rm -rf "$SOURCE_ROOT"
  git clone https://github.com/tailscale/libtailscale.git "$SOURCE_ROOT"
fi
if [[ "$(git -C "$SOURCE_ROOT" rev-parse HEAD)" != "$COMMIT" ]]; then
  git -C "$SOURCE_ROOT" fetch --depth 1 origin "$COMMIT"
  git -C "$SOURCE_ROOT" checkout --detach "$COMMIT"
fi
git -C "$SOURCE_ROOT" reset --hard "$COMMIT"
git -C "$SOURCE_ROOT" clean -fd
[[ -f "$PATCH_FILE" ]] || {
  echo "Missing libtailscale Android network patch: $PATCH_FILE" >&2
  exit 1
}
if ! git -C "$SOURCE_ROOT" apply --check "$PATCH_FILE" >/dev/null 2>&1; then
  echo "libtailscale source does not match the pinned Android network patch" >&2
  exit 1
fi
git -C "$SOURCE_ROOT" apply "$PATCH_FILE"

rm -rf "$OUTPUT_ROOT"
for target in 'arm64-v8a:arm64:aarch64-linux-android26-clang' 'armeabi-v7a:arm:armv7a-linux-androideabi26-clang' 'x86_64:amd64:x86_64-linux-android26-clang'; do
  IFS=':' read -r ABI ARCH COMPILER <<< "$target"
  destination="$OUTPUT_ROOT/$ABI"
  mkdir -p "$destination"
  (
    cd "$SOURCE_ROOT"
    CGO_ENABLED=1 GOOS=android GOARCH="$ARCH" CC="$TOOLCHAIN/$COMPILER" \
      go build -trimpath -buildvcs=false -buildmode=c-shared \
        -ldflags '-extldflags=-Wl,-soname,libtailscale.so' \
        -o "$destination/libtailscale.so" .
    cp tailscale.h "$destination/tailscale.h"
  )
done

find "$OUTPUT_ROOT" -type f -print0 | sort -z | xargs -0 sha256sum