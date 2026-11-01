#!/usr/bin/env bash
# One demo group in its own game dir, so several run in parallel under `wb job run` (4 client slots).
# Usage: dev/demo/par.sh <mc> <tag> <scenes>
HERE=$(cd "$(dirname "$0")" && pwd)
MC=$1 WORK_TAG=$2 SKIP_BUILD=1 TIMEOUT=2400 exec "$HERE/run.sh" "/tmp/bb-release/$1-$2" "$3"
