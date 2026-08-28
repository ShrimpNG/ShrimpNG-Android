# Sourced by other scripts. Puts platform-tools on PATH when adb isn't installed globally.
# shellcheck shell=bash

if ! command -v adb >/dev/null 2>&1; then
    for candidate in \
        "${ANDROID_HOME:-}/platform-tools" \
        "${ANDROID_SDK_ROOT:-}/platform-tools" \
        "$HOME/Library/Android/sdk/platform-tools" \
        /opt/homebrew/share/android-commandlinetools/platform-tools
    do
        if [[ -x "$candidate/adb" ]]; then
            export PATH="$candidate:$PATH"
            break
        fi
    done
fi

if ! command -v adb >/dev/null 2>&1; then
    echo "adb not found. Install Android SDK platform-tools." >&2
    return 1 2>/dev/null || exit 1
fi
