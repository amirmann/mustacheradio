#!/bin/bash

# ADB Bridge Setup Script for WSL2
# This connects WSL to Windows ADB server

echo "🔧 ADB Bridge Setup for WSL2"
echo "============================"
echo ""

# Get Windows host IP
WINDOWS_IP=$(cat /etc/resolv.conf | grep nameserver | awk '{print $2}')
echo "Windows Host IP: $WINDOWS_IP"
echo ""

# Set ADB server socket
export ADB_SERVER_SOCKET=tcp:${WINDOWS_IP}:5037

echo "Testing connection to Windows ADB server..."
echo ""

# Try to connect
if adb devices 2>&1 | grep -q "daemon"; then
    echo "✅ Connected to Windows ADB server!"
    echo ""
    
    # Show devices
    echo "Checking for connected devices..."
    echo ""
    adb devices
    echo ""
    
    DEVICE_COUNT=$(adb devices | grep -v "List" | grep "device" | wc -l)
    
    if [ "$DEVICE_COUNT" -gt 0 ]; then
        echo "✅ Phone detected!"
        echo ""
        echo "Phone model: $(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
        echo "Android version: $(adb shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
        echo ""
        echo "============================"
        echo "🎉 SUCCESS! ADB is working!"
        echo "============================"
        echo ""
        echo "Now you can:"
        echo "  1. Install app: adb install -r app/build/outputs/apk/debug/AmiRadio-v1.8-debug-debug.apk"
        echo "  2. View logs: adb logcat | grep -E '(MainActivity|RadioPlaybackService)'"
        echo ""
        echo "To make this permanent, add this to ~/.bashrc:"
        echo "  export ADB_SERVER_SOCKET=tcp:${WINDOWS_IP}:5037"
        echo ""
        
        # Offer to add to bashrc
        read -p "Add to ~/.bashrc now? (y/n) " -n 1 -r
        echo ""
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            if ! grep -q "ADB_SERVER_SOCKET" ~/.bashrc; then
                echo "" >> ~/.bashrc
                echo "# ADB Bridge to Windows" >> ~/.bashrc
                echo "export ADB_SERVER_SOCKET=tcp:\$(cat /etc/resolv.conf | grep nameserver | awk '{print \$2}'):5037" >> ~/.bashrc
                echo "✅ Added to ~/.bashrc"
                echo "Run 'source ~/.bashrc' or restart terminal"
            else
                echo "Already in ~/.bashrc"
            fi
        fi
    else
        echo "⚠️  No phone detected!"
        echo ""
        echo "Please:"
        echo "  1. Make sure phone is connected via USB"
        echo "  2. Enable USB debugging on phone"
        echo "  3. Check phone screen for authorization popup"
        echo "  4. Run Windows ADB server first (see USB_DEBUGGING_SETUP.md)"
        echo ""
        echo "Windows ADB commands:"
        echo "  cd C:\\platform-tools"
        echo "  .\\adb.exe start-server"
        echo "  .\\adb.exe devices"
    fi
else
    echo "❌ Cannot connect to Windows ADB server!"
    echo ""
    echo "Please do this FIRST in Windows PowerShell/CMD:"
    echo ""
    echo "  1. Download Android Platform Tools:"
    echo "     https://developer.android.com/tools/releases/platform-tools"
    echo ""
    echo "  2. Extract to C:\\platform-tools\\"
    echo ""
    echo "  3. Run in PowerShell/CMD:"
    echo "     cd C:\\platform-tools"
    echo "     .\\adb.exe start-server"
    echo "     .\\adb.exe devices"
    echo ""
    echo "  4. Connect your phone via USB"
    echo "  5. Enable USB debugging on phone"
    echo "  6. Allow authorization on phone screen"
    echo ""
    echo "See USB_DEBUGGING_SETUP.md for detailed instructions"
fi

echo ""
