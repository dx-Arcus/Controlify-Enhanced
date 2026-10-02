/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.touch;

import dev.isxander.controlify.ingame.PickBlockAccessor;
import dev.isxander.controlify.touch.TouchButtons.Box;
import dev.isxander.controlify.touch.TouchButtons.Button;
import dev.isxander.controlify.touch.TouchButtons.Icon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2fStack;

import java.util.List;

/**
 * Bedrock's Pick Block button (tl128), shown while the touch settings' Pick Block is on - off by default, as Bedrock's
 * is. Where Donny's screenshots (2 Oct 01:13) have it: below and right of where the stick rests in the two joystick
 * modes, moved and sized with the joystick; left of jump in D-pad mode, in the column beside it, jump's size. With the
 * crosshair a press picks what the crosshair is on, as the middle mouse button does. In the tap modes a press arms it -
 * drawn held - and the next tap on the world picks what is under the finger instead of using it ({@link TouchTap});
 * a second press disarms it. The pick is the game's own, through Controlify's {@link PickBlockAccessor}: in Creative
 * the block, in Survival what the player carries of it. Drawn in the touch buttons' frame: an eyedropper, the mod's
 * own picture.
 */
final class TouchPick {
	/** A side, in window heights, at the joystick's size: a touch button's. */
	static final float SIZE = 0.12f;

	/** From the stick's ring to the button, in the button's units. */
	static final int GAP = 3;

	/** A pick of what the crosshair is on. */
	private static final float[] CROSSHAIR = new float[0];

	private static final Button JUMP = button("jump");
	private static final Button SPRINT = button("sprint");

	private static boolean enabled;
	private static TouchPad.@Nullable FingerKey finger;
	private static boolean armed;
	private static long pressedAt;
	/** A pick to make this frame - the crosshair's, or a point of the window - or null. */
	private static float @Nullable [] pending;

	private TouchPick() {
	}

	private static Button button(String name) {
		for (Button each : TouchButtons.BUTTONS) {
			if (each.name().equals(name)) {
				return each;
			}
		}
		throw new IllegalStateException(name);
	}

	/** Every frame, before the fingers are read: whether the setting shows the button. Hidden, it forgets everything. */
	static void setEnabled(boolean on) {
		enabled = on;
		if (!on) {
			letGo();
		}
	}

	static boolean shown() {
		return enabled;
	}

	/** Whether the button waits for a tap on the world to pick (the tap modes). */
	static boolean armed() {
		return armed;
	}

	/** Where the button sits in this frame's window, in its pixels. */
	static Box box(TouchPad.View view) {
		if (view.mode() == TouchMode.DPAD) {
			Box jump = TouchButtons.box(JUMP, view);
			int pitch = Math.round((JUMP.offsetX() - SPRINT.offsetX()) * jump.unit());
			return new Box(Math.max(0, jump.x() - pitch), jump.y(), jump.unit());
		}
		int height = view.height();
		int scale = Math.max(1, view.scale());
		int unit = Math.max(1, Math.round(SIZE * view.layout().stickSize() * height / TouchButtons.GRID));
		int side = unit * TouchButtons.GRID;
		int radius = Math.round(view.stickRadius() * view.guiHeight());
		int[] rest = TouchPad.rest(view, view.guiWidth(), view.guiHeight(), radius);
		int x = (rest[0] + radius) * scale + GAP * unit;
		int y = (rest[1] + radius) * scale - side;
		if (view.hotbar()) {
			// Never over the hotbar or what the game stacks above it: lifted clear of them if it would reach over.
			int hotbarLeft = (view.guiWidth() / 2 - TouchButtons.HOTBAR_HALF_WIDTH) * scale;
			int hotbarRight = (view.guiWidth() / 2 + TouchButtons.HOTBAR_HALF_WIDTH) * scale;
			int stackTop = (view.guiHeight() - TouchButtons.HUD_STACK_HEIGHT) * scale;
			if (x + side > hotbarLeft && x < hotbarRight && y + side > stackTop) {
				y = stackTop - side - unit;
			}
		}
		x = Math.max(0, Math.min(x, view.width() - side));
		y = Math.max(0, Math.min(y, height - side));
		return new Box(x, y, unit);
	}

	/** A finger has just landed: if the button is shown and the finger is on it, it is pressed. True if so. */
	static boolean claim(TouchInput.Finger landed, TouchPad.View view) {
		if (!enabled || finger != null || !box(view).contains(landed.x() * view.width(), landed.y() * view.height())) {
			return false;
		}
		finger = TouchPad.FingerKey.of(landed);
		pressedAt = view.nanos();
		if (view.tap()) {
			armed = !armed;
		} else {
			armed = false;
			pending = CROSSHAIR;
		}
		return true;
	}

	/** Whether this finger is the one on the button. */
	static boolean owns(TouchPad.FingerKey key) {
		return key.equals(finger);
	}

	/** Every frame, with the fingers down: the button's finger, until it lifts. */
	static void update(List<TouchInput.Finger> fingers) {
		if (finger == null) {
			return;
		}
		for (TouchInput.Finger each : fingers) {
			if (finger.equals(TouchPad.FingerKey.of(each))) {
				return;
			}
		}
		finger = null;
	}

	/** A tap on the world, at this point of the window: while armed, a pick there instead of a use, and disarmed. True if so. */
	static boolean tapped(float x, float y) {
		if (!armed) {
			return false;
		}
		armed = false;
		pending = new float[] {x, y};
		return true;
	}

	/** The pick asked for since the last frame, once - the crosshair's (empty) or a point - or null. */
	static float @Nullable [] takePick() {
		float[] pick = pending;
		pending = null;
		return pick;
	}

	/**
	 * Makes the pick asked for, if any: the game's pick block on what the crosshair is on, or on what is under the
	 * tapped point - the game's hit there put where the pick reads it, for this frame; the game's next pick replaces it.
	 */
	static void pick(Minecraft minecraft) {
		float[] point = takePick();
		if (point == null || minecraft.player == null) {
			return;
		}
		if (point.length == 2) {
			Entity camera = minecraft.getCameraEntity();
			if (camera == null) {
				return;
			}
			minecraft.hitResult = TouchTap.pickAt(minecraft.player, camera, minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true),
					point[0], point[1]);
		}
		((PickBlockAccessor) minecraft).controlify$pickBlock();
	}

	/** Lets go: a screen is up, touch is off, or the setting is. */
	static void letGo() {
		finger = null;
		armed = false;
		pending = null;
	}

	/** Whether the button is drawn pressed: a finger on it, armed, or touched too recently to show it went down. */
	static boolean held(long now) {
		return finger != null || armed || (pressedAt != 0 && now - pressedAt < TouchButtons.MIN_PRESS_NANOS);
	}

	/** Draws the button into the window's own pixels, when shown. */
	static void render(GuiGraphicsExtractor graphics, TouchPad.View view) {
		if (!enabled) {
			return;
		}
		Matrix3x2fStack pose = graphics.pose().pushMatrix();
		pose.scale(1f / view.scale(), 1f / view.scale());
		TouchButtons.drawFramed(graphics, Icon.PICK, box(view), held(view.nanos()));
		pose.popMatrix();
	}
}
