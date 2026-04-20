#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RECIPE_DIR="$SCRIPT_DIR/emma-recipe-extractor"
DIST_DIR="$SCRIPT_DIR/dist"

gradle_property() {
    local key="$1"
    grep -E "^${key}=" "$RECIPE_DIR/gradle.properties" | head -n 1 | cut -d '=' -f 2- | tr -d '\r'
}

ARCHIVES_BASE_NAME="$(gradle_property archives_base_name)"
MINECRAFT_VERSION="$(gradle_property minecraft_version)"
LOADER_VERSION="$(gradle_property loader_version)"
MOD_VERSION="$(gradle_property mod_version)"
RECIPE_JAR="${ARCHIVES_BASE_NAME}-mc${MINECRAFT_VERSION}-fabric-loader${LOADER_VERSION}-${MOD_VERSION}.jar"

if [[ -n "${EMMA_RECIPE_EXTRACTOR_PROJECT_CACHE_DIR:-}" ]]; then
    PROJECT_CACHE_DIR="$EMMA_RECIPE_EXTRACTOR_PROJECT_CACHE_DIR"
elif [[ -n "${TMPDIR:-}" ]]; then
    PROJECT_CACHE_DIR="${TMPDIR%/}/emma-recipe-extractor-gradle-cache"
elif [[ -n "${TEMP:-}" ]]; then
    PROJECT_CACHE_DIR="${TEMP%/}/emma-recipe-extractor-gradle-cache"
elif [[ -n "${TMP:-}" ]]; then
    PROJECT_CACHE_DIR="${TMP%/}/emma-recipe-extractor-gradle-cache"
else
    PROJECT_CACHE_DIR="/tmp/emma-recipe-extractor-gradle-cache"
fi

ISOLATED_BUILD_ROOT="$PROJECT_CACHE_DIR/workspace"
ISOLATED_RECIPE_DIR="$ISOLATED_BUILD_ROOT/java/emma-recipe-extractor"
GRADLE_USER_HOME_DIR="$PROJECT_CACHE_DIR/gradle-user-home"
BUILD_RECIPE_DIR="$RECIPE_DIR"

DEPLOY_SERVER=false
LOCAL_MODS_DIR=""

