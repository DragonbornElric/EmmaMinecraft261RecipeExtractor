package com.emma.recipeextractor;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Standalone server mod for one-shot data extraction.
 *
 * Load this mod once per MC version, run {@code /emma_extract} in-game,
 * then remove the JAR. Not needed at runtime.
 */
public class RecipeExtractorMod implements DedicatedServerModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("EmmaRecipeExtractor");

    @Override
    public void onInitializeServer() {
        // Register /emma_extract command — one-time extraction of all recipe + block + mob data
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("emma_extract")
                    .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_OWNER)) // op only
                    .executes(context -> {
                        MinecraftServer srv = context.getSource().getServer();
                        try {
                            String json = RecipeExtractor.extractAll(srv);
                            int count = RecipeExtractor.countEntries(json);
                            Path output = srv.getServerDirectory().resolve("emma_extracted_recipes.json");
                            Files.writeString(output, json);
                            context.getSource().sendSuccess(
                                    () -> Component.literal("[EmmaExtract] Wrote " + count
                                            + " entries to " + output.getFileName()),
                                    true);
                        } catch (Exception e) {
                            LOGGER.error("[EmmaExtract] Extraction failed", e);
                            context.getSource().sendFailure(
                                    Component.literal("[EmmaExtract] Failed: " + e.getMessage()));
                        }
                        return 1;
                    }));
        });

        LOGGER.info("[EmmaRecipeExtractor] Initialized — use /emma_extract to run");
    }
}
