#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
: "${CVAT_URL:?Set CVAT_URL to the real deployment URL}"
if [[ -z "${CVAT_TOKEN:-}" && -n "${HPB_CVAT_CREDENTIALS:-}" ]]; then
    CVAT_TOKEN="$(jq -er '.token' "$HPB_CVAT_CREDENTIALS")"
    export CVAT_TOKEN
fi
: "${CVAT_TOKEN:?Supply CVAT_TOKEN or HPB_CVAT_CREDENTIALS; there is no test-server fallback}"
bash "$script_dir/wait-ready.sh"
exec "${GRADLE_EXE:-gradle}" -p "$script_dir/../../kotlin" :harness:realCvatTest --console=plain "$@"
