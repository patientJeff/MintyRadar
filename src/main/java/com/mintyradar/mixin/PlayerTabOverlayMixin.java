package com.mintyradar.mixin;

import com.mintyradar.PingTracker;
import com.mintyradar.RadarClientMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the tab list's signal-bar ping icons with the ping in milliseconds, e.g.
 * "42ms", coloured from green (good) to red (bad).
 *
 * <p>Other players' pings are the server's latency values, which are all a client can
 * know. Your own row uses {@link PingTracker}'s live measurement when it has one.
 */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {
	/** Widest text drawn, used to make room in each tab column. */
	@Unique
	private static final String WIDEST = "9999ms";

	/**
	 * Vanilla reserves 13px per column for the 10px ping icon (the only 13 in this
	 * method). Widen it so the number fits.
	 */
	@ModifyConstant(method = "extractRenderState", constant = @Constant(intValue = 13))
	private int mintyRadar$widenPingColumn(int original) {
		if (!RadarClientMod.config().tabPing) return original;
		return original + Minecraft.getInstance().font.width(WIDEST) - 10;
	}

	@Inject(method = "extractPingIcon", at = @At("HEAD"), cancellable = true)
	private void mintyRadar$drawPingText(GuiGraphicsExtractor g, int width, int x, int y, PlayerInfo info, CallbackInfo ci) {
		if (!RadarClientMod.config().tabPing) return;
		ci.cancel();

		Minecraft mc = Minecraft.getInstance();
		int ping = info.getLatency();
		if (mc.player != null && info.getProfile().id().equals(mc.player.getUUID())) {
			int live = PingTracker.ownPingMs();
			if (live >= 0) ping = live;
		}

		String text = ping < 0 ? "?" : ping + "ms";
		Font font = mc.font;
		// Right-aligned where the icon's right edge was.
		g.text(font, text, x + width - 1 - font.width(text), y, mintyRadar$color(ping), true);
	}

	@Unique
	private static int mintyRadar$color(int ping) {
		if (ping < 0) return 0xFFAAAAAA;
		if (ping < 80) return 0xFF55FF55;  // green
		if (ping < 150) return 0xFFB5E655; // yellow-green
		if (ping < 250) return 0xFFFFD84D; // yellow
		if (ping < 400) return 0xFFFFA23D; // orange
		return 0xFFFF5555;                 // red
	}
}
