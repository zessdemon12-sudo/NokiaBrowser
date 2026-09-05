#!/bin/bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd "$DIR"

echo "=========================================================="
echo " Building Nokia J2ME Web Browser (240x320 QVGA)"
echo " Profile: MIDP 2.0 / CLDC 1.1"
echo "=========================================================="

# 1. Clean build directory
rm -rf build/classes build/NokiaBrowser.jar build/NokiaBrowser.jad
mkdir -p build/classes

# 2. Compile Java sources
echo "[1/4] Compiling Java sources with Eclipse ECJ (CLDC 1.1 target)..."
java -jar tools/ecj.jar \
  -source 1.3 \
  -target cldc1.1 \
  -nowarn \
  -bootclasspath tools/cldcapi11.jar:tools/midpapi20.jar:tools/mmapi-jsr135.jar \
  -d build/classes \
  client/src/com/nokia/browser/*.java \
  client/src/com/nokia/browser/model/*.java \
  client/src/com/nokia/browser/ui/*.java \
  client/src/com/nokia/browser/media/*.java \
  client/src/com/nokia/browser/net/*.java \
  client/src/com/nokia/browser/storage/*.java

# 3. Copy resources
echo "[2/4] Bundling resources and icons..."
cp client/res/icon.png build/classes/icon.png

# 4. Create JAR
echo "[3/4] Creating NokiaBrowser.jar..."
jar cfm build/NokiaBrowser.jar client/res/MANIFEST.MF -C build/classes .

JAR_SIZE=$(stat -c%s "build/NokiaBrowser.jar" 2>/dev/null || stat -f%z "build/NokiaBrowser.jar")

# 5. Create JAD
echo "[4/4] Creating NokiaBrowser.jad descriptor..."
cat << JAD_EOF > build/NokiaBrowser.jad
MIDlet-1: Nokia Web, /icon.png, com.nokia.browser.BrowserMIDlet
MIDlet-Name: Nokia Web
MIDlet-Vendor: Antigravity
MIDlet-Version: 1.0.0
MicroEdition-Configuration: CLDC-1.1
MicroEdition-Profile: MIDP-2.0
MIDlet-Jar-URL: NokiaBrowser.jar
MIDlet-Jar-Size: $JAR_SIZE
JAD_EOF

echo "=========================================================="
echo " Build Succeeded!"
echo " JAR: build/NokiaBrowser.jar ($JAR_SIZE bytes)"
echo " JAD: build/NokiaBrowser.jad"
echo "=========================================================="
