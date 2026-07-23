#!/usr/bin/env bash
set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( dirname "$SCRIPT_DIR" )"
OUTPUT_AAR="$PROJECT_ROOT/app/libs/libtsrouter.aar"

if [ -d "/Users/musan/Library/Android/sdk/ndk/25.0.8775105" ]; then
    export ANDROID_NDK_HOME="/Users/musan/Library/Android/sdk/ndk/25.0.8775105"
fi

echo "=== Building libtsrouter.aar with gomobile ==="
cd "$SCRIPT_DIR"

if ! command -v gomobile &> /dev/null; then
    export PATH="$PATH:$HOME/go/bin"
fi

echo "Running gomobile bind for arm64 and amd64..."
gomobile bind -ldflags="-checklinkname=0" -target=android/arm64,android/amd64 -androidapi 26 -o "$OUTPUT_AAR" .

echo "Post-processing libtsrouter.aar: removing duplicate go/ classes and renaming libgojni.so to libtsrouterjni.so..."
TMP_DIR=$(mktemp -d)
unzip -q "$OUTPUT_AAR" -d "$TMP_DIR"
rm -f "$OUTPUT_AAR"

cd "$TMP_DIR"

# 1. Remove duplicate go/ classes from classes.jar
zip -d classes.jar "go/*"

# 2. Rename libgojni.so -> libtsrouterjni.so across all ABIs
find jni -name "libgojni.so" | while read -r libfile; do
    dir=$(dirname "$libfile")
    mv "$libfile" "$dir/libtsrouterjni.so"
done

# 3. Create clean new AAR
zip -r -q "$OUTPUT_AAR" .
rm -rf "$TMP_DIR"

echo "=== Successfully generated $OUTPUT_AAR ==="
