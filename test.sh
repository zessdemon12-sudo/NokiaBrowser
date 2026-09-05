#!/bin/bash
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd "$DIR"

echo "=========================================================="
echo " Starting Nokia J2ME Browser Test Environment"
echo " Screen: 240x320 QVGA"
echo "=========================================================="

# 1. Ensure build exists
if [ ! -f "build/NokiaBrowser.jad" ]; then
    echo "Building browser first..."
    ./build.sh
fi

# 2. Start Gateway Server in background
echo "[1/2] Launching Gateway Server on port 8080..."
node server/server.js &
SERVER_PID=$!

# Trap Ctrl+C or exit to kill server
cleanup() {
    echo ""
    echo "Stopping Gateway Server (PID $SERVER_PID)..."
    kill $SERVER_PID 2>/dev/null || true
    echo "Done."
}
trap cleanup EXIT INT TERM

sleep 1

# 3. Launch MicroEmulator in 240x320
echo "[2/2] Launching 240x320 Nokia Emulator..."
echo "-> Keypad Controls in Emulator:"
echo "   - Arrows / D-Pad: Navigate & Scroll"
echo "   - Enter / Key 5: Open link / Play media"
echo "   - Key #: Enter URL or Bing Search"
echo "   - Key 0: Bookmarks"
echo "   - Key *: Fullscreen"
echo "   - Key 1 / 7: Page Up / Page Down"
echo ""

java -cp tools/microemu-javase.jar:tools/microemu-midp.jar \
  org.microemu.app.Main \
  --resizableDevice 240 320 \
  build/NokiaBrowser.jad

