#!/usr/bin/env bash
#
# Builds the release APKs and collects them into ./releases.
#
# By default all three are universal — arm64-v8a and armeabi-v7a in one APK: ShrimpNG, the
# FOSS-Calculator disguise (both Android 12+), and the legacy build for Android 8–11 and Huawei
# (ShrimpNG palette, no Monet). Given an ABI instead, it builds ShrimpNG and FOSS-Calculator for
# that ABI alone, as before; the legacy build is universal only and is left out.
#
# The builds are the same variant with different -P values, so AGP writes them to the same
# output directory and wipes it in between. They therefore have to be built one at a time and
# copied out after each run, rather than left to accumulate there.
#
# Installing over wireless/USB adb is opt-in: --install puts shrimpng-latest.apk on the device,
# --install-disguised the disguised build. --no-install is still accepted and does nothing.
#
# Usage:
#   ./build-release.sh [abi] [--install | --install-disguised]
#   (no abi: universal)

set -euo pipefail

ABI=""
DO_INSTALL=0
INSTALL_DISGUISED=0

for arg in "$@"; do
    case "$arg" in
        --install) DO_INSTALL=1 ;;
        --no-install) DO_INSTALL=0 ;;
        --install-disguised) DO_INSTALL=1; INSTALL_DISGUISED=1 ;;
        -*)
            echo "Unknown flag: $arg" >&2
            exit 1
            ;;
        *) ABI="$arg" ;;
    esac
done

ROOT="$(cd "$(dirname "$0")" && pwd)"
GRADLE_DIR="$ROOT/V2rayNG"
SRC_DIR="$GRADLE_DIR/app/build/outputs/apk/playstore/release"
OUT_DIR="$ROOT/releases"

# Neither machine has a usable system JDK 17 (macOS: none; Fedora 44: no java-17 package), so
# fall back to Homebrew's or a Temurin unpacked into ~/.jdks when JAVA_HOME is unset.
if [[ -z "${JAVA_HOME:-}" ]]; then
    for candidate in /opt/homebrew/Cellar/openjdk@17/*/ "$HOME"/.jdks/jdk-17*/; do
        [[ -x "$candidate/bin/java" ]] && export JAVA_HOME="${candidate%/}" && break
    done
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
    echo "No JDK found. Set JAVA_HOME to a JDK 17 install." >&2
    exit 1
fi

mkdir -p "$OUT_DIR"

build_variant() {
    local label="$1"
    local latest_name="$2"
    shift 2
    echo
    echo "==> Building $label"
    if [[ -z "$ABI" ]]; then
        ( cd "$GRADLE_DIR" && ./gradlew assemblePlaystoreRelease -PUNIVERSAL=true "$@" )
    else
        ( cd "$GRADLE_DIR" && ./gradlew assemblePlaystoreRelease -PABI_FILTERS="$ABI" "$@" )
    fi

    local produced
    produced=$(find "$SRC_DIR" -maxdepth 1 -name '*.apk' -print -quit)
    if [[ -z "$produced" ]]; then
        echo "$label produced no APK in $SRC_DIR" >&2
        exit 1
    fi
    cp "$produced" "$OUT_DIR/"
    echo "    -> $OUT_DIR/$(basename "$produced")"

    # A stable filename to hand out, so a link to it doesn't go stale on every version bump.
    # Single-ABI builds get their own suffix, so they never overwrite the universal "latest"
    # that people have links to.
    local latest="$latest_name"
    if [[ -n "$ABI" ]]; then
        latest="${latest_name%.apk}-${ABI}.apk"
    fi
    cp "$produced" "$OUT_DIR/$latest"
    echo "    -> $OUT_DIR/$latest"
}

# Best-effort: reconnect wireless adb if we have a saved endpoint, then adb install -r.
install_latest() {
    local apk="$1"
    # shellcheck source=scripts/adb-env.sh
    if ! source "$ROOT/scripts/adb-env.sh"; then
        echo "==> Skip install: adb not found"
        return 0
    fi

    if [[ -f "$ROOT/.adb-wireless" ]]; then
        local endpoint
        endpoint="$(tr -d '[:space:]' <"$ROOT/.adb-wireless")"
        if ! adb devices | awk 'NR>1 && $2=="device" { found=1 } END { exit !found }'; then
            echo "==> Reconnecting wireless adb ($endpoint)"
            adb connect "$endpoint" >/dev/null || true
        fi
    fi

    if ! adb devices | awk 'NR>1 && $2=="device" { found=1 } END { exit !found }'; then
        echo "==> Skip install: no adb device (pair once with ./scripts/adb-wireless.sh)"
        adb devices -l || true
        return 0
    fi

    echo
    echo "==> Installing $(basename "$apk")"
    adb install -r "$apk"
}

build_variant "ShrimpNG (standard)" "shrimpng-latest.apk"
build_variant "FOSS-Calculator (disguised)" "foss-calculator-latest.apk" -PDISGUISED=true
if [[ -z "$ABI" ]]; then
    build_variant "ShrimpNG Legacy (Android 8–11, Huawei)" "shrimpng-legacy-latest.apk" -PLEGACY=true
fi

echo
echo "Release APKs in $OUT_DIR:"
ls -lh "$OUT_DIR"/*.apk

if [[ "$DO_INSTALL" -eq 1 ]]; then
    if [[ "$INSTALL_DISGUISED" -eq 1 ]]; then
        install_latest "$OUT_DIR/foss-calculator-latest.apk"
    else
        install_latest "$OUT_DIR/shrimpng-latest.apk"
    fi
fi
