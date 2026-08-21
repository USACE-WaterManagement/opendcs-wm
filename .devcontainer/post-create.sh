#!/usr/bin/env bash
set -euo pipefail

workspace="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
git config --global --add safe.directory "$workspace"

java -version
go version
docker version --format 'Docker client {{.Client.Version}}'
"$workspace/algorithms/gradlew" --version

# Prime the small Go module so appstarter is ready to build and test immediately.
go -C "$workspace/appstarter" mod download
