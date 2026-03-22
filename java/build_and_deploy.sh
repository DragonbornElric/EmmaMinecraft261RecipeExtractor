#!/bin/bash
# Build emma-bridge (Emmatone pathfinder + bridge mod) + overflow mod + logger mod, then deploy.
#
# Usage:
#   ./build_and_deploy.sh                    # build all + deploy all (local only)
#   ./build_and_deploy.sh --bridge           # build + deploy bridge + overflow only (skip logger)
#   ./build_and_deploy.sh --logger           # build + deploy logger only
#   ./build_and_deploy.sh --server           # build all + deploy all + deploy to server + restart
#   ./build_and_deploy.sh --bridge --server  # bridge + overflow + server deploy + restart
#
# IMPORTANT: Shut down Minecraft before running this script to avoid file lock issues.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BRIDGE_DIR="$SCRIPT_DIR/emma-pathfinder"
OVERFLOW_DIR="$SCRIPT_DIR/emma-overflow"
LOGGER_DIR="$SCRIPT_DIR/emma-gameplay-logger"
DIST_DIR="$SCRIPT_DIR/dist"

OVERFLOW_JAR="emma-overflow-0.1.0.jar"
BRIDGE_JAR="emma-bridge-mod-0.2.0.jar"
LOGGER_JAR="emma-gameplay-logger-0.1.0.jar"

EMMA_MODS="$APPDATA/PrismLauncher/instances/Emma-26.1-pre-2/minecraft/mods"
CAMERA_MODS="$APPDATA/PrismLauncher/instances/CameraBot/.minecraft/mods"

SERVER_HOST="192.168.0.225"
SERVER_USER="emmaserver"
SERVER_MODS="/var/opt/crafty/servers/EmmaServer/mods"

BRIDGE_ONLY=false
LOGGER_ONLY=false
DEPLOY_SERVER=false
for arg in "$@"; do
    case "$arg" in
        --bridge) BRIDGE_ONLY=true ;;
        --logger) LOGGER_ONLY=true ;;
        --server) DEPLOY_SERVER=true ;;
    esac
done

# Create dist directory for easy access to all built JARs
mkdir -p "$DIST_DIR"

# --- Logger-only mode: just build + deploy logger ---
if [[ "$LOGGER_ONLY" == true ]]; then
    echo "=== Building emma-gameplay-logger ==="
    cd "$LOGGER_DIR"
    ./gradlew.bat build 2>&1 | grep -v "^Note:" | grep -v "not valid semver" | grep -v "\[Incubating\]" | grep -v "problems-report"
    cp "$LOGGER_DIR/build/libs/$LOGGER_JAR" "$DIST_DIR/$LOGGER_JAR"
    echo "  dist: $LOGGER_JAR"

    # Deploy to server if requested
    if [[ "$DEPLOY_SERVER" == true ]]; then
        echo "=== Deploying logger to server at $SERVER_HOST ==="
        scp "$DIST_DIR/$LOGGER_JAR" "$SERVER_USER@$SERVER_HOST:$SERVER_MODS/$LOGGER_JAR"
        echo "  Server: deployed $LOGGER_JAR"
        echo "  NOTE: Restart the server via Crafty web UI to load new mods"
    fi

    echo ""
    echo "=== Done — JAR at: dist/$LOGGER_JAR ==="
    exit 0
fi

# --- Step 1: Build overflow mod ---
echo "=== Building emma-overflow ==="
cd "$OVERFLOW_DIR"
./gradlew.bat build 2>&1 | grep -v "^Note:" | grep -v "not valid semver" | grep -v "\[Incubating\]" | grep -v "problems-report"
echo ""

# --- Step 1b: Copy overflow JAR to bridge libs/ (compile dependency) ---
echo "=== Syncing overflow JAR to emma-pathfinder/libs/ ==="
cp "$OVERFLOW_DIR/build/libs/$OVERFLOW_JAR" "$BRIDGE_DIR/libs/$OVERFLOW_JAR"
echo "  Copied $OVERFLOW_JAR"
echo ""

