package com.mintyradar;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.serialization.Codec;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Settings screen, opened with the settings keybind or from Mod Menu. Built on
 * vanilla's options list, so it looks and behaves like the game's own settings pages.
 * It needs no config library.
 *
 * <p>Changes apply live, so the radar updates behind the screen as you adjust it.
 * The config file is written when the screen closes. Keybinds are rebound the same
 * way as in vanilla Controls: click one, then press a key or mouse button (Esc unbinds).
 */
public final class RadarConfigScreen extends OptionsSubScreen {
	private final RadarConfig config = RadarClientMod.config();

	private List<KeyMapping> mappings;
	private Button[] keyButtons;
	private Button[] resetButtons;
	/** The keybind waiting for a key press, or null when not rebinding. */
	private KeyMapping listening;
	private EditBox friendBox;

	public RadarConfigScreen(Screen parent) {
		super(parent, net.minecraft.client.Minecraft.getInstance().options,
				Component.translatable("options.minty_radar.title"));
	}

	@Override
	protected void addOptions() {
		list.addHeader(Component.translatable("options.minty_radar.section.general"));
		list.addSmall(
				OptionInstance.createBoolean("options.minty_radar.enabled", config.enabled,
						v -> config.enabled = v),
				OptionInstance.createBoolean("options.minty_radar.show_map",
						tooltip("options.minty_radar.show_map.tooltip"),
						config.showMap, v -> config.showMap = v),
				rangeOption());

		list.addHeader(Component.translatable("options.minty_radar.section.display"));
		list.addSmall(
				enumOption("options.minty_radar.shape", RadarConfig.Shape.values(), config.shape,
						v -> config.shape = v),
				enumOption("options.minty_radar.blip_style", RadarConfig.BlipStyle.values(), config.blipStyle,
						v -> config.blipStyle = v),
				intOption("options.minty_radar.head_size", 4, 16, config.headSize,
						v -> Component.literal(v + "px"), v -> config.headSize = v),
				enumOption("options.minty_radar.name_mode", RadarConfig.NameMode.values(), config.nameMode,
						v -> config.nameMode = v),
				intOption("options.minty_radar.height_threshold", 1, 16, (int) Math.round(config.verticalThreshold),
						v -> Component.translatable("options.minty_radar.blocks", v),
						v -> config.verticalThreshold = v),
				OptionInstance.createBoolean("options.minty_radar.compass",
						tooltip("options.minty_radar.compass.tooltip"),
						config.showCompass, v -> config.showCompass = v),
				OptionInstance.createBoolean("options.minty_radar.rings",
						tooltip("options.minty_radar.rings.tooltip"),
						config.showRings, v -> config.showRings = v),
				enumOption("options.minty_radar.mob_mode", RadarConfig.MobMode.values(), config.mobMode,
						v -> config.mobMode = v));

		list.addHeader(Component.translatable("options.minty_radar.section.player_list"));
		list.addSmall(
				OptionInstance.createBoolean("options.minty_radar.player_list",
						tooltip("options.minty_radar.player_list.tooltip"),
						config.showPlayerList, v -> config.showPlayerList = v),
				intOption("options.minty_radar.list_limit", 0, 20, Math.min(config.listLimit, 20),
						v -> v == 0 ? Component.translatable("options.minty_radar.list_limit.all") : Component.literal(String.valueOf(v)),
						v -> config.listLimit = v));

		list.addHeader(Component.translatable("options.minty_radar.section.alerts"));
		list.addSmall(
				OptionInstance.createBoolean("options.minty_radar.alert",
						tooltip("options.minty_radar.alert.tooltip"),
						config.alertEnabled, v -> config.alertEnabled = v),
				intOption("options.minty_radar.alert_distance", 8, 128, config.alertDistance,
						v -> Component.translatable("options.minty_radar.blocks", v), v -> config.alertDistance = v),
				OptionInstance.createBoolean("options.minty_radar.alert_sound", config.alertSound,
						v -> config.alertSound = v));

		list.addHeader(Component.translatable("options.minty_radar.section.layout"));
		list.addSmall(
				enumOption("options.minty_radar.corner", RadarConfig.Corner.values(), config.corner, v -> {
					config.corner = v;
					config.customPosition = false; // choosing a corner undoes a dragged position
				}),
				intOption("options.minty_radar.size", 40, 256, config.size,
						v -> Component.literal(v + "px"), v -> config.size = v),
				intOption("options.minty_radar.margin", 0, 100, Math.min(config.margin, 100),
						v -> Component.literal(v + "px"), v -> config.margin = v),
				intOption("options.minty_radar.opacity", 0, 100, Math.round(config.backgroundAlpha * 100f / 255f),
						v -> Component.literal(v + "%"), v -> config.backgroundAlpha = Math.round(v * 255f / 100f)),
				intOption("options.minty_radar.text_scale", 50, 200, config.textScale,
						v -> Component.literal(v + "%"), v -> config.textScale = v));
		list.addBig(Button.builder(Component.translatable("options.minty_radar.move"),
				b -> minecraft.gui.setScreen(new HudPositionScreen(this))).build());

		list.addHeader(Component.translatable("options.minty_radar.section.tab_list"));
		list.addSmall(OptionInstance.createBoolean("options.minty_radar.tab_ping",
				tooltip("options.minty_radar.tab_ping.tooltip"),
				config.tabPing, v -> config.tabPing = v));

		addFriendRows();
		addKeybindRows();
	}

