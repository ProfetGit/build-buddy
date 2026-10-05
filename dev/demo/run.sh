#!/usr/bin/env bash
# Runs the scripted ghost scenes in the real client, off-screen (headless KWin via ModTest/client.py): stills, checks, frame times.
# Usage: dev/demo/run.sh <out> [scene,scene...]   (scenes: house, perf)
# Env: PERF (perf builds, e.g. "solid:80,shell:160:2,noise:100"), PERF_EDIT=1 (measure with the handles showing), MEASURE (frames per timing, 300), FPS (260 = unlimited),
#      WIDTH/HEIGHT (1280x720), XMX (3G; big builds need more), MODS (extra jars, colon-separated), FABRIC_API=0 (on by default now),
#      WORK_TAG (own game dir, for parallel runs), SKIP_BUILD=1, TIMEOUT (s), PROFILE=1 (the user's Fabric 26.3 mods;
#      EXCLUDE=regex drops some), SHADERS=1 (Complementary Reimagined, with PROFILE=1 for Iris).
#      Gradle always runs on /usr/lib/jvm/java-25-openjdk.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
WS=$(cd "$ROOT/../.." && pwd)
OUT=$(realpath -m "${1:?usage: run.sh <out> [scenes]}")
SCENES=${2:-house}
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
if [ -z "${SKIP_BUILD:-}" ]; then (cd "$ROOT" && ./gradlew -q --console=plain build --offline); fi
JAR=$(ls -t "$ROOT"/build/libs/cyanotype-*.jar | grep -v -- -sources | head -1)
WORLD="$HERE/.work/world-26.3"
args=(26.3 fabric "$OUT" --game "$HERE/.work/game${WORK_TAG:+-$WORK_TAG}" --jar "$JAR" --world "$WORLD"
      -D "cyanotype.demo=$OUT" -D "cyanotype.demo.scenes=$SCENES" -D "cyanotype.demo.measure=${MEASURE:-300}"
      --opt "fps=${FPS:-260}" --opt volume=0.0001 --opt render_distance=8 --opt "xmx=${XMX:-3G}" --width "${WIDTH:-1280}" --height "${HEIGHT:-720}"
      --user Builder --log-errors "Cyanotype.*(cannot|failed|error)" --timeout "${TIMEOUT:-900}" --label "cyanotype $SCENES")
[ -n "${PERF:-}" ] && args+=(-D "cyanotype.demo.perf=$PERF")
[ -n "${PERF_EDIT:-}" ] && args+=(-D "cyanotype.demo.perf.edit=true")
# the mod needs Fabric API (its resource loader serves the art and sounds), so every run has it unless FABRIC_API=0
[ "${FABRIC_API:-1}" != "0" ] && args+=(--fabric-api)
if [ -n "${MODS:-}" ]; then IFS=: read -ra extra <<< "$MODS"; for m in "${extra[@]}"; do args+=(--mod "$m"); done; fi
if [ -n "${SHADERS:-}" ]; then args+=(--shaders "$HOME/.local/share/ModrinthApp/profiles/Fabric 26.3/shaderpacks/ComplementaryReimagined_r5.8.1.zip"); fi
if [ -n "${PROFILE:-}" ]; then
  P="$HOME/.local/share/ModrinthApp/profiles/Fabric 26.3/mods"
  for m in "$P"/*.jar; do
    case "$(basename "$m")" in cyanotype*) continue ;; esac
    [ -n "${EXCLUDE:-}" ] && basename "$m" | grep -qiE "$EXCLUDE" && continue
    args+=(--mod "$m")
  done
fi
exec python3 "$WS/tools/ModTest/client.py" "${args[@]}"
