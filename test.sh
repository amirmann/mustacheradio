#!/bin/bash
# Quick start script for Mustache Radio testing

export ANDROID_HOME=~/android-sdk
export PATH=$PATH:$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools

AVD_NAME="MustacheRadio_Test"

echo "Starting Mustache Radio Test Environment"
echo "========================================="

echo "1. Starting emulator ($AVD_NAME)..."
nohup emulator -avd "$AVD_NAME" -no-snapshot-load -gpu swiftshader_indirect > /tmp/emulator.log 2>&1 &
EMULATOR_PID=$!
echo "Emulator PID: $EMULATOR_PID"

echo "2. Waiting for device to be ready..."
adb wait-for-device
sleep 5

echo "3. Building APK..."
./gradlew assembleDebug

echo "4. Installing APK..."
APK=$(find app/build/outputs/apk/debug -name "*.apk" | head -1)
adb install -r "$APK"

echo "5. Launching app..."
adb shell am start -n com.mustacheradio.app.debug/.MainActivity

echo ""
echo "App launched! Check the emulator window."
echo "To view logs: adb logcat | grep -E '(MainActivity|RadioPlaybackService)'"
