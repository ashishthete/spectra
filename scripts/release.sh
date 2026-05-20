#!/usr/bin/env bash
set -euo pipefail

if [ $# -ne 1 ]; then
  echo "Usage: ./scripts/release.sh <version>"
  echo "Example: ./scripts/release.sh 1.1.0"
  exit 1
fi

VERSION="$1"
VERSION_CODE=$(date +%Y%m%d%H)

echo "=== SPECTRA Release v${VERSION} (code: ${VERSION_CODE}) ==="

# 1. Update version in build.gradle.kts
sed -i '' "s/versionCode = [0-9]*/versionCode = ${VERSION_CODE}/" app/build.gradle.kts
sed -i '' "s/versionName = \"[^\"]*\"/versionName = \"${VERSION}\"/" app/build.gradle.kts
echo "[1/6] Version updated: ${VERSION} (${VERSION_CODE})"

# 2. Run tests
echo "[2/6] Running tests..."
./gradlew testDebugUnitTest --no-daemon --quiet
echo "       Tests passed."

# 3. Build release AAB
echo "[3/6] Building release AAB..."
./gradlew :app:bundleRelease --no-daemon --quiet
echo "       AAB built: app/build/outputs/bundle/release/app-release.aab"

# 4. Create changelog
CHANGELOG_FILE="fastlane/metadata/android/en-US/changelogs/${VERSION_CODE}.txt"
if [ ! -f "$CHANGELOG_FILE" ]; then
  echo "What's new in v${VERSION}:" > "$CHANGELOG_FILE"
  echo "" >> "$CHANGELOG_FILE"
  git log --oneline "$(git describe --tags --abbrev=0 2>/dev/null || echo HEAD~10)"..HEAD \
    | sed 's/^[a-f0-9]* /- /' >> "$CHANGELOG_FILE"
  echo "[4/6] Changelog generated: ${CHANGELOG_FILE}"
  echo "       Please review and edit before continuing."
  echo ""
  cat "$CHANGELOG_FILE"
  echo ""
  read -p "Press Enter to continue after editing changelog..."
else
  echo "[4/6] Changelog already exists: ${CHANGELOG_FILE}"
fi

# 5. Commit and tag
git add app/build.gradle.kts "$CHANGELOG_FILE"
git commit -m "release: SPECTRA v${VERSION}"
git tag -a "v${VERSION}" -m "SPECTRA Camera v${VERSION}"
echo "[5/6] Committed and tagged v${VERSION}"

# 6. Push
echo "[6/6] Push to trigger deployment:"
echo "       git push && git push origin v${VERSION}"
echo ""
echo "=== Release v${VERSION} ready. Push when ready. ==="
