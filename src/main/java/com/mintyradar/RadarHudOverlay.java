package com.mintyradar;

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
 * Draws the radar: a square or round panel that rotates with the player's view, with
 * distance rings, compass letters, mob dots, player markers, Δ/∇ height markers,
 * names, and the player list.
 *
 * <p>Apart from player heads, which use vanilla's face renderer, and text, everything
 * is a plain fill, so it all batches into the vanilla GUI pass. Circles are drawn
 * one fill per pixel row.
 */
public final class RadarHudOverlay implements HudElement {
	private static final int BORDER_COLOR = 0xFF3A3A3A;
	private static final int GRID_COLOR = 0x40FFFFFF;
	private static final int RING_COLOR = 0x50FFFFFF;
	private static final int RING_LABEL_COLOR = 0xB0FFFFFF;
	private static final int SELF_COLOR = 0xFFFFFFFF;
	private static final int BLIP_COLOR = 0xFFFFFFFF;
	private static final int BLIP_OUTLINE = 0xFF000000;
	private static final int HOSTILE_MOB_COLOR = 0xFFFF5555;
	private static final int PASSIVE_MOB_COLOR = 0xFFBBBBBB;
	/** Minty Radar's accent: north on the compass, and friend stars. */
	private static final int MINT = 0xFF6EE7B7;
	private static final int COMPASS_COLOR = 0xFFCCCCCC;
	private static final int LIST_TEXT_COLOR = 0xFFE0E0E0;
	private static final int DISTANCE_COLOR = 0xFFAAAAAA;
	/** Player list row height and head size, in GUI pixels at 100% text size. */
	private static final int ROW_HEIGHT = 11;
	private static final int ROW_HEAD = 10;
	/** Space between the radar panel and the player list. */
	private static final int LIST_GAP = 3;
	/** Alpha applied to blips pinned to the edge (out of range). */
	private static final int CLAMPED_ALPHA = 0x99;
	/** Base scales, multiplied by the Text Size setting. */
	private static final float SMALL_TEXT_SCALE = 0.5f;
	private static final float COMPASS_SCALE = 0.75f;
	/** Distance from the radar edge to the compass letters' centres. */
	private static final int COMPASS_INSET = 5;
	private static final String STAR = "★";

	private static final Component NORTH = Component.translatable("hud.minty_radar.north");
	private static final Component EAST = Component.translatable("hud.minty_radar.east");
	private static final Component SOUTH = Component.translatable("hud.minty_radar.south");
	private static final Component WEST = Component.translatable("hud.minty_radar.west");

	private final RadarConfig config;
	private final RadarManager manager;

	// Cached text that only changes when the player count or range does.
	private Component header = Component.empty();
	private int headerCount = -1;
	private int headerRange = -1;
	private String halfRangeLabel = "";
	private String fullRangeLabel = "";
	private int labelRange = -1;

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
		Layout layout = layout(config, g.guiWidth(), g.guiHeight());

		if (config.showMap) {
			drawMap(g, font, self, deltaTracker, layout.x0, layout.y0, layout.size);
		}

