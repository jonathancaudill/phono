#!/usr/bin/env bash
# Sync vendored :light-ui from a local light-sdk checkout.
set -euo pipefail
SRC="${1:-$HOME/Programming/light-sdk/sdk/ui}"
DEST="$(cd "$(dirname "$0")/.." && pwd)/light-ui"
rsync -a --delete "$SRC/src/" "$DEST/src/"
echo "Synced from $SRC"
echo "Review light-ui/VENDOR_VERSION and keyboard-stripped files before committing."
