#!/bin/bash

echo "Mustache Radio — Phone Debugging"
echo "================================="
echo ""

echo "1. Checking for connected device..."
DEVICE=$(adb devices | grep -w "device" | head -1)

if [ -z "$DEVICE" ]; then
    echo "No device found!"
    echo ""
    echo "Connect your phone via USB and enable USB debugging:"
    echo "  Settings → About Phone → Tap 'Build Number' 7 times"
    echo "  Settings → Developer Options → Enable 'USB Debugging'"
    echo ""
    exit 1
fi

echo "Device connected!"
echo ""

echo "2. Clearing old logs..."
adb logcat -c
echo "Logs cleared"
echo ""

echo "3. Starting live log monitoring (Ctrl+C to stop)..."
echo ""
adb logcat | grep -E "(MainActivity|RadioPlaybackService|PrerollWarmer|ExoPlayer|MediaSession|CastSession)"
