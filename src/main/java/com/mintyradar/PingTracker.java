package com.mintyradar;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
import net.minecraft.util.Util;

/**
 * Measures your own ping live, the same way the F3 network chart does: send the
 * server a ping request stamped with the current time, and when the pong comes back
 * with that stamp, the difference is the true round trip.
 *
 * <p>This is sharper than the latency the server reports in the tab list. The server
 * value is averaged over keep-alives and only sent out every so often, and it's the
 * only value available for other players.
 *
 * <p>The pong is handled on the network thread (see
 * {@link com.mintyradar.mixin.ClientPacketListenerMixin}), which avoids adding up to a
 * frame of delay to the measurement. That's why the result is volatile.
 */
public final class PingTracker {
	/** Ticks between ping requests (1 second). */
	private static final int INTERVAL_TICKS = 20;

	private static volatile int ownPingMs = -1;
	private static int ticksUntilPing;

	private PingTracker() {
	}

	/** Call once per client tick. */
	public static void tick(Minecraft client, RadarConfig config) {
		ClientPacketListener connection = client.getConnection();
		if (!config.tabPing || connection == null || client.player == null) {
			ownPingMs = -1;
			ticksUntilPing = 0;
			return;
		}
		if (--ticksUntilPing <= 0) {
			ticksUntilPing = INTERVAL_TICKS;
			connection.send(new ServerboundPingRequestPacket(Util.getMillis()));
		}
	}

	/**
	 * Called with the timestamp echoed back in a pong. Pongs for F3's own pings arrive
	 * here too, and they're measured the same way, so they count as well.
	 */
	public static void onPong(long sentMillis) {
		long rtt = Util.getMillis() - sentMillis;
		if (rtt >= 0 && rtt < 60_000) ownPingMs = (int) rtt;
	}

	/** Your latest measured ping in ms, or -1 if none yet. */
	public static int ownPingMs() {
		return ownPingMs;
	}
}
