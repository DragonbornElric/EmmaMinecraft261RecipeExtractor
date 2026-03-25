#!/bin/bash
# Build emma-bridge (Emmatone pathfinder + bridge mod) + logger mod + twitch mod + endinv mod, then deploy.
#
# Usage:
#   ./build_and_deploy.sh                    # build all + deploy all (local only)
#   ./build_and_deploy.sh --bridge           # build + deploy bridge only (skip logger/twitch/endinv)
#   ./build_and_deploy.sh --logger           # build + deploy logger only
#   ./build_and_deploy.sh --twitch           # build + deploy twitch mod only
#   ./build_and_deploy.sh --endinv           # build + deploy endless inventory only
#   ./build_and_deploy.sh --recipe-extractor # build + deploy recipe extractor only (server, on-demand)
#   ./build_and_deploy.sh --server           # build all + deploy all + deploy to server + restart
#   ./build_and_deploy.sh --bridge --server  # bridge + server deploy + restart
#
# IMPORTANT: Shut down Minecraft before running this script to avoid file lock issues.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BRIDGE_DIR="$SCRIPT_DIR/emma-pathfinder"
LOGGER_DIR="$SCRIPT_DIR/emma-gameplay-logger"
TWITCH_DIR="$SCRIPT_DIR/emma-twitch"
ENDINV_DIR="$SCRIPT_DIR/emma-endinv"
RECIPE_DIR="$SCRIPT_DIR/emma-recipe-extractor"
DIST_DIR="$SCRIPT_DIR/dist"

BRIDGE_JAR="emma-bridge-mod-0.2.0.jar"
LOGGER_JAR="emma-gameplay-logger-0.1.0.jar"
TWITCH_JAR="emma-twitch-0.1.0.jar"
ENDINV_JAR="emma-endinv-1.2.0.jar"
RECIPE_JAR="emma-recipe-extractor-0.1.0.jar"

EMMA_MODS="$APPDATA/PrismLauncher/instances/Emma/.minecraft/mods"
CAMERA_MODS="$APPDATA/PrismLauncher/instances/CameraBot/.minecraft/mods"
ELRIC_MODS="$APPDATA/PrismLauncher/instances/Elric/.minecraft/mods"

SERVER_HOST="192.168.0.225"
SERVER_USER="emmaserver"
SERVER_MODS="/var/opt/crafty/servers/EmmaServer/mods"

BRIDGE_ONLY=false
LOGGER_ONLY=false
TWITCH_ONLY=false
ENDINV_ONLY=false
RECIPE_ONLY=false
DEPLOY_SERVER=false
for arg in "$@"; do
    case "$arg" in
        --bridge) BRIDGE_ONLY=true ;;
        --logger) LOGGER_ONLY=true ;;
        --twitch) TWITCH_ONLY=true ;;
        --endinv) ENDINV_ONLY=true ;;
        --recipe-extractor) RECIPE_ONLY=true ;;
        --server) DEPLOY_SERVER=true ;;
    esac
done

# Create dist directory for easy access to all built JARs
mkdir -p "$DIST_DIR"

# --- Twitch-only mode: just build + deploy twitch mod ---
if [[ "$TWITCH_ONLY" == true ]]; then
    echo "=== Building emma-twitch ==="
    cd "$TWITCH_DIR"
    ./gradlew.bat build 2>&1 | grep -v "^Note:" | grep -v "not valid semver" | grep -v "\[Incubating\]" | grep -v "problems-report"
    cp "$TWITCH_DIR/build/libs/$TWITCH_JAR" "$DIST_DIR/$TWITCH_JAR"
    echo "  dist: $TWITCH_JAR"

    # Deploy to server if requested (twitch is server-only)
    if [[ "$DEPLOY_SERVER" == true ]]; then
        echo "=== Deploying twitch mod to server at $SERVER_HOST ==="
        scp "$DIST_DIR/$TWITCH_JAR" "$SERVER_USER@$SERVER_HOST:$SERVER_MODS/$TWITCH_JAR"
        echo "  Server: deployed $TWITCH_JAR"
        echo "  NOTE: Restart the server via Crafty web UI to load new mods"
    fi

    echo ""
    echo "=== Done — JAR at: dist/$TWITCH_JAR ==="
    exit 0
fi

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

