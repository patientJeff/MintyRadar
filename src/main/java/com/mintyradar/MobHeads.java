package com.mintyradar;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;
import org.joml.Vector3fc;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Finds the face of any mob for drawing on the radar, straight from the game's own
 * model and texture. No hand-made table is involved, so every mob, variant (cow and
 * wolf types, villager biomes...) and resource pack works.
 *
 * <p>How: the mob's renderer gives its current texture and its model. In the model,
 * the part named "head" (or containing "head", or failing that the first cube, for
 * mobs such as slimes) has a front face (the side pointing -Z in model space) whose
 * texture coordinates are the mob's face. A "hat" part, like zombies have, is drawn on
 * top as the outer layer.
 *
 * <p>Face lookups are cached per model, so the model walk happens once per mob type.
 */
public final class MobHeads {
	/** Where a face sits on the texture (0-1 coordinates), plus its width/height ratio. */
	public record Face(float u0, float v0, float u1, float v1, float aspect) {
	}

	/** A model's face and optional hat layer. {@code face} is null if none was found. */
	private record ModelFaces(Face face, Face hat) {
	}

	/** What to draw for one mob: its current texture, face and optional hat. */
	public record Head(Identifier texture, Face face, Face hat) {
	}

	/** Weak keys: models replaced by a resource reload are dropped automatically. ModelPart
	 * uses identity equality, so this behaves like an identity map. */
	private static final Map<ModelPart, ModelFaces> CACHE = new WeakHashMap<>();
	private static final ModelFaces NO_FACE = new ModelFaces(null, null);

	private MobHeads() {
	}

	/** Returns the head to draw for {@code mob}, or null to fall back to a plain dot. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static Head lookup(Mob mob) {
		try {
			EntityRenderer renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(mob);
			if (!(renderer instanceof LivingEntityRenderer living)) return null;

			ModelFaces faces = CACHE.computeIfAbsent(living.getModel().root(), MobHeads::findFaces);
			if (faces.face() == null) return null;

			// The render state carries the mob's variant, so the texture matches what's in the world.
			EntityRenderState state = renderer.createRenderState(mob, 1f);
			Identifier texture = living.getTextureLocation((LivingEntityRenderState) state);
			return texture == null ? null : new Head(texture, faces.face(), faces.hat());
		} catch (RuntimeException e) {
			// A modded or unusual renderer: just use a dot for it.
			return null;
		}
	}

	private static ModelFaces findFaces(ModelPart root) {
		Face[] exactHead = new Face[1];
		Face[] someHead = new Face[1];
		Face[] first = new Face[1];
		Face[] hat = new Face[1];

		root.visit(new PoseStack(), (pose, path, index, cube) -> {
			Face face = frontFace(cube);
			if (face == null) return;
			String part = path.substring(path.lastIndexOf('/') + 1);
			if (first[0] == null) first[0] = face;
			if (exactHead[0] == null && part.equals("head")) exactHead[0] = face;
			if (someHead[0] == null && part.contains("head")) someHead[0] = face;
			if (hat[0] == null && part.equals("hat")) hat[0] = face;
		});

		Face face = exactHead[0] != null ? exactHead[0] : someHead[0] != null ? someHead[0] : first[0];
		return face == null ? NO_FACE : new ModelFaces(face, hat[0]);
	}

	/** The cube's -Z (front) face, or null if it has none. */
	private static Face frontFace(ModelPart.Cube cube) {
		for (ModelPart.Polygon polygon : cube.polygons) {
			Vector3fc normal = polygon.normal();
			if (normal.z() > -0.9f) continue;
			float u0 = Float.MAX_VALUE, v0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE, v1 = -Float.MAX_VALUE;
			for (ModelPart.Vertex vertex : polygon.vertices()) {
				u0 = Math.min(u0, vertex.u());
				v0 = Math.min(v0, vertex.v());
				u1 = Math.max(u1, vertex.u());
				v1 = Math.max(v1, vertex.v());
			}
			float width = cube.maxX - cube.minX;
			float height = cube.maxY - cube.minY;
			if (width <= 0 || height <= 0 || u1 <= u0 || v1 <= v0) return null;
			return new Face(u0, v0, u1, v1, width / height);
		}
		return null;
	}
}
