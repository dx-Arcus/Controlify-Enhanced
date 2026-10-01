/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.config.dto;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.isxander.controlify.touch.TouchMode;

/**
 * The touch controls' settings, as saved: their layout (tl117) - how far the stick's resting place and the five action
 * buttons are moved from where they sit by default, as fractions of the window's height, right and down positive, and
 * how big each is, as a fraction of its default size, set in the glyph editor's Touch tab - and the mode they play in
 * (tl118, {@code mode}: {@code crosshair} or {@code tap}).
 * <p>
 * Every key has a default and is left out of the file while it holds it, so a config saved before either loads as it
 * was. Out-of-range sizes are not refused here - a hand-edited file must not lose every other setting for one bad
 * number - but clamped when read ({@code TouchSettings.fromDTO}); a mode this build does not know - one a later build
 * wrote, or a typing slip - is read as the default, for the same reason.
 */
public record TouchConfig(
		float stickOffsetX,
		float stickOffsetY,
		float stickSize,
		float buttonsOffsetX,
		float buttonsOffsetY,
		float buttonSize,
		TouchMode mode
) {
	/** The smallest and largest size either may be set to: half and twice its default. */
	public static final float MIN_SIZE = 0.5f;
	public static final float MAX_SIZE = 2f;

	public static final TouchConfig DEFAULT = new TouchConfig(0f, 0f, 1f, 0f, 0f, 1f, TouchMode.CROSSHAIR);

	/** A layout in the mode every build before tl118 played: aim crosshair. */
	public TouchConfig(float stickOffsetX, float stickOffsetY, float stickSize, float buttonsOffsetX, float buttonsOffsetY, float buttonSize) {
		this(stickOffsetX, stickOffsetY, stickSize, buttonsOffsetX, buttonsOffsetY, buttonSize, TouchMode.CROSSHAIR);
	}

	public static final Codec<TouchConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.FLOAT.optionalFieldOf("stick_offset_x", DEFAULT.stickOffsetX()).forGetter(TouchConfig::stickOffsetX),
			Codec.FLOAT.optionalFieldOf("stick_offset_y", DEFAULT.stickOffsetY()).forGetter(TouchConfig::stickOffsetY),
			Codec.FLOAT.optionalFieldOf("stick_size", DEFAULT.stickSize()).forGetter(TouchConfig::stickSize),
			Codec.FLOAT.optionalFieldOf("buttons_offset_x", DEFAULT.buttonsOffsetX()).forGetter(TouchConfig::buttonsOffsetX),
			Codec.FLOAT.optionalFieldOf("buttons_offset_y", DEFAULT.buttonsOffsetY()).forGetter(TouchConfig::buttonsOffsetY),
			Codec.FLOAT.optionalFieldOf("button_size", DEFAULT.buttonSize()).forGetter(TouchConfig::buttonSize),
			TouchMode.CODEC.lenientOptionalFieldOf("mode", DEFAULT.mode()).forGetter(TouchConfig::mode)
	).apply(instance, TouchConfig::new));
}
