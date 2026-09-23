#!/usr/bin/env bash
set -euo pipefail
# Run from the repository root after downloading the pinned JARs.
# Hash-qualified versions prevent different private JARs sharing a Maven cache key.
sha256sum --check .github/dependencies.sha256

mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/MythicMobs-5.8.0-SNAPSHOT.jar" -DgroupId="local" -DartifactId="MythicMobs" \
    -Dversion="5.8.0-SNAPSHOT-tfmc-575aa30aee8e" -Dpackaging=jar -DgeneratePom=true "$@"
