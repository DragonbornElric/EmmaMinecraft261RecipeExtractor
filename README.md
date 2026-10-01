# EmmaMinecraft261RecipeExtractor

Standalone repository for the Minecraft 26.1.2 Fabric server mod in `java/emma-recipe-extractor`.

The mod is a one-shot data extractor. Start a dedicated Minecraft server with the JAR installed, run `/emma_extract`, and it writes `emma_extracted_recipes.json` into the server directory. The output contains recipe, block drop, mob drop, brewing, enchantment, banner pattern, and item property data for the running game version.

This is a server-side tool. Do not treat it as a general client mod. For Minecraft 26.1, a client-only approach is not a reliable way to get the complete recipe set because recipe visibility is tied to recipe-book unlock state rather than the full server registry. If you need a complete JSON dump for tooling, data pipelines, or recipe analysis, run this on a dedicated server.

This repository also keeps two convenience artifacts:

- `emma_extracted_recipes.json` at the repository root as a current extractorVersion 3 example output from Minecraft 26.1.2.
- `java/dist/emma-recipe-extractor-mc26.1.2-fabric-loader0.19.2-0.1.0.jar` as a prebuilt JAR for Minecraft 26.1.2 deployment.

## Why This Exists

The useful output here is a complete server-side recipe dataset.

That matters because client-visible recipe data is not a reliable source for a full recipe corpus. On Minecraft 26.1, the client recipe book is tied to unlock state, so a client-only dump can miss recipes that exist on the server but have not been unlocked by a player yet. This tool runs on the server so the JSON can be used as a fuller source of truth for downstream tooling.

## Use Cases

This kind of export is useful for people building tools around Minecraft data, not just for one-off manual inspection.

- Mod pack maintainers can generate a machine-readable recipe catalog for pack docs, progression checks, and update diffing.
- Wiki authors can build or validate crafting, smelting, smithing, brewing, and drop pages from real game data instead of hand-copying values.
- Tool authors can feed web apps, search tools, Discord bots, planners, and AI helpers with a complete recipe and obtainability dataset.
- GOAP systems and hard-forked automation projects such as AltoClef variants can use the export as a static obtainability graph for planning, crafting chains, and action scoring.
- QA and migration work can compare JSON outputs across versions to spot recipe changes, removed drops, or new obtain methods.

If you want to use this against a modded server, treat that as a server-side data extraction workflow and verify the output against the mods you actually have installed. This repository is validated for Minecraft 26.1.2 first.

## Repository Layout

```text
.
├── README.md
├── JSON_FORMAT.md
├── CLAUDE.md
├── LICENSE
├── emma_extracted_recipes.json
├── .gitignore
├── examples/
└── java/
    ├── build_and_deploy.sh
    ├── dist/
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

The project target JDK is declared in `java/emma-recipe-extractor/gradle.properties` as `java_version=25`.

If you build through `java/build_and_deploy.sh`, the script will look for a matching local JDK and export `JAVA_HOME` before it runs Gradle. That means a stale shell-level `JAVA_HOME` does not have to break the build as long as Java 25 is installed locally.

If you invoke Gradle directly, use a Java 25 runtime or set `JAVA_HOME` to a Java 25 JDK yourself.

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

The artifact name includes the targeted Minecraft and Fabric Loader versions so different builds do not overwrite each other silently.

If you just want to deploy the already-built 26.1.2 artifact from this repository, use `java/dist/emma-recipe-extractor-mc26.1.2-fabric-loader0.19.2-0.1.0.jar`.

## License

This repository is licensed under `0BSD` in [LICENSE](LICENSE). That is a very permissive open source license with no attribution requirement.

## Example Output

The checked-in file `emma_extracted_recipes.json` is an example output produced by running `/emma_extract` against Minecraft 26.1.2. It is useful for downstream consumers that want a known-good sample without spinning up a server immediately, especially if they are building tools that need the full recipe corpus instead of per-player unlocked client recipe data.

An older compact-shaped sample from the earlier extractor format is kept only as an archive under `old recipe/`.

## Format Reference

See [JSON_FORMAT.md](JSON_FORMAT.md) for a short field reference covering the top-level wrapper, entry families, common fields, and the current `obtainMethod` variants.

## Example Consumer

The repository includes [examples/query_item.py](examples/query_item.py), a small no-dependency script that reads an extractor JSON file and answers the practical question “how do I obtain item X?”

Examples:

```bash
python examples/query_item.py acacia_boat
python examples/query_item.py minecraft:netherite_sword --file emma_extracted_recipes.json
```

This is intentionally simple, but it is a good starting point for wiki generators, pack tooling, Discord bots, or GOAP / AltoClef-style planning systems.

## TaskCatalogue Generator

This repository now also includes [tools/generate_task_catalogue.py](tools/generate_task_catalogue.py), a Python script that reads extractor JSON and writes a curated, one-way `TaskCatalogue.java`-style file from a read-only Emmaclef template.

The generator is intentionally conservative:

- It picks one canonical acquisition path per item instead of recursively following every recipe edge.
- It prefers root acquisition chains such as mining and smelting raw ores over reversible decompositions like `iron_block -> iron_ingot`.
- It mirrors the current Emmaclef wood catalogue structure with aggregate categories such as `log`, `planks`, `stick`, `wooden_door`, and `wooden_slab`, plus per-species wood entries and aliases. Multi-family wood ingredient sets are rendered back to those aggregate keys instead of being collapsed onto one species.
- It skips unsupported entry families such as brewing and stonecutting in the first pass and records those gaps in a report.
- It does not edit the Emmaclef repository; it only reads your existing `TaskCatalogue.java` as a template and writes the generated result to a separate output path.

Example:

```powershell
c:/Users/Owner/Emma-RecipeExtractor/.venv/Scripts/python.exe tools/generate_task_catalogue.py `
    --file emma_extracted_recipes.json `
    --template C:/Users/Owner/Emmaclef/java/emmaclef/src/main/java/emma/emmaclef/TaskCatalogue.java `
    --output C:/temp/TaskCatalogue.generated.java `
    --report C:/temp/task_catalogue_report.json
