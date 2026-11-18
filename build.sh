#!/bin/bash
# Manual APK build for RightNews Live (plain Java, zero dependencies, no Gradle).
set -e
export JAVA_HOME=~/workspace/.tmp/jdk
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=~/workspace/.tmp/android-sdk
AAPT=$ANDROID_HOME/build-tools/36.0.0/aapt2
PLATFORM=$ANDROID_HOME/platforms/android-35/android.jar
D8_JAR=$ANDROID_HOME/build-tools/36.0.0/lib/d8.jar
ZIPALIGN=$ANDROID_HOME/build-tools/36.0.0/zipalign
APKSIGNER=$ANDROID_HOME/build-tools/36.0.0/apksigner
SRC=~/workspace/rightnews-live/app/src/main
WORK=~/workspace/.tmp/rightnews-build/work
OUT=~/workspace/your_files/rightnews-live/KenzNewsv3-35.apk
rm -rf "$WORK" && mkdir -p "$WORK/compiled" "$WORK/classes" "$WORK/dex" "$WORK/gen"
"$AAPT" compile --dir "$SRC/res" -o "$WORK/compiled/res.zip"
"$AAPT" link -o "$WORK/base.apk" \
    -I "$PLATFORM" \
    --min-sdk-version 26 --target-sdk-version 35 \
    --version-code 87 --version-name "3.35" \
    --manifest "$SRC/AndroidManifest.xml" \
    --java "$WORK/gen" \
    "$WORK/compiled/res.zip"
find "$SRC/java" "$WORK/gen" -name "*.java" > "$WORK/sources.txt"
"$JAVA_HOME/bin/javac" -J-Xmx512m -encoding UTF-8 -source 17 -target 17 -nowarn \
    -cp "$PLATFORM" -d "$WORK/classes" @"$WORK/sources.txt"
(cd "$WORK/classes" && "$JAVA_HOME/bin/jar" cf "$WORK/app-classes.jar" .)
"$JAVA_HOME/bin/java" -Xmx512m -cp "$D8_JAR" com.android.tools.r8.D8 \
    --min-api 26 --lib "$PLATFORM" --output "$WORK/dex" "$WORK/app-classes.jar"
cp "$WORK/base.apk" "$WORK/unsigned.apk"
(cd "$WORK/dex" && zip -q -0 "$WORK/unsigned.apk" classes*.dex)
"$ZIPALIGN" -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
mkdir -p ~/workspace/your_files/rightnews-live
"$APKSIGNER" sign --ks ~/workspace/.tmp/manual-build/debug.keystore \
    --ks-pass pass:android --key-pass pass:android \
    --out "$OUT" "$WORK/aligned.apk"
echo "BUILD DONE:"
ls -la "$OUT"
"$APKSIGNER" verify "$OUT" && echo "SIGNATURE OK"
"$AAPT" dump badging "$OUT" | head -n 20
