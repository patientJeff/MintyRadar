package com.mintyradar;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * The radar's terrain background: a small top-down map of the loaded ground around you,
 * in vanilla map colours with height shading.
 *
 * <p>The map is drawn into a 128×128 texture a few rows per tick (a new picture about
 * every half second), into a back texture that is swapped in when complete, so nothing
 * heavy ever happens in a frame. On a round radar the picture is cut to a circle in a
 * third texture, again only when you've moved.
 */
final class RadarTerrain {
	static final int SIZE = 128;
	/** Covers the corners of a turned square radar (sqrt 2 = 1.414). */
	private static final double COVER = 1.45;

	// 0, 1: pictures (the back one is being drawn). 2: the picture on show, cut to the round radar.
	private final DynamicTexture[] textures = new DynamicTexture[3];
	private final Identifier[] ids = new Identifier[3];
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	private int picture;
	private int cutPicture = -1;
	private long cutKey = Long.MIN_VALUE;
	private int front = -1;
	private int back = 0;
	/** Next row of the back picture to draw, or -1 to start a new picture. */
	private int row = -1;
	// Settings the back picture is being drawn with.
	private double backX;
	private double backZ;
	private double backScale;
	private int backAlpha;
	private boolean backCave;
	/** World centre and blocks per texel of the picture on show. */
	private double frontX;
	private double frontZ;
	private double frontScale;
	private ClientLevel level;

	private void ensureTextures() {
		if (textures[0] != null) return;
		for (int i = 0; i < 3; i++) {
			int n = i;
			textures[i] = new DynamicTexture(() -> "MintyRadar terrain " + n, SIZE, SIZE, true);
			ids[i] = RadarClientMod.id("terrain_" + i);
			Minecraft.getInstance().getTextureManager().register(ids[i], textures[i]);
		}
	}

	/** Forgets the picture on show, e.g. after changing world. The next one starts from scratch. */
	void reset() {
		row = -1;
		front = -1;
		cutKey = Long.MIN_VALUE;
	}

