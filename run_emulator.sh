#!/bin/bash
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd "$DIR"

if [ ! -f "build/NokiaBrowser.jad" ]; then
    echo "NokiaBrowser.jad not found! Running build.sh first..."
    ./build.sh
fi

echo "Launching MicroEmulator (240x320 Nokia QVGA Device)..."
java -cp tools/microemu-javase.jar:tools/microemu-midp.jar \
  org.microemu.app.Main \
  --resizableDevice 240 320 \
  build/NokiaBrowser.jad
