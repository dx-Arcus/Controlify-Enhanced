/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.utils.render;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;

import java.util.ArrayList;
import java.util.List;

/**
 * Text that runs through the whole spectrum, flows sideways, and has a highlight sweeping along it.
 * <p>
 * It is a {@link Component} in its own right rather than something the screen paints, which is what
 * lets it be handed to code that only knows about components - a YACL option's name, a button's
 * label - and still move. The trick is {@link #getVisualOrderText()}: Minecraft asks a component
 * for that every time it draws it, so reading the clock when the characters are handed over, rather
 * than when the sequence is made, is an animation with nothing having to drive it.
 * <p>
 * <b>Everything here is built to allocate nothing while drawing.</b> The first version of this
 * class worked out a color per character from scratch on every single draw, and rebuilt its whole
 * sibling list on every walk of the component tree - and a tree walk is not rare, it is what
 * wrapping, measuring and narrating all do. At a few hundred characters on screen and a frame rate
 * in the hundreds that is millions of short-lived objects a second, which showed up as the screen
 * flashing while scrolling and, given long enough, a render thread that stopped answering. So the
 * colors now come from a table built once, the characters are handed straight to the sink instead
 * of being composed out of one sequence each, and the siblings are kept and reused.
 * <p>
 * Text that has to be wrapped goes down a different path - the wrapper visits the component tree
 * rather than asking for visual order text - so the colors are also put on one sibling per
 * character. Wrapped text may therefore come out still rather than moving, depending on whether
 * whoever wrapped it kept the result, but it is never left plain.
 * <p>
 * Every hue is lifted to a floor of brightness before it is used. Straight HSV puts blue and
 * violet at around a third of the brightness of yellow, which on a dark screen reads as a hole in
 * the middle of a word; mixing those hues towards white until they measure up keeps a line evenly
 * readable all the way along. Nothing dull can come out of it, because the color always starts
 * on the outside of the wheel - there are no browns, greys or blacks anywhere in its range.
 */
public final class RainbowText implements Component {
	/** How far round the wheel each character moves: about twenty two characters to a full turn. */
	private static final double SPREAD = 0.045;
	/** One full turn of the spectrum, in milliseconds. */
	private static final double FLOW_MS = 3500;
	/**
	 * How long the highlight spends on each character, so a pass takes as long as the line is: a
	 * label and a paragraph get the same pace rather than the same time, which on a paragraph was
	 * a blur (tl107). Ten characters a second - the pace "Open Color Wheels" always had.
	 */
	private static final double SHINE_MS_PER_CHAR = 99;
	/** How wide the highlight is, in characters. */
	private static final double SHINE_WIDTH = 2.6;
	/** How far towards white the crest of the highlight goes. */
	private static final double SHINE_STRENGTH = 0.8;
	/** Kept off full so the color never washes out to white on its own. */
	private static final double SATURATION = 0.85;
	/**
	 * The brightness every character is brought up to, measured the way the eye weighs the three
	 * channels. Minecraft's own grey body text sits at about 0.66, so this is in the same country.
	 */
	private static final double FLOOR = 0.55;

	/** Steps round the wheel in the table. Finer than the eye can tell at text size. */
	private static final int HUE_STEPS = 512;
	/** Steps from no highlight to the crest of one. */
	private static final int SHINE_STEPS = 25;
	/**
	 * Every color this class can ever produce, worked out once. Twelve thousand odd styles is a
	 * megabyte or so held for the life of the game, traded against allocating one per character
	 * per draw forever. Against the exact arithmetic it replaces, the worst any channel is out by
	 * is 6 of 255 and the average is 1.2 - well under what the eye can pick out on moving text.
	 */
	private static final Style[] PALETTE = buildPalette();

	/** How long a built sibling list is reused before the colors are moved on, in milliseconds. */
	private static final double SIBLING_HOLD_MS = 50;

	private final Component base;
	/** Shifts one line's highlight away from the next, so a block of them does not march in step. */
	private final double offsetMs;
	/** The characters, taken once: the text itself never changes, only its color does. */
	private final int[] points;

	private List<Component> siblings;
	private long siblingsBuiltAt = Long.MIN_VALUE;

	private RainbowText(Component base, double offsetMs) {
		this.base = base;
		this.offsetMs = offsetMs;
		this.points = base.getString().codePoints().toArray();
	}

	public static Component of(Component base) {
		return new RainbowText(base, 0);
	}

	/** @param line which line of a stack this is, so each one is a little behind the last */
	public static Component of(Component base, int line) {
		return new RainbowText(base, line * 350.0);
	}

	/**
	 * A new handle each time, because whoever draws it is entitled to hold on to what it hands
	 * back - but an empty one. Nothing is worked out until the characters are actually asked for,
	 * which is what keeps the colors moving without anything being rebuilt.
	 */
	@Override
	public FormattedCharSequence getVisualOrderText() {
		return this::emit;
	}

	private boolean emit(FormattedCharSink sink) {
		double now = time();
		int length = points.length;
		for (int i = 0; i < length; i++) {
			// The real index, not zero for every character: whoever is measuring or splitting this
			// needs to be able to tell the characters apart by position.
			if (!sink.accept(i, styleAt(i, length, now), points[i])) {
				return false;
			}
		}
		return true;
	}