	/**
	 * Draws some rows of the next picture. Call once per client tick.
	 *
	 * @param range radar radius in blocks
	 * @param alpha terrain opacity, 0-255
	 */
	void tick(double range, int alpha, boolean cave, int rowsPerTick) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel lvl = mc.level;
		if (lvl == null || mc.player == null) return;
		ensureTextures();
		if (lvl != level) {
			level = lvl;
			reset();
		}
		if (row < 0) { // start a new picture where you are now
			backX = Math.floor(mc.player.getX());
			backZ = Math.floor(mc.player.getZ());
			backScale = range * COVER * 2 / SIZE;
			backAlpha = alpha;
			backCave = cave || lvl.dimensionType().hasCeiling();
			row = 0;
		}
		NativeImage img = textures[back].getPixels();
		if (img == null) return;
		int playerY = mc.player.getBlockY();
		for (int n = 0; n < rowsPerTick && row < SIZE; n++, row++) {
			int j = row;
			double dj = j - SIZE / 2.0 + 0.5;
			int wz = Mth.floor(backZ + dj * backScale);
			int northZ = Mth.floor(backZ + (dj - 1) * backScale);
			for (int i = 0; i < SIZE; i++) {
				double di = i - SIZE / 2.0 + 0.5;
				int wx = Mth.floor(backX + di * backScale);
				img.setPixel(i, j, color(lvl, wx, wz, northZ, playerY));
			}
		}
		if (row >= SIZE) { // picture done: show it, start the next one in the other texture
			textures[back].upload();
			front = back;
			picture++;
			back = 1 - back;
			frontX = backX;
			frontZ = backZ;
			frontScale = backScale;
			row = -1;
		}
	}

	/** One texel: the top block's map colour, shaded by the height to the north like vanilla maps. */
	private int color(ClientLevel lvl, int wx, int wz, int northZ, int playerY) {
		if (!lvl.hasChunk(wx >> 4, wz >> 4)) return 0;
		int h = top(lvl, wx, wz, playerY);
		if (h == Integer.MIN_VALUE) return 0;
		BlockState state = lvl.getBlockState(pos.set(wx, h, wz));
		MapColor mapColor = state.getMapColor(lvl, pos);
		if (mapColor == MapColor.NONE) return 0;
		int north = lvl.hasChunk(wx >> 4, northZ >> 4) ? top(lvl, wx, northZ, playerY) : Integer.MIN_VALUE;
		MapColor.Brightness brightness = north == Integer.MIN_VALUE || north == h ? MapColor.Brightness.NORMAL
				: h > north ? MapColor.Brightness.HIGH : MapColor.Brightness.LOW;
		int argb = mapColor.calculateARGBColor(brightness);
		return (backAlpha << 24) | (argb & 0xFFFFFF);
	}

	/** Height of the top block: the surface heightmap, or (Nether / cave view) the first floor below you. */
	private int top(ClientLevel lvl, int wx, int wz, int playerY) {
		if (!backCave) return lvl.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
		for (int y = playerY + 2; y > playerY - 32; y--) {
			if (!lvl.getBlockState(pos.set(wx, y, wz)).isAir() && lvl.getBlockState(pos.set(wx, y + 1, wz)).isAir()) return y;
		}
		return Integer.MIN_VALUE;
	}

	/**
	 * The picture on show with everything outside the round radar made clear. The circle is
	 * centred on you and the picture only moves as you move (turning spins both the same),
	 * so it's cut again only once you've moved half a texel. It's cut slightly inside the
	 * edge so no texel pokes out past the border.
	 */
	private int cut(double px, double pz, double radiusTexels) {
		double fx = (px - frontX) / frontScale + SIZE / 2.0 - 0.5;
		double fz = (pz - frontZ) / frontScale + SIZE / 2.0 - 0.5;
		long key = ((long) Math.round(fx * 2) & 0xFFFF)
				| ((long) Math.round(fz * 2) & 0xFFFF) << 16
				| ((long) Math.round(radiusTexels * 4) & 0xFFFF) << 32;
		if (key == cutKey && cutPicture == picture) return 2;
		NativeImage src = textures[front].getPixels();
		NativeImage dst = textures[2].getPixels();
		if (src == null || dst == null) return front;
		double r = Math.max(0, radiusTexels - 0.6);
		double r2 = r * r;
		for (int j = 0; j < SIZE; j++) {
			double dz = j - fz;
			for (int i = 0; i < SIZE; i++) {
				double dx = i - fx;
				dst.setPixel(i, j, dx * dx + dz * dz <= r2 ? src.getPixel(i, j) : 0);
			}
		}
		textures[2].upload();
		cutKey = key;
		cutPicture = picture;
		return 2;
	}

	/**
	 * Draws the picture under the radar markers.
	 *
	 * @param sx, sy     radar centre on screen
	 * @param pxPerBlock radar scale
	 * @param turn       the radar's rotation in radians
	 * @param px, pz     where you are this frame
	 * @param roundPx    radius of the round radar in pixels, or 0 for a square one
	 */
	void draw(GuiGraphicsExtractor g, float sx, float sy, double pxPerBlock, float turn, double px, double pz, float roundPx) {
		if (front < 0) return;
		int show = roundPx > 0 ? cut(px, pz, roundPx / (frontScale * pxPerBlock)) : front;
		g.pose().pushMatrix();
		g.pose().translate(sx, sy);
		g.pose().rotate(turn);
		g.pose().translate((float) ((frontX - px) * pxPerBlock), (float) ((frontZ - pz) * pxPerBlock));
		float s = (float) (frontScale * pxPerBlock);
		g.pose().scale(s, s);
		g.blit(ids[show], -SIZE / 2, -SIZE / 2, SIZE / 2, SIZE / 2, 0f, 1f, 0f, 1f);
		g.pose().popMatrix();
	}
}
