#!/usr/bin/env bash
set -euo pipefail

# An actual upstream CVAT deployment, not an API imitation.
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
state_dir="${HPB_CVAT_STATE_DIR:-$script_dir/../../.local/cvat}"
upstream="$state_dir/upstream"
revision=c494299bbd225d6d0fc5e8a5e2668447abf50d70
export CVAT_VERSION=v2.74.0
export CVAT_HOST="${CVAT_HOST:-localhost}"

if [[ ! -d "$upstream/.git" ]]; then
    mkdir -p "$state_dir"
    git clone --depth 1 --branch "$CVAT_VERSION" https://github.com/cvat-ai/cvat.git "$upstream"
fi
[[ "$(git -C "$upstream" rev-parse HEAD)" == "$revision" ]] || {
    echo "CVAT checkout is not the pinned $revision; refusing to run" >&2
    exit 1
}
[[ -z "$(git -C "$upstream" status --porcelain --untracked-files=no)" ]] || {
    echo "CVAT upstream checkout has edits; refusing to run" >&2
    exit 1
}
exec docker compose --project-name hpb-cvat \
    --project-directory "$upstream" \
    -f "$upstream/docker-compose.yml" -f "$script_dir/compose.override.yml" "$@"
