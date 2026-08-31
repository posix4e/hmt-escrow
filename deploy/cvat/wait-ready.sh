#!/usr/bin/env bash
set -euo pipefail
: "${CVAT_URL:?Set CVAT_URL to the real deployment URL}"
for ((attempt = 0; attempt < 120; attempt++)); do
    if curl --silent --fail --max-time 5 "$CVAT_URL/api/server/about" | \
        jq -e '.version == "2.74.0"' >/dev/null; then
        echo "Real CVAT 2.74.0 is responding at $CVAT_URL"
        exit 0
    fi
    sleep 2
done
echo "CVAT failed to become ready at $CVAT_URL; no fallback will be used" >&2
exit 1
