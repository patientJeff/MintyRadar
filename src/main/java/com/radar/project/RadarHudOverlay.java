package com.radar.project;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Draws the radar: a square panel that rotates with the player's view, blips for
 * other players, and Δ/∇ markers for large height differences.
 *
 * <p>Apart from player heads, which use vanilla's face renderer, everything is a
 * plain fill, so it all batches into the vanilla GUI pass.
 */
public final class RadarHudOverlay implements HudElement {
	private static final int BORDER_COLOR = 0xFF3A3A3A;
	private static final int GRID_COLOR = 0x40FFFFFF;
	private static final int SELF_COLOR = 0xFFFFFFFF;
	private static final int BLIP_COLOR = 0xFFFFFFFF;
	private static final int BLIP_OUTLINE = 0xFF000000;
	private static final int LIST_TEXT_COLOR = 0xFFE0E0E0;
	private static final int DISTANCE_COLOR = 0xFFAAAAAA;
	/** Player list row height and head size, in GUI pixels. */
	private static final int ROW_HEIGHT = 11;
	private static final int ROW_HEAD = 10;
	/** Space between the radar panel and the player list. */
	private static final int LIST_GAP = 3;
	/** Alpha applied to blips pinned to the edge (out of range). */
	private static final int CLAMPED_ALPHA = 0x99;
	/** Names are drawn at half size so they don't swamp the small radar. */
	private static final float NAME_SCALE = 0.5f;

	private final RadarConfig config;
	private final RadarManager manager;

	private Component header = Component.empty();
	private int headerCount = -1;
	private int headerRange = -1;

	public RadarHudOverlay(RadarConfig config, RadarManager manager) {
		this.config = config;
		this.manager = manager;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer self = mc.player;
		if (!config.enabled || self == null || mc.level == null) return;
		// Hidden while the HUD is off (F1) or the debug screen (F3) would overlap it.
		if (mc.gui.hud.isHidden() || mc.getDebugOverlay().showDebugScreen()) return;

		Font font = mc.font;
		// With the map hidden the panel collapses to zero size and the player list moves
		// to the top of the screen, keeping the corner's left/right side.
		int size = config.showMap ? config.size : 0;

		// Top-left of the radar panel for the configured corner.
		boolean right = config.corner == RadarConfig.Corner.TOP_RIGHT || config.corner == RadarConfig.Corner.BOTTOM_RIGHT;
		boolean bottom = config.showMap
				&& (config.corner == RadarConfig.Corner.BOTTOM_LEFT || config.corner == RadarConfig.Corner.BOTTOM_RIGHT);
		int x0 = right ? g.guiWidth() - config.margin - size : config.margin;
		int y0 = bottom ? g.guiHeight() - config.margin - size : config.margin;

		if (config.showMap) {
			drawMap(g, font, self, deltaTracker, x0, y0, size);
		}

		if (config.showPlayerList) {
			drawPlayerList(g, font, x0, y0, size, right, bottom);
		}
	}

	/** The radar panel itself: frame, player markers, your arrow and on-map names. */
	private void drawMap(GuiGraphicsExtractor g, Font font, LocalPlayer self, DeltaTracker deltaTracker,
			int x0, int y0, int size) {
		int cx = x0 + size / 2;
		int cy = y0 + size / 2;

		drawFrame(g, x0, y0, size, cx, cy);

		// Keep markers pinned to the edge fully inside the border.
		float radiusPx = size / 2f - markerHalf(false) - 1;
		float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
		int count = manager.computeBlips(self, partialTick, config, radiusPx);

		List<RadarManager.Blip> blips = manager.getBlips();
		// Clip markers to the panel. Height markers near the edge would otherwise spill out.
		g.enableScissor(x0 + 1, y0 + 1, x0 + size - 1, y0 + size - 1);
		// Two passes: edge-pinned (out of range) blips first, so in-range ones draw on top.
		for (int pass = 0; pass < 2; pass++) {
			boolean wantClamped = pass == 0;
			for (int i = 0; i < count; i++) {
				RadarManager.Blip b = blips.get(i);
				if (b.clamped == wantClamped) drawBlip(g, cx + Math.round(b.x), cy + Math.round(b.y), b);
			}
		}
		g.disableScissor();

		drawSelfArrow(g, cx, cy);

		// Names are drawn last so no dot covers them.
		boolean showNames = switch (config.nameMode) {
			case ALWAYS -> true;
			case WHILE_SNEAKING -> self.isShiftKeyDown();
			case NEVER -> false;
		};
		if (showNames) {
			for (int i = 0; i < count; i++) {
				RadarManager.Blip b = blips.get(i);
				if (!b.clamped) drawName(g, font, cx + Math.round(b.x), cy + Math.round(b.y), b, x0, size);
			}
		}
	}

