package com.mintyradar;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Radar shapes that don't move (the round background and its border, the distance rings),
 * painted once into a texture and drawn with a single blit. Drawn as one fill per pixel
 * row they cost hundreds of GUI draws every frame. The picture is only painted again
 * when what it shows changes (size, shape, colours), which the caller packs into a key.
 *
 * <p>Use: {@code Canvas c = decor.begin(size, key); if (c != null) { paint...; decor.finish(); }
 * decor.blit(g, x, y);}. Nothing is allocated while the key stays the same.
 */
final class RadarDecor {
	/** Paints into the picture with rectangles, like {@code fill} (later ones cover earlier ones). */
	static final class Canvas {
		private NativeImage img;
		private int size;

		void fill(int x0, int y0, int x1, int y1, int argb) {
			for (int y = Math.max(0, y0); y < Math.min(size, y1); y++) {
				for (int x = Math.max(0, x0); x < Math.min(size, x1); x++) img.setPixel(x, y, argb);
			}
		}

		/** Filled circle of radius {@code r} centred on pixel (cx, cy), one span per row. */
		void disc(int cx, int cy, int r, int color) {
			for (int dy = -r; dy <= r; dy++) {
				int half = (int) Math.sqrt((double) r * r - (double) dy * dy);
				fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
			}
		}

		/** 1px circle outline of radius {@code r}, as up to two short spans per row. */
		void ring(int cx, int cy, int r, int color) {
			if (r <= 0) return;
			for (int dy = -r; dy <= r; dy++) {
				int y = cy + dy;
				int outerHalf = (int) Math.sqrt((double) r * r - (double) dy * dy);
				if (Math.abs(dy) >= r - 1) {
					// Top and bottom rows: one solid span.
					fill(cx - outerHalf, y, cx + outerHalf + 1, y + 1, color);
					continue;
				}
				int innerHalf = (int) Math.sqrt((double) (r - 1) * (r - 1) - (double) dy * dy);
				innerHalf = Math.min(innerHalf, outerHalf - 1); // keep each side at least 1px wide
				fill(cx - outerHalf, y, cx - innerHalf, y + 1, color);
				fill(cx + innerHalf + 1, y, cx + outerHalf + 1, y + 1, color);
			}
		}
	}

	private final String name;
	private final Canvas canvas = new Canvas();
	private DynamicTexture texture;
	private Identifier id;
	private int textureSize;
	private long paintedKey;
	private boolean painted;

	RadarDecor(String name) {
		this.name = name;
	}

	/**
	 * Returns a cleared canvas to paint on if the picture must be painted again (new size or
	 * key), otherwise null. Call {@link #finish()} after painting.
	 */
	Canvas begin(int size, long key) {
		if (size <= 0) return null;
		if (texture == null || textureSize != size) {
			if (texture != null) Minecraft.getInstance().getTextureManager().release(id);
			textureSize = size;
			texture = new DynamicTexture(() -> "MintyRadar " + name, size, size, true);
			id = RadarClientMod.id("decor_" + name);
			Minecraft.getInstance().getTextureManager().register(id, texture);
			painted = false;
		}
		if (painted && key == paintedKey) return null;
		NativeImage img = texture.getPixels();
		if (img == null) return null;
		img.fillRect(0, 0, size, size, 0);
		canvas.img = img;
		canvas.size = size;
		paintedKey = key;
		return canvas;
	}

	/** Uploads what was painted since {@link #begin}. */
	void finish() {
		texture.upload();
		canvas.img = null;
		painted = true;
	}

	/** Draws the picture with its top-left corner at (x, y). */
	void blit(GuiGraphicsExtractor g, int x, int y) {
		if (texture == null || !painted) return;
		g.blit(id, x, y, x + textureSize, y + textureSize, 0f, 1f, 0f, 1f);
	}
}
