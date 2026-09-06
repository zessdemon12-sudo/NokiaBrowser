#!/usr/bin/env bash
#
# Nokia Browser Cellular Tunnel Starter
# Launches local gateway server if not running, then opens public bore.pub plain HTTP tunnel
#

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$SCRIPT_DIR"

if ! nc -z localhost 8080 2>/dev/null; then
    echo "Starting Gateway Server on port 8080..."
    node server/server.js &
    sleep 2
fi

node server/tunnel.js