	private static <T> OptionInstance.TooltipSupplier<T> tooltip(String key) {
		return OptionInstance.cachedConstantTooltip(Component.translatable(key));
	}

	private OptionInstance<Integer> rangeOption() {
		return new OptionInstance<>("options.minty_radar.range", OptionInstance.noTooltip(),
				(caption, index) -> Options.genericValueLabel(caption,
						Component.translatable("options.minty_radar.blocks", RadarConfig.RANGE_STEPS[index])),
				new OptionInstance.IntRange(0, RadarConfig.RANGE_STEPS.length - 1),
				config.rangeIndex, v -> config.rangeIndex = v);
	}

	private static OptionInstance<Integer> intOption(String key, int min, int max, int initial,
			java.util.function.IntFunction<Component> label, OptionInstance.ValueUpdateListener<Integer> onChange) {
		return new OptionInstance<>(key, OptionInstance.noTooltip(),
				(caption, v) -> Options.genericValueLabel(caption, label.apply(v)),
				new OptionInstance.IntRange(min, max), Math.clamp(initial, min, max), onChange);
	}

	/**
	 * Cycle button over an enum. Each constant is labelled via {@code <key>.<constant_name>}.
	 * Only the value is returned: the cycle button adds the "Caption: " prefix itself.
	 */
	private static <E extends Enum<E>> OptionInstance<E> enumOption(String key, E[] values, E initial,
			OptionInstance.ValueUpdateListener<E> onChange) {
		Codec<E> codec = Codec.STRING.xmap(
				name -> Arrays.stream(values).filter(e -> e.name().equals(name)).findFirst().orElse(initial),
				Enum::name);
		return new OptionInstance<>(key, OptionInstance.noTooltip(),
				(caption, v) -> Component.translatable(key + "." + v.name().toLowerCase(Locale.ROOT)),
				new OptionInstance.Enum<>(List.of(values), codec), initial, onChange);
	}

	// --- Friends --------------------------------------------------------------------

