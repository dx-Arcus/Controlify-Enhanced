/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.config.settings;

import dev.isxander.controlify.aimassist.LockBindMode;
import dev.isxander.controlify.aimassist.PushAwayMode;
import dev.isxander.controlify.aimassist.TargetLockMode;
import dev.isxander.controlify.config.dto.CompassConfig;
import dev.isxander.controlify.config.dto.LockBindConfig;
import dev.isxander.controlify.config.dto.TargetLockConfig;
import net.minecraft.util.Mth;

/** Live target lock settings, shown on the Aim Assist screen and read every tick while locked. */
public class TargetLockSettings {
	public boolean enabled;
	public TargetLockMode mode;

	/** Which mob the bind picks: the nearest, or the one nearest the crosshair. */
	public LockBindMode bindMode;
	/** How far off the crosshair F.O.V Lock can pick a mob, in degrees. */
	public int fovDegrees;
	/** How far away F.O.V Lock can pick a mob, in blocks. Separate from Locked Range. */
	public int fovRangeBlocks;
	/**
	 * How close a mob has to be for F.O.V Lock to offer it before any further out, in blocks.
	 * 0 turns that off.
	 */
	public int fovPriorityBlocks;
	/** What a hard push of the look stick away from the locked mob does: nothing, drops the lock, or pauses the help. */
	public PushAwayMode pushAway;

	/** Whether a target that has been left behind is eventually let go of on its own. */
	public boolean autoDrop;

	/** How far a mob that walks may be left behind before the drop timer starts, in blocks. */
	public int groundRange;
	/** The same for a mob that flies, which covers ground far faster and is usually further off. */
	public int flyingRange;
	/**
	 * How far back inside the boundary the player has to come for the drop timer to reset, as a
	 * percentage of the boundary. Without it the timer would restart every time a mob chasing the
	 * player crossed the line. A percentage rather than a block count so it stays sensible however
	 * the ranges above are set, instead of needing to be clamped against them.
	 */
	public int resetPercent;
	/** How long the player may stay outside the boundary before the lock is let go of. */
	public int dropSeconds;

	/** Whether a locked target gets aim assist from any angle, rather than only within the cone. */
	public boolean overrideCone;

	public int lockedStrengthPercent;
	/** How far away a locked mob can be and still get aim assist, in blocks. */
	public int lockedRangeBlocks;
	public int lockedSpeedPercent;

	/** Whether the marker over the locked mob is drawn at all. */
	public boolean arrowEnabled;
	/** Marker color as plain RGB, set on the color wheel. */
	public int arrowColor;
	/** How far out the marker keeps shrinking before it holds, in blocks. Set in Dev Functions. */
	public int markerFloorBlocks;

	/** Whether the compass bar along the top of the screen is drawn at all. */
	public boolean compassEnabled;
	/** Compass bar color as plain RGB, set on the same color wheel. */
	public int compassColor;
	/** Nudge from where the bar sits by default, in GUI pixels. Positive x is right, y is down. */
	public int compassOffsetX;
	public int compassOffsetY;
	/** How wide the bar is drawn, end caps included, in GUI pixels. */
	public int compassWidth;

	private TargetLockSettings() {
		apply(TargetLockConfig.DEFAULT);
	}

	private void apply(TargetLockConfig dto) {
		this.enabled = dto.enabled();
		this.mode = dto.mode();
		this.autoDrop = dto.autoDrop();
		this.groundRange = dto.groundRange();
		this.flyingRange = dto.flyingRange();
		this.resetPercent = dto.resetPercent();
		this.dropSeconds = dto.dropSeconds();
		this.overrideCone = dto.overrideCone();
		this.lockedStrengthPercent = dto.lockedStrengthPercent();
		this.lockedRangeBlocks = dto.lockedRangeBlocks();
		this.lockedSpeedPercent = dto.lockedSpeedPercent();
		this.arrowEnabled = dto.arrowEnabled();
		this.arrowColor = dto.arrowColor();
		this.markerFloorBlocks = dto.markerFloorBlocks();
		CompassConfig compass = dto.compass();
		this.compassEnabled = compass.enabled();
		this.compassColor = compass.color();
		this.compassOffsetX = compass.offsetX();
		this.compassOffsetY = compass.offsetY();
		this.compassWidth = compass.width();
		LockBindConfig bind = dto.bind();
		this.bindMode = bind.mode();
		this.fovDegrees = bind.fovDegrees();
		this.fovRangeBlocks = bind.fovRangeBlocks();
		this.fovPriorityBlocks = bind.fovPriorityBlocks();
		this.pushAway = bind.pushAway();
	}

	public static TargetLockSettings defaults() {
		return new TargetLockSettings();
	}

	public static TargetLockSettings fromDTO(TargetLockConfig dto) {
		TargetLockSettings settings = new TargetLockSettings();
		settings.apply(dto);
		return settings;
	}

	public TargetLockConfig toDTO() {
		return new TargetLockConfig(
				enabled, mode, autoDrop, groundRange, flyingRange, resetPercent, dropSeconds,
				overrideCone, lockedStrengthPercent, lockedRangeBlocks, lockedSpeedPercent,
				arrowEnabled, arrowColor, markerFloorBlocks,
				new CompassConfig(compassEnabled, compassColor, compassOffsetX, compassOffsetY, compassWidth),
				new LockBindConfig(bindMode, fovDegrees, fovRangeBlocks, fovPriorityBlocks, pushAway)
		);
	}

	/** The boundary for this target, in blocks: further for something that flies. */
	public int rangeFor(boolean flying) {
		return flying ? flyingRange : groundRange;
	}

	/**
	 * How close the player must get for the drop timer to reset. Derived from the boundary, so it
	 * can never end up larger than the boundary itself and leave the timer unresettable.
	 */
	public double resetRadiusFor(boolean flying) {
		int range = rangeFor(flying);
		return range * (1 - Mth.clamp(resetPercent, 0, 100) / 100.0);
	}
}
