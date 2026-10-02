/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.config.settings;

import dev.isxander.controlify.config.dto.TouchConfig;
import dev.isxander.controlify.config.dto.TouchLayoutConfig;
import dev.isxander.controlify.touch.JoystickVisibility;
import dev.isxander.controlify.touch.TouchControls;
import dev.isxander.controlify.touch.TouchMode;
import net.minecraft.util.Mth;

import java.util.Optional;

/**
 * The touch controls' settings, live: the layout (tl117) - one for each scheme since tl129, aim crosshair's in the six
 * fields below and the tap schemes' in their own - edited in the glyph editor's Touch tab, the mode (tl118),
 * when they are on (tl120), joystick visibility (tl131), the camera sensitivity (tl123), invert Y (tl130), the spyglass
 * damping (tl127), the perspective button and easy sprint (tl131), auto jump (tl124), the Pick Block button (tl128)
 * and which side of the hotbar the inventory button is on (tl133), set in the touch settings screen - all read every
 * frame by the touch controls.
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
	/** How much a swipe slows while looking through a spyglass, {@link TouchConfig#MIN_DAMPING} to {@link TouchConfig#MAX_DAMPING}. */
	public int spyglassDamping;
	/** Whether the Pick Block button is shown. */
	public boolean pickBlock;
	/** Joystick &amp; tap to interact's layout and D-pad &amp; tap to interact's (tl129). */
	public TouchLayoutConfig tapLayout;
	public TouchLayoutConfig dpadLayout;
	/** Whether a swipe up looks down and a swipe down up (tl130). */
	public boolean invertY;
	/** When the joystick is drawn, whether the camera perspective button shows, and whether the stick sprints past its rim (tl131). */
	public JoystickVisibility joystickVisibility;
	public boolean perspectiveButton;
	public boolean easySprint;
	/** Whether the inventory button sits on the left of the hotbar rather than the right (tl133). */
	public boolean leftHandedInventory;

	private TouchSettings(float stickOffsetX, float stickOffsetY, float stickSize, float buttonsOffsetX, float buttonsOffsetY,
			float buttonSize, TouchMode mode, TouchControls controls, int cameraSensitivity, boolean autoJump, int spyglassDamping,
			boolean pickBlock, Optional<TouchLayoutConfig> tapLayout, Optional<TouchLayoutConfig> dpadLayout, TouchConfig.Options options) {
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
		this.spyglassDamping = Mth.clamp(spyglassDamping, TouchConfig.MIN_DAMPING, TouchConfig.MAX_DAMPING);
		this.pickBlock = pickBlock;
		// A tap scheme with no layout of its own has aim crosshair's, as every scheme did before tl129.
		this.tapLayout = tapLayout.map(TouchSettings::read).orElse(layout(TouchMode.CROSSHAIR));
		this.dpadLayout = dpadLayout.map(TouchSettings::read).orElse(layout(TouchMode.CROSSHAIR));
		this.invertY = options.invertY();
		this.joystickVisibility = options.joystickVisibility() == null ? JoystickVisibility.ALWAYS_VISIBLE : options.joystickVisibility();
		this.perspectiveButton = options.perspectiveButton();
		this.easySprint = options.easySprint();
		this.leftHandedInventory = options.leftHandedInventory();
	}

	/** A layout as read: offsets that are not numbers at all none, sizes within what the Touch tab offers. */
	private static TouchLayoutConfig read(TouchLayoutConfig layout) {
		return new TouchLayoutConfig(offset(layout.stickOffsetX()), offset(layout.stickOffsetY()), size(layout.stickSize()),
				offset(layout.buttonsOffsetX()), offset(layout.buttonsOffsetY()), size(layout.buttonSize()));
	}

	/** A scheme's layout (tl129): aim crosshair's from the six fields above, the tap schemes' their own. */
	public TouchLayoutConfig layout(TouchMode scheme) {
		return switch (scheme) {
			case CROSSHAIR -> new TouchLayoutConfig(stickOffsetX, stickOffsetY, size(stickSize), buttonsOffsetX, buttonsOffsetY, size(buttonSize));
			case TAP -> tapLayout;
			case DPAD -> dpadLayout;
		};
	}

	/** Sets a scheme's layout (tl129), as the glyph editor's Touch tab saves it. */
	public void setLayout(TouchMode scheme, TouchLayoutConfig layout) {
		switch (scheme) {
			case CROSSHAIR -> {
				stickOffsetX = layout.stickOffsetX();
				stickOffsetY = layout.stickOffsetY();
				stickSize = size(layout.stickSize());
				buttonsOffsetX = layout.buttonsOffsetX();
				buttonsOffsetY = layout.buttonsOffsetY();
				buttonSize = size(layout.buttonSize());
			}
			case TAP -> tapLayout = read(layout);
			case DPAD -> dpadLayout = read(layout);
		}
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
				dto.cameraSensitivity(), dto.autoJump(), dto.spyglassDamping(), dto.pickBlock(), dto.tapLayout(), dto.dpadLayout(),
				dto.options());
	}

	public TouchConfig toDTO() {
		return new TouchConfig(stickOffsetX, stickOffsetY, stickSize, buttonsOffsetX, buttonsOffsetY, buttonSize, mode, controls,
				cameraSensitivity, autoJump, spyglassDamping, pickBlock, own(tapLayout), own(dpadLayout),
				new TouchConfig.Options(invertY, joystickVisibility, perspectiveButton, easySprint, leftHandedInventory));
	}

	/** A tap scheme's layout to save: none while it is aim crosshair's, which a missing one reads as. */
	private Optional<TouchLayoutConfig> own(TouchLayoutConfig layout) {
		return layout.equals(layout(TouchMode.CROSSHAIR)) ? Optional.empty() : Optional.of(layout);
	}

	/** An offset as read: a number that is not one at all is none. */
	private static float offset(float offset) {
		return Float.isFinite(offset) ? offset : 0f;
	}
}