	/**
	 * Lists every tracked player (nearest first) with their head, name and distance.
	 * It sits below the radar in top corners and above it in bottom corners, and is
	 * aligned to the radar's outer edge. Rows that don't fit on screen collapse into
	 * a "+N more" line.
	 */
	private void drawPlayerList(GuiGraphicsExtractor g, Font font, int x0, int y0, int size, boolean right, boolean bottom) {
		List<RadarManager.Tracked> players = manager.getTracked();
		int count = manager.getTrackedCount();

		// Header text only changes when the count or range does, so it is cached.
		if (count != headerCount || config.range() != headerRange) {
			headerCount = count;
			headerRange = config.range();
			header = Component.translatable("hud.player_radar.players", count, headerRange);
		}

		// Fit as many rows as the screen allows, reserving one for "+N more" if needed.
		int available = bottom ? y0 - LIST_GAP - config.margin : g.guiHeight() - config.margin - (y0 + size + LIST_GAP);
		int maxRows = Math.max(0, available / ROW_HEIGHT - 1); // minus the header row
		int shown = count <= maxRows ? count : Math.max(0, maxRows - 1);
		int more = count - shown;
		int rows = 1 + shown + (more > 0 ? 1 : 0);

		int top = bottom ? y0 - LIST_GAP - rows * ROW_HEIGHT : y0 + size + LIST_GAP;
		int edge = right ? x0 + size : x0; // outer edge the rows align to

		int y = top;
		drawListText(g, font, header, edge, y, right, LIST_TEXT_COLOR);
		y += ROW_HEIGHT;

		for (int i = 0; i < shown; i++) {
			RadarManager.Tracked t = players.get(i);
			int nameWidth = font.width(t.name);
			int distWidth = font.width(t.distanceText);
			int rowWidth = ROW_HEAD + 3 + nameWidth + 4 + distWidth;
			int x = right ? edge - rowWidth : edge;

			// Head (or a plain swatch in dot mode) with a dark frame.
			g.outline(x, y, ROW_HEAD, ROW_HEAD, BLIP_OUTLINE);
			if (config.blipStyle == RadarConfig.BlipStyle.HEADS) {
				PlayerFaceExtractor.extractRenderState(g, t.skin, x + 1, y + 1, ROW_HEAD - 2);
			} else {
				g.fill(x + 2, y + 2, x + ROW_HEAD - 2, y + ROW_HEAD - 2, BLIP_COLOR);
			}
			x += ROW_HEAD + 3;

			g.text(font, t.name, x, y + 1, LIST_TEXT_COLOR, true);
			x += nameWidth + 4;
			g.text(font, t.distanceText, x, y + 1, DISTANCE_COLOR, true);
			y += ROW_HEIGHT;
		}

		if (more > 0) {
			drawListText(g, font, Component.translatable("hud.player_radar.more", more), edge, y, right, DISTANCE_COLOR);
		}
	}

	private static void drawListText(GuiGraphicsExtractor g, Font font, Component text, int edge, int y, boolean right, int color) {
		int x = right ? edge - font.width(text) : edge;
		g.text(font, text, x, y + 1, color, true);
	}

	private void drawFrame(GuiGraphicsExtractor g, int x0, int y0, int size, int cx, int cy) {
		int bg = (config.backgroundAlpha << 24) | 0x101010;
		g.fill(x0, y0, x0 + size, y0 + size, bg);
		g.outline(x0, y0, size, size, BORDER_COLOR);

		// Crosshair plus a half-range box to help judge distance.
		g.fill(cx, y0 + 1, cx + 1, y0 + size - 1, GRID_COLOR);
		g.fill(x0 + 1, cy, x0 + size - 1, cy + 1, GRID_COLOR);
		int half = size / 4;
		g.outline(cx - half, cy - half, half * 2 + 1, half * 2 + 1, GRID_COLOR);
	}

	/** Upward-pointing arrow for the local player. The radar is heading-up, so it never rotates. */
	private static void drawSelfArrow(GuiGraphicsExtractor g, int cx, int cy) {
		g.fill(cx, cy - 2, cx + 1, cy - 1, SELF_COLOR);
		g.fill(cx - 1, cy - 1, cx + 2, cy, SELF_COLOR);
		g.fill(cx - 2, cy, cx + 3, cy + 1, SELF_COLOR);
		g.fill(cx - 2, cy + 1, cx - 1, cy + 2, SELF_COLOR);
		g.fill(cx + 2, cy + 1, cx + 3, cy + 2, SELF_COLOR);
	}

