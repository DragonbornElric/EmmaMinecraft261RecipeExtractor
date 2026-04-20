#!/bin/bash
# Build emma-bridge (Emmatone pathfinder + bridge mod) + logger mod + endinv mod, then deploy.
#
# Usage:
#   ./build_and_deploy.sh                    # build all + deploy all (local only)
#   ./build_and_deploy.sh --bridge           # build + deploy bridge only (skip logger/endinv)
#   ./build_and_deploy.sh --logger           # build + deploy logger only
#   ./build_and_deploy.sh --endinv           # build + deploy endless inventory only to all configured client instances
#   ./build_and_deploy.sh --recipe-extractor # build + deploy recipe extractor only (server, on-demand)
#   ./build_and_deploy.sh --server           # build all + deploy all + deploy to server + restart
#   ./build_and_deploy.sh --bridge --server  # bridge + server deploy + restart
#
# IMPORTANT: Shut down Minecraft before running this script to avoid file lock issues.
# Twitch integration now lives in a separate repository:
# https://github.com/DragonbornElric/emmaminecraft261twitch

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BRIDGE_DIR="$SCRIPT_DIR/emma-pathfinder"
LOGGER_DIR="$SCRIPT_DIR/emma-gameplay-logger"
ENDINV_DIR="$SCRIPT_DIR/emma-endinv"
RECIPE_DIR="$SCRIPT_DIR/emma-recipe-extractor"
DIST_DIR="$SCRIPT_DIR/dist"

BRIDGE_JAR="emma-bridge-mod-0.2.0.jar"
LOGGER_JAR="emma-gameplay-logger-0.1.0.jar"
ENDINV_JAR="emma-endinv-1.2.0.jar"
RECIPE_JAR="emma-recipe-extractor-0.1.0.jar"

PRISM_INSTANCES_DIR="$APPDATA/PrismLauncher/instances"

# Resolve a Prism instance mods folder, supporting both MC 26.x layout
# (<instance>/minecraft/mods) and legacy layout (<instance>/.minecraft/mods).
# Accepts multiple instance names and returns the first existing directory.
resolve_mods_dir() {
    for instance_name in "$@"; do
        local modern="$PRISM_INSTANCES_DIR/$instance_name/minecraft/mods"
        if [[ -d "$modern" ]]; then
            echo "$modern"
            return 0
        fi

        local legacy="$PRISM_INSTANCES_DIR/$instance_name/.minecraft/mods"
        if [[ -d "$legacy" ]]; then
            echo "$legacy"
            return 0
        fi
    done

    return 1
}

# Try modern/renamed instances first, then legacy names.
if EMMA_MODS_RESOLVED="$(resolve_mods_dir "Emma 26.1" "Emma")"; then
    EMMA_MODS="$EMMA_MODS_RESOLVED"
else
    EMMA_MODS="$PRISM_INSTANCES_DIR/Emma 26.1/minecraft/mods"
fi

if CAMERA_MODS_RESOLVED="$(resolve_mods_dir "CameraBot 26.1" "CameraBot")"; then
    CAMERA_MODS="$CAMERA_MODS_RESOLVED"
else
    CAMERA_MODS="$PRISM_INSTANCES_DIR/CameraBot/minecraft/mods"
fi

if ELRIC_MODS_RESOLVED="$(resolve_mods_dir "Elric 26.1" "Elric")"; then
    ELRIC_MODS="$ELRIC_MODS_RESOLVED"
else
    ELRIC_MODS="$PRISM_INSTANCES_DIR/Elric/minecraft/mods"
fi

if ALTOCLEF_MODS_RESOLVED="$(resolve_mods_dir "Emma 26.1 AltoClef")"; then
    ALTOCLEF_MODS="$ALTOCLEF_MODS_RESOLVED"
else
    ALTOCLEF_MODS="$PRISM_INSTANCES_DIR/Emma 26.1 AltoClef/minecraft/mods"
fi

SERVER_HOST="192.168.0.225"
SERVER_USER="emmaserver"
SERVER_MODS="/var/opt/crafty/servers/EmmaServer/mods"

BRIDGE_ONLY=false
LOGGER_ONLY=false
ENDINV_ONLY=false
RECIPE_ONLY=false
DEPLOY_SERVER=false
for arg in "$@"; do
    case "$arg" in
        --bridge) BRIDGE_ONLY=true ;;
        --logger) LOGGER_ONLY=true ;;
        --twitch)
            echo "emma-twitch moved to https://github.com/DragonbornElric/emmaminecraft261twitch" >&2
            exit 1
            ;;
        --endinv) ENDINV_ONLY=true ;;
        --recipe-extractor) RECIPE_ONLY=true ;;
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

    # Deploy to AltoClef instance as well.
    if [[ -d "$ALTOCLEF_MODS" ]]; then
        cp "$DIST_DIR/$ENDINV_JAR" "$ALTOCLEF_MODS/$ENDINV_JAR"
        echo "  Emma 26.1 AltoClef: deployed $ENDINV_JAR"
    else
        echo "  WARNING: AltoClef mods folder not found at $ALTOCLEF_MODS"
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

# --- Step 2b: Build emma-endinv (unless --bridge) ---
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
    cp "$ENDINV_DIR/build/libs/$ENDINV_JAR" "$DIST_DIR/$ENDINV_JAR"
fi
echo "  All JARs copied to dist/"
ls -la "$DIST_DIR"/*.jar
echo ""

# --- Step 4: Deploy to mods folders ---
echo "=== Deploying to PrismLauncher mods folders ==="

echo "  Emma mods dir: $EMMA_MODS"
echo "  CameraBot mods dir: $CAMERA_MODS"
if [[ "$BRIDGE_ONLY" == false ]]; then
    echo "  Elric mods dir: $ELRIC_MODS"
    echo "  Emma 26.1 AltoClef mods dir: $ALTOCLEF_MODS"
fi

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

    if [[ -d "$ALTOCLEF_MODS" ]]; then
        cp "$ENDINV_DIR/build/libs/$ENDINV_JAR" "$ALTOCLEF_MODS/$ENDINV_JAR"
        echo "  Emma 26.1 AltoClef: deployed $ENDINV_JAR"
    else
        echo "  WARNING: AltoClef mods folder not found at $ALTOCLEF_MODS"
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

    if [[ "$BRIDGE_ONLY" == false ]]; then
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
