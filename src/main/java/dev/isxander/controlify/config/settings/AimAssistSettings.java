/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.config.settings;

import dev.isxander.controlify.aimassist.AimAssistMode;
import dev.isxander.controlify.aimassist.AimAssistTargets;
import dev.isxander.controlify.config.dto.AimAssistConfig;

import java.util.ArrayList;
import java.util.List;

/** Live aim assist settings, shown on the Aim Assist screen and read every tick while looking. */
public class AimAssistSettings {
	public AimAssistMode mode;
	public AimAssistTargets targets;
	/** Target Players: other players count as targets too, whatever {@link #targets} is. Off by default. */
	public boolean targetPlayers;

	/** How hard the assist slows and pulls, 0 to 100. */
	public int meleeStrengthPercent;
	/** Half-angle around the crosshair, in tenths of a degree. */
	public int meleeConeTenths;
	/** How far a mob can be and still be helped with, in blocks. */
	public int meleeDistanceBlocks;

	public int bowStrengthPercent;
	public int bowConeTenths;
	public int bowDistanceBlocks;

	/** Entity type ids ("minecraft:silverfish") used when {@link #targets} is CUSTOM. */
	public List<String> customTargets;

	public TargetLockSettings targetLock;
	/**
	 * Override Melee Settings: while a mob is locked, melee aim help uses the Locked settings on the
	 * Lock-On tab rather than melee's own. On by default, which is how it always was (tl89).
	 */
	public boolean lockOverridesMelee;
	/** Override Bow Settings: the same for a drawn bow or a loaded crossbow. */
	public boolean lockOverridesBow;

	/** Swing Timing Assist: swing again each time the weapon recharges, while attack is held. */
	public boolean swingTiming;
	/** Melee Snap: turn onto a target on a swing at nothing. */
	public SnapSettings meleeSnap;
	/** Ranged Snap: turn onto a target as aiming a bow or crossbow starts. */
	public SnapSettings rangedSnap;

	private AimAssistSettings() {
		apply(AimAssistConfig.DEFAULT);
		this.targetLock = TargetLockSettings.defaults();
	}

	private void apply(AimAssistConfig dto) {
		this.mode = dto.mode();
		this.targets = dto.targets();
		this.targetPlayers = dto.targetPlayers();
		this.meleeStrengthPercent = dto.meleeStrengthPercent();
		this.meleeConeTenths = dto.meleeConeTenths();
		this.meleeDistanceBlocks = dto.meleeDistanceBlocks();
		this.bowStrengthPercent = dto.bowStrengthPercent();
		this.bowConeTenths = dto.bowConeTenths();
		this.bowDistanceBlocks = dto.bowDistanceBlocks();
		this.customTargets = new ArrayList<>(dto.customTargets());
		this.swingTiming = dto.swingTiming();
		this.lockOverridesMelee = dto.lockOverridesMelee();
		this.lockOverridesBow = dto.lockOverridesBow();
		this.meleeSnap = SnapSettings.fromDTO(dto.meleeSnap());
		this.rangedSnap = SnapSettings.fromDTO(dto.rangedSnap());
	}

	public static AimAssistSettings defaults() {
		return new AimAssistSettings();
	}

	public static AimAssistSettings fromDTO(AimAssistConfig dto) {
		AimAssistSettings settings = new AimAssistSettings();
		settings.apply(dto);
		settings.targetLock = TargetLockSettings.fromDTO(dto.targetLock());
		return settings;
	}

	public AimAssistConfig toDTO() {
		return new AimAssistConfig(
				mode,
				targets,
				meleeStrengthPercent,
				meleeConeTenths,
				meleeDistanceBlocks,
				bowStrengthPercent,
				bowConeTenths,
				bowDistanceBlocks,
				List.copyOf(customTargets),
				targetLock.toDTO(),
				swingTiming,
				meleeSnap.toDTO(),
				rangedSnap.toDTO(),
				targetPlayers,
				lockOverridesMelee,
				lockOverridesBow
		);
	}
}
