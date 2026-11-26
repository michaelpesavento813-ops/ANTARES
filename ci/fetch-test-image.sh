#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
destination=app/src/androidTest/assets/astronaut.png
checksum=88431cd9653ccd539741b555fb0a46b61558b301d4110412b5bc28b5e3ea6cb5
mkdir -p "$(dirname "$destination")"
if printf '%s  %s\n' "$checksum" "$destination" | sha256sum --check --status; then exit 0; fi
temporary=$(mktemp "${destination}.XXXXXX")
trap 'rm -f "$temporary"' EXIT
# Public-domain NASA photograph of Eileen Collins, distributed with scikit-image.
curl --fail --silent --show-error --location --retry 3 \
  https://raw.githubusercontent.com/scikit-image/scikit-image/v0.23.2/skimage/data/astronaut.png \
  -o "$temporary"
printf '%s  %s\n' "$checksum" "$temporary" | sha256sum --check
mv "$temporary" "$destination"
