/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.touch;

import dev.isxander.controlify.utils.MinecraftUtil;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/**
 * Draws every finger {@link TouchInput} can see, for checking that touch reaches the mod at all (tl110):
 * a line of text at the top left with the count, the number of finger events seen and whether the mouse is
 * standing in for a finger; and for each finger a dot where it landed, a ring where it is, a line between
 * the two, and a label with its number and position in GUI pixels. Fingers are coloured by their place in
 * the order they landed, so two down at once can be told apart.
 *
 * <p>Drawn twice over: as a HUD layer in the world, and again after any screen so a finger on a menu shows
 * over the menu rather than under its shade. Off unless the Dev Functions panel's Show Fingers has been
 * pressed; the switch is not saved.
 */
public final class TouchDebugOverlay {
	/** Half the ring's width, in GUI pixels. */
	private static final int RING = 6;

	/** One per finger, by the order it landed, round again past the last. */
	private static final int[] COLORS = {0xFF55FF55, 0xFF55FFFF, 0xFFFF55FF, 0xFFFFFF55, 0xFFFF5555, 0xFF5555FF};

	private static final int WHITE = 0xFFFFFFFF;

	private static volatile boolean shown;

	private TouchDebugOverlay() {
	}

	/** Whether the overlay is drawn. */
	public static boolean shown() {
		return shown;
	}

	public static void setShown(boolean on) {
		shown = on;
	}

	/** The HUD layer: in the world with no screen up. A screen draws the overlay again on top, {@link #renderScreen}. */
	public static void renderHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (shown && MinecraftUtil.getScreen() == null) {
			draw(graphics);
		}
	}

	/** After any screen has drawn. */
	public static void renderScreen(GuiGraphicsExtractor graphics) {
		if (shown) {
			draw(graphics);
		}
	}

	private static void draw(GuiGraphicsExtractor graphics) {
		Font font = Minecraft.getInstance().font;
		int width = graphics.guiWidth();
		int height = graphics.guiHeight();
		List<TouchInput.Finger> fingers = TouchInput.fingers();

		String status = "touch: " + fingers.size() + " down, " + TouchInput.eventsSeen() + " events"
				+ (TouchInput.mouseAsFinger() ? ", mouse as finger" : "");
		graphics.text(font, status, 4, 4, WHITE, true);

		int index = 0;
		for (TouchInput.Finger finger : fingers) {
			int color = COLORS[index % COLORS.length];
			int x = Math.round(finger.x() * width);
			int y = Math.round(finger.y() * height);
			int startX = Math.round(finger.startX() * width);
			int startY = Math.round(finger.startY() * height);

			line(graphics, startX, startY, x, y, color);
			graphics.fill(startX - 1, startY - 1, startX + 2, startY + 2, color);
			ring(graphics, x, y, color);

			String label = "#" + (index + 1) + (finger.fromMouse() ? " mouse " : " ") + x + "," + y;
			int labelX = x + RING + 2;
			if (labelX + font.width(label) > width) {
				labelX = x - RING - 2 - font.width(label);
			}
			graphics.text(font, label, labelX, y - 4, color, true);
			index++;
		}
	}

	/** A one-pixel square ring, {@link #RING} out from the centre each way. */
	private static void ring(GuiGraphicsExtractor graphics, int x, int y, int color) {
		int left = x - RING;
		int top = y - RING;
		int right = x + RING + 1;
		int bottom = y + RING + 1;
		graphics.fill(left, top, right, top + 1, color);
		graphics.fill(left, bottom - 1, right, bottom, color);
		graphics.fill(left, top, left + 1, bottom, color);
		graphics.fill(right - 1, top, right, bottom, color);
	}

	/** A one-pixel line, a pixel per step along the longer axis. */
	private static void line(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color) {
		int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
		for (int step = 0; step <= steps; step++) {
			int x = steps == 0 ? x0 : x0 + Math.round((x1 - x0) * (float) step / steps);
			int y = steps == 0 ? y0 : y0 + Math.round((y1 - y0) * (float) step / steps);
			graphics.fill(x, y, x + 1, y + 1, color);
		}
	}
}
