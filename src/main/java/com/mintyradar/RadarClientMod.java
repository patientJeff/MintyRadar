package com.mintyradar;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint. Wires together the config, keybindings, the per-tick
 * {@link RadarManager} update and the {@link RadarHudOverlay} HUD element.
 */
public class RadarClientMod implements ClientModInitializer {
	public static final String MOD_ID = "minty_radar";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static RadarConfig config;

	@Override
	public void onInitializeClient() {
		config = RadarConfig.load();
		RadarManager manager = new RadarManager();
		PlayerAlerts alerts = new PlayerAlerts();

		Keybindings.register();

		// All game-state work (filtering, sorting, distance text) runs at
		// 20 Hz here, so the per-frame render path only does cheap interpolation math.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			Keybindings.handleInput(client, config);
			manager.tick(client, config);
			alerts.tick(client, config, manager);
			PingTracker.tick(client, config);
		});

		// Drawn just below chat so chat messages stay readable on top of the radar.
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, id("radar"),
				new RadarHudOverlay(config, manager));

		LOGGER.info("Minty Radar initialized (range {} blocks, {})",
				config.range(), config.enabled ? "enabled" : "disabled");
	}

	/** The live config shared by the HUD, keybinds and settings screen. */
	public static RadarConfig config() {
		return config;
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