	/** Head size in GUI pixels. Out-of-range heads pinned to the edge are drawn smaller. */
	private int headSize(boolean clamped) {
		return clamped ? Math.max(4, config.headSize - 2) : config.headSize;
	}

	/** Distance from a marker's center to its outer edge, including the 1px border. */
	private int markerHalf(boolean clamped) {
		if (config.blipStyle == RadarConfig.BlipStyle.HEADS) return headSize(clamped) / 2 + 1;
		return clamped ? 1 : 2;
	}

	private void drawBlip(GuiGraphicsExtractor g, int x, int y, RadarManager.Blip b) {
		int color = b.clamped ? withAlpha(BLIP_COLOR, CLAMPED_ALPHA) : BLIP_COLOR;
		int outline = b.clamped ? withAlpha(BLIP_OUTLINE, CLAMPED_ALPHA) : BLIP_OUTLINE;

		if (config.blipStyle == RadarConfig.BlipStyle.HEADS) {
			// Face plus hat (outer) layer, framed by a 1px dark border so it stands out on
			// the background. Off-range heads are faded.
			int s = headSize(b.clamped);
			int left = x - s / 2;
			int top = y - s / 2;
			g.outline(left - 1, top - 1, s + 2, s + 2, outline);
			PlayerFaceExtractor.extractRenderState(g, b.skin, left, top, s,
					b.clamped ? withAlpha(0xFFFFFF, CLAMPED_ALPHA) : 0xFFFFFFFF);
		} else if (b.clamped) {
			// Smaller 2x2 marker for off-range players.
			g.fill(x - 1, y - 1, x + 1, y + 1, color);
		} else {
			// 3x3 dot with a 1px dark outline for contrast on bright backgrounds.
			g.fill(x - 2, y - 2, x + 3, y + 3, BLIP_OUTLINE);
			g.fill(x - 1, y - 1, x + 2, y + 2, color);
		}

		// Height indicators: Δ above the marker if the player is higher, ∇ below if lower.
		// The triangle's wide base sits one pixel clear of the marker's edge.
		int gap = markerHalf(b.clamped) + 2;
		if (b.dy > config.verticalThreshold) {
			drawTriangle(g, x, y - gap, -1, color);
		} else if (b.dy < -config.verticalThreshold) {
			drawTriangle(g, x, y + gap, 1, color);
		}
	}

	/**
	 * Draws the player's name at half scale, centered above the dot (or above the Δ
	 * marker when there is one). It is nudged sideways so it stays inside the panel.
	 */
	private void drawName(GuiGraphicsExtractor g, Font font, int x, int y, RadarManager.Blip b, int panelX, int panelSize) {
		float halfWidth = font.width(b.name) * NAME_SCALE / 2f;
		float minX = panelX + 1 + halfWidth;
		float maxX = panelX + panelSize - 1 - halfWidth;
		// A name wider than the panel (possible at small sizes) is just centered on it.
		float nameX = minX <= maxX ? Math.clamp(x, minX, maxX) : panelX + panelSize / 2f;
		// Bottom of the text sits one pixel above the marker's top edge, or above the Δ tip
		// (which ends 4 px above the marker).
		int markerTop = y - markerHalf(false);
		int bottom = b.dy > config.verticalThreshold ? markerTop - 5 : markerTop - 1;

		g.pose().pushMatrix();
		g.pose().translate(nameX, bottom);
		g.pose().scale(NAME_SCALE);
		g.text(font, b.name, -font.width(b.name) / 2, -font.lineHeight + 1, BLIP_COLOR, true);
		g.pose().popMatrix();
	}

	/**
	 * Draws a filled triangle 5 px wide and 3 px tall. {@code baseY} is the row of the
	 * wide base. The tip extends in {@code direction} (-1 = up for Δ, +1 = down for ∇).
	 */
	private static void drawTriangle(GuiGraphicsExtractor g, int x, int baseY, int direction, int color) {
		for (int row = 0; row < 3; row++) {
			int halfWidth = 2 - row;
			int y = baseY + row * direction;
			g.fill(x - halfWidth, y, x + halfWidth + 1, y + 1, color);
		}
	}

	private static int withAlpha(int argb, int alpha) {
		return (alpha << 24) | (argb & 0x00FFFFFF);
	}
}
