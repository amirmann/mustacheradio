#!/bin/bash

# AmiRadio Emulator Helper Script

export ANDROID_HOME=~/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools

echo "======================================"
echo "AmiRadio Emulator Helper"
echo "======================================"
echo ""

# Check if emulator is installed
if [ ! -f "$ANDROID_HOME/emulator/emulator" ]; then
    echo "❌ Emulator not found. Please install it with:"
    echo "   sdkmanager 'emulator' 'system-images;android-34;google_apis;x86_64'"
    exit 1
fi

# Check if AVD exists
AVD_NAME="AmiRadio_Test"
if ! avdmanager list avd | grep -q "$AVD_NAME"; then
    echo "Creating Android Virtual Device: $AVD_NAME"
    echo "no" | avdmanager create avd -n "$AVD_NAME" -k "system-images;android-34;google_apis;x86_64" -d "pixel_5"
    if [ $? -ne 0 ]; then
        echo "❌ Failed to create AVD. System image may still be downloading."
        echo "   Check installation status with: ls -la ~/android-sdk/system-images/android-34/google_apis/x86_64/"
        exit 1
    fi
fi

echo "✅ AVD '$AVD_NAME' is ready"
echo ""

# Function to launch emulator
launch_emulator() {
    echo "🚀 Launching emulator..."
    emulator -avd "$AVD_NAME" -no-snapshot-load -gpu swiftshader_indirect > /dev/null 2>&1 &
    EMULATOR_PID=$!
    echo "   Emulator started (PID: $EMULATOR_PID)"
    echo "   Waiting for device to boot (this may take 1-2 minutes)..."
    
    # Wait for device to be ready
    adb wait-for-device
    echo "   Device detected, waiting for boot to complete..."
    
    # Wait for boot animation to finish
    while [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
        sleep 2
    done
    
    echo "✅ Emulator is ready!"
    return 0
}

# Function to install APK
install_apk() {
    APK_PATH="app/build/outputs/apk/debug/AmiRadio-v1.6-debug-debug.apk"
    
    if [ ! -f "$APK_PATH" ]; then
        echo "❌ APK not found at: $APK_PATH"
        echo "   Build it first with: ./gradlew assembleDebug"
        return 1
    fi
    
    echo "📦 Installing AmiRadio v1.6..."
    adb install -r "$APK_PATH"
    
    if [ $? -eq 0 ]; then
        echo "✅ App installed successfully!"
        echo ""
        echo "To view logs:"
        echo "   adb logcat | grep -E '(MainActivity|RadioPlaybackService)'"
        echo ""
        echo "To launch the app:"
        echo "   adb shell am start -n com.amiradio.app/.MainActivity"
    else
        echo "❌ Installation failed"
        return 1
    fi
}

# Main menu
echo "What would you like to do?"
echo "1) Launch emulator and install app"
echo "2) Just launch emulator"
echo "3) Just install app (emulator must be running)"
echo "4) View logs"
echo "5) Exit"
echo ""
read -p "Choose option (1-5): " choice

case $choice in
    1)
        launch_emulator
        sleep 3
        install_apk
        ;;
    2)
        launch_emulator
        ;;
    3)
        install_apk
        ;;
    4)
        echo "📊 Showing logs (Ctrl+C to stop)..."
        adb logcat | grep -E "(MainActivity|RadioPlaybackService)"
        ;;
    5)
        echo "👋 Goodbye!"
        exit 0
        ;;
    *)
        echo "❌ Invalid option"
        exit 1
        ;;
esac

echo ""
echo "======================================"
echo "Done!"
echo "======================================"
