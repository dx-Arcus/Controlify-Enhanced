/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.aimassist;

import dev.isxander.controlify.Controlify;
import dev.isxander.controlify.config.settings.TargetLockSettings;
import dev.isxander.controlify.utils.CUtil;
import dev.isxander.controlify.utils.MinecraftUtil;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
//? if >=26.3 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
//?}
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a marker over whatever target lock is holding.
 * <p>
 * Done as a HUD layer with the projection worked out here, rather than as a world renderer: how
 * the marker shrinks with range is a choice rather than a consequence of perspective, and the
 * arithmetic below is stable across versions in a way that hooking the world render pipeline is
 * not.
 * <p>
 * Everything it projects with comes off the camera the frame was actually drawn from — its basis
 * vectors and its effective field of view. Taking any of it from settings or from the player
 * instead is what put the marker in the wrong place: view bob and third person move the camera
 * without moving the eyes, and the game widens the field of view as the player speeds up or zooms,
 * so a marker placed with the base setting drifts further out the faster you go.
 */
public final class TargetLockRenderer {
	/** Radius of the pyramid's square top, in GUI pixels. Wider than tall on purpose. */
	private static final float BASE_RADIUS = 9.5f;
	/** How far the point hangs below that top face. */
	private static final float SOLID_HEIGHT = 8.5f;

	/**
	 * How far above the horizontal the marker is viewed from. Shallow on purpose: steepen it and
	 * you end up looking down onto the top face, which then fills the silhouette and reads as a
	 * flat diamond tipped towards you rather than something hanging point down. Kept low, the top
	 * is a thin horizontal sliver and the sides and the point carry the shape.
	 */
	private static final double TILT = Math.toRadians(14);
	/** One full turn in this many milliseconds. Slow enough to read as shape, not motion. */
	private static final double SPIN_PERIOD_MS = 5200;
	/** Direction the imaginary light comes from, for shading the four sides differently. */
	private static final double LIGHT_ANGLE = Math.toRadians(-50);

	/** How far above the mob's head the point of the marker sits, in blocks. */
	private static final double HEAD_CLEARANCE = 0.05;
	/**
	 * How far the marker is kept above the mob's outline on screen, in GUI pixels. Small, because
	 * the point of it is to sit on the mob rather than float somewhere near it.
	 */
	private static final double SILHOUETTE_GAP = 2;

	/**
	 * Closer than this the marker is left at full size. Inside melee range it already fills plenty
	 * of screen, and the look at that range took four rounds of tuning to settle.
	 */
	private static final double SCALE_NEAR_BLOCKS = 4;

	/** How much larger the dark copy is drawn, which is what gives the solid its outline. */
	private static final float OUTLINE_SCALE = 1.12f;

	/** Solid when the shot is clear, faded when something is in the way. */
	private static final float ALPHA_VISIBLE = 1.0f;
	private static final float ALPHA_OCCLUDED = 0.42f;

	private TargetLockRenderer() {
	}

	public static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		Entity target = TargetLock.locked();

		if (player == null || target == null || !TargetLock.active() || MinecraftUtil.getScreen() != null) {
			return;
		}
		TargetLockSettings settings = Controlify.instance().config().getSettings().aimAssistSettings().targetLock;
		if (!settings.arrowEnabled) {
			return;
		}

		float partial = deltaTracker.getGameTimeDeltaPartialTick(false);
		Vec3 markerPos = new Vec3(
				Mth.lerp(partial, target.xOld, target.getX()),
				Mth.lerp(partial, target.yOld, target.getY()) + target.getBbHeight() + HEAD_CLEARANCE,
				Mth.lerp(partial, target.zOld, target.getZ()));

		//? if >=26.2 {
		Camera camera = minecraft.gameRenderer.mainCamera();
		//?} else {
		/*Camera camera = minecraft.gameRenderer.getMainCamera();
		*///?}
		Vec3 relative = markerPos.subtract(camera.position());

		// The camera hands out its own basis, so there is no trig here to get a sign wrong in.
		Vector3fc forward = camera.forwardVector();
		Vector3fc up = camera.upVector();
		Vector3fc left = camera.leftVector();

		double depth = dot(relative, forward);
		if (depth <= 0.05) {
			// Behind the camera. The compass bar is the place to say where it went, not this.
			return;
		}

