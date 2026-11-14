#!/usr/bin/env bash
# Makes a fresh clone buildable while com.arjangconsulting.codeeditor:code-editor is unpublished:
# clones the public kmp-code-editor repository at the commit CI builds against into
# studio/.code-editor, which settings.gradle.kts includes as a composite build.
#
# Skips the clone when a sibling ../../kmp-code-editor checkout exists (local development) or when
# studio/.code-editor is already at the pinned commit. Override the commit with CODE_EDITOR_REF.
set -euo pipefail

studio_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
target="$studio_dir/.code-editor"
repository="https://github.com/ArjangConsulting/kmp-code-editor.git"
# Keep in sync with the `ref:` of the "Check out code editor" steps in .github/workflows.
ref="${CODE_EDITOR_REF:-28790a500c93efc717acf03ab475f988e236087d}"

if [ -f "$studio_dir/../../kmp-code-editor/settings.gradle.kts" ]; then
    echo "Using sibling kmp-code-editor checkout; nothing to fetch."
    exit 0
fi

if [ -d "$target/.git" ] && [ "$(git -C "$target" rev-parse HEAD)" = "$ref" ]; then
    echo "studio/.code-editor is already at $ref."
    exit 0
fi

rm -rf "$target"
git init --quiet "$target"
git -C "$target" remote add origin "$repository"
git -C "$target" fetch --quiet --depth 1 origin "$ref"
git -C "$target" checkout --quiet --detach FETCH_HEAD
echo "Fetched kmp-code-editor $ref into studio/.code-editor."
