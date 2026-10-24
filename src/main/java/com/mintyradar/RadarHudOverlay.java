package com.mintyradar;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * Draws the radar: a square or round panel that turns with the player's view (or stays
 * north up), with optional terrain, distance rings, compass letters, portals, trails,
 * mobs and items, player markers with facing lines and Δ/∇ height markers, names, and
 * the player list.
 *
 * <p>The round background and the rings are painted once into textures (see
 * {@link RadarDecor}) and only repainted when they change. Apart from those, the terrain
 * map, heads and text, everything is a plain fill, so it all batches into the vanilla
 * GUI pass.
 */
public final class RadarHudOverlay implements HudElement {
	private static final int BORDER_COLOR = 0xFF3A3A3A;
	private static final int GRID_COLOR = 0x40FFFFFF;
	private static final int RING_COLOR = 0x50FFFFFF;
	private static final int RING_LABEL_COLOR = 0xB0FFFFFF;
	private static final int SELF_COLOR = 0xFFFFFFFF;
	private static final int BLIP_COLOR = 0xFFFFFFFF;
	private static final int BLIP_OUTLINE = 0xFF000000;
	private static final int FACING_COLOR = 0xFFFFE08A;
	private static final int HOSTILE_MOB_COLOR = 0xFFFF5555;
	private static final int PASSIVE_MOB_COLOR = 0xFFBBBBBB;
	private static final int ITEM_COLOR = 0xFFFFD84D;
	private static final int PORTAL_COLOR = 0xFFB14BFF;
	/** MintyRadar's accent: north on the compass, and friend stars. */
	private static final int MINT = 0xFF6EE7B7;
	private static final int COMPASS_COLOR = 0xFFCCCCCC;
	private static final int LIST_TEXT_COLOR = 0xFFE0E0E0;
	private static final int DISTANCE_COLOR = 0xFFAAAAAA;
	/** Distance colour for players right next to you. It fades to {@link #DISTANCE_COLOR} at the alert distance. */
	private static final int NEAR_COLOR = 0xFFFF6B6B;
	/** Player list row height and head size, in GUI pixels at 100% text size. */
	private static final int ROW_HEIGHT = 11;
	private static final int ROW_HEAD = 10;
	/** Space between the radar panel and the player list. */
	private static final int LIST_GAP = 3;
	/** Alpha applied to blips pinned to the edge (out of range). */
	private static final int CLAMPED_ALPHA = 0x99;
	/** Length of the facing line beyond a marker's edge, in pixels. */
	private static final int FACING_LENGTH = 4;
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
	/** The round background with its border, and the distance rings. */
	private final RadarDecor background = new RadarDecor("background");
	private final RadarDecor rings = new RadarDecor("rings");

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

