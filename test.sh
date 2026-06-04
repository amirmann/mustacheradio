#!/bin/bash

# Quick Start Script for AmiRadio v1.6

export ANDROID_HOME=~/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools

echo "🚀 Starting AmiRadio Test Environment"
echo "======================================"
echo ""

# Launch emulator in background
echo "1️⃣  Launching emulator (this takes 30-60 seconds)..."
nohup emulator -avd AmiRadio_Test -no-snapshot-load -gpu swiftshader_indirect > /tmp/emulator.log 2>&1 &
EMULATOR_PID=$!

echo "   Emulator starting (PID: $EMULATOR_PID)"
echo "   Waiting for device..."

# Wait for device
adb wait-for-device
echo "   ✅ Device detected!"

# Wait for boot to complete
echo "   Waiting for boot to complete..."
while [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
    sleep 2
    echo -n "."
done
echo ""
echo "   ✅ Emulator fully booted!"
echo ""

# Install APK
echo "2️⃣  Installing AmiRadio v1.6..."
APK="app/build/outputs/apk/debug/AmiRadio-v1.6-debug-debug.apk"

if [ -f "$APK" ]; then
    adb install -r "$APK"
    echo ""
    echo "   ✅ App installed!"
else
    echo "   ❌ APK not found. Build it first with: ./gradlew assembleDebug"
    exit 1
fi

echo ""
echo "3️⃣  Launching app..."
adb shell am start -n com.amiradio.app/.MainActivity
sleep 2

echo ""
echo "======================================"
echo "✅ READY TO TEST!"
echo "======================================"
echo ""
echo "📱 The emulator window should open showing AmiRadio"
echo "🎵 Tap a station to test playback"
echo ""
echo "📊 To view logs in real-time, open a new terminal and run:"
echo "   adb logcat | grep -E '(MainActivity|RadioPlaybackService)'"
echo ""
echo "🔊 IMPORTANT: Check emulator volume!"
echo "   - Press volume up on emulator (or use host volume keys)"
echo "   - Look for 'WARNING: System volume is ZERO!' in logs"
echo ""
echo "❌ To stop the emulator:"
echo "   adb emu kill"
echo ""
