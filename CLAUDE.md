# EmmaMinecraft261RecipeExtractor

Standalone repository for the `java/emma-recipe-extractor` Fabric server mod.

## Purpose

This project exists only to build and maintain the recipe extractor mod for Minecraft 26.1. The mod is intended for temporary server-side use: install it, run `/emma_extract`, collect `emma_extracted_recipes.json`, then remove the JAR.

## Repository Layout

| Path | Purpose |
|------|---------|
| `java/emma-recipe-extractor/` | Fabric mod source, Gradle wrapper, and mod metadata |
| `java/build_and_deploy.sh` | Standalone helper to build the mod and optionally copy or upload the JAR |
| `README.md` | User-facing build and usage instructions |
| `.gitignore` | Ignore rules for this standalone repository |

## Build

- Java 25 on `PATH` or via `JAVA_HOME` is required.
- Windows build: `cd java/emma-recipe-extractor && .\gradlew.bat build`
- Git Bash or Linux build: `cd java/emma-recipe-extractor && ./gradlew build`
- Output JAR: `java/emma-recipe-extractor/build/libs/emma-recipe-extractor-0.1.0.jar`

## Runtime Behavior

- Entrypoint: `com.emma.recipeextractor.RecipeExtractorMod`
- Command: `/emma_extract`
- Output file: `emma_extracted_recipes.json` in the Minecraft server directory
- Environment: dedicated server only

## Maintenance Notes

- Keep this repository scoped to the recipe extractor only. Do not reintroduce references to removed modules, unrelated tooling, or sibling repositories.
- `java/emma-recipe-extractor/gradle.properties` must remain free of machine-specific paths.
- The extractor targets unobfuscated Minecraft 26.1 names, Fabric Loader 0.18.4, Fabric Loom 1.15.5, and Java 25.
- Treat comments as hints, not truth. Verify behavior from code flow before changing extraction logic.
