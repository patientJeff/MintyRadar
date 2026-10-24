package com.mintyradar.mixin;

import com.mintyradar.Keybindings;
import com.mintyradar.RadarClientMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Scroll zoom: while the scroll zoom key is held, the mouse wheel zooms the radar instead of changing hotbar slot. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void mintyRadar$onScroll(long handle, double xoffset, double yoffset, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || RadarClientMod.currentScreen() != null || handle != mc.getWindow().handle()) return;
		// Some mice and drivers turn the wheel sideways while a modifier key is held, so count that too.
		double amount = yoffset != 0 ? yoffset : xoffset;
		if (Keybindings.onScroll(mc, RadarClientMod.config(), amount)) ci.cancel();
	}
}
