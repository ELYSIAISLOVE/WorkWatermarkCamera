#!/usr/bin/env bash
# Clean Android/Gradle build garbage from repo working tree.
# Run from repository root: bash scripts/clean_build_artifacts.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
echo "[clean] root=$ROOT"

rm -rf app/build build .gradle 2>/dev/null || true
rm -rf app/.cxx app/.externalNativeBuild .cxx .externalNativeBuild captures 2>/dev/null || true
find . -type d -name "intermediates" -prune -exec rm -rf {} + 2>/dev/null || true

find . -name ".DS_Store" -delete 2>/dev/null || true
find . -name "Thumbs.db" -delete 2>/dev/null || true
find . -name "*.iml" -not -path "./.git/*" -delete 2>/dev/null || true
rm -rf .idea 2>/dev/null || true

if [ -f local.properties ]; then
  echo "[clean] note: local.properties exists (SDK path) — keep local, do not commit"
fi
if ls *.jks *.keystore 2>/dev/null; then
  echo "[clean] WARN: keystore files present — do not commit"
fi

echo "[clean] done. Next: ensure .gitignore is present, then: git status"
