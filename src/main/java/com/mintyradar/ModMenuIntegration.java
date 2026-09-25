package com.mintyradar;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Adds the "Configure" button for Minty Radar in Mod Menu. Loaded only when Mod Menu
 * is installed, via the {@code modmenu} entrypoint in fabric.mod.json.
 */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return RadarConfigScreen::new;
	}
}
