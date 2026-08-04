#!/usr/bin/env sh
set -eu

usage() {
    cat <<'USAGE'
Usage: ./scripts/package-apk.sh <debug|release> [--skip-build]

Release builds require:
  CGE_KEYSTORE_PATH
  CGE_KEYSTORE_PASSWORD
  CGE_KEY_ALIAS
  CGE_KEY_PASSWORD

Optional:
  CGE_EXPECTED_TAG=<versionCode-versionName>
USAGE
}

VARIANT="${1:-debug}"
MODE="${2:-}"

case "$VARIANT" in
    debug)
        TASK_SUFFIX="Debug"
        APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
        ;;
    release)
        TASK_SUFFIX="Release"
        APK_PATH="app/build/outputs/apk/release/app-release.apk"
        ;;
    -h|--help)
        usage
        exit 0
        ;;
    *)
        usage >&2
        exit 2
        ;;
esac

if [ -n "$MODE" ] && [ "$MODE" != "--skip-build" ]; then
    usage >&2
    exit 2
fi

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
cd "$ROOT_DIR"

if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi

if [ "$MODE" != "--skip-build" ]; then
    ./gradlew --no-daemon clean "lint${TASK_SUFFIX}" "assemble${TASK_SUFFIX}"
fi

if [ ! -f "$APK_PATH" ]; then
    if [ "$VARIANT" = "release" ] && [ -f "app/build/outputs/apk/release/app-release-unsigned.apk" ]; then
        echo "Release APK is unsigned. Configure CGE_KEYSTORE_* variables." >&2
    else
        echo "APK not found: $APK_PATH" >&2
    fi
    exit 1
fi

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK_ROOT" ]; then
    for candidate in "$HOME/Library/Android/sdk" "/opt/homebrew/share/android-commandlinetools"; do
        if [ -d "$candidate/build-tools" ]; then
            SDK_ROOT="$candidate"
            break
        fi
    done
fi

if [ -z "$SDK_ROOT" ] || [ ! -d "$SDK_ROOT/build-tools" ]; then
    echo "Android SDK build-tools directory not found." >&2
    exit 1
fi

BUILD_TOOLS_DIR=$(find "$SDK_ROOT/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
AAPT2="$BUILD_TOOLS_DIR/aapt2"
APKSIGNER="$BUILD_TOOLS_DIR/apksigner"

if [ ! -x "$AAPT2" ] || [ ! -x "$APKSIGNER" ]; then
    echo "aapt2 or apksigner not found under $BUILD_TOOLS_DIR" >&2
    exit 1
fi

BADGING=$($AAPT2 dump badging "$APK_PATH")
PACKAGE_NAME=$(printf '%s\n' "$BADGING" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
VERSION_CODE=$(printf '%s\n' "$BADGING" | sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p")
VERSION_NAME=$(printf '%s\n' "$BADGING" | sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p")

if [ "$PACKAGE_NAME" != "io.github.yunshan.colorosglance" ]; then
    echo "Unexpected package name: $PACKAGE_NAME" >&2
    exit 1
fi
if [ -z "$VERSION_CODE" ] || [ -z "$VERSION_NAME" ]; then
    echo "Unable to read APK version metadata." >&2
    exit 1
fi

SIGNATURE=$($APKSIGNER verify --verbose --print-certs "$APK_PATH")
if [ "$VARIANT" = "release" ] && printf '%s\n' "$SIGNATURE" | grep -q "CN=Android Debug"; then
    echo "Release APK is signed with the Android Debug certificate." >&2
    exit 1
fi

LSPOSED_TAG="${VERSION_CODE}-${VERSION_NAME}"
if [ -n "${CGE_EXPECTED_TAG:-}" ] && [ "$CGE_EXPECTED_TAG" != "$LSPOSED_TAG" ]; then
    echo "Tag mismatch: expected $LSPOSED_TAG, received $CGE_EXPECTED_TAG" >&2
    exit 1
fi

DIST_DIR="$ROOT_DIR/dist"
mkdir -p "$DIST_DIR"
if [ "$VARIANT" = "release" ]; then
    ARTIFACT_NAME="ColorOS-Negative-Screen-Extension-v${VERSION_NAME}.apk"
else
    ARTIFACT_NAME="ColorOS-Negative-Screen-Extension-v${VERSION_NAME}-debug.apk"
fi

cp "$APK_PATH" "$DIST_DIR/$ARTIFACT_NAME"

if command -v sha256sum >/dev/null 2>&1; then
    (cd "$DIST_DIR" && sha256sum "$ARTIFACT_NAME" > "$ARTIFACT_NAME.sha256")
else
    (cd "$DIST_DIR" && shasum -a 256 "$ARTIFACT_NAME" > "$ARTIFACT_NAME.sha256")
fi
cp "$DIST_DIR/$ARTIFACT_NAME.sha256" "$DIST_DIR/SHA256SUMS"

cat > "$DIST_DIR/release-metadata.env" <<META
PACKAGE_NAME='$PACKAGE_NAME'
VERSION_CODE='$VERSION_CODE'
VERSION_NAME='$VERSION_NAME'
LSPOSED_TAG='$LSPOSED_TAG'
ARTIFACT_NAME='$ARTIFACT_NAME'
META

printf '%s\n' "$SIGNATURE"
printf '%s\n' \
    "Package: $PACKAGE_NAME" \
    "Version: $VERSION_NAME ($VERSION_CODE)" \
    "LSPosed tag: $LSPOSED_TAG" \
    "Artifact: $DIST_DIR/$ARTIFACT_NAME" \
    "Checksum: $DIST_DIR/$ARTIFACT_NAME.sha256"
