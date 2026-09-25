package com.mintyradar;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Lets you drag the radar anywhere on screen. The screen has no background, so the
 * real, live radar shows through and moves as you drag it. A mint outline marks the
 * area you can grab.
 *
 * <p>The position is stored as a fraction of the free space on screen (see
 * {@link RadarConfig#posX}), so it stays in the same relative place when the window
 * size or GUI scale changes. Reset returns the radar to its corner setting.
 */
public final class HudPositionScreen extends Screen {
	private static final int OUTLINE = 0xFF6EE7B7; // mint
	private static final int OUTLINE_HOVER = 0xFFFFFFFF;
	private static final int FILL = 0x206EE7B7;
	private static final int HINT_COLOR = 0xFFFFFFFF;

	private final Screen parent;
	private final RadarConfig config = RadarClientMod.config();

	private boolean dragging;
	/** Where inside the box it was grabbed, so it doesn't jump to the cursor. */
	private double grabX;
	private double grabY;

	public HudPositionScreen(Screen parent) {
		super(Component.translatable("options.minty_radar.move.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int y = height - 28;
		addRenderableWidget(Button.builder(Component.translatable("options.minty_radar.move.reset"), b -> {
			config.customPosition = false;
		}).bounds(width / 2 - 154, y, 150, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(width / 2 + 4, y, 150, 20).build());
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		// No blur or dimming: the live radar underneath is what's being positioned.
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);

		RadarHudOverlay.Layout layout = RadarHudOverlay.layout(config, width, height);
		int s = config.size;
		boolean hover = dragging || isOverBox(layout, mouseX, mouseY);
		g.fill(layout.boxX(), layout.boxY(), layout.boxX() + s, layout.boxY() + s, FILL);
		g.outline(layout.boxX() - 1, layout.boxY() - 1, s + 2, s + 2, hover ? OUTLINE_HOVER : OUTLINE);

		Component hint = Component.translatable(config.showMap
				? "options.minty_radar.move.hint"
				: "options.minty_radar.move.hint_list");
		g.centeredText(font, hint.getString(), width / 2, height / 2 - 20, HINT_COLOR);
		g.centeredText(font, title.getString(), width / 2, 12, HINT_COLOR);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true; // buttons first
		RadarHudOverlay.Layout layout = RadarHudOverlay.layout(config, width, height);
		if (event.button() == 0 && isOverBox(layout, event.x(), event.y())) {
			dragging = true;
			grabX = event.x() - layout.boxX();
			grabY = event.y() - layout.boxY();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (!dragging) return super.mouseDragged(event, dragX, dragY);
		int s = config.size;
		double freeX = Math.max(1, width - s);
		double freeY = Math.max(1, height - s);
		double x = Math.clamp(event.x() - grabX, 0, freeX);
		double y = Math.clamp(event.y() - grabY, 0, freeY);
		config.customPosition = true;
		config.posX = (float) (x / freeX);
		config.posY = (float) (y / freeY);
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging && event.button() == 0) {
			dragging = false;
			return true;
		}
		return super.mouseReleased(event);
	}

	private boolean isOverBox(RadarHudOverlay.Layout layout, double x, double y) {
		int s = config.size;
		return x >= layout.boxX() && x < layout.boxX() + s && y >= layout.boxY() && y < layout.boxY() + s;
	}

	@Override
	public boolean isPauseScreen() {
		return false; // keep the world (and the radar) live while positioning
	}

	@Override
	public void onClose() {
		config.save();
		minecraft.gui.setScreen(parent);
	}
}