# --- Endinv-only mode: just build + deploy endless inventory ---
if [[ "$ENDINV_ONLY" == true ]]; then
    echo "=== Building emma-endinv ==="
    cd "$ENDINV_DIR"
    ./gradlew.bat build 2>&1 | grep -v "^Note:" | grep -v "not valid semver" | grep -v "\[Incubating\]" | grep -v "problems-report"
    cp "$ENDINV_DIR/build/libs/$ENDINV_JAR" "$DIST_DIR/$ENDINV_JAR"
    echo "  dist: $ENDINV_JAR"

    # Deploy to Emma instance (client+server mod)
    if [[ -d "$EMMA_MODS" ]]; then
        cp "$DIST_DIR/$ENDINV_JAR" "$EMMA_MODS/$ENDINV_JAR"
        echo "  Emma: deployed $ENDINV_JAR"
    else
        echo "  WARNING: Emma mods folder not found at $EMMA_MODS"
    fi

    # Deploy to Elric instance (endinv only)
    if [[ -d "$ELRIC_MODS" ]]; then
        cp "$DIST_DIR/$ENDINV_JAR" "$ELRIC_MODS/$ENDINV_JAR"
        echo "  Elric: deployed $ENDINV_JAR"
    else
        echo "  WARNING: Elric mods folder not found at $ELRIC_MODS"
    fi

    # Deploy to server if requested
    if [[ "$DEPLOY_SERVER" == true ]]; then
        echo "=== Deploying endinv to server at $SERVER_HOST ==="
        scp "$DIST_DIR/$ENDINV_JAR" "$SERVER_USER@$SERVER_HOST:$SERVER_MODS/$ENDINV_JAR"
        echo "  Server: deployed $ENDINV_JAR"
        echo "  NOTE: Restart the server via Crafty web UI to load new mods"
    fi

    echo ""
    echo "=== Done — JAR at: dist/$ENDINV_JAR ==="
    exit 0
fi

# --- Recipe-extractor-only mode: build + deploy (server only, on-demand) ---
if [[ "$RECIPE_ONLY" == true ]]; then
    echo "=== Building emma-recipe-extractor ==="
    cd "$RECIPE_DIR"
    ./gradlew.bat build 2>&1 | grep -v "^Note:" | grep -v "not valid semver" | grep -v "\[Incubating\]" | grep -v "problems-report"
    cp "$RECIPE_DIR/build/libs/$RECIPE_JAR" "$DIST_DIR/$RECIPE_JAR"
    echo "  dist: $RECIPE_JAR"

    # Deploy to server if requested (server-only mod)
    if [[ "$DEPLOY_SERVER" == true ]]; then
        echo "=== Deploying recipe-extractor to server at $SERVER_HOST ==="
        scp "$DIST_DIR/$RECIPE_JAR" "$SERVER_USER@$SERVER_HOST:$SERVER_MODS/$RECIPE_JAR"
        echo "  Server: deployed $RECIPE_JAR"
        echo "  NOTE: Restart the server via Crafty web UI to load new mods"
    fi

    echo ""
    echo "=== Done — JAR at: dist/$RECIPE_JAR ==="
    exit 0
fi

# --- Step 1: Build bridge mod (includes Emmatone pathfinder) ---
echo "=== Building emma-bridge-mod ==="
cd "$BRIDGE_DIR"
./gradlew.bat build 2>&1 | grep -v "^Note:" | grep -v "not valid semver" | grep -v "\[Incubating\]" | grep -v "problems-report"
echo ""

# --- Step 2: Build emma-gameplay-logger (unless --bridge) ---
if [[ "$BRIDGE_ONLY" == false ]]; then
    echo "=== Building emma-gameplay-logger ==="
    cd "$LOGGER_DIR"
    ./gradlew.bat build 2>&1 | grep -v "^Note:" | grep -v "not valid semver" | grep -v "\[Incubating\]" | grep -v "problems-report"
    echo ""
fi

# --- Step 2b: Build emma-twitch (unless --bridge) ---
if [[ "$BRIDGE_ONLY" == false ]]; then
    echo "=== Building emma-twitch ==="
    cd "$TWITCH_DIR"
    ./gradlew.bat build 2>&1 | grep -v "^Note:" | grep -v "not valid semver" | grep -v "\[Incubating\]" | grep -v "problems-report"
    echo ""