	/** The radar panel: frame, terrain, rings, compass, extras, mobs, players, your arrow and names. */
	private void drawMap(GuiGraphicsExtractor g, Font font, LocalPlayer self, DeltaTracker deltaTracker,
			int x0, int y0, int size) {
		boolean circle = config.shape == RadarConfig.Shape.CIRCLE;
		int cx = x0 + size / 2;
		int cy = y0 + size / 2;
		int outer = (size - 1) / 2; // radius of the round panel

		// Keep markers pinned to the edge fully inside the border.
		float radiusPx = (circle ? outer : size / 2f) - markerHalf(false) - 1;
		float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
		// Also updates the view rotation used by the compass, terrain and extras below.
		int count = manager.computeBlips(self, partialTick, config, radiusPx);
		double pxPerBlock = radiusPx / config.range();
		double selfX = Mth.lerp(partialTick, self.xo, self.getX());
		double selfZ = Mth.lerp(partialTick, self.zo, self.getZ());

		drawFrame(g, x0, y0, size, cx, cy, outer, circle);
		if (config.terrain) {
			g.enableScissor(x0 + 1, y0 + 1, x0 + size - 1, y0 + size - 1);
			manager.getTerrain().draw(g, cx + 0.5f, cy + 0.5f, pxPerBlock, (float) (Math.PI - manager.viewYawRad()),
					selfX, selfZ, circle ? outer : 0);
			g.disableScissor();
			if (!circle) g.outline(x0, y0, size, size, BORDER_COLOR); // the map covers the square's border
		}
		if (config.showRings) drawRings(g, font, x0, y0, size, radiusPx, circle);
		if (config.showCompass) drawCompass(g, font, cx, cy, size, outer, circle);

		// Clip markers to the panel. Height markers near the edge would otherwise spill out.
		g.enableScissor(x0 + 1, y0 + 1, x0 + size - 1, y0 + size - 1);

		if (config.showPortals) drawPortals(g, cx, cy, radiusPx, pxPerBlock, selfX, selfZ, circle);
		if (config.showTrails) drawTrails(g, cx, cy, radiusPx, pxPerBlock, selfX, selfZ, circle);

		if (config.mobMode != RadarConfig.MobMode.OFF || config.showItems) {
			int mobs = manager.computeMobBlips(self, partialTick, config, radiusPx);
			List<RadarManager.MobBlip> mobBlips = manager.getMobBlips();
			for (int i = 0; i < mobs; i++) {
				RadarManager.MobBlip m = mobBlips.get(i);
				drawMob(g, cx + Math.round(m.x), cy + Math.round(m.y), m);
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

		drawSelfArrow(g, cx, cy, self, partialTick);

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
			RadarDecor.Canvas c = background.begin(size, ((long) bg << 32) | ((long) outer << 16) | size);
			if (c != null) {
				int local = size / 2;
				c.disc(local, local, outer, bg);
				c.ring(local, local, outer, BORDER_COLOR);
				background.finish();
			}
			background.blit(g, x0, y0);
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
	private void drawRings(GuiGraphicsExtractor g, Font font, int x0, int y0, int size, float radiusPx, boolean circle) {
		if (labelRange != config.range()) {
			labelRange = config.range();
			halfRangeLabel = (labelRange / 2) + "m";
			fullRangeLabel = labelRange + "m";
		}

		int full = Math.round(radiusPx);
		int half = Math.round(radiusPx / 2f);
		RadarDecor.Canvas c = rings.begin(size, ((long) full << 32) | ((long) half << 16) | (circle ? 1 : 0));
		if (c != null) {
			int local = size / 2;
			c.ring(local, local, half, RING_COLOR);
			if (!circle) c.ring(local, local, full, RING_COLOR);
			rings.finish();
		}
		rings.blit(g, x0, y0);

		int cx = x0 + size / 2;
		int cy = y0 + size / 2;
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

	/** Purple 3×3 squares on portal columns inside the radar. */
	private void drawPortals(GuiGraphicsExtractor g, int cx, int cy, float radiusPx, double pxPerBlock,
			double selfX, double selfZ, boolean circle) {
		for (long column : manager.getPortals()) {
			double dx = BlockPos.getX(column) + 0.5 - selfX;
			double dz = BlockPos.getZ(column) + 0.5 - selfZ;
			float x = (float) (manager.screenX(dx, dz) * pxPerBlock);
			float y = (float) (manager.screenY(dx, dz) * pxPerBlock);
			if (!RadarManager.inside(x, y, radiusPx, circle)) continue;
			int px = cx + Math.round(x);
			int py = cy + Math.round(y);
			g.fill(px - 1, py - 1, px + 2, py + 2, PORTAL_COLOR);
		}
	}

	/** One pixel per trail point, fading out towards the oldest, in the player's marker colour. */
	private void drawTrails(GuiGraphicsExtractor g, int cx, int cy, float radiusPx, double pxPerBlock,
			double selfX, double selfZ, boolean circle) {
		List<RadarManager.Tracked> players = manager.getTracked();
		for (int i = 0; i < manager.getTrackedCount(); i++) {
			RadarManager.Tracked t = players.get(i);
			RadarManager.Trail trail = t.trail;
			if (trail == null || trail.size() < 2) continue;
			int color = t.friend ? MINT : t.team != -1 ? t.team : BLIP_COLOR;
			int n = trail.size();
			for (int k = 0; k < n; k++) {
				double dx = trail.x(k) - selfX;
				double dz = trail.z(k) - selfZ;
				float x = (float) (manager.screenX(dx, dz) * pxPerBlock);
				float y = (float) (manager.screenY(dx, dz) * pxPerBlock);
				if (!RadarManager.inside(x, y, radiusPx, circle)) continue;
				int px = cx + Math.round(x);
				int py = cy + Math.round(y);
				g.fill(px, py, px + 1, py + 1, withAlpha(color, Math.round(30 + 150f * (k + 1) / n)));
			}
		}
	}

	/**
	 * Arrow for the local player. Heading up: it always points up, since the map turns
	 * instead. North up: it turns with your view.
	 */
	private void drawSelfArrow(GuiGraphicsExtractor g, int cx, int cy, LocalPlayer self, float partialTick) {
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		if (config.rotation == RadarConfig.Rotation.NORTH_UP) {
			// Rotate about the centre of the arrow's middle pixel.
			g.pose().translate(0.5f, 0.5f);
			g.pose().rotate((float) Math.toRadians(self.getViewYRot(partialTick) - 180));
			g.pose().translate(-0.5f, -0.5f);
		}
		g.fill(0, -2, 1, -1, SELF_COLOR);
		g.fill(-1, -1, 2, 0, SELF_COLOR);
		g.fill(-2, 0, 3, 1, SELF_COLOR);
		g.fill(-2, 1, -1, 2, SELF_COLOR);
		g.fill(2, 1, 3, 2, SELF_COLOR);
		g.pose().popMatrix();
	}

	/**
	 * The mob's face (with hat layer if it has one), framed red for hostile mobs and
	 * gray for the rest. Mob heads are 2px smaller than player heads so players stand
	 * out. A mob whose face can't be found gets a small dot instead, and items are a
	 * 2×2 yellow dot.
	 */
	private void drawMob(GuiGraphicsExtractor g, int x, int y, RadarManager.MobBlip m) {
		if (m.item) {
			g.fill(x - 1, y - 1, x + 1, y + 1, ITEM_COLOR);
			return;
		}
		int border = m.hostile ? HOSTILE_MOB_COLOR : PASSIVE_MOB_COLOR;
		MobHeads.Head head = m.head;
		if (head == null) {
			g.fill(x - 2, y - 2, x + 2, y + 2, BLIP_OUTLINE);
			g.fill(x - 1, y - 1, x + 1, y + 1, border);
			return;
		}

		int s = Math.max(4, config.headSize - 2);
		int left = x - s / 2;
		int top = y - s / 2;
		g.outline(left - 1, top - 1, s + 2, s + 2, border);
		drawFace(g, head.texture(), head.face(), left, top, s);
		if (head.hat() != null) drawFace(g, head.texture(), head.hat(), left, top, s);
	}

	/** Draws a face into an s×s box, keeping its shape (e.g. villagers' tall faces). */
	private static void drawFace(GuiGraphicsExtractor g, net.minecraft.resources.Identifier texture,
			MobHeads.Face face, int left, int top, int s) {
		int w = s;
		int h = s;
		if (face.aspect() > 1) h = Math.max(1, Math.round(s / face.aspect()));
		else if (face.aspect() < 1) w = Math.max(1, Math.round(s * face.aspect()));
		int x = left + (s - w) / 2;
		int y = top + (s - h) / 2;
		g.blit(texture, x, y, x + w, y + h, face.u0(), face.u1(), face.v0(), face.v1());
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
		// Team colour frames the marker; otherwise a dark frame so it stands out on the background.
		int frame = b.team != -1 ? b.team : BLIP_OUTLINE;
		if (b.clamped) frame = withAlpha(frame, CLAMPED_ALPHA);
		int half = markerHalf(b.clamped);

		if (config.showFacing && !b.clamped) {
			// A short line from the marker's edge, the way the player is looking.
			int lineColor = b.friend ? MINT : FACING_COLOR;
			for (int i = 1; i <= FACING_LENGTH; i++) {
				int px = x + Math.round(b.lookX * (half + i));
				int py = y + Math.round(b.lookY * (half + i));
				g.fill(px, py, px + 1, py + 1, lineColor);
			}
		}

		if (config.blipStyle == RadarConfig.BlipStyle.HEADS) {
			// Face plus hat (outer) layer inside a 1px frame. Off-range heads are faded.
			int s = headSize(b.clamped);
			int left = x - s / 2;
			int top = y - s / 2;
			g.outline(left - 1, top - 1, s + 2, s + 2, frame);
			PlayerFaceExtractor.extractRenderState(g, b.skin, left, top, s,
					b.clamped ? withAlpha(0xFFFFFF, CLAMPED_ALPHA) : 0xFFFFFFFF);
		} else if (b.clamped) {
			// Smaller 2x2 marker for off-range players.
			g.fill(x - 1, y - 1, x + 1, y + 1, b.team != -1 ? withAlpha(b.team, CLAMPED_ALPHA) : color);
		} else {
			// 3x3 dot with a 1px dark outline for contrast on bright backgrounds.
			g.fill(x - 2, y - 2, x + 3, y + 3, BLIP_OUTLINE);
			g.fill(x - 1, y - 1, x + 2, y + 2, b.team != -1 ? b.team : color);
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
		g.text(font, b.name, -font.width(b.name) / 2, -font.lineHeight + 1, nameColor(b.friend, b.team, BLIP_COLOR), true);
		g.pose().popMatrix();
	}

	/** Friends in mint, then team colour, then the default. */
	private static int nameColor(boolean friend, int team, int fallback) {
		return friend ? MINT : team != -1 ? team : fallback;
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

	// --- Player list ----------------------------------------------------------------

	/**
	 * Lists tracked players (nearest first) with their head, name, distance and health.
	 * It sits below the radar in top corners and above it in bottom corners, and is
	 * aligned to the radar's outer edge. Players beyond the list limit, or that don't fit
	 * on screen, collapse into a "+N more" line. The whole list scales with Text Size.
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
			int nameWidth = font.width(t.shownName);
			int distWidth = font.width(t.distanceText);
			int healthWidth = config.listHealth ? 4 + font.width(t.healthText) : 0;
			int rowWidth = ROW_HEAD + 3 + starWidth + nameWidth + 4 + distWidth + healthWidth;
			int x = right ? -rowWidth : 0;

			// Head (or a plain swatch in dot mode) with a dark frame.
			g.outline(x, y, ROW_HEAD, ROW_HEAD, BLIP_OUTLINE);
			if (config.blipStyle == RadarConfig.BlipStyle.HEADS) {
				PlayerFaceExtractor.extractRenderState(g, t.skin, x + 1, y + 1, ROW_HEAD - 2);
			} else {
				g.fill(x + 2, y + 2, x + ROW_HEAD - 2, y + ROW_HEAD - 2, t.team != -1 ? t.team : BLIP_COLOR);
			}
			x += ROW_HEAD + 3;

			if (t.friend) {
				g.text(font, STAR, x, y + 1, MINT, true);
				x += starWidth;
			}
			g.text(font, t.shownName, x, y + 1, nameColor(t.friend, t.team, LIST_TEXT_COLOR), true);
			x += nameWidth + 4;
			g.text(font, t.distanceText, x, y + 1, distanceColor(t), true);
			if (config.listHealth) {
				x += distWidth + 4;
				g.text(font, t.healthText, x, y + 1, healthColor(t.health), true);
			}
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

	/** Red for players right next to you, fading to gray at the alert distance and beyond. */
	private int distanceColor(RadarManager.Tracked t) {
		float f = (float) Math.clamp(Math.sqrt(t.distSq) / Math.max(8, config.alertDistance), 0, 1);
		return lerpColor(NEAR_COLOR, DISTANCE_COLOR, f);
	}

	private static int healthColor(int health) {
		if (health >= 16) return 0xFF55FF55; // green
		if (health >= 10) return 0xFFFFD84D; // yellow
		if (health >= 6) return 0xFFFFA23D;  // orange
		return 0xFFFF5555;                   // red
	}

	/** Blends two ARGB colours channel by channel, {@code t} from 0 (all c0) to 1 (all c1). */
	private static int lerpColor(int c0, int c1, float t) {
		int a = Math.round(Mth.lerp(t, (c0 >>> 24) & 0xFF, (c1 >>> 24) & 0xFF));
		int r = Math.round(Mth.lerp(t, (c0 >> 16) & 0xFF, (c1 >> 16) & 0xFF));
		int gr = Math.round(Mth.lerp(t, (c0 >> 8) & 0xFF, (c1 >> 8) & 0xFF));
		int b = Math.round(Mth.lerp(t, c0 & 0xFF, c1 & 0xFF));
		return (a << 24) | (r << 16) | (gr << 8) | b;
	}

	private static int withAlpha(int argb, int alpha) {
		return (alpha << 24) | (argb & 0x00FFFFFF);
	}
}
