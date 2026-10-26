#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
archive="$repo_root/java/adapters-http/build/distributions/tracepermit-simulator-approval-0.1.0.tar.gz"
context="$(mktemp -d /tmp/tracepermit-container-build.XXXXXX)"

cleanup() {
  case "$context" in
    /tmp/tracepermit-container-build.*) rm -rf -- "$context" ;;
    *) echo "Refusing unsafe temporary cleanup." >&2; exit 2 ;;
  esac
}
trap cleanup EXIT

"$repo_root/scripts/build-simulator-approval-distribution.sh"

source_commit="$(git -C "$repo_root" rev-parse HEAD)"
short_commit="$(git -C "$repo_root" rev-parse --short=12 HEAD)"
archive_sha256="$(sha256sum "$archive" | cut -d ' ' -f 1)"
container_image="${TRACEPERMIT_CONTAINER_IMAGE:-tracepermit/simulator-approval:0.1.0-$short_commit}"

cp "$archive" "$context/distribution.tar.gz"
cp "$repo_root/deploy/container/Dockerfile" "$context/Dockerfile"
cp "$repo_root/deploy/container/tracepermit-container-prepare" "$context/tracepermit-container-prepare"
cp "$repo_root/deploy/container/tracepermit-container-healthcheck" "$context/tracepermit-container-healthcheck"

docker build \
  --build-arg "TRACEPERMIT_SOURCE_COMMIT=$source_commit" \
  --build-arg "TRACEPERMIT_ARCHIVE_SHA256=$archive_sha256" \
  --build-arg "TRACEPERMIT_PACKAGE_VERSION=0.1.0" \
  --tag "$container_image" \
  "$context"

echo "TRACEPERMIT_CONTAINER_IMAGE_BUILT=$container_image"
