#!/usr/bin/env bash
set -euo pipefail
: "${GH_TOKEN:?Set DEPS_TOKEN with Contents read access to TF-Minecraft/ServerAssets}"
ref=183a187cf6128371a31ad20f3b524f6f30d537b9
mkdir -p libs
curl --fail --location --silent --show-error --retry 3 -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/ServerAssets/contents/jars/575aa30aee8e/MythicMobs-5.8.0-SNAPSHOT.jar?ref=$ref" > "libs/MythicMobs-5.8.0-SNAPSHOT.jar"
bash .github/scripts/install-local-dependencies.sh "$@"