		int width = graphics.guiWidth();
		int height = graphics.guiHeight();
		if (width <= 0 || height <= 0) {
			return;
		}

		//? if >=26.3 {
		// Nothing to draw here. The marker is real geometry in the world now, handed to the level
		// renderer by TargetLockRenderer#submitWorldMarker so it goes through the same solid pass
		// the mobs do - which is the only way it gets to write depth, and the only way anything
		// drawn after it can be made to respect it.
		//?} else {
		/*drawOverTheWorld(graphics, minecraft, camera, player, target, markerPos, relative,
				forward, up, left, depth, partial, width, height, settings);
		*///?}
	}

	/**
	 * The older way of drawing it: worked out where the mob lands on screen and painted the marker
	 * over the finished picture. Kept for the versions that have no way to put it in the world, so
	 * it is always in front of everything and has to be lifted clear of the mob's outline rather
	 * than simply going behind it.
	 */
	private static void drawOverTheWorld(GuiGraphicsExtractor graphics, Minecraft minecraft, Camera camera,
			LocalPlayer player, Entity target, Vec3 markerPos, Vec3 relative,
			Vector3fc forward, Vector3fc up, Vector3fc left, double depth, float partial,
			int width, int height, TargetLockSettings settings) {

		// The camera's own field of view, which already carries the widening the game applies for
		// speed and any zoom in effect. The value in the options menu is only where that starts.
		double tanHalfFov = Math.tan(Math.toRadians(camera.getFov()) / 2);
		double aspect = (double) width / height;
		double screenX = width / 2.0 * (1 - (dot(relative, left) / depth) / (tanHalfFov * aspect));
		double screenY = height / 2.0 * (1 - (dot(relative, up) / depth) / tanHalfFov);

		// A fixed height above the head is only "above" from level with the mob. Look down at one
		// and that point projects onto its chest, and the marker - drawn over the world rather
		// than in it - reads as painted on. So the height is taken from where the mob actually
		// ends on screen instead: project its box and sit above the topmost corner. From the side
		// that is its head and nothing moves; from above it is the far edge, and the marker clears
		// the whole thing.
		double outlineTop = silhouetteTop(target, camera.position(), forward, up, tanHalfFov, height,
				markerPos.x, Mth.lerp(partial, target.yOld, target.getY()), markerPos.z);
		if (!Double.isNaN(outlineTop)) {
			screenY = outlineTop - SILHOUETTE_GAP;
		}

		int margin = Math.round(BASE_RADIUS * 3);
		if (screenX < -margin || screenX > width + margin || screenY < -margin || screenY > height + margin) {
			return;
		}

		float alpha = player.hasLineOfSight(target) ? ALPHA_VISIBLE : ALPHA_OCCLUDED;

		// Drawn around a local origin with the real position carried in the transform, because
		// fill only takes whole pixels. Rounding the position instead pinned the marker to the
		// pixel grid, so a mob drifting smoothly across the screen made it hop a whole GUI pixel
		// at a time in whichever axis crossed a boundary - three or four screen pixels at a
		// typical GUI scale, which is the twitching in all four directions while strafing.
		// Everything above is in GUI pixels, which at a GUI scale of 4 are four real pixels each -
		// so a shape this small ends up built out of very fat blocks. Shrinking the coordinate
		// system by that scale and building the solid the same amount larger puts one unit back on
		// one real pixel, and the edges come out as fine as the display can draw them.
		float detail = (float) Math.max(1, (double) minecraft.getWindow().getWidth() / width);

		// Shrunk with range rather than held at one size. Scaled on the matrix instead of built
		// smaller, so the shape is still assembled at full precision and only the finished solid is
		// reduced - and so the translate below can pull the apex up by the same amount, keeping the
		// point on the mob's head at every size.
		float sizeScale = distanceScale(relative.length(), settings.markerFloorBlocks);

		graphics.pose().pushMatrix();
		graphics.pose().translate((float) screenX, (float) screenY - SOLID_HEIGHT * sizeScale);
		graphics.pose().scale(sizeScale / detail, sizeScale / detail);
		draw(graphics, settings.arrowColor, alpha, detail);
		graphics.pose().popMatrix();
	}

	//? if >=26.3 {
	/**
	 * The marker's own texture: a grey mottle in the style of an ore block, multiplied by whatever
	 * color is set, so any color comes out looking like faceted stone rather than flat paint.
	 */
	private static final Identifier MARKER_TEXTURE = CUtil.rl("textures/misc/target_marker.png");

	/** How many square rings the marker is stacked from, widest at the top, smallest at the point. */
	private static final int RINGS = 6;
	/** Half the width of that widest ring, in the same GUI pixels the rest of this is measured in. */
	private static final double TOP_RADIUS = 9.5;
	/** Point to top face. */
	private static final double TOTAL_HEIGHT = 21;
	private static final double LEVEL_HEIGHT = TOTAL_HEIGHT / RINGS;
	/**
	 * How much of the marker one tile of the texture covers, in those same pixels. A little wider
	 * than the marker itself, so every face lands inside the texture and none of it has to repeat -
	 * which is the one thing that would depend on how the texture happens to be sampled.
	 */
	private static final double UV_SPAN = 24;

	/** Which way the light comes from, so the four sides and the treads do not all read the same. */
	private static final Vec3 LIGHT = new Vec3(-0.35, 1.0, -0.55).normalize();
	/** How dark a face pointing straight away from the light gets. */
	private static final float LAMBERT_MIN = 0.80f;
	/** How dark the point is compared to the top ring, which is left at full strength. */
	private static final float TIP_SHADE = 0.72f;

	/** Lit by the marker's own shading rather than by wherever the mob happens to be standing. */
	private static final int FULL_BRIGHT = 0xF000F0;

	/**
	 * One corner of a face before the spin: where it sits, its texture coordinate, and how far down
	 * towards the point it is - all of it fixed, so it is worked out once.
	 */
	private record Corner(double x, double y, double z, float u, float v, float tipShade) {
	}

	/** One face of the marker before the spin: which way it faces, and its four corners in order. */
	private record Face(double nx, double ny, double nz, Corner[] corners) {
	}

	/**
	 * The marker's geometry, laid out once. Only the spin, the light it catches and the color change
	 * from frame to frame, so a frame turns these rather than building the solid again: the first
	 * version laid out every face, its texture coordinates and its shading on every frame, some three
	 * hundred small arrays each time.
	 */
	private static final Face[] FACES = layOut();

	/**
	 * Hands the marker to the game as geometry in the world, in the same pass the mobs themselves
	 * are drawn in.
	 * <p>
	 * Called from the level renderer rather than from the HUD, because that is the only place
	 * anything can be put into the world's solid pass. What that buys is depth: the marker writes
	 * to the depth buffer like everything else, so whatever is drawn after it - clouds, weather,
	 * water - is tested against it instead of simply painting over it, and its own far faces
	 * cannot show through its near ones.
	 * <p>
	 * The gizmo API this used to go through could not do that. Every gizmo, quads included, is
	 * drawn through {@code debug_filled_box}, which blends and does not write depth - so the marker
	 * came out slightly see-through, took on the color of whatever was behind it, and had clouds
	 * drawn straight over the top of it.
	 * <p>
	 * The distance falloff comes out of this for free over most of its range: a fixed size in
	 * blocks already shrinks as 1/distance, which is what the falloff was imitating. The arithmetic
	 * is kept anyway rather than deleted, because it is what holds the marker at a floor past the
	 * set distance, where a fixed size in blocks would carry on shrinking into nothing.
	 *
	 * @param cameraPos where the frame is being drawn from; everything submitted here is measured
	 *                  from it, because that is the origin the level renderer works in
	 */
	public static void submitWorldMarker(SubmitNodeCollector collector, Vec3 cameraPos) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		Entity target = TargetLock.locked();

		if (player == null || target == null || !TargetLock.active() || MinecraftUtil.getScreen() != null) {
			return;
		}
		TargetLockSettings settings = Controlify.instance().config().getSettings().aimAssistSettings().targetLock;
		if (!settings.arrowEnabled) {
			return;
		}

		float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Vec3 apex = new Vec3(
				Mth.lerp(partial, target.xOld, target.getX()),
				Mth.lerp(partial, target.yOld, target.getY()) + target.getBbHeight() + HEAD_CLEARANCE,
				Mth.lerp(partial, target.zOld, target.getZ()));

		double distance = apex.subtract(cameraPos).length();
		int guiHeight = minecraft.getWindow().getGuiScaledHeight();
		if (distance < 1.0e-4 || guiHeight <= 0) {
			return;
		}

		// The camera's own field of view, which already carries the widening the game applies for
		// speed and any zoom in effect. The value in the options menu is only where that starts.
		Camera camera = minecraft.gameRenderer.mainCamera();
		double blocksPerPixel = 2 * Math.tan(Math.toRadians(camera.getFov()) / 2) * distance / guiHeight;
		double unit = blocksPerPixel * distanceScale(distance, settings.markerFloorBlocks);
		if (unit <= 0) {
			return;
		}

		double spin = (System.nanoTime() / 1_000_000L % (long) SPIN_PERIOD_MS) / SPIN_PERIOD_MS * Math.PI * 2;
		int rgb = settings.arrowColor;

		// Scale on the transform rather than in the shape, so the marker is laid out once in whole
		// pixels and only the finished solid is shrunk. A uniform scale leaves normals alone, so
		// the shading below is unaffected by how small it ends up.
		PoseStack transform = new PoseStack();
		transform.translate((float) (apex.x - cameraPos.x), (float) (apex.y - cameraPos.y), (float) (apex.z - cameraPos.z));
		transform.scale((float) unit, (float) unit, (float) unit);

		collector.submitCustomGeometry(transform, RenderTypes.entitySolid(MARKER_TEXTURE),
				(pose, consumer) -> emitMarker(pose, consumer, spin, rgb));
	}

	/**
	 * The marker itself: a stepped square funnel hanging point down, hollow, with the inside
	 * stepping in exactly as the outside steps out. Seen from level it is a chunky arrowhead; from
	 * above it opens into concentric square rings.
	 * <p>
	 * Each ring is the width of one step, because the wall between the outer staircase and the
	 * inner one is exactly one step thick - that is what makes the hollow match the steps rather
	 * than being a plain hole. The smallest ring has nothing left inside it, so it is capped and
	 * becomes the point that rests on the mob's head.
	 * <p>
	 * Every face goes out twice, wound both ways. The pass this lands in culls back faces, and
	 * rather than depend on which way round that is, both are sent and the depth buffer - which is
	 * being written now - decides. Two copies of the same face at the same depth resolve to the
	 * same pixel either way.
	 */
	private static void emitMarker(PoseStack.Pose pose, VertexConsumer consumer, double spin, int rgb) {
		double cos = Math.cos(spin);
		double sin = Math.sin(spin);

		// Every face goes out twice, wound both ways (see above). Corners and normal are turned here,
		// as the marker spins: that way the texture and the shading stay put on the shape, and the
		// light stays put in the world, so the sides catch it in turn as they come round.
		for (Face face : FACES) {
			float lambert = (float) (LAMBERT_MIN + (1 - LAMBERT_MIN)
					* Math.max(0, turnX(face.nx(), face.nz(), cos, sin) * LIGHT.x + face.ny() * LIGHT.y + turnZ(face.nx(), face.nz(), cos, sin) * LIGHT.z));
			float normalX = (float) turnX(face.nx(), face.nz(), cos, sin);
			float normalY = (float) face.ny();
			float normalZ = (float) turnZ(face.nx(), face.nz(), cos, sin);

			for (int pass = 0; pass < 2; pass++) {
				for (int step = 0; step < 4; step++) {
					Corner corner = face.corners()[pass == 0 ? step : 3 - step];
					consumer.addVertex(pose,
									(float) turnX(corner.x(), corner.z(), cos, sin),
									(float) corner.y(),
									(float) turnZ(corner.x(), corner.z(), cos, sin))
							.setColor(0xFF000000 | shade(rgb, lambert * corner.tipShade()))
							.setUv(corner.u(), corner.v())
							.setOverlay(OverlayTexture.NO_OVERLAY)
							.setLight(FULL_BRIGHT)
							.setNormal(pose, normalX, normalY, normalZ);
				}
			}
		}
	}

	/** The marker's faces, ring by ring from the top face down to the point, in the order they are sent. */
	private static Face[] layOut() {
		List<Face> faces = new ArrayList<>();
		for (int level = 0; level < RINGS; level++) {
			double outer = TOP_RADIUS * (RINGS - level) / RINGS;
			double inner = TOP_RADIUS * (RINGS - level - 1) / RINGS;
			double bottom = (RINGS - 1 - level) * LEVEL_HEIGHT;
			double top = bottom + LEVEL_HEIGHT;

			walls(faces, outer, bottom, top, 1);
			if (inner > 1.0e-6) {
				walls(faces, inner, bottom, top, -1);
				tread(faces, outer, inner, top, 1);
				tread(faces, outer, inner, bottom, -1);
			} else {
				face(faces,
						new double[]{-outer, outer, outer, -outer},
						new double[]{top, top, top, top},
						new double[]{-outer, -outer, outer, outer}, 0, 1, 0);
				face(faces,
						new double[]{-outer, outer, outer, -outer},
						new double[]{bottom, bottom, bottom, bottom},
						new double[]{-outer, -outer, outer, outer}, 0, -1, 0);
			}
		}
		return faces.toArray(new Face[0]);
	}

	/**
	 * The four upright sides of one ring, at half-width {@code radius}.
	 *
	 * @param facing 1 for the outside of the ring, -1 for the wall of the cavity
	 */
	private static void walls(List<Face> faces, double radius, double bottom, double top, int facing) {
		double r = radius;
		face(faces,
				new double[]{r, r, r, r}, new double[]{bottom, bottom, top, top},
				new double[]{-r, r, r, -r}, facing, 0, 0);
		face(faces,
				new double[]{-r, -r, -r, -r}, new double[]{bottom, bottom, top, top},
				new double[]{r, -r, -r, r}, -facing, 0, 0);
		face(faces,
				new double[]{r, -r, -r, r}, new double[]{bottom, bottom, top, top},
				new double[]{r, r, r, r}, 0, 0, facing);
		face(faces,
				new double[]{-r, r, r, -r}, new double[]{bottom, bottom, top, top},
				new double[]{-r, -r, -r, -r}, 0, 0, -facing);
	}

	/**
	 * The flat square ring between {@code inner} and {@code outer} at one height - the step you see
	 * from outside looking down, or from inside looking up. Four strips, laid out so they meet edge
	 * to edge without overlapping.
	 */
	private static void tread(List<Face> faces, double outer, double inner, double y, int facing) {
		double[][] strips = {
				{-outer, outer, inner, outer},
				{-outer, outer, -outer, -inner},
				{inner, outer, -inner, inner},
				{-outer, -inner, -inner, inner},
		};
		for (double[] s : strips) {
			face(faces,
					new double[]{s[0], s[1], s[1], s[0]},
					new double[]{y, y, y, y},
					new double[]{s[2], s[2], s[3], s[3]}, 0, facing, 0);
		}
	}

	/** One face, unturned: its corners with their texture coordinates and how far down the point each sits. */
	private static void face(List<Face> faces, double[] xs, double[] ys, double[] zs, double nx, double ny, double nz) {
		Corner[] corners = new Corner[4];
		for (int i = 0; i < 4; i++) {
			float u;
			float v;
			if (Math.abs(ny) > 0.5) {
				u = (float) (0.5 + xs[i] / UV_SPAN);
				v = (float) (0.5 + zs[i] / UV_SPAN);
			} else {
				u = (float) (0.5 + (Math.abs(nx) > 0.5 ? zs[i] : xs[i]) / UV_SPAN);
				v = (float) (ys[i] / UV_SPAN);
			}
			corners[i] = new Corner(xs[i], ys[i], zs[i], u, v,
					(float) (TIP_SHADE + (1 - TIP_SHADE) * Mth.clamp(ys[i] / TOTAL_HEIGHT, 0, 1)));
		}
		faces.add(new Face(nx, ny, nz, corners));
	}

	private static double turnX(double x, double z, double cos, double sin) {
		return x * cos + z * sin;
	}

	private static double turnZ(double x, double z, double cos, double sin) {
		return z * cos - x * sin;
	}
	//?}

	/**
	 * How large to draw the marker for a mob this far from the camera, as a fraction of full size.
	 * <p>
	 * Plain 1/distance between {@link #SCALE_NEAR_BLOCKS} and {@code floorBlocks}, which is exactly
	 * what the game does to everything else in the world - so the marker shrinks in step with the
	 * mob it is sitting over and keeps the same share of it at every range. Held flat either side
	 * of that: full size in close, and whatever it had reached at the far end.
	 * <p>
	 * A clamp rather than anything with memory, so the two ends are the same walking out as
	 * walking back in.
	 *
	 * @param floorBlocks how far out it keeps shrinking before it holds. The codec keeps this
	 *                    inside its range, and the floor below catches a config edited by hand:
	 *                    under the near distance the marker would be asked to draw larger than
	 *                    full size, which none of this means anything for.
	 */
	private static float distanceScale(double distance, double floorBlocks) {
		double minScale = SCALE_NEAR_BLOCKS / Math.max(floorBlocks, SCALE_NEAR_BLOCKS);
		return (float) Mth.clamp(SCALE_NEAR_BLOCKS / distance, minScale, 1.0);
	}

	/**
	 * A four sided pyramid hanging point down over the mob, turning slowly so its shape reads.
	 * <p>
	 * This is real geometry rather than a drawing of some: the five corners are placed in three
	 * dimensions, spun, projected, ordered back to front and filled a scanline at a time, with each
	 * face lit by which way it happens to be facing. Doing it here rather than handing a model to
	 * the world renderer keeps the marker the same size at any range, which is the thing worth
	 * protecting — a world-space model would shrink into nothing at forty blocks.
	 */
	private static void draw(GuiGraphicsExtractor graphics, int rgb, float alpha, float detail) {
		double spin = (System.nanoTime() / 1_000_000L % (long) SPIN_PERIOD_MS) / SPIN_PERIOD_MS * Math.PI * 2;
		double cosTilt = Math.cos(TILT);
		double sinTilt = Math.sin(TILT);

		// Top corners, going round the square, plus the point underneath them.
		float radius = BASE_RADIUS * detail;
		float[] cornerX = new float[4];
		float[] cornerY = new float[4];
		float[] cornerDepth = new float[4];
		for (int i = 0; i < 4; i++) {
			double angle = spin + i * Math.PI / 2;
			double x = Math.cos(angle) * radius;
			double z = Math.sin(angle) * radius;
			cornerX[i] = (float) x;
			// Viewed from above, so depth leans into how high up the corner lands on screen.
			cornerY[i] = (float) (-z * sinTilt);
			cornerDepth[i] = (float) z;
		}
		float apexX = 0;
		float apexY = (float) (SOLID_HEIGHT * detail * cosTilt);

		int outline = argb(shade(rgb, 0.12f), alpha * 0.9f);
		drawSolid(graphics, cornerX, cornerY, cornerDepth, apexX, apexY, rgb, alpha, outline, OUTLINE_SCALE, true);
		drawSolid(graphics, cornerX, cornerY, cornerDepth, apexX, apexY, rgb, alpha, outline, 1.0f, false);
	}

	/**
	 * @param scale     drawn slightly large and flat dark first, which is how the whole shape gets
	 *                  an outline without working out its silhouette
	 * @param asOutline whether this pass is that dark copy
	 */
	private static void drawSolid(GuiGraphicsExtractor graphics, float[] cornerX, float[] cornerY,
								float[] cornerDepth, float apexX, float apexY,
								int rgb, float alpha, int outline, float scale, boolean asOutline) {
		// Back to front, so the near faces simply paint over the far ones and no depth test is
		// needed. A pyramid is convex, so ordering alone is enough to get it right.
		Integer[] faces = {0, 1, 2, 3};
		java.util.Arrays.sort(faces, (a, b) -> Float.compare(
				cornerDepth[b] + cornerDepth[(b + 1) % 4],
				cornerDepth[a] + cornerDepth[(a + 1) % 4]));

		for (int face : faces) {
			int next = (face + 1) % 4;
			double facing = Math.atan2(cornerY[face] + cornerY[next], cornerX[face] + cornerX[next]);
			float light = (float) (0.42 + 0.46 * Math.max(0, Math.cos(facing - LIGHT_ANGLE)));
			fillTriangle(graphics,
					new float[]{cornerX[face] * scale, cornerX[next] * scale, apexX},
					new float[]{cornerY[face] * scale, cornerY[next] * scale, apexY * scale},
					asOutline ? outline : argb(shade(rgb, light), alpha));
		}

		// The top face last: from above it is always the nearest thing on the shape.
		fillQuad(graphics, cornerX, cornerY, scale,
				asOutline ? outline : argb(lighten(rgb, 0.55f), alpha));
	}

	private static void fillQuad(GuiGraphicsExtractor graphics, float[] xs, float[] ys, float scale, int color) {
		fillTriangle(graphics,
				new float[]{xs[0] * scale, xs[1] * scale, xs[2] * scale},
				new float[]{ys[0] * scale, ys[1] * scale, ys[2] * scale}, color);
		fillTriangle(graphics,
				new float[]{xs[0] * scale, xs[2] * scale, xs[3] * scale},
				new float[]{ys[0] * scale, ys[2] * scale, ys[3] * scale}, color);
	}

	/** Scanline fill, so the whole solid still needs nothing of the renderer beyond rectangles. */
	private static void fillTriangle(GuiGraphicsExtractor graphics, float[] xs, float[] ys, int color) {
		int top = (int) Math.floor(Math.min(ys[0], Math.min(ys[1], ys[2])));
		int bottom = (int) Math.ceil(Math.max(ys[0], Math.max(ys[1], ys[2])));

		for (int y = top; y <= bottom; y++) {
			float centre = y + 0.5f;
			float left = Float.MAX_VALUE;
			float right = -Float.MAX_VALUE;

			for (int i = 0; i < 3; i++) {
				int j = (i + 1) % 3;
				float y0 = ys[i];
				float y1 = ys[j];
				if ((centre >= y0 && centre < y1) || (centre >= y1 && centre < y0)) {
					float along = (centre - y0) / (y1 - y0);
					float x = xs[i] + along * (xs[j] - xs[i]);
					left = Math.min(left, x);
					right = Math.max(right, x);
				}
			}
			if (right > left) {
				graphics.fill(Math.round(left), y, Math.round(right), y + 1, color);
			}
		}
	}

	/**
	 * The highest point of the mob's box on screen, or NaN when none of it can be projected.
	 * <p>
	 * Eight corners rather than the top face alone: which one comes out highest depends on where
	 * it is being looked at from, and from below the bottom of the box wins.
	 * <p>
	 * A corner behind the camera is skipped rather than giving up on the whole box. Standing close
	 * to something tall - an enderman at two blocks - puts its near-bottom corners behind the
	 * camera plane while the rest is still perfectly visible, and giving up there dropped the
	 * marker back onto the mob's face in exactly the case the box was being measured for.
	 */
	private static double silhouetteTop(Entity target, Vec3 eye, Vector3fc forward, Vector3fc up,
			double tanHalfFov, int height, double x, double y, double z) {
		double half = target.getBbWidth() / 2.0;
		double tall = target.getBbHeight();
		double top = Double.NaN;
		for (int corner = 0; corner < 8; corner++) {
			Vec3 point = new Vec3(
					x + ((corner & 1) == 0 ? -half : half),
					y + ((corner & 2) == 0 ? 0 : tall),
					z + ((corner & 4) == 0 ? -half : half));
			double screenY = projectY(point, eye, forward, up, tanHalfFov, height);
			if (Double.isNaN(screenY)) {
				continue;
			}
			top = Double.isNaN(top) ? screenY : Math.min(top, screenY);
		}
		return top;
	}

	/** Where one point in the world lands down the screen. NaN once it is behind the camera. */
	private static double projectY(Vec3 point, Vec3 eye, Vector3fc forward, Vector3fc up,
			double tanHalfFov, int height) {
		Vec3 relative = point.subtract(eye);
		double depth = dot(relative, forward);
		if (depth <= 0.05) {
			return Double.NaN;
		}
		return height / 2.0 * (1 - (dot(relative, up) / depth) / tanHalfFov);
	}

	private static double dot(Vec3 a, Vector3fc b) {
		return a.x * b.x() + a.y * b.y() + a.z * b.z();
	}

	private static int argb(int rgb, float alpha) {
		return (Mth.clamp(Math.round(alpha * 255), 0, 255) << 24) | (rgb & 0xFFFFFF);
	}

	private static int shade(int rgb, float factor) {
		return channel(rgb, 16, factor) << 16 | channel(rgb, 8, factor) << 8 | channel(rgb, 0, factor);
	}

	private static int channel(int rgb, int shift, float factor) {
		return Mth.clamp(Math.round(((rgb >> shift) & 0xFF) * factor), 0, 255);
	}

	private static int lighten(int rgb, float amount) {
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		r = Mth.clamp(Math.round(r + (255 - r) * amount), 0, 255);
		g = Mth.clamp(Math.round(g + (255 - g) * amount), 0, 255);
		b = Mth.clamp(Math.round(b + (255 - b) * amount), 0, 255);
		return r << 16 | g << 8 | b;
	}
}
