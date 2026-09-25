package com.mintyradar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * User-editable settings, persisted as JSON in {@code config/minty_radar.json}.
 * Toggle state and zoom level are saved automatically when changed via keybind.
 */
public final class RadarConfig {
	/** Radar radius steps (in blocks) cycled through by the zoom keybinds. */
	public static final int[] RANGE_STEPS = {32, 48, 64, 96, 128};

	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("minty_radar.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

	/** When player names are drawn above their radar dots. */
	public enum NameMode { ALWAYS, WHILE_SNEAKING, NEVER }

	/** How players are drawn on the radar. */
	public enum BlipStyle { HEADS, DOTS }

	public enum Shape { SQUARE, CIRCLE }

	/** Which mobs appear on the radar. Mobs never appear in the player list. */
	public enum MobMode { OFF, HOSTILE, ALL }

	/** How friends are shown: marked with a star, or left off the radar and list entirely. */
	public enum FriendMode { STAR, HIDE }

	// --- General ---
	public boolean enabled = true;
	/** Show the radar map panel. When off, only the player list is shown. */
	public boolean showMap = true;
	/** Index into {@link #RANGE_STEPS}. */
	public int rangeIndex = 2;

	// --- Radar display ---
	public Shape shape = Shape.SQUARE;
	public BlipStyle blipStyle = BlipStyle.HEADS;
	/** Width/height of player heads in GUI pixels (face plus hat layer). */
	public int headSize = 8;
	public NameMode nameMode = NameMode.WHILE_SNEAKING;
	/** Height difference (blocks) beyond which the above/below indicators are shown. */
	public double verticalThreshold = 3.0;
	/** N/E/S/W letters around the radar edge. */
	public boolean showCompass = true;
	/** Circles at half and full range, labelled with their distance. */
	public boolean showRings = true;
	public MobMode mobMode = MobMode.OFF;

	// --- Player list ---
	/** Show the list of tracked players and their distances next to the radar. */
	public boolean showPlayerList = true;
	/** Maximum players in the list. 0 means no limit (only the screen height limits it). */
	public int listLimit = 0;

	// --- Alerts ---
	/** Action-bar message when a player comes within {@link #alertDistance}. */
	public boolean alertEnabled = true;
	public int alertDistance = 48;
	public boolean alertSound = true;

	// --- Layout ---
	/** Which screen corner the radar sits in, unless {@link #customPosition} is on. */
	public Corner corner = Corner.TOP_LEFT;
	/** Set by dragging the radar in the Move Radar screen. Overrides {@link #corner}. */
	public boolean customPosition = false;
	/**
	 * Custom position of the radar's top-left corner, as a fraction (0-1) of the free
	 * space on screen. Fractions keep it in the same relative place when the window
	 * or GUI scale changes.
	 */
	public float posX = 0f;
	public float posY = 0f;
	/** Radar width/height in GUI pixels. */
	public int size = 90;
	/** Distance from the screen edges in GUI pixels. */
	public int margin = 6;
	/** Background opacity, 0-255. */
	public int backgroundAlpha = 0x90;
	/** Scale for all radar text (names, list, compass, ring labels), in percent. */
	public int textScale = 100;

	// --- Tab list ---
	/** Show each player's ping in milliseconds in the tab list, instead of signal bars. */
	public boolean tabPing = true;

	// --- Friends ---
	public FriendMode friendMode = FriendMode.STAR;
	/** Friend usernames, as entered. Matching ignores case. */
	public List<String> friends = new ArrayList<>();

	/** Lower-cased copy of {@link #friends} for fast lookups. Not saved. */
	private transient Set<String> friendLookup = new HashSet<>();

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

	public float textScale() {
		return textScale / 100f;
	}

	public boolean isFriend(String name) {
		return friendLookup.contains(name.toLowerCase(Locale.ROOT));
	}

	/** Adds a friend by username. Returns false if the name is invalid or already listed. */
	public boolean addFriend(String name) {
		String trimmed = name.trim();
		if (!isValidUsername(trimmed) || isFriend(trimmed)) return false;
		friends.add(trimmed);
		friendLookup.add(trimmed.toLowerCase(Locale.ROOT));
		return true;
	}

	public void removeFriend(String name) {
		friends.removeIf(f -> f.equalsIgnoreCase(name));
		friendLookup.remove(name.toLowerCase(Locale.ROOT));
	}

	/** Minecraft usernames: 1-16 letters, digits or underscores. */
	public static boolean isValidUsername(String name) {
		return name.matches("[A-Za-z0-9_]{1,16}");
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

	/** Clamps hand-edited values into safe ranges and fills in anything missing. */
	private void sanitize() {
		rangeIndex = Math.clamp(rangeIndex, 0, RANGE_STEPS.length - 1);
		size = Math.clamp(size, 40, 256);
		margin = Math.clamp(margin, 0, 200);
		backgroundAlpha = Math.clamp(backgroundAlpha, 0, 255);
		verticalThreshold = Math.clamp(verticalThreshold, 0.5, 64.0);
		headSize = Math.clamp(headSize, 4, 16);
		listLimit = Math.clamp(listLimit, 0, 50);
		alertDistance = Math.clamp(alertDistance, 8, 128);
		textScale = Math.clamp(textScale, 50, 200);
		posX = Float.isFinite(posX) ? Math.clamp(posX, 0f, 1f) : 0f;
		posY = Float.isFinite(posY) ? Math.clamp(posY, 0f, 1f) : 0f;
		if (corner == null) corner = Corner.TOP_LEFT;
		if (nameMode == null) nameMode = NameMode.WHILE_SNEAKING;
		if (blipStyle == null) blipStyle = BlipStyle.HEADS;
		if (shape == null) shape = Shape.SQUARE;
		if (mobMode == null) mobMode = MobMode.OFF;
		if (friendMode == null) friendMode = FriendMode.STAR;

		// A hand-edited file may hold duplicates, invalid names or nulls, so rebuild the
		// friends list (and its lookup set) from scratch.
		List<String> loaded = friends == null ? List.of() : friends;
		friends = new ArrayList<>();
		friendLookup = new HashSet<>();
		for (String name : loaded) {
			if (name != null) addFriend(name);
		}
	}
}