	/** Nothing of its own: all the text lives on the siblings, one per character. */
	@Override
	public ComponentContents getContents() {
		return PlainTextContents.EMPTY;
	}

	/**
	 * Kept and reused. A walk of the component tree is what wrapping, measuring, narrating and
	 * {@link #getString()} all do, so this is called far more often than anything is drawn, and
	 * building a component per character every time was the most expensive thing this class did.
	 */
	@Override
	public List<Component> getSiblings() {
		long bucket = (long) (time() / SIBLING_HOLD_MS);
		List<Component> built = siblings;
		if (built == null || bucket != siblingsBuiltAt) {
			built = buildSiblings();
			siblings = built;
			siblingsBuiltAt = bucket;
		}
		return built;
	}

	private List<Component> buildSiblings() {
		double now = time();
		List<Component> built = new ArrayList<>(points.length);
		for (int i = 0; i < points.length; i++) {
			built.add(Component.literal(new String(Character.toChars(points[i])))
					.setStyle(styleAt(i, points.length, now)));
		}
		return List.copyOf(built);
	}

	@Override
	public Style getStyle() {
		return Style.EMPTY;
	}

	private double time() {
		return System.nanoTime() / 1_000_000.0 + offsetMs;
	}

	/**
	 * @param index which character along the line this is
	 * @param length how many there are, so the highlight can be made to cross all of them
	 */
	private Style styleAt(int index, int length, double now) {
		// Round the wheel with position, and backwards with time - which is what makes a given
		// color travel forwards along the line rather than the pattern simply flickering.
		double hue = frac(SPREAD * index - now / FLOW_MS);

		// The highlight starts off the near end and finishes off the far one, so it enters and
		// leaves rather than appearing in the middle.
		double span = length + SHINE_WIDTH * 2;
		double travel = frac(now / (span * SHINE_MS_PER_CHAR)) * span - SHINE_WIDTH;
		double distance = (index - travel) / SHINE_WIDTH;
		double shine = Math.exp(-distance * distance * 4) * SHINE_STRENGTH;

		int hueStep = (int) (hue * HUE_STEPS) % HUE_STEPS;
		int shineStep = (int) Math.round(shine / SHINE_STRENGTH * (SHINE_STEPS - 1));
		// Clamped rather than trusted: a rounding edge landing one past the end would be an index
		// out of bounds thrown from inside a draw, which is a hard crash rather than a wrong color.
		shineStep = Math.max(0, Math.min(SHINE_STEPS - 1, shineStep));
		return PALETTE[hueStep * SHINE_STEPS + shineStep];
	}

	private static Style[] buildPalette() {
		Style[] palette = new Style[HUE_STEPS * SHINE_STEPS];
		double[] rgb = new double[3];
		for (int hueStep = 0; hueStep < HUE_STEPS; hueStep++) {
			for (int shineStep = 0; shineStep < SHINE_STEPS; shineStep++) {
				fromHue((double) hueStep / HUE_STEPS, rgb);
				lift(rgb, FLOOR);
				// Spread over the range the highlight actually reaches rather than all the way to
				// white, so none of the table is spent on brightnesses that can never come up.
				towardsWhite(rgb, (double) shineStep / (SHINE_STEPS - 1) * SHINE_STRENGTH);
				palette[hueStep * SHINE_STEPS + shineStep] = Style.EMPTY.withColor(pack(rgb));
			}
		}
		return palette;
	}

	private static double frac(double value) {
		double part = value % 1.0;
		return part < 0 ? part + 1 : part;
	}

	/** Full brightness, fixed saturation: the outside of the color wheel and nothing else. */
	private static void fromHue(double hue, double[] rgb) {
		double sector = hue * 6;
		double offset = sector - Math.floor(sector);
		double dip = 1 - SATURATION;
		double falling = 1 - offset * SATURATION;
		double rising = 1 - (1 - offset) * SATURATION;
		switch ((int) sector % 6) {
			case 0 -> set(rgb, 1, rising, dip);
			case 1 -> set(rgb, falling, 1, dip);
			case 2 -> set(rgb, dip, 1, rising);
			case 3 -> set(rgb, dip, falling, 1);
			case 4 -> set(rgb, rising, dip, 1);
			default -> set(rgb, 1, dip, falling);
		}
	}

	private static void set(double[] rgb, double red, double green, double blue) {
		rgb[0] = red;
		rgb[1] = green;
		rgb[2] = blue;
	}

	/** How bright a color looks, rather than how large its numbers are. */
	private static double brightness(double[] rgb) {
		return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
	}

	private static void lift(double[] rgb, double floor) {
		double have = brightness(rgb);
		if (have < floor) {
			// Solving towards white for the amount that lands exactly on the floor, so the dark
			// hues are raised no further than they need to be and keep as much color as they can.
			towardsWhite(rgb, (floor - have) / (1 - have));
		}
	}

	private static void towardsWhite(double[] rgb, double amount) {
		for (int i = 0; i < 3; i++) {
			rgb[i] += (1 - rgb[i]) * amount;
		}
	}

	private static int pack(double[] rgb) {
		int red = (int) Math.round(Math.min(1, rgb[0]) * 255);
		int green = (int) Math.round(Math.min(1, rgb[1]) * 255);
		int blue = (int) Math.round(Math.min(1, rgb[2]) * 255);
		return red << 16 | green << 8 | blue;
	}
}
