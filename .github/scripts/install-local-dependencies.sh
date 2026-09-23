#!/usr/bin/env bash
set -euo pipefail
# Run from the repository root after downloading the pinned JARs.
# Hash-qualified versions prevent different private JARs sharing a Maven cache key.
sha256sum --check .github/dependencies.sha256

mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/MythicMobs-5.13.1-SNAPSHOT-88530541.jar" -DgroupId="local" -DartifactId="MythicMobs" \
    -Dversion="5.13.1-SNAPSHOT-88530541-tfmc-6df72b5b331d" -Dpackaging=jar -DgeneratePom=true "$@"
