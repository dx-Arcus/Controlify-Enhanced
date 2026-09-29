/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.config.dto;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.isxander.controlify.aimassist.LockBindMode;
import dev.isxander.controlify.aimassist.PushAwayMode;

/**
 * Serialised Keybind Mode settings: which mob the Lock Target bind picks, and how far F.O.V Lock
 * looks. A section of its own inside {@link TargetLockConfig}, which already had 15 of the 16 fields
 * a single record codec allows; this makes the sixteenth, so anything added to that record next has
 * to nest the same way.
 * <p>
 * F.O.V Range is deliberately not Locked Range. Locked Range is also how far a locked mob gets aim
 * assist, and picking by where you are looking wants to reach further than that without widening
 * the aim help along with it.
 * <p>
 * F.O.V Priority Range puts the mobs close by first: F.O.V Lock only offers one further out when
 * nothing within it is in view, however much nearer the crosshair the further one is. 0 turns it
 * off and leaves the angle alone to decide. A config saved without it loads the default.
 * <p>
 * Hard Push Away (tl104) lives here too, as the other way a lock is let go of by hand: {@code push_away},
 * off unless chosen, so a config saved before it loads as every build before did.
 */
public record LockBindConfig(
		LockBindMode mode,
		int fovDegrees,
		int fovRangeBlocks,
		int fovPriorityBlocks,
		PushAwayMode pushAway
) {
	/** Anything under a degree would mean the crosshair already has to be on the mob. */
	public static final int MIN_FOV_DEGREES = 1;
	/** Past a right angle it would reach behind the player, which is Proximity's job, not this one's. */
	public static final int MAX_FOV_DEGREES = 90;

	public static final LockBindConfig DEFAULT = new LockBindConfig(
			LockBindMode.PROXIMITY,
			15,
			32,
			20,
			PushAwayMode.OFF
	);

	public static final Codec<LockBindConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			LockBindMode.CODEC.optionalFieldOf("mode", DEFAULT.mode()).forGetter(LockBindConfig::mode),
			Codec.intRange(MIN_FOV_DEGREES, MAX_FOV_DEGREES).optionalFieldOf("fov_degrees", DEFAULT.fovDegrees()).forGetter(LockBindConfig::fovDegrees),
			Codec.intRange(1, TargetLockConfig.MAX_LOCKED_RANGE).optionalFieldOf("fov_range_blocks", DEFAULT.fovRangeBlocks()).forGetter(LockBindConfig::fovRangeBlocks),
			Codec.intRange(0, TargetLockConfig.MAX_LOCKED_RANGE).optionalFieldOf("fov_priority_blocks", DEFAULT.fovPriorityBlocks()).forGetter(LockBindConfig::fovPriorityBlocks),
			PushAwayMode.CODEC.optionalFieldOf("push_away", DEFAULT.pushAway()).forGetter(LockBindConfig::pushAway)
	).apply(instance, LockBindConfig::new));
}
