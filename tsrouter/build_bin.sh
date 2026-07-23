#!/usr/bin/env bash
set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( dirname "$SCRIPT_DIR" )"
JNI_LIBS_DIR="$PROJECT_ROOT/app/src/main/jniLibs"

NDK_PATH="/Users/musan/Library/Android/sdk/ndk/25.0.8775105"
TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64/bin"

mkdir -p "$JNI_LIBS_DIR/arm64-v8a"
mkdir -p "$JNI_LIBS_DIR/x86_64"

echo "=== Building libtsrouter.so binary process for arm64-v8a ==="
cd "$SCRIPT_DIR"

CC="$TOOLCHAIN/aarch64-linux-android26-clang" \
CGO_ENABLED=1 GOOS=android GOARCH=arm64 \
go build -ldflags="-checklinkname=0 -s -w" -v -o "$JNI_LIBS_DIR/arm64-v8a/libtsrouter.so" ./cmd/tsrouter

echo "=== Building libtsrouter.so binary process for x86_64 ==="
CC="$TOOLCHAIN/x86_64-linux-android26-clang" \
CGO_ENABLED=1 GOOS=android GOARCH=amd64 \
go build -ldflags="-checklinkname=0 -s -w" -v -o "$JNI_LIBS_DIR/x86_64/libtsrouter.so" ./cmd/tsrouter

echo "=== Successfully built libtsrouter.so for arm64-v8a and x86_64 ==="
