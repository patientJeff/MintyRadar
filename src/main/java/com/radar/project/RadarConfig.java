package com.radar.project;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * User-editable settings, persisted as JSON in {@code config/player_radar.json}.
 * Toggle state and zoom level are saved automatically when changed via keybind.
 */
public final class RadarConfig {
	/** Radar radius steps (in blocks) cycled through by the zoom keybinds. */
	public static final int[] RANGE_STEPS = {32, 48, 64, 96, 128};

	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("player_radar.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

	/** When player names are drawn above their radar dots. */
	public enum NameMode { ALWAYS, WHILE_SNEAKING, NEVER }

	/** How players are drawn on the radar. */
	public enum BlipStyle { HEADS, DOTS }

	public boolean enabled = true;
	/** Show the radar map panel. When off, only the player list is shown. */
	public boolean showMap = true;
	/** Index into {@link #RANGE_STEPS}. */
	public int rangeIndex = 2;
	/** Radar width/height in GUI pixels. */
	public int size = 90;
	/** Distance from the screen edges in GUI pixels. */
	public int margin = 6;
	public Corner corner = Corner.TOP_LEFT;
	/** Background opacity, 0-255. */
	public int backgroundAlpha = 0x90;
	/** Height difference (blocks) beyond which the above/below indicators are shown. */
	public double verticalThreshold = 3.0;
	/** Show the list of all tracked players and their distances next to the radar. */
	public boolean showPlayerList = true;
	public NameMode nameMode = NameMode.WHILE_SNEAKING;
	public BlipStyle blipStyle = BlipStyle.HEADS;
	/** Width/height of player heads in GUI pixels (face plus hat layer). */
	public int headSize = 8;

	public int range() {
		return RANGE_STEPS[rangeIndex];
	}

	/** Zooms in (smaller radius). Returns true if the range changed. */
	public boolean zoomIn() {
		if (rangeIndex == 0) return false;
		rangeIndex--;
		return true;
	}

	/** Zooms out (larger radius). Returns true if the range changed. */
	public boolean zoomOut() {
		if (rangeIndex == RANGE_STEPS.length - 1) return false;
		rangeIndex++;
		return true;
	}

	public static RadarConfig load() {
		RadarConfig config = null;
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH)) {
				config = GSON.fromJson(reader, RadarConfig.class);
			} catch (IOException | JsonParseException e) {
				RadarClientMod.LOGGER.warn("Failed to read {}, using defaults", PATH, e);
			}
		}
		if (config == null) config = new RadarConfig();
		config.sanitize();
		config.save(); // writes defaults on first launch / fills in newly added fields
		return config;
	}

	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			RadarClientMod.LOGGER.warn("Failed to save {}", PATH, e);
		}
	}

	/** Clamps hand-edited values into safe ranges. */
	private void sanitize() {
		rangeIndex = Math.clamp(rangeIndex, 0, RANGE_STEPS.length - 1);
		size = Math.clamp(size, 40, 256);
		margin = Math.clamp(margin, 0, 200);
		backgroundAlpha = Math.clamp(backgroundAlpha, 0, 255);
		verticalThreshold = Math.clamp(verticalThreshold, 0.5, 64.0);
		if (corner == null) corner = Corner.TOP_LEFT;
		if (nameMode == null) nameMode = NameMode.WHILE_SNEAKING;
		if (blipStyle == null) blipStyle = BlipStyle.HEADS;
		headSize = Math.clamp(headSize, 4, 16);
	}
}
