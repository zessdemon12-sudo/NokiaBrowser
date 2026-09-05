#!/bin/bash
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd "$DIR"

echo "=========================================================="
echo " Starting Nokia J2ME Browser in KEmulator (kemnnx64)"
echo " Screen: 240x320 QVGA (Nokia S40 / S60)"
echo "=========================================================="

# 1. Ensure build exists
if [ ! -f "build/NokiaBrowser.jar" ]; then
    echo "[1/3] Building NokiaBrowser.jar..."
    ./build.sh
fi

# 2. Ensure Gateway Server is running on port 8080
if ! lsof -i :8080 >/dev/null 2>&1; then
    echo "[2/3] Starting Modern Gateway Server in background..."
    if command -v tmux >/dev/null 2>&1; then
        tmux kill-session -t nokia-gateway 2>/dev/null || true
        tmux new-session -d -s nokia-gateway "node server/server.js"
    else
        nohup node server/server.js > server.log 2>&1 &
    fi
    sleep 1
    if lsof -i :8080 >/dev/null 2>&1; then
        echo "      Gateway Server is active on port 8080."
    else
        echo "      Warning: Gateway Server failed to start. Run 'node server/server.js' manually."
    fi
else
    echo "[2/3] Gateway Server is already running on port 8080."
fi

# 3. Locate and launch KEmulator
KEM="/home/a1/Downloads/kemnnx64/kemulator.sh"
if [ ! -f "$KEM" ]; then
    KEM=$(find /home/a1 -name "kemulator.sh" 2>/dev/null | head -n 1)
fi

if [ -f "$KEM" ]; then
    echo "[3/3] Launching KEmulator with build/NokiaBrowser.jar..."
    bash "$KEM" "$DIR/build/NokiaBrowser.jar"
else
    echo "Error: KEmulator script not found at $KEM"
    exit 1
fi
