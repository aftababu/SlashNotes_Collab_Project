#!/usr/bin/env bash
set -e

# Store the project root directory path
PROJECT_ROOT="$(pwd)"

echo "=== 1. Cleaning Old Build Artifacts ==="
# Clean Android
cd android
./gradlew clean
rm -rf app/build build .gradle
cd "$PROJECT_ROOT"

# Clean Core Rust bindings
cargo clean

# Clean Desktop & Web Frontend (target under project root)
rm -rf dist build out target/release/bundle

echo "=== 2. Building Desktop Frontend & Tauri Release ==="
pnpm build
export NO_STRIP=1
pnpm tauri build

echo "=== 3. Building Android Release APK ==="
cd android
./gradlew assembleRelease --no-daemon
cd "$PROJECT_ROOT"

echo "=== Build Complete ==="
echo "Desktop Artifacts:"
ls -lh target/release/bundle/ 2>/dev/null || echo "Check target/release/"
echo "Android Release APK:"
ls -lh android/app/build/outputs/apk/release/ 2>/dev/null || echo "Check android/app/build/outputs/apk/"
