#!/usr/bin/env bash
#
# Cross-compiles rust/spotify-core for Android and generates the UniFFI Kotlin
# bindings. Run from anywhere; paths are resolved relative to the repo root.
#
# Prerequisites:
#   - Rust toolchain (rustup) with the Android targets installed:
#       rustup target add aarch64-linux-android x86_64-linux-android
#   - cargo-ndk:   cargo install cargo-ndk
#   - Android NDK installed and ANDROID_NDK_HOME (or ANDROID_NDK_ROOT) set.
#
# Output:
#   - app/src/main/jniLibs/{arm64-v8a,x86_64}/libspotify_core.so
#   - app/src/main/java/com/lightphone/spotify/ffi/<generated>.kt

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CRATE_DIR="$REPO_ROOT/rust/spotify-core"
JNILIBS_DIR="$REPO_ROOT/app/src/main/jniLibs"
BINDINGS_DIR="$REPO_ROOT/app/src/main/java"

# arm64-v8a is the Light Phone III; x86_64 is for the emulator.
# Override with e.g. ANDROID_ABIS="arm64-v8a" to skip the emulator ABI.
read -r -a ABIS <<< "${ANDROID_ABIS:-arm64-v8a x86_64}"

echo "==> Cross-compiling spotify-core (release) for: ${ABIS[*]}"
NDK_ARGS=()
for abi in "${ABIS[@]}"; do
    NDK_ARGS+=(-t "$abi")
done

(
    cd "$CRATE_DIR"
    # The librespot feature set (rustls-tls-webpki-roots, rodio-backend,
    # with-libmdns) is pinned in Cargo.toml, so no --features flag is needed.
    cargo ndk "${NDK_ARGS[@]}" -o "$JNILIBS_DIR" \
        --platform 26 \
        build --release
)

# Copy libc++_shared.so next to our lib for each ABI. cpal/AAudio pulls in the
# C++ runtime; bundling it avoids "libc++_shared.so not found" at load time.
copy_libcxx() {
    local ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
    [ -z "$ndk" ] && { echo "WARN: ANDROID_NDK_HOME unset; skipping libc++_shared.so"; return; }
    local host
    case "$(uname -s)" in
        Darwin) host="darwin-x86_64" ;;
        Linux)  host="linux-x86_64" ;;
        *)      host="" ;;
    esac
    local sysroot="$ndk/toolchains/llvm/prebuilt/$host/sysroot/usr/lib"
    declare -A TRIPLE=( [arm64-v8a]=aarch64-linux-android [x86_64]=x86_64-linux-android )
    for abi in "${ABIS[@]}"; do
        local src="$sysroot/${TRIPLE[$abi]}/libc++_shared.so"
        if [ -f "$src" ]; then
            cp -f "$src" "$JNILIBS_DIR/$abi/"
            echo "    copied libc++_shared.so -> $abi"
        fi
    done
}
copy_libcxx

echo "==> Generating UniFFI Kotlin bindings"
# `uniffi-bindgen --library` reads interface metadata embedded in the compiled
# library. The release Android .so is stripped (profile.release strip = true),
# which removes that metadata, so we generate from an UNstripped host build
# instead (the metadata is target-independent).
(
    cd "$CRATE_DIR"
    cargo build
    TARGET_DIR="${CARGO_TARGET_DIR:-$CRATE_DIR/target}"
    HOST_LIB=""
    for ext in dylib so; do
        cand="$TARGET_DIR/debug/libspotify_core.$ext"
        [ -f "$cand" ] && HOST_LIB="$cand" && break
    done
    [ -z "$HOST_LIB" ] && { echo "ERROR: host libspotify_core not found in $TARGET_DIR/debug"; exit 1; }
    cargo run --bin uniffi-bindgen -- generate \
        --library "$HOST_LIB" \
        --language kotlin \
        --out-dir "$BINDINGS_DIR"
)

echo "==> Done."
