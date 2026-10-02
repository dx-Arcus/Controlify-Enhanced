/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.touch;

import dev.isxander.controlify.touch.TouchButtons.Box;
import dev.isxander.controlify.touch.TouchButtons.Icon;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Bedrock's D-pad (tl121), for D-pad &amp; tap to interact: bottom left, in place of the stick, five cells touching -
 * forward, left, sneak in the middle, right, back - and while forward is held the two diagonals beside it. One finger
 * has it: the one that lands on a cell, which slides from cell to cell. Forward tapped twice sprints while it is held.
 * Drawn as the touch buttons are drawn, their size, moved and sized with the player's joystick layout (tl117).
 */
final class TouchDpad {
	/** A cell's side, in window heights: the touch buttons' (tl115). */
	static final float SIZE = 0.12f;

	/** From one cell's centre to the next, in the cells' own units: a unit apart. */
	static final int PITCH = TouchButtons.GRID + 1;

	/** The middle cell's centre, in the cells' units in from the left and up from the bottom: the cross five units clear of both edges. */
	static final float FROM_CORNER = 5f + PITCH + TouchButtons.GRID / 2f;

	/** A second tap of forward this soon after letting go of the first sprints. */
	static final long DOUBLE_TAP_NANOS = 300_000_000L;

	private static final float DIAGONAL = 0.70710677f;

	/** The cells: where each sits in the cross, its picture, and which way it walks (as the stick would, up forward). */
	enum Cell {
		FORWARD(0, -1, Icon.DPAD_UP, 0f, -1f),
		LEFT(-1, 0, Icon.DPAD_LEFT, -1f, 0f),
		SNEAK(0, 0, Icon.SNEAK, 0f, 0f),
		RIGHT(1, 0, Icon.DPAD_RIGHT, 1f, 0f),
		BACK(0, 1, Icon.DPAD_DOWN, 0f, 1f),
		FORWARD_LEFT(-1, -1, Icon.DPAD_UP_LEFT, -DIAGONAL, -DIAGONAL),
		FORWARD_RIGHT(1, -1, Icon.DPAD_UP_RIGHT, DIAGONAL, -DIAGONAL);

		final int column;
		final int row;
		final Icon icon;
		final float x;
		final float y;

		Cell(int column, int row, Icon icon, float x, float y) {
			this.column = column;
			this.row = row;
			this.icon = icon;
			this.x = x;
			this.y = y;
		}

		boolean diagonal() {
			return column != 0 && row != 0;
		}

		/** Forward or one of the diagonals beside it: what shows the diagonals, and keeps a sprint going. */
		boolean forward() {
			return row < 0;
		}
	}

	private static TouchPad.@Nullable FingerKey finger;
	/** The cell under the finger now, or null when it has slid off them all. */
	private static @Nullable Cell cell;
	private static boolean sprint;
	/** When a finger last left forward by lifting, for a double tap; 0 for never. */
	private static long forwardLiftedAt;
	/** When sneak was last touched, for the shortest press; 0 for never. */
	private static long sneakAt;

	private TouchDpad() {
	}

	/** Whether a cell shows: the five always, the diagonals while forward is held. */
	static boolean shown(Cell candidate) {
		return !candidate.diagonal() || (cell != null && cell.forward());
	}

	/** Where a cell sits in this frame's window. */
	static Box box(Cell of, TouchPad.View view) {
		int height = view.height();
		int unit = Math.max(1, Math.round(SIZE * view.layout().stickSize() * height / TouchButtons.GRID));
		int half = Math.round((PITCH + TouchButtons.GRID / 2f) * unit);
		int middleX = Math.round(FROM_CORNER * unit) + Math.round(view.layout().stickX() * height);
		int middleY = height - Math.round(FROM_CORNER * unit) + Math.round(view.layout().stickY() * height);
		// The whole cross kept inside the window, however the player moved it.
		middleX = Math.max(half, Math.min(view.width() - half, middleX));
		middleY = Math.max(half, Math.min(height - half, middleY));
		int side = unit * TouchButtons.GRID;
		return new Box(middleX + of.column * PITCH * unit - side / 2, middleY + of.row * PITCH * unit - side / 2, unit);
	}

