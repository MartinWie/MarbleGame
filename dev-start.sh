#!/bin/bash
# Start the MarbleGame development server with PostHog analytics disabled.
# The server runs in the background and logs to dev-server.log.
# PID is stored in dev-server.pid for stop/reload scripts.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

PID_FILE="dev-server.pid"
LOG_FILE="dev-server.log"

# Check if already running
if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if ps -p "$PID" > /dev/null 2>&1; then
        echo "Dev server is already running (PID: $PID)"
        echo "Use ./dev-stop.sh to stop it or ./dev-reload.sh to restart"
        exit 1
    else
        # Stale PID file
        rm "$PID_FILE"
    fi
fi

echo "Starting MarbleGame dev server..."
echo "  - PostHog analytics: disabled"
echo "  - Log file: $LOG_FILE"

# Start the server in background (--no-daemon ensures reliable PID tracking)
POSTHOG_ENABLED=false nohup ./gradlew run --no-daemon > "$LOG_FILE" 2>&1 &
echo $! > "$PID_FILE"

echo "  - PID: $(cat "$PID_FILE")"
echo ""
echo "Server starting... (may take a few seconds to compile)"
echo "View logs: tail -f $LOG_FILE"
echo "Open: http://localhost:8080"
