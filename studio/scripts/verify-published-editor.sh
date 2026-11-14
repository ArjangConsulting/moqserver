#!/usr/bin/env bash
set -euo pipefail
repository_url=https://repo.maven.apache.org/maven2/com/arjangconsulting/codeeditor
for module in code-editor code-editor-jvm; do
    curl --fail --silent --show-error --retry 2 --output /dev/null \
        "$repository_url/$module/0.1.0/$module-0.1.0.pom"
done
