/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.aimassist;

import dev.isxander.controlify.Controlify;
import dev.isxander.controlify.config.dto.CompassConfig;
import dev.isxander.controlify.config.settings.TargetLockSettings;
import dev.isxander.controlify.utils.MinecraftUtil;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.stream.IntStream;

/**
 * A strip along the top of the screen showing where the locked mob is by bearing, so it can be
 * found again without sweeping the camera around.
 * <p>
 * Drawn as flat rectangles rather than blitted from a texture: it stays crisp at every GUI scale,
 * the marker can slide along it a real pixel at a time, the player's chosen color can be shaded
 * into a tube without shipping one texture per hue, and the whole thing can be drawn at any width
 * without a texture to stretch.
 * <p>
 * It is only on screen while something is actually locked. A compass showing nothing is worse than
 * no compass, and this is a HUD element on a game people play looking at the middle of the screen.
 */
public final class CompassBarRenderer {
	public static final int HEIGHT = 11;
	/** Height of the whole thing including the name and distance line underneath. */
	public static final int TOTAL_HEIGHT = HEIGHT + 2 + 9;

	/** Where the top of the frame sits before the player's own offset is added. */
	public static final int TOP = 4;

	/** The bar runs under both caps, so no background shows through at either end. */
	private static final int BAR_INSET = 4;

	/**
	 * How far either side of straight ahead the bar covers, in degrees. A little wider than the
	 * default field of view, so a mob that has just left the screen is still on the bar rather
	 * than pinned to the end the moment it goes.
	 */
	private static final double HALF_ARC = 90;

	// The frame is charcoal whatever color the bar is set to, so it never fights the choice.
	private static final int OUTLINE = 0xFF232323;
	private static final int TRACK = 0xFF14181A;
	private static final int CAP_TOP = 0xFF545454;
	private static final int CAP_BOTTOM = 0xFF2E2E2E;
	private static final int CAP_LIT = 0xFF6A6A6A;
	private static final int CAP_SHADE = 0xFF272727;
	private static final int ARM = 0xFF3E3E3E;
	private static final int ARM_LIT = 0xFF5A5A5A;
	private static final int ARM_SHADE = 0xFF262626;

	private static final int WHITE = 0xFFFFFFFF;
	/** Candidates are meant to be read past, not read. */
	private static final int CANDIDATE = 0x70000000;

	/** A cap's post, row by row from CAP_TOP down to CAP_BOTTOM: nine colors, worked out once rather than every frame. */
	private static final int[] CAP_ROWS = IntStream.range(0, 9).map(row -> lerpColor(CAP_TOP, CAP_BOTTOM, row / 8f)).toArray();

	private CompassBarRenderer() {
	}

	public static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		Entity target = TargetLock.locked();

		if (player == null || target == null || !TargetLock.active() || MinecraftUtil.getScreen() != null) {
			return;
		}
		TargetLockSettings settings = Controlify.instance().config().getSettings().aimAssistSettings().targetLock;
		if (!settings.compassEnabled) {
			return;
		}

		float partial = deltaTracker.getGameTimeDeltaPartialTick(false);

		//? if >=26.2 {
		Camera camera = minecraft.gameRenderer.mainCamera();
		//?} else {
		/*Camera camera = minecraft.gameRenderer.getMainCamera();
		*///?}
		double facing = facingAngle(camera, player);
		Vec3 eye = camera.position();

		int width = barWidth(settings.compassWidth, graphics.guiWidth());
		int left = leftEdge(graphics.guiWidth(), width, settings.compassOffsetX);
		int top = TOP + settings.compassOffsetY;
		drawFrame(graphics, left, top, width, settings.compassColor);

		// Candidates first, so the locked marker is never drawn under one of them.
		for (Entity candidate : TargetLock.candidates()) {
			if (candidate == target) {
				continue;
			}
			double candidateBearing = bearing(eye, facing, position(candidate, partial));
			if (Math.abs(candidateBearing) > HALF_ARC) {
				continue;
			}
			int x = barX(left, width, candidateBearing);
			graphics.fill(x, top + 4, x + 1, top + 7, CANDIDATE);
		}

		double bearing = bearing(eye, facing, position(target, partial));
		if (Math.abs(bearing) > HALF_ARC) {
			drawBehindChevron(graphics, left, top, width, bearing > 0);
		} else {
			drawMarker(graphics, barX(left, width, bearing), top, settings.arrowColor);
		}

