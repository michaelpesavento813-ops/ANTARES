#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
destination=app/src/main/assets/magic_touch.tflite
checksum=e24338a717c1b7ad8d159666677ef400babb7f33b8ad60c4d96db4ecf694cd25
mkdir -p "$(dirname "$destination")"
if ! printf '%s  %s\n' "$checksum" "$destination" | sha256sum --check --status; then
temporary=$(mktemp "${destination}.XXXXXX")
trap 'rm -f "$temporary"' EXIT
curl --fail --silent --show-error --location --retry 3 \
  https://storage.googleapis.com/mediapipe-models/interactive_segmenter/magic_touch/float32/1/magic_touch.tflite \
  -o "$temporary"
printf '%s  %s\n' "$checksum" "$temporary" | sha256sum --check
mv "$temporary" "$destination"
fi

destination=app/src/main/assets/efficientdet_lite0.tflite
checksum=4b59100025bea1235a84c1038879a6cccc9f6c49f5e41144e91e74d99e780993
if ! printf '%s  %s\n' "$checksum" "$destination" | sha256sum --check --status; then
  temporary=$(mktemp "${destination}.XXXXXX")
  curl --fail --silent --show-error --location --retry 3 \
    https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float16/1/efficientdet_lite0.tflite \
    -o "$temporary"
  printf '%s  %s\n' "$checksum" "$temporary" | sha256sum --check
  mv "$temporary" "$destination"
fi
