package com.mintyradar;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Registers the radar keybinds (rebindable under Options → Controls → Minty Radar)
 * and turns key presses into config changes or opening the settings screen.
 */
public final class Keybindings {
	private static KeyMapping toggle;
	private static KeyMapping zoomIn;
	private static KeyMapping zoomOut;
	private static KeyMapping openSettings;

	private Keybindings() {
	}

	public static void register() {
		KeyMapping.Category category = KeyMapping.Category.register(RadarClientMod.id("radar"));

		toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.minty_radar.toggle", InputConstants.Type.KEYBOARD, InputConstants.KEY_R, category));
		zoomIn = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.minty_radar.zoom_in", InputConstants.Type.KEYBOARD, InputConstants.KEY_EQUALS, category));
		zoomOut = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.minty_radar.zoom_out", InputConstants.Type.KEYBOARD, InputConstants.KEY_MINUS, category));
		openSettings = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.minty_radar.open_settings", InputConstants.Type.KEYBOARD, InputConstants.KEY_O, category));
	}

	/** All radar keybinds, in the order they appear in the settings screen. */
	public static List<KeyMapping> all() {
		return List.of(toggle, zoomIn, zoomOut, openSettings);
	}

	/** Called once per client tick; drains queued presses so none are lost or repeated. */
	public static void handleInput(Minecraft client, RadarConfig config) {
		boolean changed = false;

		while (toggle.consumeClick()) {
			config.enabled = !config.enabled;
			changed = true;
			notify(client, Component.translatable(config.enabled
					? "message.minty_radar.enabled"
					: "message.minty_radar.disabled"));
		}

		while (zoomIn.consumeClick()) {
			if (config.zoomIn()) changed = true;
			notifyRange(client, config);
		}

		while (zoomOut.consumeClick()) {
			if (config.zoomOut()) changed = true;
			notifyRange(client, config);
		}

		if (changed) config.save();

		// Opens the same screen as Mod Menu's Configure button, so Mod Menu isn't required.
		// Key presses only arrive here while no other screen is open.
		while (openSettings.consumeClick()) {
			client.gui.setScreen(new RadarConfigScreen(client.gui.screen()));
		}
	}

	private static void notifyRange(Minecraft client, RadarConfig config) {
		notify(client, Component.translatable("message.minty_radar.range", config.range()));
	}

	/** Shows feedback in the action bar rather than spamming chat. */
	private static void notify(Minecraft client, Component message) {
		if (client.player != null) client.player.sendOverlayMessage(message);
	}
}
