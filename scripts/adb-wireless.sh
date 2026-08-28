#!/usr/bin/env bash
#
# One-time / reconnect helpers for wireless adb.
#
# Phone (Android 11+):
#   Settings → Developer options → Wireless debugging → ON
#   "Pair device with pairing code" → note IP:port + 6-digit code
#   Main Wireless debugging screen → note the other IP:port (connect port)
#
# Then on the Mac:
#   ./scripts/adb-wireless.sh pair 192.168.1.10:37123 123456
#   ./scripts/adb-wireless.sh connect 192.168.1.10:41235
#
# After that, reconnect is just:
#   ./scripts/adb-wireless.sh reconnect
#
# Usage:
#   ./scripts/adb-wireless.sh pair HOST:PORT CODE
#   ./scripts/adb-wireless.sh connect HOST:PORT
#   ./scripts/adb-wireless.sh reconnect
#   ./scripts/adb-wireless.sh status
#   ./scripts/adb-wireless.sh devices

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=adb-env.sh
source "$ROOT/scripts/adb-env.sh"

STATE_FILE="$ROOT/.adb-wireless"

cmd="${1:-status}"

case "$cmd" in
    pair)
        endpoint="${2:-}"
        code="${3:-}"
        if [[ -z "$endpoint" || -z "$code" ]]; then
            echo "Usage: $0 pair HOST:PORT CODE" >&2
            exit 1
        fi
        adb pair "$endpoint" "$code"
        ;;
    connect)
        endpoint="${2:-}"
        if [[ -z "$endpoint" ]]; then
            echo "Usage: $0 connect HOST:PORT" >&2
            exit 1
        fi
        adb connect "$endpoint"
        printf '%s\n' "$endpoint" >"$STATE_FILE"
        echo "Saved $endpoint to $STATE_FILE"
        adb devices -l
        ;;
    reconnect)
        if [[ ! -f "$STATE_FILE" ]]; then
            echo "No saved endpoint. Run: $0 connect HOST:PORT" >&2
            exit 1
        fi
        endpoint="$(tr -d '[:space:]' <"$STATE_FILE")"
        echo "Connecting to $endpoint ..."
        adb connect "$endpoint"
        adb devices -l
        ;;
    status|devices)
        if [[ -f "$STATE_FILE" ]]; then
            echo "Saved endpoint: $(tr -d '[:space:]' <"$STATE_FILE")"
        else
            echo "Saved endpoint: (none)"
        fi
        adb devices -l
        ;;
    *)
        echo "Unknown command: $cmd" >&2
        echo "Usage: $0 {pair|connect|reconnect|status}" >&2
        exit 1
        ;;
esac
