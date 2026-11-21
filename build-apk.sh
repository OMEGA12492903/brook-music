#!/usr/bin/env bash
set -euo pipefail

export JAVA_HOME="$HOME/.local/share/noctalia-android-toolchain/jdk"
export ANDROID_HOME="$HOME/.local/share/noctalia-android-toolchain/sdk"
export PATH="$JAVA_HOME/bin:$HOME/.local/share/noctalia-android-toolchain/gradle/gradle-8.11.1/bin:$ANDROID_HOME/platform-tools:$PATH"

cd "$(dirname "$0")"
gradle assembleRelease --no-daemon

APK="app/build/outputs/apk/release/app-release.apk"
cp -f "$APK" "./Brook.apk"
echo "APK listo en: $(pwd)/Brook.apk"

if [[ "${1:-}" == "--install" ]]; then
    adb install -r "./Brook.apk"
fi
