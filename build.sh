#!/usr/bin/env bash
# Builds the mod jar with plain javac + jar — no Gradle/Loom required.
# Compile-time dependencies (fabric-loader, log4j-api) are fetched from Maven if missing.
set -euo pipefail
cd "$(dirname "$0")"

VERSION="1.0.0"
CLASSES="build/classes"
RES="build/resources"
OUT="release"
MAVEN_FABRIC="https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar"
MAVEN_LOG4J="https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-api/2.25.3/log4j-api-2.25.3.jar"

# ---- fetch a URL into lib/ unless already present ----
fetch() {
    local url="$1" dest="$2"
    if [[ -f "$dest" ]]; then return 0; fi
    echo "==> downloading $(basename "$dest")"
    mkdir -p "$(dirname "$dest")"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL -o "$dest" "$url"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$dest" "$url"
    elif command -v python3 >/dev/null 2>&1; then
        python3 - "$url" "$dest" <<'PY'
import sys, urllib.request
urllib.request.urlretrieve(sys.argv[1], sys.argv[2])
PY
    else
        echo "!! no download tool (curl/wget/python3) found" >&2; exit 1
    fi
}

fetch "$MAVEN_FABRIC" "lib/fabric-loader-0.19.3.jar"
fetch "$MAVEN_LOG4J"  "lib/log4j-api-2.25.3.jar"

rm -rf "$CLASSES" "$RES" "$OUT"
mkdir -p "$CLASSES" "$RES/native/ffmpeg" "$OUT"

echo "==> compiling (--release 17)"
javac --release 17 -encoding UTF-8 -Xlint:all \
    -cp "lib/fabric-loader-0.19.3.jar:lib/log4j-api-2.25.3.jar" \
    -d "$CLASSES" \
    src/main/java/github/xhserver000/watermedia_android_nativepatch/WaterMediaAndroidNativePatchMod.java

echo "==> staging resources"
cp lib/ffmpeg/*.so            "$RES/native/ffmpeg/"
cp lib/libstdc++.so.6         "$RES/native/libstdc++.so.6"
cp lib/libgcc_s.so.1          "$RES/native/libgcc_s.so.1"
# Copy the whole resources tree (fabric.mod.json + LICENSE + assets/<mod-id>/icon.png),
# so the icon referenced by fabric.mod.json is actually packaged.
cp -r src/main/resources/.    "$RES/"

echo "==> packaging"
( cd "$CLASSES" && jar cf "$OLDPWD/$OUT/watermedia-android-nativepatch-$VERSION.jar" . )
( cd "$RES" && jar uf "$OLDPWD/$OUT/watermedia-android-nativepatch-$VERSION.jar" . )

echo "==> verifying"
jar -tvf "$OUT/watermedia-android-nativepatch-$VERSION.jar" | tail -3
echo ""
echo "OK -> $OUT/watermedia-android-nativepatch-$VERSION.jar ($(du -h "$OUT/watermedia-android-nativepatch-$VERSION.jar" | cut -f1))"
