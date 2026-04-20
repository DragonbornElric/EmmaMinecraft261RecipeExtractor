# EmmaMinecraft261RecipeExtractor

Standalone repository for the Minecraft 26.1 Fabric server mod in `java/emma-recipe-extractor`.

The mod is a one-shot data extractor. Start a dedicated Minecraft server with the JAR installed, run `/emma_extract`, and it writes `emma_extracted_recipes.json` into the server directory. The output contains recipe, block drop, mob drop, brewing, enchantment, banner pattern, and item property data for the running game version.

## Repository Layout

```text
.
├── README.md
├── CLAUDE.md
├── .gitignore
└── java/
    ├── build_and_deploy.sh
    └── emma-recipe-extractor/
        ├── build.gradle
        ├── gradle.properties
        ├── gradlew
        ├── gradlew.bat
        ├── settings.gradle
        ├── gradle/
        └── src/
```

## Build

Java 25 on `PATH` or via `JAVA_HOME` is required.

On Windows PowerShell:

```powershell
Set-Location java/emma-recipe-extractor
.\gradlew.bat build
```

On Git Bash or Linux:

```bash
cd java/emma-recipe-extractor
./gradlew build
```

The built JAR is written to `java/emma-recipe-extractor/build/libs/`.

## Standalone Helper Script

`java/build_and_deploy.sh` builds the mod and copies the JAR into `java/dist/`.

Examples:

```bash
cd java
./build_and_deploy.sh
./build_and_deploy.sh --mods-dir /path/to/server/mods
EMMA_RECIPE_EXTRACTOR_SERVER_HOST=host \
EMMA_RECIPE_EXTRACTOR_SERVER_USER=user \
EMMA_RECIPE_EXTRACTOR_SERVER_MODS_DIR=/path/to/mods \
./build_and_deploy.sh --server
```

## Runtime Use

1. Copy the built JAR into the dedicated server's `mods/` directory.
2. Start the server.
3. Run `/emma_extract` as an operator.
4. Collect `emma_extracted_recipes.json` from the server root.
5. Remove the mod when extraction is complete.