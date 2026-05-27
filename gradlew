#!/bin/sh
set -eu

if ! command -v gradle >/dev/null 2>&1; then
  echo "gradlew: gradle is not installed or not in PATH" >&2
  exit 1
fi

exec gradle "$@"