usage() {
    cat <<'EOF'
Usage:
  ./build_and_deploy.sh
  ./build_and_deploy.sh --mods-dir /path/to/mods
  ./build_and_deploy.sh --server
  ./build_and_deploy.sh --mods-dir /path/to/mods --server

Behavior:
  - Builds java/emma-recipe-extractor
  - Copies the JAR into java/dist/
  - Optionally copies the JAR into a local mods directory
  - Optionally uploads the JAR with scp using these environment variables:
      EMMA_RECIPE_EXTRACTOR_SERVER_HOST
      EMMA_RECIPE_EXTRACTOR_SERVER_USER
      EMMA_RECIPE_EXTRACTOR_SERVER_MODS_DIR
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --mods-dir)
            if [[ $# -lt 2 ]]; then
                echo "ERROR: --mods-dir requires a directory path." >&2
                exit 1
            fi
            LOCAL_MODS_DIR="$2"
            shift 2
            ;;
        --server)
            DEPLOY_SERVER=true
            shift
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            echo "ERROR: Unknown argument: $1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

resolve_java_bin() {
    if [[ -n "${JAVA_HOME:-}" ]]; then
        if [[ -x "$JAVA_HOME/bin/java" ]]; then
            echo "$JAVA_HOME/bin/java"
            return 0
        fi
        if [[ -x "$JAVA_HOME/bin/java.exe" ]]; then
            echo "$JAVA_HOME/bin/java.exe"
            return 0
        fi
        echo "ERROR: JAVA_HOME is set but no Java executable was found under $JAVA_HOME/bin." >&2
        exit 1
    fi

    if command -v java >/dev/null 2>&1; then
        command -v java
        return 0
    fi

    echo "ERROR: Java was not found. Set JAVA_HOME or put Java 25 on PATH." >&2
    exit 1
}

require_java_25() {
    local java_bin
    local java_major

    java_bin="$(resolve_java_bin)"
    java_major="$("$java_bin" -version 2>&1 | awk -F '[\".]' '/version/ {print $2; exit}')"

    if [[ "$java_major" != "25" ]]; then
        echo "ERROR: Java 25 is required. Current Java reports major version $java_major via $java_bin." >&2
        exit 1
    fi
}

prepare_isolated_workspace() {
    rm -rf "$ISOLATED_BUILD_ROOT"
    mkdir -p "$ISOLATED_BUILD_ROOT/java"
    cp "$REPO_ROOT/LICENSE" "$ISOLATED_BUILD_ROOT/LICENSE"
    cp -R "$RECIPE_DIR" "$ISOLATED_BUILD_ROOT/java/"
    rm -rf \
        "$ISOLATED_RECIPE_DIR/.gradle" \
        "$ISOLATED_RECIPE_DIR/bin" \
        "$ISOLATED_RECIPE_DIR/build" \
        "$ISOLATED_RECIPE_DIR/run"
    BUILD_RECIPE_DIR="$ISOLATED_RECIPE_DIR"
}

run_gradle_build() {
    echo "=== Building emma-recipe-extractor ==="
    require_java_25
    mkdir -p "$PROJECT_CACHE_DIR" "$GRADLE_USER_HOME_DIR"
    prepare_isolated_workspace
    cd "$BUILD_RECIPE_DIR"
    if [[ "${OS:-}" == "Windows_NT" ]]; then
        GRADLE_USER_HOME="$GRADLE_USER_HOME_DIR" ./gradlew.bat --project-cache-dir "$PROJECT_CACHE_DIR/project-cache" build
    else
        GRADLE_USER_HOME="$GRADLE_USER_HOME_DIR" ./gradlew --project-cache-dir "$PROJECT_CACHE_DIR/project-cache" build
    fi
}

copy_to_dist() {
    mkdir -p "$DIST_DIR"
    cp "$BUILD_RECIPE_DIR/build/libs/$RECIPE_JAR" "$DIST_DIR/$RECIPE_JAR"
    echo "=== Wrote dist/$RECIPE_JAR ==="
}

copy_to_local_mods() {
    local target_dir="$1"
    mkdir -p "$target_dir"
    cp "$DIST_DIR/$RECIPE_JAR" "$target_dir/$RECIPE_JAR"
    echo "=== Copied JAR to $target_dir ==="
}

deploy_to_server() {
    local server_host="${EMMA_RECIPE_EXTRACTOR_SERVER_HOST:-}"
    local server_user="${EMMA_RECIPE_EXTRACTOR_SERVER_USER:-}"
    local server_mods_dir="${EMMA_RECIPE_EXTRACTOR_SERVER_MODS_DIR:-}"

    if [[ -z "$server_host" || -z "$server_user" || -z "$server_mods_dir" ]]; then
        echo "ERROR: --server requires EMMA_RECIPE_EXTRACTOR_SERVER_HOST, EMMA_RECIPE_EXTRACTOR_SERVER_USER, and EMMA_RECIPE_EXTRACTOR_SERVER_MODS_DIR." >&2
        exit 1
    fi

    echo "=== Uploading to $server_user@$server_host:$server_mods_dir ==="
    scp "$DIST_DIR/$RECIPE_JAR" "$server_user@$server_host:$server_mods_dir/$RECIPE_JAR"
}

run_gradle_build
copy_to_dist

if [[ -n "$LOCAL_MODS_DIR" ]]; then
    copy_to_local_mods "$LOCAL_MODS_DIR"
fi

if [[ "$DEPLOY_SERVER" == true ]]; then
    deploy_to_server
fi

echo "=== Done ==="
