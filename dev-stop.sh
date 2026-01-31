#!/bin/bash
# Stop the MarbleGame development server.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

PID_FILE="dev-server.pid"

if [ ! -f "$PID_FILE" ]; then
    echo "Dev server is not running (no PID file found)"
    exit 0
fi

PID=$(cat "$PID_FILE")

if ! ps -p "$PID" > /dev/null 2>&1; then
    echo "Dev server is not running (stale PID file)"
    rm "$PID_FILE"
    exit 0
fi

echo "Stopping dev server (PID: $PID)..."

# Kill the gradle process and its children
pkill -P "$PID" 2>/dev/null || true
kill "$PID" 2>/dev/null || true

# Wait for process to terminate
for i in {1..10}; do
    if ! ps -p "$PID" > /dev/null 2>&1; then
        break
    fi
    sleep 0.5
done

# Force kill if still running
if ps -p "$PID" > /dev/null 2>&1; then
    echo "Force killing..."
    kill -9 "$PID" 2>/dev/null || true
fi

rm -f "$PID_FILE"
echo "Dev server stopped"
