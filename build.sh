#!/bin/bash
# Quick build script for Mustache Radio

export ANDROID_HOME=~/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/34.0.0
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

cd "$(dirname "$0")"

echo "Building Mustache Radio..."
./gradlew assembleDebug

if [ $? -eq 0 ]; then
    APK=$(find app/build/outputs/apk/debug -name "*.apk" | head -1)
    echo ""
    echo "Build successful!"
    echo "APK: $APK"
    echo "Size: $(du -h "$APK" | cut -f1)"
    echo ""
    echo "Install: adb install -r \"$APK\""
else
    echo ""
    echo "Build failed."
    exit 1
fi