		if (config.showPlayerList) {
			drawPlayerList(g, font, layout.x0, layout.y0, layout.size, layout.right, layout.bottom);
		}
	}

	/**
	 * Where things go on screen.
	 *
	 * @param x0, y0, size the radar panel (size 0 when the map is hidden: then x0/y0 is
	 *                     the point the player list hangs from)
	 * @param right        align the player list to the right edge
	 * @param bottom       grow the player list upwards, above the panel
	 * @param boxX, boxY   the radar's full-size box, which the Move Radar screen drags
	 */
	public record Layout(int x0, int y0, int size, boolean right, boolean bottom, int boxX, int boxY) {
	}

	/** Works out the layout for the configured corner or custom (dragged) position. */
	public static Layout layout(RadarConfig config, int guiWidth, int guiHeight) {
		int s = config.size;
		int boxX;
		int boxY;
		boolean right;
		boolean bottom;
		if (config.customPosition) {
			boxX = Math.round(config.posX * Math.max(0, guiWidth - s));
			boxY = Math.round(config.posY * Math.max(0, guiHeight - s));
			// The list goes on whichever side has more room.
			right = boxX + s / 2 > guiWidth / 2;
			bottom = boxY + s / 2 > guiHeight / 2;
		} else {
			right = config.corner == RadarConfig.Corner.TOP_RIGHT || config.corner == RadarConfig.Corner.BOTTOM_RIGHT;
			bottom = config.corner == RadarConfig.Corner.BOTTOM_LEFT || config.corner == RadarConfig.Corner.BOTTOM_RIGHT;
			boxX = right ? guiWidth - config.margin - s : config.margin;
			boxY = bottom ? guiHeight - config.margin - s : config.margin;
		}

		if (config.showMap) {
			return new Layout(boxX, boxY, s, right, bottom, boxX, boxY);
		}
		// Map hidden: the list takes the radar's place.
		int edge = right ? boxX + s : boxX;
		if (!config.customPosition) {
			// Corner mode moves the list to the top of the screen, on the corner's side.
			return new Layout(edge, config.margin, 0, right, false, boxX, boxY);
		}
		// Custom position: hang the list from the box, growing away from the nearer edge.
		return bottom
				? new Layout(edge, boxY + s, 0, right, true, boxX, boxY)
				: new Layout(edge, boxY, 0, right, false, boxX, boxY);
	}

	// --- Map ------------------------------------------------------------------------

	/** The radar panel: frame, rings, compass, mobs, player markers, your arrow and names. */
	private void drawMap(GuiGraphicsExtractor g, Font font, LocalPlayer self, DeltaTracker deltaTracker,
			int x0, int y0, int size) {
		boolean circle = config.shape == RadarConfig.Shape.CIRCLE;
		int cx = x0 + size / 2;
		int cy = y0 + size / 2;
		int outer = (size - 1) / 2; // radius of the round panel

		// Keep markers pinned to the edge fully inside the border.
		float radiusPx = (circle ? outer : size / 2f) - markerHalf(false) - 1;
		float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
		// Also updates the view rotation used by the compass below.
		int count = manager.computeBlips(self, partialTick, config, radiusPx);

		drawFrame(g, x0, y0, size, cx, cy, outer, circle);
		if (config.showRings) drawRings(g, font, cx, cy, radiusPx, circle);
		if (config.showCompass) drawCompass(g, font, cx, cy, size, outer, circle);

		// Clip markers to the panel. Height markers near the edge would otherwise spill out.
		g.enableScissor(x0 + 1, y0 + 1, x0 + size - 1, y0 + size - 1);

		if (config.mobMode != RadarConfig.MobMode.OFF) {
			int mobs = manager.computeMobBlips(self, partialTick, config, radiusPx);
			List<RadarManager.MobBlip> mobBlips = manager.getMobBlips();
			for (int i = 0; i < mobs; i++) {
				RadarManager.MobBlip m = mobBlips.get(i);
				drawMob(g, cx + Math.round(m.x), cy + Math.round(m.y), m.hostile);
			}
		}

		List<RadarManager.Blip> blips = manager.getBlips();
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

		// Names are drawn last so no marker covers them.
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

	private void drawFrame(GuiGraphicsExtractor g, int x0, int y0, int size, int cx, int cy, int outer, boolean circle) {
		int bg = (config.backgroundAlpha << 24) | 0x101010;
		if (circle) {
			fillDisc(g, cx, cy, outer, bg);
			drawRing(g, cx, cy, outer, BORDER_COLOR);
			// Crosshair within the circle.
			g.fill(cx, cy - outer + 1, cx + 1, cy + outer, GRID_COLOR);
			g.fill(cx - outer + 1, cy, cx + outer, cy + 1, GRID_COLOR);
		} else {
			g.fill(x0, y0, x0 + size, y0 + size, bg);
			g.outline(x0, y0, size, size, BORDER_COLOR);
			g.fill(cx, y0 + 1, cx + 1, y0 + size - 1, GRID_COLOR);
			g.fill(x0 + 1, cy, x0 + size - 1, cy + 1, GRID_COLOR);
		}
	}

	/**
	 * Circles at half and full radar range, each labelled with its distance on the
	 * lower-right diagonal, just inside the circle. On a round radar the full-range
	 * circle is the border, so only its label is drawn.
	 */
	private void drawRings(GuiGraphicsExtractor g, Font font, int cx, int cy, float radiusPx, boolean circle) {
		if (labelRange != config.range()) {
			labelRange = config.range();
			halfRangeLabel = (labelRange / 2) + "m";
			fullRangeLabel = labelRange + "m";
		}

		int full = Math.round(radiusPx);
		int half = Math.round(radiusPx / 2f);
		drawRing(g, cx, cy, half, RING_COLOR);
		if (!circle) drawRing(g, cx, cy, full, RING_COLOR);

		float scale = SMALL_TEXT_SCALE * config.textScale();
		drawRingLabel(g, font, halfRangeLabel, cx, cy, half, scale);
		drawRingLabel(g, font, fullRangeLabel, cx, cy, full, scale);
	}

	private static void drawRingLabel(GuiGraphicsExtractor g, Font font, String label, int cx, int cy, int radius, float scale) {
		float diag = radius * 0.7071f; // 45°
		float width = font.width(label) * scale;
		float height = font.lineHeight * scale;
		g.pose().pushMatrix();
		g.pose().translate(cx + diag - width - 1, cy + diag - height - 1);
		g.pose().scale(scale);
		g.text(font, label, 0, 0, RING_LABEL_COLOR, true);
		g.pose().popMatrix();
	}

	/**
	 * N/E/S/W letters just inside the edge, turning with your view. North is -Z and
	 * east is +X in Minecraft.
	 */
	private void drawCompass(GuiGraphicsExtractor g, Font font, int cx, int cy, int size, int outer, boolean circle) {
		float inset = (circle ? outer : size / 2f) - COMPASS_INSET;
		float scale = COMPASS_SCALE * config.textScale();
		drawCompassLetter(g, font, NORTH, 0, -1, cx, cy, inset, circle, scale, MINT);
		drawCompassLetter(g, font, EAST, 1, 0, cx, cy, inset, circle, scale, COMPASS_COLOR);
		drawCompassLetter(g, font, SOUTH, 0, 1, cx, cy, inset, circle, scale, COMPASS_COLOR);
		drawCompassLetter(g, font, WEST, -1, 0, cx, cy, inset, circle, scale, COMPASS_COLOR);
	}

	private void drawCompassLetter(GuiGraphicsExtractor g, Font font, Component letter, int dirX, int dirZ,
			int cx, int cy, float inset, boolean circle, float scale, int color) {
		double sx = manager.screenX(dirX, dirZ);
		double sy = manager.screenY(dirX, dirZ);
		// Round radar: the direction is already a unit vector. Square radar: push it
		// out to the square's edge so letters follow the border.
		double extent = circle ? 1 : Math.max(Math.abs(sx), Math.abs(sy));
		float px = (float) (cx + 0.5 + sx / extent * inset);
		float py = (float) (cy + 0.5 + sy / extent * inset);

		g.pose().pushMatrix();
		g.pose().translate(px, py);
		g.pose().scale(scale);
		g.text(font, letter, -font.width(letter) / 2, -font.lineHeight / 2 + 1, color, true);
		g.pose().popMatrix();
	}

	/** Upward-pointing arrow for the local player. The radar is heading-up, so it never rotates. */
	private static void drawSelfArrow(GuiGraphicsExtractor g, int cx, int cy) {
		g.fill(cx, cy - 2, cx + 1, cy - 1, SELF_COLOR);
		g.fill(cx - 1, cy - 1, cx + 2, cy, SELF_COLOR);
		g.fill(cx - 2, cy, cx + 3, cy + 1, SELF_COLOR);
		g.fill(cx - 2, cy + 1, cx - 1, cy + 2, SELF_COLOR);
		g.fill(cx + 2, cy + 1, cx + 3, cy + 2, SELF_COLOR);
	}

	/** 2x2 dot with a dark outline: red for hostile mobs, gray for the rest. */
	private static void drawMob(GuiGraphicsExtractor g, int x, int y, boolean hostile) {
		g.fill(x - 2, y - 2, x + 2, y + 2, BLIP_OUTLINE);
		g.fill(x - 1, y - 1, x + 1, y + 1, hostile ? HOSTILE_MOB_COLOR : PASSIVE_MOB_COLOR);
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
		int half = markerHalf(b.clamped);

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

		if (b.friend) drawFriendStar(g, x + half, y - half);

		// Height indicators: Δ above the marker if the player is higher, ∇ below if lower.
		// The triangle's wide base sits one pixel clear of the marker's edge.
		int gap = half + 2;
		if (b.dy > config.verticalThreshold) {
			drawTriangle(g, x, y - gap, -1, color);
		} else if (b.dy < -config.verticalThreshold) {
			drawTriangle(g, x, y + gap, 1, color);
		}
	}

	/** Small mint plus-shaped star centred on the marker's top-right corner. */
	private static void drawFriendStar(GuiGraphicsExtractor g, int x, int y) {
		g.fill(x - 2, y - 1, x + 3, y + 2, BLIP_OUTLINE);
		g.fill(x - 1, y - 2, x + 2, y + 3, BLIP_OUTLINE);
		g.fill(x - 1, y, x + 2, y + 1, MINT);
		g.fill(x, y - 1, x + 1, y + 2, MINT);
	}

	/**
	 * Draws the player's name centered above the marker (or above the Δ marker when
	 * there is one). It is nudged sideways so it stays inside the panel.
	 */
	private void drawName(GuiGraphicsExtractor g, Font font, int x, int y, RadarManager.Blip b, int panelX, int panelSize) {
		float scale = SMALL_TEXT_SCALE * config.textScale();
		float halfWidth = font.width(b.name) * scale / 2f;
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
		g.pose().scale(scale);
		g.text(font, b.name, -font.width(b.name) / 2, -font.lineHeight + 1, b.friend ? MINT : BLIP_COLOR, true);
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

	/** Filled circle of radius {@code r} centred on pixel (cx, cy), one fill per row. */
	private static void fillDisc(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
		for (int dy = -r; dy <= r; dy++) {
			int half = (int) Math.sqrt((double) r * r - (double) dy * dy);
			g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
		}
	}

	/** 1px circle outline of radius {@code r}, drawn as up to two short fills per row. */
	private static void drawRing(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
		if (r <= 0) return;
		for (int dy = -r; dy <= r; dy++) {
			int y = cy + dy;
			int outerHalf = (int) Math.sqrt((double) r * r - (double) dy * dy);
			if (Math.abs(dy) >= r - 1) {
				// Top and bottom rows: one solid span.
				g.fill(cx - outerHalf, y, cx + outerHalf + 1, y + 1, color);
				continue;
			}
			int innerHalf = (int) Math.sqrt((double) (r - 1) * (r - 1) - (double) dy * dy);
			innerHalf = Math.min(innerHalf, outerHalf - 1); // keep each side at least 1px wide
			g.fill(cx - outerHalf, y, cx - innerHalf, y + 1, color);
			g.fill(cx + innerHalf + 1, y, cx + outerHalf + 1, y + 1, color);
		}
	}

	// --- Player list ----------------------------------------------------------------

	/**
	 * Lists tracked players (nearest first) with their head, name and distance. It sits
	 * below the radar in top corners and above it in bottom corners, and is aligned to
	 * the radar's outer edge. Players beyond the list limit, or that don't fit on
	 * screen, collapse into a "+N more" line. The whole list scales with Text Size.
	 */
	private void drawPlayerList(GuiGraphicsExtractor g, Font font, int x0, int y0, int size, boolean right, boolean bottom) {
		List<RadarManager.Tracked> players = manager.getTracked();
		int count = manager.getTrackedCount();
		float scale = config.textScale();

		// Header text only changes when the count or range does, so it is cached.
		if (count != headerCount || config.range() != headerRange) {
			headerCount = count;
			headerRange = config.range();
			header = Component.translatable("hud.minty_radar.players", count, headerRange);
		}

		// Rows that fit on screen (minus the header), then the user's limit on top.
		int available = bottom ? y0 - LIST_GAP - config.margin : g.guiHeight() - config.margin - (y0 + size + LIST_GAP);
		int fitRows = Math.max(0, (int) (available / (ROW_HEIGHT * scale)) - 1);
		int limit = config.listLimit == 0 ? Integer.MAX_VALUE : config.listLimit;
		int shown;
		if (count <= Math.min(limit, fitRows)) {
			shown = count;
		} else {
			shown = Math.max(0, Math.min(limit, fitRows - 1)); // leave room for "+N more"
		}
		int more = count - shown;
		int rows = 1 + shown + (more > 0 ? 1 : 0);

		float height = rows * ROW_HEIGHT * scale;
		float top = bottom ? y0 - LIST_GAP - height : y0 + size + LIST_GAP;
		int edge = right ? x0 + size : x0; // outer edge the rows align to

		// Rows are laid out in unscaled local coordinates from the list's anchor corner.
		g.pose().pushMatrix();
		g.pose().translate(edge, top);
		g.pose().scale(scale);

		int y = 0;
		drawListText(g, font, header, y, right, LIST_TEXT_COLOR);
		y += ROW_HEIGHT;

		for (int i = 0; i < shown; i++) {
			RadarManager.Tracked t = players.get(i);
			int starWidth = t.friend ? font.width(STAR) + 2 : 0;
			int nameWidth = font.width(t.name);
			int distWidth = font.width(t.distanceText);
			int rowWidth = ROW_HEAD + 3 + starWidth + nameWidth + 4 + distWidth;
			int x = right ? -rowWidth : 0;

			// Head (or a plain swatch in dot mode) with a dark frame.
			g.outline(x, y, ROW_HEAD, ROW_HEAD, BLIP_OUTLINE);
			if (config.blipStyle == RadarConfig.BlipStyle.HEADS) {
				PlayerFaceExtractor.extractRenderState(g, t.skin, x + 1, y + 1, ROW_HEAD - 2);
			} else {
				g.fill(x + 2, y + 2, x + ROW_HEAD - 2, y + ROW_HEAD - 2, BLIP_COLOR);
			}
			x += ROW_HEAD + 3;

			if (t.friend) {
				g.text(font, STAR, x, y + 1, MINT, true);
				x += starWidth;
			}
			g.text(font, t.name, x, y + 1, t.friend ? MINT : LIST_TEXT_COLOR, true);
			x += nameWidth + 4;
			g.text(font, t.distanceText, x, y + 1, DISTANCE_COLOR, true);
			y += ROW_HEIGHT;
		}

		if (more > 0) {
			drawListText(g, font, Component.translatable("hud.minty_radar.more", more), y, right, DISTANCE_COLOR);
		}
		g.pose().popMatrix();
	}

	/** One line of list text in local coordinates, right-aligned to x = 0 on the right side. */
	private static void drawListText(GuiGraphicsExtractor g, Font font, Component text, int y, boolean right, int color) {
		int x = right ? -font.width(text) : 0;
		g.text(font, text, x, y + 1, color, true);
	}

	private static int withAlpha(int argb, int alpha) {
		return (alpha << 24) | (argb & 0x00FFFFFF);
	}
}
