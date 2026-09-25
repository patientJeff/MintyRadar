package com.mintyradar;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Collects the players (and optionally mobs) the radar should display and converts
 * their world positions into radar-space offsets.
 *
 * <p>Work is split into two phases:
 * <ul>
 *   <li>{@link #tick} (20 Hz): filters players and mobs, looks up skins and names,
 *       and sorts players by distance.</li>
 *   <li>{@link #computeBlips} / {@link #computeMobBlips} (every frame): interpolate
 *       positions and rotate them into the local player's view. Only arithmetic is
 *       done here.</li>
 * </ul>
 * Both phases write into pooled objects that are reused every tick or frame, so
 * steady-state rendering allocates nothing. Entity references are dropped each
 * tick, and immediately when the world unloads, so no entity is ever held after
 * it despawns.
 */
public final class RadarManager {
	/** Upper bounds on tracked entities, keeping the per-frame cost bounded on busy servers. */
	private static final int MAX_TRACKED = 256;
	private static final int MAX_MOBS = 256;
	/**
	 * Mobs are collected out to this multiple of the radar range. A square radar's corners
	 * reach sqrt(2) × range, so this covers them. Anything further is off the map anyway.
	 */
	private static final double MOB_RANGE_FACTOR = 1.45;

	/** One radar entry in pixel offsets from the radar center (+x right, +y down). */
	public static final class Blip {
		public float x;
		public float y;
		/** Height difference in blocks (positive means the other player is above you). */
		public double dy;
		/** True when the player is beyond radar range and pinned to the edge. */
		public boolean clamped;
		public boolean friend;
		public String name;
		/** Skin used to draw the player's head. */
		public PlayerSkin skin;
	}

	/** A mob's position on the radar. Mobs outside the radar are skipped rather than pinned. */
	public static final class MobBlip {
		public float x;
		public float y;
		public boolean hostile;
		/** The mob's face to draw, or null to draw a dot. */
		public MobHeads.Head head;
	}

	/** Tick-time snapshot of a single player. It also serves as a row in the player list. */
	public static final class Tracked {
		AbstractClientPlayer player;
		public UUID uuid;
		public double distSq;
		public PlayerSkin skin;
		public String name;
		public boolean friend;
		/** e.g. "23m". Built at tick rate so the HUD never formats strings per frame. */
		public String distanceText;
		public int meters;
	}

	private static final class TrackedMob {
		Entity entity;
		boolean hostile;
		MobHeads.Head head;
	}

	private static final Comparator<Tracked> BY_DISTANCE = Comparator.comparingDouble(t -> t.distSq);

	private final List<Tracked> tracked = new ArrayList<>();
	private int trackedCount;

	private final List<TrackedMob> mobs = new ArrayList<>();
	private int mobCount;

	private final List<Blip> blips = new ArrayList<>();
	private final List<MobBlip> mobBlips = new ArrayList<>();

	/** View rotation from the latest {@link #computeBlips} call, reused for the compass. */
	private double viewSin;
	private double viewCos = 1;

	/** Rebuilds the tracked player and mob lists. Call once per client tick. */
	public void tick(Minecraft client, RadarConfig config) {
		LocalPlayer self = client.player;
		ClientLevel level = client.level;
		if (!config.enabled || self == null || level == null) {
			clear();
			return;
		}

		tickPlayers(self, level, config);

		// Mobs only ever appear on the map, so skip the entity scan when it can't show them.
		if (config.showMap && config.mobMode != RadarConfig.MobMode.OFF) {
			tickMobs(self, level, config);
		} else {
			clearMobs();
		}
	}

	private void tickPlayers(LocalPlayer self, ClientLevel level, RadarConfig config) {
		boolean hideFriends = config.friendMode == RadarConfig.FriendMode.HIDE;
		int count = 0;

		for (AbstractClientPlayer other : level.players()) {
			if (count >= MAX_TRACKED) break;
			if (other == self || other.isRemoved() || !other.isAlive()) continue;
			// Spectators and players we can't legitimately see are excluded. isInvisibleTo()
			// still returns false for teammates when the team allows seeing invisible allies.
			if (other.isSpectator() || other.isInvisibleTo(self)) continue;

			String name = other.getScoreboardName();
			boolean friend = config.isFriend(name);
			if (friend && hideFriends) continue;

			Tracked entry = obtain(tracked, count, Tracked::new);
			entry.player = other;
			entry.uuid = other.getUUID();
			entry.skin = other.getSkin();
			entry.name = name;
			entry.friend = friend;
			entry.distSq = other.distanceToSqr(self);
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

	private void tickMobs(LocalPlayer self, ClientLevel level, RadarConfig config) {
		boolean hostileOnly = config.mobMode == RadarConfig.MobMode.HOSTILE;
		double maxDist = config.range() * MOB_RANGE_FACTOR;
		double maxDistSq = maxDist * maxDist;
		int count = 0;

		for (Entity entity : level.entitiesForRendering()) {
			if (count >= MAX_MOBS) break;
			if (!(entity instanceof Mob mob) || mob.isRemoved() || !mob.isAlive()) continue;
			boolean hostile = mob instanceof Enemy;
			if (hostileOnly && !hostile) continue;
			if (mob.isInvisibleTo(self) || mob.distanceToSqr(self) > maxDistSq) continue;

			TrackedMob entry = obtain(mobs, count, TrackedMob::new);
			entry.entity = mob;
			entry.hostile = hostile;
			entry.head = MobHeads.lookup(mob); // at tick rate, so variant changes show up
			count++;
		}

		for (int i = count; i < mobCount; i++) {
			mobs.get(i).entity = null;
			mobs.get(i).head = null;
		}
		mobCount = count;
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
		viewSin = Math.sin(yawRad);
		viewCos = Math.cos(yawRad);
		double pxPerBlock = radiusPx / config.range();
		boolean circle = config.shape == RadarConfig.Shape.CIRCLE;

		int n = 0;
		for (int i = 0; i < trackedCount; i++) {
			Tracked t = tracked.get(i);
			AbstractClientPlayer p = t.player;
			if (p.isRemoved()) continue; // may have despawned since the last tick

			double dx = Mth.lerp(partialTick, p.xo, p.getX()) - selfX;
			double dz = Mth.lerp(partialTick, p.zo, p.getZ()) - selfZ;
			float sx = (float) (screenX(dx, dz) * pxPerBlock);
			float sy = (float) (screenY(dx, dz) * pxPerBlock);

			// Pin out-of-range players to the border so their direction stays visible.
			float extent = circle
					? (float) Math.sqrt(sx * sx + sy * sy)
					: Math.max(Math.abs(sx), Math.abs(sy));
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
			b.friend = t.friend;
			b.name = t.name;
			b.skin = t.skin;
		}
		return n;
	}

	/**
	 * Projects tracked mobs for this frame. Must be called after {@link #computeBlips},
	 * which sets the view rotation. Mobs outside the radar are skipped.
	 *
	 * @return the number of valid entries in {@link #getMobBlips()}
	 */
	public int computeMobBlips(LocalPlayer self, float partialTick, RadarConfig config, float radiusPx) {
		double selfX = Mth.lerp(partialTick, self.xo, self.getX());
		double selfZ = Mth.lerp(partialTick, self.zo, self.getZ());
		double pxPerBlock = radiusPx / config.range();
		boolean circle = config.shape == RadarConfig.Shape.CIRCLE;
		float radiusSq = radiusPx * radiusPx;

		int n = 0;
		for (int i = 0; i < mobCount; i++) {
			TrackedMob m = mobs.get(i);
			Entity e = m.entity;
			if (e.isRemoved()) continue;

			double dx = Mth.lerp(partialTick, e.xo, e.getX()) - selfX;
			double dz = Mth.lerp(partialTick, e.zo, e.getZ()) - selfZ;
			float sx = (float) (screenX(dx, dz) * pxPerBlock);
			float sy = (float) (screenY(dx, dz) * pxPerBlock);

			boolean outside = circle
					? sx * sx + sy * sy > radiusSq
					: Math.abs(sx) > radiusPx || Math.abs(sy) > radiusPx;
			if (outside) continue;

			MobBlip b = obtain(mobBlips, n++, MobBlip::new);
			b.x = sx;
			b.y = sy;
			b.hostile = m.hostile;
			b.head = m.head;
		}
		return n;
	}

	/** Rightward component of a world offset in the current view (blocks). */
	public double screenX(double dx, double dz) {
		return -dx * viewCos - dz * viewSin;
	}

	/** Downward component of a world offset in the current view (blocks). */
	public double screenY(double dx, double dz) {
		return -(-dx * viewSin + dz * viewCos);
	}

	public List<Blip> getBlips() {
		return blips;
	}

	public List<MobBlip> getMobBlips() {
		return mobBlips;
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
		clearMobs();
	}

	private void clearMobs() {
		for (int i = 0; i < mobCount; i++) {
			mobs.get(i).entity = null;
			mobs.get(i).head = null;
		}
		mobCount = 0;
	}

	/** Returns the pooled element at {@code index}, growing the pool if needed. */
	private static <T> T obtain(List<T> pool, int index, Supplier<T> factory) {
		if (index < pool.size()) return pool.get(index);
		T created = factory.get();
		pool.add(created);
		return created;
	}
}
