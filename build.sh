#!/bin/sh
# 在 Linux 上不依赖 Gradle 构建 APK。需要：JDK 17、android.jar、d8.jar、apksigner.jar、aapt2。
set -e
cd "$(dirname "$0")"
: "${LAMBDA_JAR:?set LAMBDA_JAR (core-lambda-stubs.jar)}" "${ANDROID_JAR:?set ANDROID_JAR}" "${D8_JAR:?set D8_JAR}" "${APKSIGNER_JAR:?set APKSIGNER_JAR}" "${AAPT2:=aapt2}" "${ZIPALIGN:=zipalign}"
rm -rf build && mkdir -p build/classes build/dex
$AAPT2 compile --dir res -o build/res.zip
$AAPT2 link -I "$ANDROID_JAR" --manifest AndroidManifest.xml -o build/unsigned.apk \
  --java build/gen build/res.zip --min-sdk-version 29 --target-sdk-version 34
javac -source 8 -target 8 -encoding UTF-8 -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR:$LAMBDA_JAR" \
  -Xlint:-options -d build/classes $(find src build/gen -name '*.java')
java -cp "$D8_JAR" com.android.tools.r8.D8 --release --min-api 29 --lib "$ANDROID_JAR" \
  --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../unsigned.apk classes.dex)
$ZIPALIGN -f -p 4 build/unsigned.apk build/aligned.apk
if [ ! -f release.keystore ]; then
  keytool -genkeypair -keystore release.keystore -storepass shengli123 -keypass shengli123 \
    -alias shengli -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Shengli Watermark"
fi
java -jar "$APKSIGNER_JAR" sign --ks release.keystore --ks-pass pass:shengli123 \
  --out build/shengli-watermark.apk build/aligned.apk
echo "built build/shengli-watermark.apk"
