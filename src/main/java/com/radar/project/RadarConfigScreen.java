package com.radar.project;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.serialization.Codec;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
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
 * Settings screen, reachable from Mod Menu. Built on vanilla's options list, so it
 * looks and behaves like the game's own settings pages. It needs no config library.
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

	public RadarConfigScreen(Screen parent) {
		super(parent, net.minecraft.client.Minecraft.getInstance().options,
				Component.translatable("options.player_radar.title"));
	}

	@Override
	protected void addOptions() {
		list.addHeader(Component.translatable("options.player_radar.section.general"));
		list.addSmall(
				OptionInstance.createBoolean("options.player_radar.enabled", config.enabled,
						v -> config.enabled = v),
				OptionInstance.createBoolean("options.player_radar.show_map",
						OptionInstance.cachedConstantTooltip(Component.translatable("options.player_radar.show_map.tooltip")),
						config.showMap, v -> config.showMap = v),
				rangeOption());

		list.addHeader(Component.translatable("options.player_radar.section.display"));
		list.addSmall(
				enumOption("options.player_radar.blip_style", RadarConfig.BlipStyle.values(), config.blipStyle,
						v -> config.blipStyle = v),
				intOption("options.player_radar.head_size", 4, 16, config.headSize,
						v -> Component.literal(v + "px"), v -> config.headSize = v),
				enumOption("options.player_radar.name_mode", RadarConfig.NameMode.values(), config.nameMode,
						v -> config.nameMode = v),
				OptionInstance.createBoolean("options.player_radar.player_list",
						OptionInstance.cachedConstantTooltip(Component.translatable("options.player_radar.player_list.tooltip")),
						config.showPlayerList, v -> config.showPlayerList = v),
				intOption("options.player_radar.height_threshold", 1, 16, (int) Math.round(config.verticalThreshold),
						v -> Component.translatable("options.player_radar.blocks", v),
						v -> config.verticalThreshold = v));

		list.addHeader(Component.translatable("options.player_radar.section.layout"));
		list.addSmall(
				enumOption("options.player_radar.corner", RadarConfig.Corner.values(), config.corner,
						v -> config.corner = v),
				intOption("options.player_radar.size", 40, 256, config.size,
						v -> Component.literal(v + "px"), v -> config.size = v),
				intOption("options.player_radar.margin", 0, 100, Math.min(config.margin, 100),
						v -> Component.literal(v + "px"), v -> config.margin = v),
				intOption("options.player_radar.opacity", 0, 100, Math.round(config.backgroundAlpha * 100f / 255f),
						v -> Component.literal(v + "%"), v -> config.backgroundAlpha = Math.round(v * 255f / 100f)));

		addKeybindRows();
	}

	private OptionInstance<Integer> rangeOption() {
		return new OptionInstance<>("options.player_radar.range", OptionInstance.noTooltip(),
				(caption, index) -> Options.genericValueLabel(caption,
						Component.translatable("options.player_radar.blocks", RadarConfig.RANGE_STEPS[index])),
				new OptionInstance.IntRange(0, RadarConfig.RANGE_STEPS.length - 1),
				config.rangeIndex, v -> config.rangeIndex = v);
	}

	private static OptionInstance<Integer> intOption(String key, int min, int max, int initial,
			java.util.function.IntFunction<Component> label, OptionInstance.ValueUpdateListener<Integer> onChange) {
		return new OptionInstance<>(key, OptionInstance.noTooltip(),
				(caption, v) -> Options.genericValueLabel(caption, label.apply(v)),
				new OptionInstance.IntRange(min, max), Math.clamp(initial, min, max), onChange);
	}

	/** Cycle button over an enum. Each constant is labelled via {@code <key>.<constant_name>}. */
	private static <E extends Enum<E>> OptionInstance<E> enumOption(String key, E[] values, E initial,
			OptionInstance.ValueUpdateListener<E> onChange) {
		Codec<E> codec = Codec.STRING.xmap(
				name -> Arrays.stream(values).filter(e -> e.name().equals(name)).findFirst().orElse(initial),
				Enum::name);
		return new OptionInstance<>(key, OptionInstance.noTooltip(),
				(caption, v) -> Options.genericValueLabel(caption,
						Component.translatable(key + "." + v.name().toLowerCase(Locale.ROOT))),
				new OptionInstance.Enum<>(List.of(values), codec), initial, onChange);
	}

	// --- Keybinds -------------------------------------------------------------------

	private void addKeybindRows() {
		list.addHeader(Component.translatable("key.category.player_radar.radar"));

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
