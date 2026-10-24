package com.mintyradar;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
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
	private static RadarManager manager;

	@Override
	public void onInitializeClient() {
		config = RadarConfig.load();
		manager = new RadarManager();
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

		LOGGER.info("MintyRadar initialized (range {} blocks, {})",
				config.range(), config.enabled ? "enabled" : "disabled");
	}

	/** The live config shared by the HUD, keybinds and settings screen. */
	public static RadarConfig config() {
		return config;
	}

	/** What the radar is tracking. Used by the game tests. */
	public static RadarManager manager() {
		return manager;
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	// --- Calls that differ between Minecraft versions --------------------------------

	/** The screen that's open, or null in game. */
	public static Screen currentScreen() {
		//? if >=26.2 {
		return Minecraft.getInstance().gui.screen();
		//?} else {
		/*return Minecraft.getInstance().screen;
		*///?}
	}

	public static void setScreen(Screen screen) {
		//? if >=26.2 {
		Minecraft.getInstance().gui.setScreen(screen);
		//?} else {
		/*Minecraft.getInstance().setScreen(screen);
		*///?}
	}

	/** Shows a message in the action bar, above the hotbar. */
	public static void actionBar(Component message) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return;
		//? if >=26.1 {
		player.sendOverlayMessage(message);
		//?} else {
		/*player.displayClientMessage(message, true);
		*///?}
	}

	/** True while the HUD is hidden with F1. */
	public static boolean hudHidden() {
		//? if >=26.2 {
		return Minecraft.getInstance().gui.hud.isHidden();
		//?} else {
		/*return Minecraft.getInstance().options.hideGui;
		*///?}
	}
}
