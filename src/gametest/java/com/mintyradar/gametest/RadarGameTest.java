package com.mintyradar.gametest;

import com.mintyradar.RadarClientMod;
import com.mintyradar.RadarConfig;
import com.mintyradar.RadarManager;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.sounds.SoundSource;

import java.nio.file.Files;
import java.util.UUID;

/**
 * Turns on every radar feature in a test world and takes screenshots: a fake second
 * player (walking, so it leaves a trail), mobs, a dropped item, portal blocks and the
 * terrain map, on a round heading-up radar and a square north-up one. Also checks
 * that 1.2.0 settings are moved over, invisible players, and scroll zoom.
 */
public class RadarGameTest implements FabricClientGameTest {
	private static final int FAKE_PLAYER_ID = 900_000;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0));
		RadarConfig config = RadarClientMod.config();

		// build.gradle writes a 1.2.0 config with rangeIndex 2 (64 blocks) as minty_radar.json.
		var configDir = FabricLoader.getInstance().getConfigDir();
		check(Files.exists(configDir.resolve("MintyRadar.json")), "settings should be saved as MintyRadar.json");
		check(!Files.exists(configDir.resolve("minty_radar.json")), "the old settings file should have been moved");
		check(config.range() == 64, "64-block range should carry over from 1.2.0, got " + config.range());
		check(config.isFriend("notch"), "friends should carry over from 1.2.0");

		try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			TestServerContext server = world.getServer();
			server.runCommand("gamerule spawn_mobs false");
			server.runCommand("time set day");
			server.runCommand("execute as @p at @s run tp @s ~ ~ ~ 0 0");
			server.runCommand("execute as @p at @s run summon zombie ~6 ~ ~10 {NoAI:1b}");
			server.runCommand("execute as @p at @s run summon cow ~-8 ~ ~6 {NoAI:1b}");
			server.runCommand("execute as @p at @s run summon item ~3 ~ ~-6 {Item:{id:\"minecraft:diamond\",count:1}}");
			server.runCommand("execute as @p at @s run setblock ~-10 ~ ~-12 minecraft:end_portal");
			server.runCommand("execute as @p at @s run fill ~12 ~ ~-4 ~13 ~1 ~-4 minecraft:end_gateway");
			ctx.waitTicks(20);

			config.shape = RadarConfig.Shape.CIRCLE;
			config.rotation = RadarConfig.Rotation.HEADING_UP;
			config.nameMode = RadarConfig.NameMode.ALWAYS;
			config.mobMode = RadarConfig.MobMode.ALL;
			config.showItems = true;
			config.showPortals = true;
			config.showTrails = true;
			config.terrain = true;
			config.showInvisible = false;
			config.size = 120;
			config.rangeIndex = 2; // 32 blocks, so everything is well inside the radar

			ctx.runOnClient(RadarGameTest::addFakePlayer);
			// Walk the fake player along a curve so it leaves a trail.
			for (int step = 0; step < 40; step++) {
				int s = step;
				ctx.runOnClient(mc -> {
					var p = (RemotePlayer) mc.level.getEntity(FAKE_PLAYER_ID);
					var self = mc.player;
					double x = self.getX() - 14 + s * 0.5;
					double z = self.getZ() - 10 + Math.sin(s / 6.0) * 4;
					p.setPos(x, self.getY(), z);
					p.setYHeadRot(-90);
				});
				ctx.waitTicks(1);
			}
			ctx.waitTicks(20); // terrain map finishes its first picture, portal scan has run

			RadarManager manager = RadarClientMod.manager();
			check(ctx.computeOnClient(mc -> manager.getTrackedCount()) == 1, "the fake player should be tracked");
			check(ctx.computeOnClient(mc -> manager.getTracked().get(0).trail != null
					&& manager.getTracked().get(0).trail.size() > 2), "the fake player should have a trail"); // a point every half second while walking
			check(ctx.computeOnClient(mc -> manager.getPortals().length) > 0, "portal blocks should be found");
			ctx.takeScreenshot("radar-round-heading-up");

			config.shape = RadarConfig.Shape.SQUARE;
			config.rotation = RadarConfig.Rotation.NORTH_UP;
			config.blipStyle = RadarConfig.BlipStyle.HEADS;
			ctx.runOnClient(mc -> mc.player.setYRot(135));
			ctx.waitTicks(5);
			ctx.takeScreenshot("radar-square-north-up");

			// Invisible players are left out unless the setting is on.
			ctx.runOnClient(mc -> mc.level.getEntity(FAKE_PLAYER_ID).setInvisible(true));
			ctx.waitTicks(2);
			check(ctx.computeOnClient(mc -> manager.getTrackedCount()) == 0, "invisible player should be hidden by default");
			config.showInvisible = true;
			ctx.waitTicks(2);
			check(ctx.computeOnClient(mc -> manager.getTrackedCount()) == 1, "invisible player should show with the setting on");
			String shown = ctx.computeOnClient(mc -> manager.getTracked().get(0).shownName);
			check(shown.endsWith("(invisible)"), "invisible player should be marked in the list, got " + shown);

			// Scroll zoom: hold the key (left Alt) and scroll down to zoom out a step.
			ctx.getInput().holdKey(InputConstants.KEY_LALT);
			ctx.getInput().scroll(-1);
			ctx.waitTick();
			ctx.getInput().releaseKey(InputConstants.KEY_LALT);
			check(config.range() == 48, "scroll down with Alt held should zoom out to 48 blocks, got " + config.range());
			int slot = ctx.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
			ctx.getInput().scroll(-1);
			ctx.waitTick();
			check(config.range() == 48, "scrolling without Alt should not zoom");
			check(ctx.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot()) != slot,
					"scrolling without Alt should still change hotbar slot");
		}
	}

	/** A client-only player standing next to you, which the radar sees like any other player. */
	private static void addFakePlayer(Minecraft mc) {
		var player = new RemotePlayer(mc.level, new GameProfile(UUID.randomUUID(), "RadarBuddy"));
		player.setId(FAKE_PLAYER_ID);
		player.setPos(mc.player.getX() - 14, mc.player.getY(), mc.player.getZ() - 10);
		mc.level.addEntity(player);
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new AssertionError(message);
	}
}
