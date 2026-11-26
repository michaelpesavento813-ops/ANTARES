#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
destination=app/src/main/assets/face_landmarker.task
checksum=64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff
mkdir -p "$(dirname "$destination")"
if printf '%s  %s\n' "$checksum" "$destination" | sha256sum --check --status; then exit 0; fi
temporary=$(mktemp "${destination}.XXXXXX")
trap 'rm -f "$temporary"' EXIT
curl --fail --silent --show-error --location --retry 3 \
  https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task \
  -o "$temporary"
printf '%s  %s\n' "$checksum" "$temporary" | sha256sum --check
mv "$temporary" "$destination"