fi

# --- Step 2c: Build emma-endinv (unless --bridge) ---
if [[ "$BRIDGE_ONLY" == false ]]; then
    echo "=== Building emma-endinv ==="
    cd "$ENDINV_DIR"
    ./gradlew.bat build 2>&1 | grep -v "^Note:" | grep -v "not valid semver" | grep -v "\[Incubating\]" | grep -v "problems-report"
    echo ""
fi

# --- Step 3: Copy all JARs to dist/ for easy access ---
echo "=== Copying JARs to dist/ ==="
cp "$BRIDGE_DIR/build/libs/$BRIDGE_JAR" "$DIST_DIR/$BRIDGE_JAR"
if [[ "$BRIDGE_ONLY" == false ]]; then
    cp "$LOGGER_DIR/build/libs/$LOGGER_JAR" "$DIST_DIR/$LOGGER_JAR"
    cp "$TWITCH_DIR/build/libs/$TWITCH_JAR" "$DIST_DIR/$TWITCH_JAR"
    cp "$ENDINV_DIR/build/libs/$ENDINV_JAR" "$DIST_DIR/$ENDINV_JAR"
fi
echo "  All JARs copied to dist/"
ls -la "$DIST_DIR"/*.jar
echo ""

# --- Step 4: Deploy to mods folders ---
echo "=== Deploying to PrismLauncher mods folders ==="

# Remove old bridge mod version
rm -f "$EMMA_MODS/emma-bridge-mod-0.1.0.jar" 2>/dev/null
rm -f "$CAMERA_MODS/emma-bridge-mod-0.1.0.jar" 2>/dev/null

# Emma instance: bridge mod + endinv (Emmatone pathfinder is bundled inside bridge)
if [[ -d "$EMMA_MODS" ]]; then
    cp "$BRIDGE_DIR/build/libs/$BRIDGE_JAR" "$EMMA_MODS/$BRIDGE_JAR"
    echo "  Emma: deployed $BRIDGE_JAR"
    if [[ "$BRIDGE_ONLY" == false ]]; then
        cp "$ENDINV_DIR/build/libs/$ENDINV_JAR" "$EMMA_MODS/$ENDINV_JAR"
        echo "  Emma: deployed $ENDINV_JAR"
    fi
else
    echo "  WARNING: Emma mods folder not found at $EMMA_MODS"
fi

# Elric instance: endinv only
if [[ "$BRIDGE_ONLY" == false ]]; then
    if [[ -d "$ELRIC_MODS" ]]; then
        cp "$ENDINV_DIR/build/libs/$ENDINV_JAR" "$ELRIC_MODS/$ENDINV_JAR"
        echo "  Elric: deployed $ENDINV_JAR"
    else
        echo "  WARNING: Elric mods folder not found at $ELRIC_MODS"
    fi
fi

# CameraBot instance: bridge mod (spectator only)
if [[ -d "$CAMERA_MODS" ]]; then
    cp "$BRIDGE_DIR/build/libs/$BRIDGE_JAR" "$CAMERA_MODS/$BRIDGE_JAR"
    echo "  CameraBot: deployed $BRIDGE_JAR"
else
    echo "  WARNING: CameraBot mods folder not found at $CAMERA_MODS"
fi

# --- Step 5: Deploy mods to server ---
if [[ "$DEPLOY_SERVER" == true ]]; then
    echo ""
    echo "=== Deploying mods to server at $SERVER_HOST (Crafty/EmmaServer) ==="

    # Copy twitch JAR to server (server-side mod)
    if [[ "$BRIDGE_ONLY" == false ]]; then
        scp "$DIST_DIR/$TWITCH_JAR" "$SERVER_USER@$SERVER_HOST:$SERVER_MODS/$TWITCH_JAR"
        echo "  Server: deployed $TWITCH_JAR"
        scp "$DIST_DIR/$ENDINV_JAR" "$SERVER_USER@$SERVER_HOST:$SERVER_MODS/$ENDINV_JAR"
        echo "  Server: deployed $ENDINV_JAR"
    fi

    echo ""
    echo "  NOTE: Restart the server via Crafty web UI to load new mods"
else
    echo ""
    echo "  NOTE: Use --server to also deploy server mods to $SERVER_HOST"
fi

echo ""
echo "=== Done ==="
