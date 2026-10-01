#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RECIPE_DIR="$SCRIPT_DIR/emma-recipe-extractor"
DIST_DIR="$SCRIPT_DIR/dist"
BUILD_LIBS_DIR="$RECIPE_DIR/build/libs"

gradle_property() {
    local key="$1"
    awk -F '=' -v key="$key" '$1 == key {print substr($0, index($0, "=") + 1); exit}' "$RECIPE_DIR/gradle.properties" | tr -d '\r'
}

ARCHIVES_BASE_NAME="$(gradle_property archives_base_name)"
REQUIRED_JAVA_MAJOR="$(gradle_property java_version)"
MINECRAFT_VERSION="$(gradle_property minecraft_version)"
LOADER_VERSION="$(gradle_property loader_version)"
MOD_VERSION="$(gradle_property mod_version)"
RECIPE_JAR="${ARCHIVES_BASE_NAME}-mc${MINECRAFT_VERSION}-fabric-loader${LOADER_VERSION}-${MOD_VERSION}.jar"

MODS_DIRS=()
GRADLE_ARGS=()

if [[ -n "${EMMA_RECIPE_EXTRACTOR_PROJECT_CACHE_DIR:-}" ]]; then
    GRADLE_PROJECT_CACHE_DIR="$EMMA_RECIPE_EXTRACTOR_PROJECT_CACHE_DIR"
elif [[ -z "${GRADLE_PROJECT_CACHE_DIR:-}" && -n "${LOCALAPPDATA:-}" ]]; then
    GRADLE_PROJECT_CACHE_DIR="${LOCALAPPDATA}\\Temp\\emma-recipe-extractor-gradle-cache"
fi

if [[ -n "${GRADLE_PROJECT_CACHE_DIR:-}" ]]; then
    GRADLE_ARGS+=(--project-cache-dir "$GRADLE_PROJECT_CACHE_DIR")
fi

