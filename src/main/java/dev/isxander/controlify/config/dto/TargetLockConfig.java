/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.config.dto;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.isxander.controlify.aimassist.TargetLockMode;

/**
 * Serialised target lock settings. A section of its own rather than more fields on
 * {@link AimAssistConfig}, which would otherwise run into the 16-field limit a single record
 * codec allows.
 * <p>
 * This record is now at that limit itself: {@code bind} is the sixteenth field. Anything added
 * here next has to go into a nested section, the way {@link CompassConfig} and
 * {@link LockBindConfig} do.
 */
public record TargetLockConfig(
		boolean enabled,
		TargetLockMode mode,
		boolean autoDrop,
		int groundRange,
		int flyingRange,
		int resetPercent,
		int dropSeconds,
		boolean overrideCone,
		int lockedStrengthPercent,
		int lockedRangeBlocks,
		int lockedSpeedPercent,
		boolean arrowEnabled,
		int arrowColor,
		int markerFloorBlocks,
		CompassConfig compass,
		LockBindConfig bind
) {
	/** Widest a range slider goes. Well past any sensible value, but it costs nothing to allow. */
	public static final int MAX_RANGE = 500;
	public static final int MAX_LOCKED_RANGE = 160;
	/** Default marker color, as plain RGB. */
	public static final int DEFAULT_ARROW_COLOR = 0xFF3B30;

	/**
	 * How far out the marker keeps shrinking before it holds, in blocks. Under the plain
	 * 1/distance falloff it shrinks with the mob it sits over, so this one number decides how
	 * small it ever gets: it ends up at four blocks over this, as a fraction of full size.
	 * <p>
	 * Not on the Aim Assist screen - it is a feel to be found rather than a setting to be set, so
	 * it lives in Dev Functions until it is settled.
	 */
	public static final int DEFAULT_MARKER_FLOOR = 30;
	/**
	 * Any closer than this and the marker would be held at over half full size everywhere past a
	 * few blocks, which is no falloff at all. Below four it would be asked to draw larger than
	 * full size, which the shrinking maths has no meaning for.
	 */
	public static final int MIN_MARKER_FLOOR = 8;
	/** Past here the marker is under a pixel wide and there is nothing left to see. */
	public static final int MAX_MARKER_FLOOR = 120;

	public static final TargetLockConfig DEFAULT = new TargetLockConfig(
			false,
			TargetLockMode.KEYBIND,
			true,
			25,
			50,
			40,
			15,
			false,
			50,
			32,
			50,
			true,
			DEFAULT_ARROW_COLOR,
			DEFAULT_MARKER_FLOOR,
			CompassConfig.DEFAULT,
			LockBindConfig.DEFAULT
	);

	public static final Codec<TargetLockConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.BOOL.optionalFieldOf("enabled", DEFAULT.enabled()).forGetter(TargetLockConfig::enabled),
			TargetLockMode.CODEC.optionalFieldOf("mode", DEFAULT.mode()).forGetter(TargetLockConfig::mode),
			Codec.BOOL.optionalFieldOf("auto_drop", DEFAULT.autoDrop()).forGetter(TargetLockConfig::autoDrop),
			Codec.intRange(0, MAX_RANGE).optionalFieldOf("ground_range", DEFAULT.groundRange()).forGetter(TargetLockConfig::groundRange),
			Codec.intRange(0, MAX_RANGE).optionalFieldOf("flying_range", DEFAULT.flyingRange()).forGetter(TargetLockConfig::flyingRange),
			Codec.intRange(0, 100).optionalFieldOf("reset_percent", DEFAULT.resetPercent()).forGetter(TargetLockConfig::resetPercent),
			Codec.intRange(1, 300).optionalFieldOf("drop_seconds", DEFAULT.dropSeconds()).forGetter(TargetLockConfig::dropSeconds),
			Codec.BOOL.optionalFieldOf("override_cone", DEFAULT.overrideCone()).forGetter(TargetLockConfig::overrideCone),
			Codec.intRange(0, 100).optionalFieldOf("locked_strength_percent", DEFAULT.lockedStrengthPercent()).forGetter(TargetLockConfig::lockedStrengthPercent),
			Codec.intRange(1, MAX_LOCKED_RANGE).optionalFieldOf("locked_range_blocks", DEFAULT.lockedRangeBlocks()).forGetter(TargetLockConfig::lockedRangeBlocks),
			Codec.intRange(0, 100).optionalFieldOf("locked_speed_percent", DEFAULT.lockedSpeedPercent()).forGetter(TargetLockConfig::lockedSpeedPercent),
			Codec.BOOL.optionalFieldOf("arrow_enabled", DEFAULT.arrowEnabled()).forGetter(TargetLockConfig::arrowEnabled),
			Codec.intRange(0, 0xFFFFFF).optionalFieldOf("arrow_colour", DEFAULT.arrowColor()).forGetter(TargetLockConfig::arrowColor),
			Codec.intRange(MIN_MARKER_FLOOR, MAX_MARKER_FLOOR).optionalFieldOf("marker_floor_blocks", DEFAULT.markerFloorBlocks()).forGetter(TargetLockConfig::markerFloorBlocks),
			CompassConfig.CODEC.optionalFieldOf("compass", DEFAULT.compass()).forGetter(TargetLockConfig::compass),
			LockBindConfig.CODEC.optionalFieldOf("bind", DEFAULT.bind()).forGetter(TargetLockConfig::bind)
	).apply(instance, TargetLockConfig::new));
}
