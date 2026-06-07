#!/bin/bash
# Mustache Radio — Emulator Helper

export ANDROID_HOME=~/android-sdk
export PATH=$PATH:$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools

AVD_NAME="MustacheRadio_Test"

echo "Mustache Radio Emulator Helper"
echo "==============================="

case "$1" in
    start)
        echo "Starting emulator ($AVD_NAME)..."
        nohup emulator -avd "$AVD_NAME" -no-snapshot-load -gpu swiftshader_indirect > /tmp/emulator.log 2>&1 &
        echo "Emulator starting in background (PID $!)"
        echo "Waiting for device..."
        adb wait-for-device
        echo "Device ready."
        ;;
    stop)
        echo "Stopping emulator..."
        adb emu kill
        ;;
    install)
        APK=$(find app/build/outputs/apk/debug -name "*.apk" | head -1)
        if [ -z "$APK" ]; then
            echo "No APK found. Run ./gradlew assembleDebug first."
            exit 1
        fi
        echo "Installing $APK..."
        adb install -r "$APK"
        ;;
    *)
        echo "Usage: $0 {start|stop|install}"
        exit 1
        ;;
esac
