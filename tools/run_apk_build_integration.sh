#!/usr/bin/env bash
set -euo pipefail
on_error() {
  local code=$?
  echo "::error title=Offline Android APK integration::failed at line ${BASH_LINENO[0]}: ${BASH_COMMAND} (exit ${code})"
  exit "$code"
}
trap on_error ERR
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
: "${JAVA_HOME:?JAVA_HOME must point to JDK 17}"
ENGINE_AAR="engine/build/outputs/aar/engine-release.aar"
RUNTIME_APK="runtime/build/outputs/apk/release/runtime-release-unsigned.apk"
OUT_DIR="build/apk-integration"
HOST_LIB="$OUT_DIR/host-lib"
CLASSES="$OUT_DIR/classes"
mkdir -p "$HOST_LIB" "$CLASSES"

# Build the same native codec JNI bridge for the CI host so the real in-app APK
# builder (including its production container and v2 signer) can be exercised.
C_SOURCES=(
  engine/src/main/cpp/aes.c
  engine/src/main/cpp/lzma/LzmaEnc.c
  engine/src/main/cpp/lzma/LzmaDec.c
  engine/src/main/cpp/lzma/LzFind.c
  engine/src/main/cpp/lzma/LzFindMt.c
  engine/src/main/cpp/lzma/Alloc.c
  engine/src/main/cpp/lzma/CpuArch.c
  engine/src/main/cpp/lzma/Bra86.c
  engine/src/main/cpp/lzma/Threads.c
)
for source in "${C_SOURCES[@]}"; do
  object="$HOST_LIB/$(basename "${source%.*}").o"
  gcc -O2 -fPIC -Iengine/src/main/cpp -Iengine/src/main/cpp/lzma -c "$source" -o "$object"
done
g++ -O2 -fPIC -std=c++17 -Iengine/src/main/cpp -c engine/src/main/cpp/codec.cpp -o "$HOST_LIB/codec.o"
g++ -O2 -fPIC -std=c++17 -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  -Iengine/src/main/cpp -c engine/src/main/cpp/lscodec.cpp -o "$HOST_LIB/lscodec.o"
g++ -shared -o "$HOST_LIB/liblscodec.so" "$HOST_LIB"/*.o -lpthread

unzip -p "$ENGINE_AAR" classes.jar > "$OUT_DIR/engine-classes.jar"
javac -encoding UTF-8 -cp "$OUT_DIR/engine-classes.jar" -d "$CLASSES" tools/ApkBuildIntegration.java
java -Djava.library.path="$HOST_LIB" -cp "$CLASSES:$OUT_DIR/engine-classes.jar" \
  ApkBuildIntegration "$RUNTIME_APK" "$OUT_DIR/LS-Android-ARM64-test.apk"

BUILD_TOOLS="$ANDROID_SDK_ROOT/build-tools/36.0.0"
"$BUILD_TOOLS/apksigner" verify --verbose "$OUT_DIR/LS-Android-ARM64-test.apk"
"$BUILD_TOOLS/aapt2" dump badging "$OUT_DIR/LS-Android-ARM64-test.apk" > "$OUT_DIR/badging.txt"
grep -F "package: name='com.lsengine.integration'" "$OUT_DIR/badging.txt"
grep -F "launchable-activity: name='com.lsengine.runtime.RuntimeActivity'" "$OUT_DIR/badging.txt"
"$BUILD_TOOLS/zipalign" -c -P 16 -v 4 "$OUT_DIR/LS-Android-ARM64-test.apk"
unzip -l "$OUT_DIR/LS-Android-ARM64-test.apk" > "$OUT_DIR/contents.txt"
size=$(stat -c%s "$OUT_DIR/LS-Android-ARM64-test.apk")
if [ "$size" -gt 10485760 ]; then echo "Generated ARM64 APK exceeds 10 MiB: $size"; exit 1; fi
! grep -Eq 'lib/(x86_64|x86|armeabi-v7a|armeabi)/' "$OUT_DIR/contents.txt"
! grep -Fq 'liblsapp.so' "$OUT_DIR/contents.txt"
echo 'Real offline Android ARM64 package passed signature, manifest/resource, and 16 KB alignment checks.'
