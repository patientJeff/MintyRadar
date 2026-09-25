package com.mintyradar;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * Collects the players the radar should display and converts their world positions
 * into radar-space offsets.
 *
 * <p>Work is split into two phases:
 * <ul>
 *   <li>{@link #tick} (20 Hz): filters players, looks up their skin and name,
 *       and sorts them by distance.</li>
 *   <li>{@link #computeBlips} (every frame): interpolates positions and rotates them
 *       into the local player's view. Only arithmetic is done here.</li>
 * </ul>
 * Both phases write into pooled objects that are reused every tick or frame, so
 * steady-state rendering allocates nothing. Player references are dropped each
 * tick, and immediately when the world unloads, so no entity is ever held after
 * it despawns.
 */
public final class RadarManager {
	/** Upper bound on tracked players, keeping the per-frame cost bounded on huge servers. */
	private static final int MAX_TRACKED = 256;

	/** One radar entry in pixel offsets from the radar center (+x right, +y down). */
	public static final class Blip {
		public float x;
		public float y;
		/** Height difference in blocks (positive means the other player is above you). */
		public double dy;
		/** True when the player is beyond radar range and pinned to the edge. */
		public boolean clamped;
		public String name;
		/** Skin used to draw the player's head. */
		public PlayerSkin skin;
	}

	/** Tick-time snapshot of a single player. It also serves as a row in the player list. */
	public static final class Tracked {
		AbstractClientPlayer player;
		double distSq;
		public PlayerSkin skin;
		public String name;
		/** e.g. "23m". Built at tick rate so the HUD never formats strings per frame. */
		public String distanceText;
		int meters;
	}

	private static final Comparator<Tracked> BY_DISTANCE = Comparator.comparingDouble(t -> t.distSq);

	private final List<Tracked> tracked = new ArrayList<>();
	private int trackedCount;

	private final List<Blip> blips = new ArrayList<>();

	/** Rebuilds the tracked player list. Call once per client tick. */
	public void tick(Minecraft client, RadarConfig config) {
		LocalPlayer self = client.player;
		ClientLevel level = client.level;
		if (!config.enabled || self == null || level == null) {
			clear();
			return;
		}

		int count = 0;

		for (AbstractClientPlayer other : level.players()) {
			if (count >= MAX_TRACKED) break;
			if (other == self || other.isRemoved() || !other.isAlive()) continue;
			// Spectators and players we can't legitimately see are excluded. isInvisibleTo()
			// still returns false for teammates when the team allows seeing invisible allies.
			if (other.isSpectator() || other.isInvisibleTo(self)) continue;

			double distSq = other.distanceToSqr(self);

			Tracked entry = obtain(tracked, count, Tracked::new);
			entry.player = other;
			entry.skin = other.getSkin();
			entry.name = other.getScoreboardName();
			entry.distSq = distSq;
			count++;
		}

		// Null out references beyond the live count so despawned players can be GC'd.
		for (int i = count; i < trackedCount; i++) tracked.get(i).player = null;
		trackedCount = count;

		// Nearest first, for the player list. The distance text is only rebuilt when it
		// changes, so a player standing still costs no allocations.
		tracked.subList(0, count).sort(BY_DISTANCE);
		for (int i = 0; i < count; i++) {
			Tracked t = tracked.get(i);
			int meters = Mth.floor(Math.sqrt(t.distSq));
			if (t.distanceText == null || meters != t.meters) {
				t.meters = meters;
				t.distanceText = meters + "m";
			}
		}
	}

	/**
	 * Projects every tracked player into radar space for this frame.
	 *
	 * @param partialTick frame interpolation factor so blips move smoothly between ticks
	 * @param radiusPx    radar radius in GUI pixels (center to edge)
	 * @return the number of valid entries in {@link #getBlips()}
	 */
	public int computeBlips(LocalPlayer self, float partialTick, RadarConfig config, float radiusPx) {
		double selfX = Mth.lerp(partialTick, self.xo, self.getX());
		double selfY = Mth.lerp(partialTick, self.yo, self.getY());
		double selfZ = Mth.lerp(partialTick, self.zo, self.getZ());

		// Minecraft yaw: 0 = facing +Z (south), 90 = facing -X (west).
		//   forward = (-sin(yaw), cos(yaw)),  right = (-cos(yaw), -sin(yaw))
		// Projecting the offset onto these axes puts "straight ahead" at the top of the radar.
		double yawRad = Math.toRadians(self.getViewYRot(partialTick));
		double sin = Math.sin(yawRad);
		double cos = Math.cos(yawRad);
		double pxPerBlock = radiusPx / config.range();

		int n = 0;
		for (int i = 0; i < trackedCount; i++) {
			Tracked t = tracked.get(i);
			AbstractClientPlayer p = t.player;
			if (p.isRemoved()) continue; // may have despawned since the last tick

			double dx = Mth.lerp(partialTick, p.xo, p.getX()) - selfX;
			double dz = Mth.lerp(partialTick, p.zo, p.getZ()) - selfZ;

			double right = -dx * cos - dz * sin;
			double forward = -dx * sin + dz * cos;

			float sx = (float) (right * pxPerBlock);
			float sy = (float) (-forward * pxPerBlock);

			// Pin out-of-range players to the square border so their direction stays visible.
			float extent = Math.max(Math.abs(sx), Math.abs(sy));
			boolean clamped = extent > radiusPx;
			if (clamped) {
				float k = radiusPx / extent;
				sx *= k;
				sy *= k;
			}

			Blip b = obtain(blips, n++, Blip::new);
			b.x = sx;
			b.y = sy;
			b.dy = Mth.lerp(partialTick, p.yo, p.getY()) - selfY;
			b.clamped = clamped;
			b.name = t.name;
			b.skin = t.skin;
		}
		return n;
	}

	public List<Blip> getBlips() {
		return blips;
	}

	/** Players for the list under the radar, nearest first. Valid up to {@link #getTrackedCount()}. */
	public List<Tracked> getTracked() {
		return tracked;
	}

	public int getTrackedCount() {
		return trackedCount;
	}

	private void clear() {
		for (int i = 0; i < trackedCount; i++) tracked.get(i).player = null;
		trackedCount = 0;
	}

	/** Returns the pooled element at {@code index}, growing the pool if needed. */
	private static <T> T obtain(List<T> pool, int index, Supplier<T> factory) {
		if (index < pool.size()) return pool.get(index);
		T created = factory.get();
		pool.add(created);
		return created;
	}
}
