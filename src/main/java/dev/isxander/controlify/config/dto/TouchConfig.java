/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.config.dto;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.isxander.controlify.touch.TouchControls;
import dev.isxander.controlify.touch.TouchMode;

/**
 * The touch controls' settings, as saved: their layout (tl117) - how far the stick's resting place and the five action
 * buttons are moved from where they sit by default, as fractions of the window's height, right and down positive, and
 * how big each is, as a fraction of its default size, set in the glyph editor's Touch tab - the mode they play in
 * (tl118, {@code mode}: {@code crosshair} or {@code tap}), when they are on (tl120, {@code controls}:
 * {@code automatic}, {@code on} or {@code off}), how fast a swipe turns the camera (tl123,
 * {@code camera_sensitivity}: 0 to 100, Bedrock's slider), how much it slows while looking through a spyglass (tl127,
 * {@code spyglass_damping}: 0 to 100), and whether walking into a single block jumps up it while they are on (tl124,
 * {@code auto_jump}: on by default, as Bedrock has it for touch).
 * <p>
 * Every key has a default and is left out of the file while it holds it, so a config saved before either loads as it
 * was. Out-of-range sizes and sensitivities are not refused here - a hand-edited file must not lose every other setting for one bad
 * number - but clamped when read ({@code TouchSettings.fromDTO}); a mode or a setting for when they are on that this
 * build does not know - one a later build wrote, or a typing slip - is read as the default, for the same reason.
 */
public record TouchConfig(
		float stickOffsetX,
		float stickOffsetY,
		float stickSize,
		float buttonsOffsetX,
		float buttonsOffsetY,
		float buttonSize,
		TouchMode mode,
		TouchControls controls,
		int cameraSensitivity,
		boolean autoJump,
		int spyglassDamping
) {
	/** The smallest and largest size either may be set to: half and twice its default. */
	public static final float MIN_SIZE = 0.5f;
	public static final float MAX_SIZE = 2f;

	/** The camera sensitivity's range, as Bedrock's slider has it, and its default: the middle, the speed every build before tl123 turned at. */
	public static final int MIN_SENSITIVITY = 0;
	public static final int MAX_SENSITIVITY = 100;
	public static final int DEFAULT_SENSITIVITY = 50;

	/** The spyglass damping's range, the camera sensitivity's, and its default: the middle, the game's own for the mouse. */
	public static final int MIN_DAMPING = 0;
	public static final int MAX_DAMPING = 100;
	public static final int DEFAULT_DAMPING = 50;

	public static final TouchConfig DEFAULT = new TouchConfig(0f, 0f, 1f, 0f, 0f, 1f, TouchMode.CROSSHAIR, TouchControls.AUTOMATIC,
			DEFAULT_SENSITIVITY, true, DEFAULT_DAMPING);

	/** A layout in the mode every build before tl118 played: aim crosshair; on by themselves, as tl120 has them. */
	public TouchConfig(float stickOffsetX, float stickOffsetY, float stickSize, float buttonsOffsetX, float buttonsOffsetY, float buttonSize) {
		this(stickOffsetX, stickOffsetY, stickSize, buttonsOffsetX, buttonsOffsetY, buttonSize, TouchMode.CROSSHAIR);
	}

	/** A layout and a mode, on by themselves (tl120). */
	public TouchConfig(float stickOffsetX, float stickOffsetY, float stickSize, float buttonsOffsetX, float buttonsOffsetY, float buttonSize,
			TouchMode mode) {
		this(stickOffsetX, stickOffsetY, stickSize, buttonsOffsetX, buttonsOffsetY, buttonSize, mode, TouchControls.AUTOMATIC);
	}

	/** A layout, a mode and when they are on, at the middle camera sensitivity (tl123). */
	public TouchConfig(float stickOffsetX, float stickOffsetY, float stickSize, float buttonsOffsetX, float buttonsOffsetY, float buttonSize,
			TouchMode mode, TouchControls controls) {
		this(stickOffsetX, stickOffsetY, stickSize, buttonsOffsetX, buttonsOffsetY, buttonSize, mode, controls, DEFAULT_SENSITIVITY);
	}

	/** A layout, a mode, when they are on and a camera sensitivity, with auto jump on (tl124). */
	public TouchConfig(float stickOffsetX, float stickOffsetY, float stickSize, float buttonsOffsetX, float buttonsOffsetY, float buttonSize,
			TouchMode mode, TouchControls controls, int cameraSensitivity) {
		this(stickOffsetX, stickOffsetY, stickSize, buttonsOffsetX, buttonsOffsetY, buttonSize, mode, controls, cameraSensitivity, true);
	}

	/** All but the spyglass damping, which is the middle (tl127). */
	public TouchConfig(float stickOffsetX, float stickOffsetY, float stickSize, float buttonsOffsetX, float buttonsOffsetY, float buttonSize,
			TouchMode mode, TouchControls controls, int cameraSensitivity, boolean autoJump) {
		this(stickOffsetX, stickOffsetY, stickSize, buttonsOffsetX, buttonsOffsetY, buttonSize, mode, controls, cameraSensitivity, autoJump,
				DEFAULT_DAMPING);
	}

	public static final Codec<TouchConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.FLOAT.optionalFieldOf("stick_offset_x", DEFAULT.stickOffsetX()).forGetter(TouchConfig::stickOffsetX),
			Codec.FLOAT.optionalFieldOf("stick_offset_y", DEFAULT.stickOffsetY()).forGetter(TouchConfig::stickOffsetY),
			Codec.FLOAT.optionalFieldOf("stick_size", DEFAULT.stickSize()).forGetter(TouchConfig::stickSize),
			Codec.FLOAT.optionalFieldOf("buttons_offset_x", DEFAULT.buttonsOffsetX()).forGetter(TouchConfig::buttonsOffsetX),
			Codec.FLOAT.optionalFieldOf("buttons_offset_y", DEFAULT.buttonsOffsetY()).forGetter(TouchConfig::buttonsOffsetY),
			Codec.FLOAT.optionalFieldOf("button_size", DEFAULT.buttonSize()).forGetter(TouchConfig::buttonSize),
			TouchMode.CODEC.lenientOptionalFieldOf("mode", DEFAULT.mode()).forGetter(TouchConfig::mode),
			TouchControls.CODEC.lenientOptionalFieldOf("controls", DEFAULT.controls()).forGetter(TouchConfig::controls),
			Codec.INT.optionalFieldOf("camera_sensitivity", DEFAULT.cameraSensitivity()).forGetter(TouchConfig::cameraSensitivity),
			Codec.BOOL.optionalFieldOf("auto_jump", DEFAULT.autoJump()).forGetter(TouchConfig::autoJump),
			Codec.INT.optionalFieldOf("spyglass_damping", DEFAULT.spyglassDamping()).forGetter(TouchConfig::spyglassDamping)
	).apply(instance, TouchConfig::new));
}
