#!/bin/bash
set -e
BASE=$HOME/workspace/.android-build
PROJ=$HOME/workspace/android-expense-tracker
export JAVA_HOME=$BASE/jdk17
export ANDROID_HOME=$BASE/android-sdk
export PATH=$JAVA_HOME/bin:$PATH
BT=$ANDROID_HOME/build-tools/34.0.0
PT=$ANDROID_HOME/platform-tools
AJAR=$ANDROID_HOME/platforms/android-34/android.jar

cd $PROJ
rm -rf build && mkdir -p build/gen build/classes build/dex

echo "== aapt2 compile =="
$BT/aapt2 compile --dir res -o build/compiled.zip

echo "== aapt2 link =="
$BT/aapt2 link -o build/app-unsigned.apk \
  -I $AJAR \
  --manifest AndroidManifest.xml \
  --java build/gen \
  --min-sdk-version 26 --target-sdk-version 34 \
  build/compiled.zip

echo "== javac =="
find src build/gen -name "*.java" > build/sources.txt
$JAVA_HOME/bin/javac -source 8 -target 8 -nowarn \
  -cp $AJAR -d build/classes @build/sources.txt

echo "== d8 =="
$BT/d8 --lib $AJAR --min-api 26 \
  --output build/dex \
  $(find build/classes -name "*.class" | tr '\n' ' ')

echo "== add classes.dex =="
python3 - "$PWD/build/app-unsigned.apk" "$PWD/build/dex/classes.dex" <<'EOF'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print("dex added")
EOF

echo "== zipalign =="
$BT/zipalign -f 4 build/app-unsigned.apk build/app-aligned.apk

echo "== keystore =="
if [ ! -f build/debug.keystore ]; then
  $JAVA_HOME/bin/keytool -genkeypair -keystore build/debug.keystore \
    -alias androiddebugkey -storepass android -keypass android \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=Android Debug,O=Android,C=US"
fi

echo "== apksigner =="
$BT/apksigner sign --ks build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out build/expense-tracker.apk build/app-aligned.apk

echo "== verify =="
$BT/apksigner verify --print-certs build/expense-tracker.apk | head -3
ls -la build/expense-tracker.apk
echo BUILD_OK
