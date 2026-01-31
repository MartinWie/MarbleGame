#!/bin/bash
# Run tests for the MarbleGame project.
#
# Usage:
#   ./test.sh              # Kotlin unit tests + fast E2E tests (~10s)
#   ./test.sh --full       # Kotlin unit tests + ALL E2E tests (~2-3 min)
#   ./test.sh --headed     # Fast tests with browser visible
#   ./test.sh --full --headed  # All tests with browser visible
#
# Exit codes:
#   0 - All tests passed
#   1 - Tests failed

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Parse arguments
FULL=false
HEADED=false

for arg in "$@"; do
    case $arg in
        --full)
            FULL=true
            ;;
        --headed)
            HEADED=true
            ;;
        --help|-h)
            echo "Usage: $0 [--full] [--headed]"
            echo ""
            echo "Options:"
            echo "  --full      Include slow E2E tests (gameplay, disconnect scenarios)"
            echo "  --headed    Run E2E tests with browser visible"
            echo ""
            echo "Examples:"
            echo "  $0              # Unit tests + fast E2E tests (~10s)"
            echo "  $0 --full       # Unit tests + ALL E2E tests (~2-3 min)"
            echo "  $0 --headed     # Fast E2E with visible browser"
            echo "  $0 --full --headed  # All tests with visible browser"
            exit 0
            ;;
        *)
            echo "Unknown option: $arg"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

echo "==================================="
echo "  MarbleGame Test Suite"
echo "==================================="
echo ""

# Step 1: Run Kotlin unit tests
echo ">>> Running Kotlin unit tests..."
./gradlew test
echo ""
echo "✓ Kotlin unit tests passed"
echo ""

# Step 2: Run E2E tests
if [ "$FULL" = true ]; then
    echo ">>> Running ALL E2E tests (including slow tests)..."
    if [ "$HEADED" = true ]; then
        npm run test:e2e:headed
    else
        npm run test:e2e
    fi
else
    echo ">>> Running fast E2E tests..."
    if [ "$HEADED" = true ]; then
        npm run test:e2e:fast:headed
    else
        npm run test:e2e:fast
    fi
fi
echo ""
echo "✓ E2E tests passed"
echo ""

echo "==================================="
echo "  All tests passed!"
echo "==================================="
