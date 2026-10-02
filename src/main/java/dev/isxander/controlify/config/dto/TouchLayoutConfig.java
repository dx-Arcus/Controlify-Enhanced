/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.config.dto;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One touch control scheme's layout, as saved (tl129): how far the stick's - in D-pad mode the D-pad's - resting place
 * and the buttons are moved from where they sit by default, as fractions of the window's height, right and down
 * positive, and how big each is, as a fraction of its default size. The touch section keeps the aim crosshair
 * scheme's in its own six keys, as it has since tl117, and each of the others in a section of its own under the same
 * six keys ({@code tap}, {@code dpad}); each key is left out while it holds its default.
 */
public record TouchLayoutConfig(
		float stickOffsetX,
		float stickOffsetY,
		float stickSize,
		float buttonsOffsetX,
		float buttonsOffsetY,
		float buttonSize
) {
	/** Nothing moved, nothing resized. */
	public static final TouchLayoutConfig DEFAULT = new TouchLayoutConfig(0f, 0f, 1f, 0f, 0f, 1f);

	public static final Codec<TouchLayoutConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.FLOAT.optionalFieldOf("stick_offset_x", DEFAULT.stickOffsetX()).forGetter(TouchLayoutConfig::stickOffsetX),
			Codec.FLOAT.optionalFieldOf("stick_offset_y", DEFAULT.stickOffsetY()).forGetter(TouchLayoutConfig::stickOffsetY),
			Codec.FLOAT.optionalFieldOf("stick_size", DEFAULT.stickSize()).forGetter(TouchLayoutConfig::stickSize),
			Codec.FLOAT.optionalFieldOf("buttons_offset_x", DEFAULT.buttonsOffsetX()).forGetter(TouchLayoutConfig::buttonsOffsetX),
			Codec.FLOAT.optionalFieldOf("buttons_offset_y", DEFAULT.buttonsOffsetY()).forGetter(TouchLayoutConfig::buttonsOffsetY),
			Codec.FLOAT.optionalFieldOf("button_size", DEFAULT.buttonSize()).forGetter(TouchLayoutConfig::buttonSize)
	).apply(instance, TouchLayoutConfig::new));
}
