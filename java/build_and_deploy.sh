#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RECIPE_DIR="$SCRIPT_DIR/emma-recipe-extractor"
DIST_DIR="$SCRIPT_DIR/dist"
RECIPE_JAR="emma-recipe-extractor-0.1.0.jar"

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

run_gradle_build() {
    echo "=== Building emma-recipe-extractor ==="
    require_java_25
    cd "$RECIPE_DIR"
    if [[ "${OS:-}" == "Windows_NT" ]]; then
        ./gradlew.bat build
    else
        ./gradlew build
    fi
}

copy_to_dist() {
    mkdir -p "$DIST_DIR"
    cp "$RECIPE_DIR/build/libs/$RECIPE_JAR" "$DIST_DIR/$RECIPE_JAR"
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
