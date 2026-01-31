#!/bin/bash
# Reload (stop and start) the MarbleGame development server.
# Useful after making code changes.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "Reloading dev server..."
echo ""

# Stop if running
./dev-stop.sh

echo ""

# Start again
./dev-start.sh
