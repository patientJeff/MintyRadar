package com.mintyradar;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Collects the players (and optionally mobs, dropped items and portals) the radar should
 * display and converts their world positions into radar-space offsets.
 *
 * <p>Work is split into two phases:
 * <ul>
 *   <li>{@link #tick} (20 Hz): filters players and mobs, looks up skins, names, teams and
 *       health, sorts players by distance, records trails, and draws a few rows of the
 *       terrain map. Portals are rescanned every few seconds.</li>
 *   <li>{@link #computeBlips} / {@link #computeMobBlips} (every frame): interpolate
 *       positions and rotate them into the radar's view. Only arithmetic is done here.</li>
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
	private static final int MAX_ITEMS = 128;
	/** Most portal columns kept. Scanning stops once this many are found. */
	private static final int MAX_PORTALS = 400;
	/** Ticks between portal scans (5 seconds). */
	private static final int PORTAL_SCAN_TICKS = 100;
	/** Ticks between trail points (half a second). */
	private static final int TRAIL_TICKS = 10;
	/**
	 * Mobs and portals are collected out to this multiple of the radar range. A square radar's
	 * corners reach sqrt(2) × range, so this covers them. Anything further is off the map anyway.
	 */
	private static final double RANGE_FACTOR = 1.45;

	private static final Predicate<BlockState> IS_PORTAL = state -> state.is(Blocks.NETHER_PORTAL)
			|| state.is(Blocks.END_PORTAL) || state.is(Blocks.END_GATEWAY);

	/** One radar entry in pixel offsets from the radar center (+x right, +y down). */
	public static final class Blip {
		public float x;
		public float y;
		/** Unit vector of the way the player is looking, in radar space. */
		public float lookX;
		public float lookY;
		/** Height difference in blocks (positive means the other player is above you). */
		public double dy;
		/** True when the player is beyond radar range and pinned to the edge. */
		public boolean clamped;
		public boolean friend;
		public String name;
		/** Skin used to draw the player's head. */
		public PlayerSkin skin;
		/** Scoreboard team colour (ARGB), or -1 for none. */
		public int team;
	}

	/** A mob or item's position on the radar. These are skipped outside the radar rather than pinned. */
	public static final class MobBlip {
		public float x;
		public float y;
		public boolean hostile;
		/** A dropped item rather than a mob. */
		public boolean item;
		/** The mob's face to draw, or null to draw a dot. */
		public MobHeads.Head head;
	}

	/** Recent positions of one player, as a ring buffer of x/z pairs. Oldest first. */
	public static final class Trail {
		private double[] xs = new double[0];
		private double[] zs = new double[0];
		private int start;
		private int size;
		/** The trail tick this player was last seen on, so trails of players who left are dropped. */
		private int seen;

		public int size() {
			return size;
		}

		/** The {@code i}-th point's x, counting from the oldest. */
		public double x(int i) {
			return xs[(start + i) % xs.length];
		}

		public double z(int i) {
			return zs[(start + i) % zs.length];
		}

		private void add(double x, double z, int capacity) {
			if (xs.length != capacity) {
				xs = new double[capacity];
				zs = new double[capacity];
				start = 0;
				size = 0;
			}
			if (size > 0) {
				double dx = x - x(size - 1);
				double dz = z - z(size - 1);
				if (dx * dx + dz * dz < 0.25) return; // hasn't moved half a block
			}
			int at = (start + size) % capacity;
			xs[at] = x;
			zs[at] = z;
			if (size < capacity) size++;
			else start = (start + 1) % capacity;
		}
	}

	/** Tick-time snapshot of a single player. It also serves as a row in the player list. */
	public static final class Tracked {
		AbstractClientPlayer player;
		public UUID uuid;
		public double distSq;
		public PlayerSkin skin;
		public String name;
		/** The name as the list shows it: marked when the player is invisible. */
		public String shownName;
		public boolean invisible;
		public boolean friend;
		/** Scoreboard team colour (ARGB), or -1 for none. */
		public int team;
		/** e.g. "23m". Built at tick rate so the HUD never formats strings per frame. */
		public String distanceText;
		public int meters;
		/** Health plus absorption, rounded up, e.g. "20♥". */
		public String healthText;
		public int health = -1;
		/** Where the player has been, or null when trails are off. */
		public Trail trail;
	}

	private static final class TrackedMob {
		Entity entity;
		boolean hostile;
		boolean item;
		MobHeads.Head head;
	}

	private static final Comparator<Tracked> BY_DISTANCE = Comparator.comparingDouble(t -> t.distSq);

	private final List<Tracked> tracked = new ArrayList<>();
	private int trackedCount;

	private final List<TrackedMob> mobs = new ArrayList<>();
	private int mobCount;

	private final List<Blip> blips = new ArrayList<>();
	private final List<MobBlip> mobBlips = new ArrayList<>();

	private final Map<UUID, Trail> trails = new HashMap<>();
	private int trailTick;
	private int ticksUntilTrail;

	/** Portal columns (x/z packed with {@link BlockPos#asLong}, y = 0) from the latest scan. */
	private final LongOpenHashSet portalScan = new LongOpenHashSet();
	private static final long[] NO_PORTALS = new long[0];
	private long[] portals = NO_PORTALS;
	private int ticksUntilPortalScan;

	private final RadarTerrain terrain = new RadarTerrain();
	private ClientLevel lastLevel;

	/** View rotation from the latest {@link #computeBlips} call, reused for the compass and terrain. */
	private double viewYawRad;
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
		if (level != lastLevel) {
			// New world or dimension: trails and portals from the old one mean nothing here.
			lastLevel = level;
			trails.clear();
			portals = NO_PORTALS;
			ticksUntilPortalScan = 0;
		}

		tickPlayers(self, level, config);

		// Mobs and items only ever appear on the map, so skip the entity scan when it can't show them.
		if (config.showMap && (config.mobMode != RadarConfig.MobMode.OFF || config.showItems)) {
			tickMobs(self, level, config);
		} else {
			clearMobs();
		}

		if (config.showTrails) {
			if (--ticksUntilTrail <= 0) {
				ticksUntilTrail = TRAIL_TICKS;
				tickTrails(config);
			}
		} else if (!trails.isEmpty()) {
			trails.clear();
			for (int i = 0; i < trackedCount; i++) tracked.get(i).trail = null;
		}

		if (config.showMap && config.showPortals) {
			if (--ticksUntilPortalScan <= 0) {
				ticksUntilPortalScan = PORTAL_SCAN_TICKS;
				scanPortals(self, level, config);
			}
		} else {
			portals = NO_PORTALS;
			ticksUntilPortalScan = 0;
		}

		if (config.showMap && config.terrain) {
			boolean cave = config.caveView || level.dimensionType().hasCeiling();
			// Cave mode looks down through many blocks per texel, so it draws fewer rows per tick.
			terrain.tick(config.range(), Math.round(config.terrainOpacity * 2.55f), config.caveView, cave ? 4 : 16);
		} else {
			terrain.reset();
		}
	}

	private void tickPlayers(LocalPlayer self, ClientLevel level, RadarConfig config) {
		boolean hideFriends = config.friendMode == RadarConfig.FriendMode.HIDE;
		int count = 0;

		for (AbstractClientPlayer other : level.players()) {
			if (count >= MAX_TRACKED) break;
			if (other == self || other.isRemoved() || !other.isAlive() || other.isSpectator()) continue;
			// Players we can't see are left out unless Invisible Players is on. isInvisibleTo()
			// still returns false for teammates when the team allows seeing invisible allies.
			if (!config.showInvisible && other.isInvisibleTo(self)) continue;

			String name = other.getScoreboardName();
			boolean friend = config.isFriend(name);
			if (friend && hideFriends) continue;

			Tracked entry = obtain(tracked, count, Tracked::new);
			boolean invisible = other.isInvisible();
			// Pooled entries are reused for other players after sorting, so compare the name too.
			if (entry.shownName == null || invisible != entry.invisible || !name.equals(entry.name)) {
				entry.shownName = invisible
						? Component.translatable("hud.minty_radar.invisible", name).getString()
						: name;
			}
			entry.player = other;
			entry.uuid = other.getUUID();
			entry.skin = other.getSkin();
			entry.name = name;
			entry.invisible = invisible;
			entry.friend = friend;
			entry.team = config.teamColors && other.getTeam() != null && other.getTeam().getColor().isPresent()
					? 0xFF000000 | other.getTeamColor()
					: -1;
			entry.distSq = other.distanceToSqr(self);
			count++;
		}

		// Null out references beyond the live count so despawned players can be GC'd.
		for (int i = count; i < trackedCount; i++) {
			tracked.get(i).player = null;
			tracked.get(i).trail = null;
		}
		trackedCount = count;

		// Nearest first, for the player list. The distance and health text is only rebuilt
		// when it changes, so a player standing still costs no allocations.
		tracked.subList(0, count).sort(BY_DISTANCE);
		for (int i = 0; i < count; i++) {
			Tracked t = tracked.get(i);
			int meters = Mth.floor(Math.sqrt(t.distSq));
			if (t.distanceText == null || meters != t.meters) {
				t.meters = meters;
				t.distanceText = meters + "m";
			}
			int health = Mth.ceil(t.player.getHealth() + t.player.getAbsorptionAmount());
			if (t.healthText == null || health != t.health) {
				t.health = health;
				t.healthText = health + "♥";
			}
			t.trail = config.showTrails ? trails.get(t.uuid) : null;
		}
	}

	private void tickMobs(LocalPlayer self, ClientLevel level, RadarConfig config) {
		boolean anyMobs = config.mobMode != RadarConfig.MobMode.OFF;
		boolean hostileOnly = config.mobMode == RadarConfig.MobMode.HOSTILE;
		double maxDist = config.range() * RANGE_FACTOR;
		double maxDistSq = maxDist * maxDist;
		int count = 0;
		int items = 0;

		for (Entity entity : level.entitiesForRendering()) {
			if (count >= MAX_MOBS) break;
			if (entity.isRemoved() || entity.distanceToSqr(self) > maxDistSq) continue;

			if (entity instanceof ItemEntity) {
				if (!config.showItems || items >= MAX_ITEMS) continue;
				items++;
				TrackedMob entry = obtain(mobs, count++, TrackedMob::new);
				entry.entity = entity;
				entry.hostile = false;
				entry.item = true;
				entry.head = null;
				continue;
			}

			if (!anyMobs || !(entity instanceof Mob mob) || !mob.isAlive()) continue;
			boolean hostile = mob instanceof Enemy;
			if (hostileOnly && !hostile) continue;
			if (!config.showInvisible && mob.isInvisibleTo(self)) continue;

			TrackedMob entry = obtain(mobs, count++, TrackedMob::new);
			entry.entity = mob;
			entry.hostile = hostile;
			entry.item = false;
			entry.head = MobHeads.lookup(mob); // at tick rate, so variant changes show up
		}

		for (int i = count; i < mobCount; i++) {
			mobs.get(i).entity = null;
			mobs.get(i).head = null;
		}
		mobCount = count;
	}

	/** Adds each tracked player's position to their trail, and drops trails of players who left. */
	private void tickTrails(RadarConfig config) {
		int capacity = config.trailSeconds * 20 / TRAIL_TICKS;
		trailTick++;
		for (int i = 0; i < trackedCount; i++) {
			Tracked t = tracked.get(i);
			Trail trail = trails.get(t.uuid);
			if (trail == null) {
				trail = new Trail();
				trails.put(t.uuid, trail);
			}
			trail.add(t.player.getX(), t.player.getZ(), capacity);
			trail.seen = trailTick;
			t.trail = trail;
		}
		for (Iterator<Trail> it = trails.values().iterator(); it.hasNext(); ) {
			if (it.next().seen != trailTick) it.remove();
		}
	}

	/**
	 * Finds portal blocks in loaded chunks around you. Only chunk sections whose palette
	 * holds a portal block are looked through, every other block across, which is enough
	 * to find every portal (they're at least 2 wide).
	 */
	private void scanPortals(LocalPlayer self, ClientLevel level, RadarConfig config) {
		portalScan.clear();
		int chunkRadius = (int) Math.ceil(config.range() * RANGE_FACTOR / 16);
		int centerX = self.getBlockX() >> 4;
		int centerZ = self.getBlockZ() >> 4;
		scan:
		for (int x = centerX - chunkRadius; x <= centerX + chunkRadius; x++) {
			for (int z = centerZ - chunkRadius; z <= centerZ + chunkRadius; z++) {
				var chunk = level.getChunkSource().getChunk(x, z, false);
				if (chunk == null) continue;
				LevelChunkSection[] sections = chunk.getSections();
				for (LevelChunkSection section : sections) {
					if (section.hasOnlyAir() || !section.getStates().maybeHas(IS_PORTAL)) continue;
					for (int by = 0; by < 16; by++) {
						for (int bz = 0; bz < 16; bz += 2) {
							for (int bx = 0; bx < 16; bx += 2) {
								if (IS_PORTAL.test(section.getBlockState(bx, by, bz))) {
									portalScan.add(BlockPos.asLong((x << 4) + bx, 0, (z << 4) + bz));
								}
							}
						}
					}
					if (portalScan.size() >= MAX_PORTALS) break scan;
				}
			}
		}
		portals = portalScan.toLongArray();
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
		// North up uses a fixed yaw of 180 (facing north) instead of your view.
		double yaw = config.rotation == RadarConfig.Rotation.NORTH_UP ? 180 : self.getViewYRot(partialTick);
		viewYawRad = Math.toRadians(yaw);
		viewSin = Math.sin(viewYawRad);
		viewCos = Math.cos(viewYawRad);
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

			double look = Math.toRadians(Mth.rotLerp(partialTick, p.yHeadRotO, p.yHeadRot));
			double lookX = -Math.sin(look);
			double lookZ = Math.cos(look);

			Blip b = obtain(blips, n++, Blip::new);
			b.x = sx;
			b.y = sy;
			b.lookX = (float) screenX(lookX, lookZ);
			b.lookY = (float) screenY(lookX, lookZ);
			b.dy = Mth.lerp(partialTick, p.yo, p.getY()) - selfY;
			b.clamped = clamped;
			b.friend = t.friend;
			b.name = t.name;
			b.skin = t.skin;
			b.team = t.team;
		}
		return n;
	}

	/**
	 * Projects tracked mobs and items for this frame. Must be called after {@link #computeBlips},
	 * which sets the view rotation. Anything outside the radar is skipped.
	 *
	 * @return the number of valid entries in {@link #getMobBlips()}
	 */
	public int computeMobBlips(LocalPlayer self, float partialTick, RadarConfig config, float radiusPx) {
		double selfX = Mth.lerp(partialTick, self.xo, self.getX());
		double selfZ = Mth.lerp(partialTick, self.zo, self.getZ());
		double pxPerBlock = radiusPx / config.range();
		boolean circle = config.shape == RadarConfig.Shape.CIRCLE;

		int n = 0;
		for (int i = 0; i < mobCount; i++) {
			TrackedMob m = mobs.get(i);
			Entity e = m.entity;
			if (e.isRemoved()) continue;

			double dx = Mth.lerp(partialTick, e.xo, e.getX()) - selfX;
			double dz = Mth.lerp(partialTick, e.zo, e.getZ()) - selfZ;
			float sx = (float) (screenX(dx, dz) * pxPerBlock);
			float sy = (float) (screenY(dx, dz) * pxPerBlock);
			if (!inside(sx, sy, radiusPx, circle)) continue;

			MobBlip b = obtain(mobBlips, n++, MobBlip::new);
			b.x = sx;
			b.y = sy;
			b.hostile = m.hostile;
			b.item = m.item;
			b.head = m.head;
		}
		return n;
	}

	/** True if a radar-space offset lies within the radar's square or circle. */
	public static boolean inside(float x, float y, float radiusPx, boolean circle) {
		return circle
				? x * x + y * y <= radiusPx * radiusPx
				: Math.abs(x) <= radiusPx && Math.abs(y) <= radiusPx;
	}

	/** Rightward component of a world offset in the current view (blocks). */
	public double screenX(double dx, double dz) {
		return -dx * viewCos - dz * viewSin;
	}

	/** Downward component of a world offset in the current view (blocks). */
	public double screenY(double dx, double dz) {
		return -(-dx * viewSin + dz * viewCos);
	}

	/** The radar's view yaw in radians, from the latest {@link #computeBlips} call. */
	public double viewYawRad() {
		return viewYawRad;
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

	/** Portal columns from the latest scan, as {@link BlockPos#asLong} values with y = 0. */
	public long[] getPortals() {
		return portals;
	}

	RadarTerrain getTerrain() {
		return terrain;
	}

	private void clear() {
		for (int i = 0; i < trackedCount; i++) {
			tracked.get(i).player = null;
			tracked.get(i).trail = null;
		}
		trackedCount = 0;
		clearMobs();
		trails.clear();
		portals = NO_PORTALS;
		ticksUntilPortalScan = 0;
		terrain.reset();
		lastLevel = null;
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