	/** Friend display mode, a name box with an Add button, then one row per friend. */
	private void addFriendRows() {
		list.addHeader(Component.translatable("options.minty_radar.section.friends"));
		list.addSmall(enumOption("options.minty_radar.friend_mode", RadarConfig.FriendMode.values(),
				config.friendMode, v -> config.friendMode = v));

		friendBox = new EditBox(font, Button.DEFAULT_WIDTH, Button.DEFAULT_HEIGHT,
				Component.translatable("options.minty_radar.friend_name"));
		friendBox.setMaxLength(16);
		friendBox.setHint(Component.translatable("options.minty_radar.friend_name"));
		Button addButton = Button.builder(Component.translatable("options.minty_radar.friend_add"), b -> addFriend()).build();
		addButton.active = false;
		// Only allow Add for a valid username that isn't already a friend.
		friendBox.setResponder(value -> {
			String name = value.trim();
			addButton.active = RadarConfig.isValidUsername(name) && !config.isFriend(name);
		});
		list.addSmall(friendBox, addButton);

		for (String friend : config.friends) {
			Button nameButton = Button.builder(Component.literal(friend), b -> {}).build();
			nameButton.active = false; // just a label
			Button removeButton = Button.builder(Component.translatable("options.minty_radar.friend_remove"), b -> {
				config.removeFriend(friend);
				rebuildKeepingScroll();
			}).build();
			list.addSmall(nameButton, removeButton);
		}
	}

	private void addFriend() {
		if (friendBox != null && config.addFriend(friendBox.getValue())) {
			rebuildKeepingScroll();
		}
	}

	/** Rebuilds the list (to show friend changes) without jumping back to the top. */
	private void rebuildKeepingScroll() {
		double scroll = list.scrollAmount();
		listening = null;
		rebuildWidgets();
		list.setScrollAmount(scroll);
	}

	// --- Keybinds -------------------------------------------------------------------

	private void addKeybindRows() {
		list.addHeader(Component.translatable("key.category.minty_radar.radar"));

		mappings = Keybindings.all();
		keyButtons = new Button[mappings.size()];
		resetButtons = new Button[mappings.size()];

		for (int i = 0; i < mappings.size(); i++) {
			KeyMapping mapping = mappings.get(i);
			keyButtons[i] = Button.builder(Component.empty(), b -> {
				listening = mapping;
				refreshKeyLabels();
			}).build();
			resetButtons[i] = Button.builder(Component.translatable("controls.reset"), b -> {
				rebind(mapping, mapping.getDefaultKey());
			}).build();
			list.addSmall(keyButtons[i], resetButtons[i]);
		}
		refreshKeyLabels();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (listening != null) {
			rebind(listening, event.key() == InputConstants.KEY_ESCAPE
					? InputConstants.UNKNOWN
					: InputConstants.getKey(event));
			return true;
		}
		// Enter in the friend name box works like the Add button.
		if (friendBox != null && friendBox.isFocused()
				&& (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)) {
			addFriend();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (listening != null) {
			rebind(listening, InputConstants.Type.MOUSE.getOrCreate(event.button()));
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	private void rebind(KeyMapping mapping, InputConstants.Key key) {
		mapping.setKey(key);
		KeyMapping.resetMapping(); // rebuilds vanilla's key → mapping lookup
		listening = null;
		refreshKeyLabels();
	}

	/** Mirrors vanilla Controls: "> key <" while listening, red when the key is shared. */
	private void refreshKeyLabels() {
		for (int i = 0; i < mappings.size(); i++) {
			KeyMapping mapping = mappings.get(i);
			MutableComponent key = mapping.getTranslatedKeyMessage().copy();

			if (mapping == listening) {
				key = Component.literal("> ").append(key.withStyle(ChatFormatting.WHITE, ChatFormatting.UNDERLINE))
						.append(" <").withStyle(ChatFormatting.YELLOW);
			} else if (hasConflict(mapping)) {
				key = Component.literal("[ ").append(key).append(" ]").withStyle(ChatFormatting.RED);
			}

			keyButtons[i].setMessage(Component.translatable(mapping.getName()).append(": ").append(key));
			resetButtons[i].active = !mapping.isDefault();
		}
	}

	private boolean hasConflict(KeyMapping mapping) {
		if (mapping.isUnbound()) return false;
		for (KeyMapping other : minecraft.options.keyMappings) {
			if (other != mapping && mapping.same(other)) return true;
		}
		return false;
	}

	@Override
	public void removed() {
		super.removed(); // saves options.txt, which is where keybinds are stored
		config.save();
	}
}
