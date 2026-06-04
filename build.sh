#!/bin/bash
# Quick build script for Amiradio

# Load environment variables
export ANDROID_HOME=~/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/34.0.0
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# Navigate to project directory
cd /home/amirn/GIT2/amiradio

# Build the app
echo "🏗️  Building AmiRadio..."
./gradlew assembleDebug

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Build successful!"
    echo "📦 APK location: app/build/outputs/apk/debug/app-debug.apk"
    echo "📱 Size: $(du -h app/build/outputs/apk/debug/app-debug.apk | cut -f1)"
    echo ""
    echo "To install on connected device, run:"
    echo "  ./gradlew installDebug"
    echo ""
    echo "Or manually:"
    echo "  adb install app/build/outputs/apk/debug/app-debug.apk"
else
    echo ""
    echo "❌ Build failed. Check the errors above."
    exit 1
fi