# --- Step 2: Build bridge mod (includes Emmatone pathfinder) ---
echo "=== Building emma-bridge-mod ==="
cd "$BRIDGE_DIR"
./gradlew.bat build 2>&1 | grep -v "^Note:" | grep -v "not valid semver" | grep -v "\[Incubating\]" | grep -v "problems-report"
echo ""

# --- Step 3: Build emma-gameplay-logger (unless --bridge) ---
if [[ "$BRIDGE_ONLY" == false ]]; then
    echo "=== Building emma-gameplay-logger ==="
    cd "$LOGGER_DIR"
    ./gradlew.bat build 2>&1 | grep -v "^Note:" | grep -v "not valid semver" | grep -v "\[Incubating\]" | grep -v "problems-report"
    echo ""
fi

# --- Step 4: Copy all JARs to dist/ for easy access ---
echo "=== Copying JARs to dist/ ==="
cp "$BRIDGE_DIR/build/libs/$BRIDGE_JAR" "$DIST_DIR/$BRIDGE_JAR"
cp "$OVERFLOW_DIR/build/libs/$OVERFLOW_JAR" "$DIST_DIR/$OVERFLOW_JAR"
if [[ "$BRIDGE_ONLY" == false ]]; then
    cp "$LOGGER_DIR/build/libs/$LOGGER_JAR" "$DIST_DIR/$LOGGER_JAR"
fi
echo "  All JARs copied to dist/"
ls -la "$DIST_DIR"/*.jar
echo ""

# --- Step 5: Deploy to mods folders ---
echo "=== Deploying to PrismLauncher mods folders ==="

# Remove old bridge mod version
rm -f "$EMMA_MODS/emma-bridge-mod-0.1.0.jar" 2>/dev/null
rm -f "$CAMERA_MODS/emma-bridge-mod-0.1.0.jar" 2>/dev/null

# Emma instance: bridge mod + overflow mod (Emmatone pathfinder is bundled inside bridge)
if [[ -d "$EMMA_MODS" ]]; then
    cp "$BRIDGE_DIR/build/libs/$BRIDGE_JAR" "$EMMA_MODS/$BRIDGE_JAR"
    echo "  Emma: deployed $BRIDGE_JAR"
    cp "$OVERFLOW_DIR/build/libs/$OVERFLOW_JAR" "$EMMA_MODS/$OVERFLOW_JAR"
    echo "  Emma: deployed $OVERFLOW_JAR"
else
    echo "  WARNING: Emma mods folder not found at $EMMA_MODS"
fi

# CameraBot instance: bridge mod + overflow mod (spectator only)
if [[ -d "$CAMERA_MODS" ]]; then
    cp "$BRIDGE_DIR/build/libs/$BRIDGE_JAR" "$CAMERA_MODS/$BRIDGE_JAR"
    echo "  CameraBot: deployed $BRIDGE_JAR"
    cp "$OVERFLOW_DIR/build/libs/$OVERFLOW_JAR" "$CAMERA_MODS/$OVERFLOW_JAR"
    echo "  CameraBot: deployed $OVERFLOW_JAR"
else
    echo "  WARNING: CameraBot mods folder not found at $CAMERA_MODS"
fi

# --- Step 6: Deploy overflow mod to server + restart ---
if [[ "$DEPLOY_SERVER" == true ]]; then
    echo ""
    echo "=== Deploying mods to server at $SERVER_HOST (Crafty/EmmaServer) ==="

    # Copy overflow JAR to server (only server-side mod)
    scp "$DIST_DIR/$OVERFLOW_JAR" "$SERVER_USER@$SERVER_HOST:$SERVER_MODS/$OVERFLOW_JAR"
    echo "  Server: deployed $OVERFLOW_JAR"

    echo ""
    echo "  NOTE: Restart the server via Crafty web UI to load new mods"
else
    echo ""
    echo "  NOTE: Use --server to also deploy $OVERFLOW_JAR + $LOGGER_JAR to the server at $SERVER_HOST"
fi

echo ""
echo "=== Done ==="
