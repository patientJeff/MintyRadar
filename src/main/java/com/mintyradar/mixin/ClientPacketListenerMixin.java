package com.mintyradar.mixin;

import com.mintyradar.PingTracker;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Times pong replies as soon as they arrive, on the network thread. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "handlePongResponse", at = @At("HEAD"))
	private void mintyRadar$onPong(ClientboundPongResponsePacket packet, CallbackInfo ci) {
		PingTracker.onPong(packet.time());
	}
}
