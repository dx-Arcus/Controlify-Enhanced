/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.config.settings;

import dev.isxander.controlify.config.dto.SnapConfig;

/** Live settings for one snap, shown on the Aim Assist screen and read on every swing or draw. */
public class SnapSettings {
	/** Whether the snap runs at all. */
	public boolean enabled;
	/** How far away a mob can be for the snap to turn to it, in blocks. */
	public int rangeBlocks;
	/** How far off the crosshair a mob can be for the snap to pick it, in degrees. */
	public int angleDegrees;
	/** The snap's top speed, as a percentage of the fastest it can go. */
	public int strengthPercent;
	/** How quickly the snap gets up to its top speed. */
	public int rampUpPercent;
	/** How quickly the snap slows down again to land. */
	public int rampDownPercent;

	private SnapSettings(SnapConfig dto) {
		this.enabled = dto.enabled();
		this.rangeBlocks = dto.rangeBlocks();
		this.angleDegrees = dto.angleDegrees();
		this.strengthPercent = dto.strengthPercent();
		this.rampUpPercent = dto.rampUpPercent();
		this.rampDownPercent = dto.rampDownPercent();
	}

	public static SnapSettings fromDTO(SnapConfig dto) {
		return new SnapSettings(dto);
	}

	public SnapConfig toDTO() {
		return new SnapConfig(enabled, rangeBlocks, angleDegrees, strengthPercent, rampUpPercent, rampDownPercent);
	}
}
