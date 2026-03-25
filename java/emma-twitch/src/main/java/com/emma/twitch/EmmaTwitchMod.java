package com.emma.twitch;

import com.emma.twitch.eventsub.TwitchConfig;
import com.emma.twitch.eventsub.TwitchEffectDispatcher;
import com.emma.twitch.eventsub.TwitchEventSubClient;
import com.emma.twitch.web.WebServer;
import dev.qixils.crowdcontrol.plugin.fabric.CommandRegister;
import dev.qixils.crowdcontrol.plugin.fabric.FabricCrowdControlPlugin;
import live.crowdcontrol.cc4j.CrowdControl;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

public class EmmaTwitchMod implements ModInitializer {
	public static final String MOD_ID = "emma-twitch";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final Path CONFIG_PATH = Paths.get("config", "emma-twitch.json");

	private TwitchConfig config;
	private CrowdControl crowdControl;
	private TwitchEventSubClient eventSubClient;
	private TwitchEffectDispatcher dispatcher;
	private WebServer webServer;
	private FabricCrowdControlPlugin ccPlugin;

	private static EmmaTwitchMod instance;

	public static EmmaTwitchMod getInstance() { return instance; }

	@Override
	public void onInitialize() {
		instance = this;
		LOGGER.info("[emma-twitch] Twitch viewer interaction mod initializing");

		// Initialize the CC plugin (registers effects, mixins already loaded)
		ccPlugin = new FabricCrowdControlPlugin();
		ccPlugin.onInitialize();

		ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
	}

	private void onServerStarted(MinecraftServer server) {
		try {
			// Load config
			config = TwitchConfig.load(CONFIG_PATH);
			config.save(CONFIG_PATH); // ensure file exists with defaults
			LOGGER.info("[emma-twitch] Config loaded from {}", CONFIG_PATH);

			// Create CrowdControl instance and register all effects
			crowdControl = new CrowdControl("minecraft", "Minecraft", "", "", CONFIG_PATH.getParent());
			registerEffects();

			// Create the effect dispatcher
			dispatcher = new TwitchEffectDispatcher(config, crowdControl, server);

			// Connect to Twitch EventSub
			eventSubClient = new TwitchEventSubClient(config, dispatcher::dispatch);
			eventSubClient.connect();

			// Start web GUI
			webServer = new WebServer(config, CONFIG_PATH, dispatcher, crowdControl);
			webServer.start(config.webPort);

			LOGGER.info("[emma-twitch] Ready! Web GUI at http://localhost:{}", config.webPort);

		} catch (Exception e) {
			LOGGER.error("[emma-twitch] Failed to start", e);
		}
	}

	private void registerEffects() {
		// Use CommandRegister to get the list of all effect IDs and register them
		// For now, register effects as simple stubs — the actual Command instances
		// are registered by FabricCrowdControlPlugin
		int count = crowdControl.getEffects().size();
		LOGGER.info("[emma-twitch] {} effects registered", count);
	}

	private void onServerStopping(MinecraftServer server) {
		LOGGER.info("[emma-twitch] Shutting down...");

		if (eventSubClient != null) eventSubClient.shutdown();
		if (webServer != null) webServer.stop();

		try {
			if (config != null) config.save(CONFIG_PATH);
		} catch (Exception e) {
			LOGGER.error("[emma-twitch] Failed to save config on shutdown", e);
		}

		if (crowdControl != null) crowdControl.close();
	}

	// Accessors for web GUI
	public TwitchConfig getConfig() { return config; }
	public TwitchEffectDispatcher getDispatcher() { return dispatcher; }
	public TwitchEventSubClient getEventSubClient() { return eventSubClient; }
	public CrowdControl getCrowdControl() { return crowdControl; }
}