	/** The shown cell under a point of the window, in pixels; null for none. */
	private static @Nullable Cell at(float px, float py, TouchPad.View view) {
		for (Cell candidate : Cell.values()) {
			if (shown(candidate) && box(candidate, view).contains(px, py)) {
				return candidate;
			}
		}
		return null;
	}

	/** A finger has just landed: in D-pad mode, if it is on a cell and no finger has the D-pad, it has it now. True if so. */
	static boolean claim(TouchInput.Finger landed, TouchPad.View view) {
		if (view.mode() != TouchMode.DPAD || finger != null) {
			return false;
		}
		Cell under = at(landed.x() * view.width(), landed.y() * view.height(), view);
		if (under == null) {
			return false;
		}
		finger = TouchPad.FingerKey.of(landed);
		cell = under;
		sprint = under == Cell.FORWARD && forwardLiftedAt != 0 && view.nanos() - forwardLiftedAt < DOUBLE_TAP_NANOS;
		if (under == Cell.SNEAK) {
			sneakAt = view.nanos();
		}
		return true;
	}

	/** Whether this finger has the D-pad. */
	static boolean owns(TouchPad.FingerKey key) {
		return key.equals(finger);
	}

	/** Every frame, with the fingers that are down: the D-pad's finger slides from cell to cell, or has lifted. */
	static void update(List<TouchInput.Finger> fingers, TouchPad.View view) {
		if (finger == null) {
			return;
		}
		TouchInput.Finger now = null;
		for (TouchInput.Finger candidate : fingers) {
			if (finger.equals(TouchPad.FingerKey.of(candidate))) {
				now = candidate;
			}
		}
		if (now == null) {
			if (cell != null && cell.forward()) {
				forwardLiftedAt = view.nanos();
			}
			finger = null;
			cell = null;
			sprint = false;
			return;
		}
		float px = now.x() * view.width();
		float py = now.y() * view.height();
		if (cell != null && box(cell, view).contains(px, py)) {
			return;
		}
		Cell under = at(px, py, view);
		if (under == Cell.SNEAK && cell != Cell.SNEAK) {
			sneakAt = view.nanos();
		}
		cell = under;
		if (cell == null || !cell.forward()) {
			sprint = false;
		}
	}

	/** Which way the D-pad walks, as the stick's x and y, up forward. */
	static float[] direction() {
		return cell == null ? new float[] {0f, 0f} : new float[] {cell.x, cell.y};
	}

	/** Whether forward was tapped twice and is held: sprint. */
	static boolean sprinting() {
		return sprint;
	}

	/** Whether sneak is pressed: a finger on it, or touched too recently to let go - so the game's tick sees a tap. */
	static boolean sneakHeld(long now) {
		return cell == Cell.SNEAK || (sneakAt != 0 && now - sneakAt < TouchButtons.MIN_PRESS_NANOS);
	}

	/** Lets go: a screen is up, or touch is off. */
	static void letGo() {
		finger = null;
		cell = null;
		sprint = false;
		sneakAt = 0L;
	}

	/** Draws the shown cells into the window's own pixels, the one under the finger held; sneak shows flying down while the player flies. */
	static void render(GuiGraphicsExtractor graphics, TouchPad.View view) {
		Matrix3x2fStack pose = graphics.pose().pushMatrix();
		pose.scale(1f / view.scale(), 1f / view.scale());
		for (Cell each : Cell.values()) {
			if (!shown(each)) {
				continue;
			}
			Icon icon = each == Cell.SNEAK && view.flying() ? Icon.FLY_DOWN : each.icon;
			TouchButtons.drawFramed(graphics, icon, box(each, view), each == cell || (each == Cell.SNEAK && sneakHeld(view.nanos())));
		}
		pose.popMatrix();
	}
}