usage() {
    cat <<'EOF'
Usage:
  ./build_and_deploy.sh
  ./build_and_deploy.sh --mods-dir /path/to/mods
  ./build_and_deploy.sh --mods-dir /path/a --mods-dir /path/b

Behavior:
  - Builds java/emma-recipe-extractor
  - Copies the JAR into java/dist/
  - Optionally copies the JAR into one or more local mods directories
  - Auto-selects a locally installed JDK matching gradle.properties:java_version
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --mods-dir)
            if [[ $# -lt 2 ]]; then
                echo "ERROR: --mods-dir requires a directory path." >&2
                exit 1
            fi
            MODS_DIRS+=("$2")
            shift 2
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

java_bin_for_home() {
    local java_home="$1"
    if [[ -x "$java_home/bin/java" ]]; then
        echo "$java_home/bin/java"
        return 0
    fi
    if [[ -x "$java_home/bin/java.exe" ]]; then
        echo "$java_home/bin/java.exe"
        return 0
    fi
    return 1
}

java_major_version() {
    local java_bin="$1"
    "$java_bin" -version 2>&1 | awk -F '[\".]' '/version/ {print ($2 == 1 ? $3 : $2); exit}'
}

java_home_from_runtime() {
    local java_bin="$1"
    "$java_bin" -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java\.home = //p' | head -n 1 | tr -d '\r'
}

java_home_matches_version() {
    local java_home="$1"
    local required_major="$2"
    local java_bin
    local actual_major

    if ! java_bin="$(java_bin_for_home "$java_home")"; then
        return 1
    fi

    actual_major="$(java_major_version "$java_bin")"
    [[ "$actual_major" == "$required_major" ]]
}

find_matching_java_home() {
    local required_major="$1"
    local path_java_bin=""
    local path_java_home=""
    local candidate
    local search_root
    local search_roots=()

    if [[ -n "${EMMA_RECIPE_EXTRACTOR_JAVA_HOME:-}" ]]; then
        if java_home_matches_version "$EMMA_RECIPE_EXTRACTOR_JAVA_HOME" "$required_major"; then
            echo "$EMMA_RECIPE_EXTRACTOR_JAVA_HOME"
            return 0
        fi

        echo "ERROR: EMMA_RECIPE_EXTRACTOR_JAVA_HOME is set to $EMMA_RECIPE_EXTRACTOR_JAVA_HOME, but it is not Java $required_major." >&2
        exit 1
    fi

    if [[ -n "${JAVA_HOME:-}" ]] && java_home_matches_version "$JAVA_HOME" "$required_major"; then
        echo "$JAVA_HOME"
        return 0
    fi

    if command -v java >/dev/null 2>&1; then
        path_java_bin="$(command -v java)"
        if [[ "$(java_major_version "$path_java_bin")" == "$required_major" ]]; then
            path_java_home="$(java_home_from_runtime "$path_java_bin")"
            if [[ -n "$path_java_home" ]] && java_home_matches_version "$path_java_home" "$required_major"; then
                echo "$path_java_home"
                return 0
            fi
        fi
    fi

    if [[ "${OS:-}" == "Windows_NT" ]]; then
        search_roots=(
            "/c/Program Files/Java"
            "/c/Program Files/Oracle/Java"
            "/c/Program Files/Eclipse Adoptium"
            "/c/Program Files/Microsoft"
            "/c/Program Files/Amazon Corretto"
        )
    else
        search_roots=(
            "/usr/lib/jvm"
            "/Library/Java/JavaVirtualMachines"
        )
    fi

    for search_root in "${search_roots[@]}"; do
        [[ -d "$search_root" ]] || continue
        while IFS= read -r -d '' candidate; do
            if [[ "$candidate" == *.jdk ]]; then
                candidate="$candidate/Contents/Home"
            fi
            if [[ -d "$candidate" ]] && java_home_matches_version "$candidate" "$required_major"; then
                echo "$candidate"
                return 0
            fi
        done < <(find "$search_root" -mindepth 1 -maxdepth 1 -type d -print0 2>/dev/null)
    done

    return 1
}

configure_java_home() {
    local java_home

    if ! java_home="$(find_matching_java_home "$REQUIRED_JAVA_MAJOR")"; then
        echo "ERROR: Could not find a local Java $REQUIRED_JAVA_MAJOR installation." >&2
        echo "Set EMMA_RECIPE_EXTRACTOR_JAVA_HOME to a JDK $REQUIRED_JAVA_MAJOR home or install a matching JDK." >&2
        exit 1
    fi

    export JAVA_HOME="$java_home"
    export PATH="$JAVA_HOME/bin:$PATH"

    echo "=== Using Java $REQUIRED_JAVA_MAJOR from $JAVA_HOME ==="
}

run_gradle_build() {
    echo "=== Building emma-recipe-extractor ==="
    configure_java_home
    mkdir -p "$DIST_DIR"
    cd "$RECIPE_DIR"
    if [[ "${OS:-}" == "Windows_NT" ]]; then
        ./gradlew.bat "${GRADLE_ARGS[@]}" build 2>&1 | grep -v "^Note:" | grep -v "not valid semver" | grep -v "\[Incubating\]" | grep -v "problems-report"
    else
        ./gradlew "${GRADLE_ARGS[@]}" build 2>&1 | grep -v "^Note:" | grep -v "not valid semver" | grep -v "\[Incubating\]" | grep -v "problems-report"
    fi
}

copy_to_dist() {
    if [[ ! -f "$BUILD_LIBS_DIR/$RECIPE_JAR" ]]; then
        echo "ERROR: Expected jar not found: $BUILD_LIBS_DIR/$RECIPE_JAR" >&2
        exit 1
    fi

    cp "$BUILD_LIBS_DIR/$RECIPE_JAR" "$DIST_DIR/$RECIPE_JAR"
    echo "=== Wrote dist/$RECIPE_JAR ==="
}

copy_to_local_mods() {
    local target_dir="$1"

    if [[ ! -d "$target_dir" ]]; then
        echo "WARNING: Mods directory not found: $target_dir" >&2
        return 0
    fi

    cp "$DIST_DIR/$RECIPE_JAR" "$target_dir/$RECIPE_JAR"
    echo "=== Copied JAR to $target_dir ==="
}

run_gradle_build
copy_to_dist

for mods_dir in "${MODS_DIRS[@]}"; do
    copy_to_local_mods "$mods_dir"
done

echo "=== Done ==="
