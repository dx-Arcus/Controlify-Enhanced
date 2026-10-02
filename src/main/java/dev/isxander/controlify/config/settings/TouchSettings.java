/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.config.settings;

import dev.isxander.controlify.config.dto.TouchConfig;
import dev.isxander.controlify.touch.TouchControls;
import dev.isxander.controlify.touch.TouchMode;
import net.minecraft.util.Mth;

/**
 * The touch controls' settings, live: the layout (tl117), edited in the glyph editor's Touch tab, the mode (tl118),
 * when they are on (tl120), the camera sensitivity (tl123) and auto jump (tl124), set in the touch settings screen -
 * all read every frame by the touch controls.
 * Offsets are fractions of the window's height, right and down positive; sizes are fractions of the default size,
 * {@link TouchConfig#MIN_SIZE} to {@link TouchConfig#MAX_SIZE}.
 */
public class TouchSettings {
	public float stickOffsetX;
	public float stickOffsetY;
	public float stickSize;
	public float buttonsOffsetX;
	public float buttonsOffsetY;
	public float buttonSize;
	public TouchMode mode;
	public TouchControls controls;
	/** How fast a swipe turns the camera, {@link TouchConfig#MIN_SENSITIVITY} to {@link TouchConfig#MAX_SENSITIVITY}. */
	public int cameraSensitivity;
	/** Whether walking into a single block jumps up it while the touch controls are on. */
	public boolean autoJump;

	private TouchSettings(float stickOffsetX, float stickOffsetY, float stickSize, float buttonsOffsetX, float buttonsOffsetY,
			float buttonSize, TouchMode mode, TouchControls controls, int cameraSensitivity, boolean autoJump) {
		this.stickOffsetX = stickOffsetX;
		this.stickOffsetY = stickOffsetY;
		this.stickSize = size(stickSize);
		this.buttonsOffsetX = buttonsOffsetX;
		this.buttonsOffsetY = buttonsOffsetY;
		this.buttonSize = size(buttonSize);
		this.mode = mode == null ? TouchMode.CROSSHAIR : mode;
		this.controls = controls == null ? TouchControls.AUTOMATIC : controls;
		this.cameraSensitivity = Mth.clamp(cameraSensitivity, TouchConfig.MIN_SENSITIVITY, TouchConfig.MAX_SENSITIVITY);
		this.autoJump = autoJump;
	}

	/** A size within what the Touch tab offers; a number that is not one at all is the default. */
	public static float size(float size) {
		return Float.isFinite(size) ? Mth.clamp(size, TouchConfig.MIN_SIZE, TouchConfig.MAX_SIZE) : 1f;
	}

	public static TouchSettings defaults() {
		return fromDTO(TouchConfig.DEFAULT);
	}

	public static TouchSettings fromDTO(TouchConfig dto) {
		return new TouchSettings(offset(dto.stickOffsetX()), offset(dto.stickOffsetY()), dto.stickSize(),
				offset(dto.buttonsOffsetX()), offset(dto.buttonsOffsetY()), dto.buttonSize(), dto.mode(), dto.controls(),
				dto.cameraSensitivity(), dto.autoJump());
	}

	public TouchConfig toDTO() {
		return new TouchConfig(stickOffsetX, stickOffsetY, stickSize, buttonsOffsetX, buttonsOffsetY, buttonSize, mode, controls,
				cameraSensitivity, autoJump);
	}

	/** An offset as read: a number that is not one at all is none. */
	private static float offset(float offset) {
		return Float.isFinite(offset) ? offset : 0f;
	}
}
