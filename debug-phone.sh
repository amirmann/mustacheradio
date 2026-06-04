#!/bin/bash

echo "📱 AmiRadio Phone Debugging Script"
echo "===================================="
echo ""

# Check if phone is connected
echo "1️⃣  Checking for connected device..."
DEVICE=$(adb devices | grep -w "device" | head -1)

if [ -z "$DEVICE" ]; then
    echo "❌ No device found!"
    echo ""
    echo "Please connect your phone via USB and enable USB debugging:"
    echo "  Settings → About Phone → Tap 'Build Number' 7 times"
    echo "  Settings → Developer Options → Enable 'USB Debugging'"
    echo ""
    exit 1
fi

echo "✅ Device connected!"
echo ""

# Clear old logs
echo "2️⃣  Clearing old logs..."
adb logcat -c

echo "✅ Logs cleared"
echo ""

echo "3️⃣  Starting live log monitoring..."
echo "===================================="
echo ""
echo "📊 NOW DO THIS ON YOUR PHONE:"
echo "   1. Open the AmiRadio app"
echo "   2. Tap a station (e.g., Galatz)"
echo "   3. Wait 5-10 seconds"
echo ""
echo "📋 Watching logs (press Ctrl+C to stop)..."
echo ""
echo "------------------------------------"
echo ""

# Monitor logs with filters
adb logcat | grep -E "(MainActivity|RadioPlaybackService|ExoPlayer|MediaSession)"
