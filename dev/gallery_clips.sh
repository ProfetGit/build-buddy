#!/bin/bash
# Re-encodes the showcase WebP clips under CurseForge's 2 MB gallery cap: steps down fps, width and quality until it fits.
cd "$(dirname "$0")/../release-art" || exit 1
S=../docs/clips
O="$HOME/Projects/Minecraft Datapacks/media/Showcase/out"
mkdir -p gallery/clips
enc() {
  for cfg in "30 640 60" "30 560 50" "24 560 45" "24 480 40" "20 480 35" "20 400 30"; do
    set -- "$1" "$2" $cfg
    ffmpeg -y -loglevel error -i "$1" -vf "fps=$3,scale=$4:-2:flags=lanczos" -c:v libwebp -lossless 0 -q:v "$5" -loop 0 -preset picture -an "$2"
    s=$(stat -c %s "$2")
    [ "$s" -lt 1950000 ] && break
  done
  echo "$2 fps$3 w$4 q$5 $s"
}
enc $S/ghost-lock.webp gallery/clips/ghost-lock.webp
enc $S/auto-build.webp gallery/clips/auto-build.webp
enc $S/smart-pick.webp gallery/clips/smart-pick.webp
enc $S/materials.webp gallery/clips/materials.webp
enc "$O/cy_wheel/cy_wheel.webp" gallery/clips/tool-wheel.webp
enc "$O/cy_library/cy_library.webp" gallery/clips/library.webp
echo done
