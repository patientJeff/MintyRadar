package com.mintyradar;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Warns when a player comes within the alert distance: an action-bar message such as
 * "Steve is 40m away", plus an optional sound. Friends never trigger it.
 *
 * <p>Each player alerts once, then re-arms after moving {@link #REARM_MARGIN} blocks
 * beyond the alert distance. That margin stops someone standing right on the edge
 * from triggering it again and again.
 */
public final class PlayerAlerts {
	private static final int REARM_MARGIN = 8;

	/** Players currently inside the alert zone (already alerted). */
	private final Set<UUID> inside = new HashSet<>();
	/** Scratch set of players seen this tick, reused to avoid allocating every tick. */
	private final Set<UUID> seen = new HashSet<>();
	private ClientLevel lastLevel;

	/** Call once per client tick, after {@link RadarManager#tick}. */
	public void tick(Minecraft client, RadarConfig config, RadarManager manager) {
		ClientLevel level = client.level;
		if (!config.enabled || client.player == null || level == null) {
			inside.clear();
			lastLevel = null;
			return;
		}

		// On joining a world or changing dimension, players who are already nearby are
		// recorded silently rather than all alerting at once.
		boolean silent = level != lastLevel;
		lastLevel = level;

		double alertSq = (double) config.alertDistance * config.alertDistance;
		double rearm = config.alertDistance + REARM_MARGIN;
		double rearmSq = rearm * rearm;

		List<RadarManager.Tracked> players = manager.getTracked();
		int count = manager.getTrackedCount();
		// If several players arrive in the same tick, the nearest one is announced. The
		// list is sorted nearest-first, so that's the first new arrival found.
		RadarManager.Tracked newest = null;
		seen.clear();

		for (int i = 0; i < count; i++) {
			RadarManager.Tracked t = players.get(i);
			if (t.friend) continue;
			seen.add(t.uuid);

			if (t.distSq <= alertSq) {
				if (inside.add(t.uuid) && newest == null) newest = t;
			} else if (t.distSq > rearmSq) {
				inside.remove(t.uuid);
			}
		}
		// Players who left tracking range entirely are re-armed too.
		inside.retainAll(seen);

		if (newest != null && !silent && config.alertEnabled) {
			fire(client, config, newest);
		}
	}

	private static void fire(Minecraft client, RadarConfig config, RadarManager.Tracked t) {
		client.player.sendOverlayMessage(Component.translatable("message.minty_radar.alert", t.name, t.meters));
		if (config.alertSound) {
			client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.5f));
		}
	}
}