```

Important:

- The generator requires extractor JSON with `extractorVersion >= 3`.
- The checked-in [emma_extracted_recipes.json](emma_extracted_recipes.json) sample is a real `extractorVersion = 3` export and is suitable for full catalogue generation.
- A small `extractorVersion = 3` fixture is included at [examples/task_catalogue_fixture_v3.json](examples/task_catalogue_fixture_v3.json) so the generator can be smoke-tested locally without re-running Minecraft. It is intentionally tiny and is not a parity fixture for the current Emmaclef catalogue.

To measure parity against the current Emmaclef catalogue, use [tools/compare_task_catalogues.py](tools/compare_task_catalogues.py) against the real `TaskCatalogue.java` and a generated candidate:

```powershell
c:/Users/Owner/Emma-RecipeExtractor/.venv/Scripts/python.exe tools/compare_task_catalogues.py `
    --baseline C:/Users/Owner/Emmaclef/java/emmaclef/src/main/java/emma/emmaclef/TaskCatalogue.java `
    --candidate C:/temp/TaskCatalogue.generated.java `
    --report C:/temp/task_catalogue_compare.json
```

That script reports line counts, normalized helper invocation counts, and missing or extra derived registration keys so coverage gaps are measured explicitly instead of inferred from a small smoke fixture.

If you want generation plus comparison in one command, use [tools/run_full_task_catalogue_generation.py](tools/run_full_task_catalogue_generation.py). It locates the real current Emmaclef `TaskCatalogue.java`, runs generation, then runs the parity comparator:

```powershell
c:/Users/Owner/Emma-RecipeExtractor/.venv/Scripts/python.exe tools/run_full_task_catalogue_generation.py `
    --file emma_extracted_recipes.json
```

In this environment, the real current file is:

```text
C:/Users/Owner/Emmaclef/java/emmaclef/src/main/java/emma/emmaclef/TaskCatalogue.java
```

## Standalone Helper Script

`java/build_and_deploy.sh` builds the mod and copies the JAR into `java/dist/`.

Examples:

```bash
cd java
./build_and_deploy.sh
./build_and_deploy.sh --mods-dir /path/to/server/mods
./build_and_deploy.sh --mods-dir /path/to/mods-a --mods-dir /path/to/mods-b
```

## Runtime Use

1. Copy the built JAR into the dedicated server's `mods/` directory.
2. Start the server.
3. Run `/emma_extract` as an operator.
4. Collect `emma_extracted_recipes.json` from the server root.
5. Remove the mod when extraction is complete.

Client-only use is intentionally out of scope. The useful case here is extracting the full recipe universe from the server so other tools do not depend on a player's unlocked recipes.

## Version Compatibility

The current codebase targets Minecraft 26.1.2 with Fabric Loader 0.19.2 and Fabric API 0.146.1+26.1.2.

- The mod now builds directly against 26.1.2 rather than relying on patch-version compatibility by assumption.
- `26.1`, `26.1.1`, or newer patch releases may still work if the relevant Minecraft, Fabric Loader, and Fabric API pieces stay binary-compatible, but that should be treated as a compatibility question, not a naming shortcut.
- The artifact name includes the targeted Minecraft version and Fabric Loader version so future builds for other targets can coexist in `java/dist/`.
- Snapshots should be treated as unsupported by default. This extractor uses unobfuscated game internals and reflection over concrete field names, so snapshot changes can break it even when patch releases do not.

If you want reliable support for `26.1.1`, `26.1.2`, or snapshots, the correct approach is to rebuild and smoke-test the mod against each target Minecraft version and publish version-specific artifacts when needed.