		StringBuilder right = new StringBuilder();
		right.append(Mth.ceil(player.distanceTo(target))).append('m');
		double countdown = TargetLock.dropCountdown();
		if (countdown >= 0) {
			right.append(" · ").append(String.format("%.1fs", countdown));
		}
		drawReadout(graphics, minecraft.font, left, top, width, target.getDisplayName(), right.toString());
	}

	/**
	 * The same bar the HUD draws, for the screen that sets its position and width. Takes a bearing
	 * and its text rather than reading a locked mob, because nothing is locked while that screen is
	 * open — but every pixel of geometry below is the real thing, so what is nudged into place is
	 * what turns up in game.
	 */
	public static void extractPreview(GuiGraphicsExtractor graphics, Font font, int guiWidth,
			int offsetX, int offsetY, int width, int color, int markerColor,
			double bearingDegrees, Component name, String readout) {
		int clamped = barWidth(width, guiWidth);
		int left = leftEdge(guiWidth, clamped, offsetX);
		int top = TOP + offsetY;

		drawFrame(graphics, left, top, clamped, color);
		if (Math.abs(bearingDegrees) > HALF_ARC) {
			drawBehindChevron(graphics, left, top, clamped, bearingDegrees > 0);
		} else {
			drawMarker(graphics, barX(left, clamped, bearingDegrees), top, markerColor);
		}
		drawReadout(graphics, font, left, top, clamped, name, readout);
	}

	/** Never wider than the screen, whatever the setting says, so it cannot run off both ends. */
	public static int barWidth(int setting, int guiWidth) {
		return Math.min(Mth.clamp(setting, CompassConfig.MIN_WIDTH, CompassConfig.MAX_WIDTH), Math.max(guiWidth, CompassConfig.MIN_WIDTH));
	}

	/** Centred on the screen, then nudged by however far the player dragged it. */
	public static int leftEdge(int guiWidth, int width, int offsetX) {
		return (guiWidth - width) / 2 + offsetX;
	}

	/** Where the mob is this frame, interpolated so the marker doesn't step at 20 ticks a second. */
	private static Vec3 position(Entity entity, float partial) {
		return new Vec3(
				Mth.lerp(partial, entity.xOld, entity.getX()),
				Mth.lerp(partial, entity.yOld, entity.getY()),
				Mth.lerp(partial, entity.zOld, entity.getZ()));
	}

	/**
	 * Which way the view is pointing, in the same space as {@link Math#atan2}.
	 * <p>
	 * Taken off the camera rather than the player so third person and view bob move the bar with
	 * the picture. Looking straight up or down leaves the camera with almost no horizontal
	 * component to take an angle from, so the player's own yaw stands in for those few degrees.
	 */
	private static double facingAngle(Camera camera, LocalPlayer player) {
		Vector3fc forward = camera.forwardVector();
		double x = forward.x();
		double z = forward.z();
		if (x * x + z * z < 1.0e-6) {
			double yaw = Math.toRadians(player.getYRot());
			return Math.atan2(Math.cos(yaw), -Math.sin(yaw));
		}
		return Math.atan2(z, x);
	}

	/** Degrees the mob sits off straight ahead: negative to the left, positive to the right. */
	private static double bearing(Vec3 eye, double facing, Vec3 target) {
		double angle = Math.atan2(target.z - eye.z, target.x - eye.x);
		return Mth.wrapDegrees(Math.toDegrees(angle - facing));
	}

	private static int barX(int left, int width, double bearing) {
		double fraction = 0.5 + bearing / (HALF_ARC * 2);
		int span = width - BAR_INSET * 2 - 1;
		return left + BAR_INSET + (int) Math.round(fraction * span);
	}

	private static void drawFrame(GuiGraphicsExtractor graphics, int left, int top, int width, int color) {
		int base = 0xFF000000 | color;
		int barLeft = left + BAR_INSET;
		int barRight = left + width - BAR_INSET;
		graphics.fill(barLeft, top + 1, barRight, top + 10, OUTLINE);
		graphics.fill(barLeft + 1, top + 2, barRight - 1, top + 9, TRACK);

		int fillLeft = barLeft + 2;
		int fillRight = barRight - 2;
		// Five rows shaded like a tube: a bright sheen on top, the chosen color through the
		// middle, a darker edge underneath. All of it worked out from the one hex the player set.
		graphics.fill(fillLeft, top + 3, fillRight, top + 4, shade(color, 1.62f));
		graphics.fill(fillLeft, top + 4, fillRight, top + 5, shade(color, 1.28f));
		graphics.fill(fillLeft, top + 5, fillRight, top + 7, base);
		graphics.fill(fillLeft, top + 7, fillRight, top + 8, shade(color, 0.66f));

		drawCap(graphics, left, top, width, false);
		drawCap(graphics, left, top, width, true);
	}

	/**
	 * One bracket cap: a post with an arm stepping over the bar's top and bottom edge. The right
	 * hand one is the left mirrored, so the lit row stays on top at both ends.
	 */
	private static void drawCap(GuiGraphicsExtractor graphics, int left, int top, int width, boolean right) {
		int post = left + (right ? width - 4 : 0);
		int arm = left + (right ? width - 5 : 4);

		graphics.fill(post, top, post + 4, top + HEIGHT, OUTLINE);
		for (int row = 0; row < 9; row++) {
			graphics.fill(post + 1, top + 1 + row, post + 3, top + 2 + row, CAP_ROWS[row]);
		}
		graphics.fill(post + 1, top + 1, post + 3, top + 2, CAP_LIT);
		graphics.fill(post + 1, top + 9, post + 3, top + 10, CAP_SHADE);

		graphics.fill(arm, top, arm + 1, top + 2, ARM);
		graphics.fill(arm, top, arm + 1, top + 1, ARM_LIT);
		graphics.fill(arm, top + 9, arm + 1, top + HEIGHT, ARM);
		graphics.fill(arm, top + 10, arm + 1, top + HEIGHT, ARM_SHADE);
	}

	/**
	 * The locked mob. White with a dark outline so it reads on any bar color, capped with the
	 * marker's own color so the thing on the bar and the thing over the mob are recognisably a
	 * pair even when the two colors are set close together.
	 */
	private static void drawMarker(GuiGraphicsExtractor graphics, int x, int top, int markerColor) {
		graphics.fill(x - 2, top + 2, x - 1, top + 9, OUTLINE);
		graphics.fill(x + 2, top + 2, x + 3, top + 9, OUTLINE);
		graphics.fill(x - 1, top + 2, x + 2, top + 9, WHITE);
		graphics.fill(x - 1, top + 3, x + 2, top + 5, 0xFF000000 | markerColor);
	}

	/** Pinned to whichever end it went past, pointing the way round you would have to turn. */
	private static void drawBehindChevron(GuiGraphicsExtractor graphics, int left, int top, int width, boolean toTheRight) {
		int tip = left + (toTheRight ? width - BAR_INSET - 3 : BAR_INSET + 2);
		int step = toTheRight ? 1 : -1;
		for (int i = 0; i < 3; i++) {
			int half = 2 - i;
			int x = tip + step * i;
			graphics.fill(x, top + 4 - half, x + 1, top + 6 + half, OUTLINE);
		}
		for (int i = 0; i < 3; i++) {
			int half = 2 - i;
			int x = tip + step * i;
			graphics.fill(x, top + 5 - half, x + 1, top + 6 + half, WHITE);
		}
	}

	/** The mob's name on the left, how far off it is on the right, with the countdown once it runs. */
	private static void drawReadout(GuiGraphicsExtractor graphics, Font font, int left, int top, int width,
			Component name, String readout) {
		int y = top + HEIGHT + 2;
		graphics.text(font, name, left, y, WHITE, true);
		graphics.text(font, readout, left + width - font.width(readout), y, WHITE, true);
	}

	private static int lerpColor(int from, int to, float t) {
		int r = Mth.lerpInt(t, (from >> 16) & 0xFF, (to >> 16) & 0xFF);
		int g = Mth.lerpInt(t, (from >> 8) & 0xFF, (to >> 8) & 0xFF);
		int b = Mth.lerpInt(t, from & 0xFF, to & 0xFF);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	/** Lightens above 1, darkens below, so one hex gives the whole tube. */
	private static int shade(int rgb, float factor) {
		return 0xFF000000
				| channel((rgb >> 16) & 0xFF, factor) << 16
				| channel((rgb >> 8) & 0xFF, factor) << 8
				| channel(rgb & 0xFF, factor);
	}

	private static int channel(int value, float factor) {
		float scaled = factor >= 1 ? value + (255 - value) * (factor - 1) : value * factor;
		return Mth.clamp(Math.round(scaled), 0, 255);
	}
}
