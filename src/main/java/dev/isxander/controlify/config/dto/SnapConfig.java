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
 * Serialised settings for one of the two snaps: Melee Snap, on a swing at nothing, and Ranged
 * Snap, on starting to aim a bow or crossbow. The same six settings twice, so one record, with a
 * codec made for each - they differ in their defaults and in how far their range can go.
 * <p>
 * Strength is the snap's top speed; the ramps are how quickly it gets up to that speed and slows
 * again to land. All three are percentages. Range is blocks, angle whole degrees.
 */
public record SnapConfig(
		boolean enabled,
		int rangeBlocks,
		int angleDegrees,
		int strengthPercent,
		int rampUpPercent,
		int rampDownPercent
) {
	public static final int MIN_ANGLE_DEGREES = 1;
	/** All the way round: a mob directly behind is 180 degrees off the crosshair. */
	public static final int MAX_ANGLE_DEGREES = 180;

	/**
	 * Both off until switched on, like Ignore Crosshair Cone: a snap turns the camera for the
	 * player, which nothing else in aim assist does by itself.
	 */
	public static final SnapConfig MELEE_DEFAULT = new SnapConfig(false, 6, 60, 50, 50, 50);
	public static final SnapConfig RANGED_DEFAULT = new SnapConfig(false, 32, 30, 50, 50, 50);

	/** A codec that fills anything missing from {@code defaults}, and caps the range at {@code maxRange}. */
	public static Codec<SnapConfig> codec(SnapConfig defaults, int maxRange) {
		return RecordCodecBuilder.create(instance -> instance.group(
				Codec.BOOL.optionalFieldOf("enabled", defaults.enabled()).forGetter(SnapConfig::enabled),
				Codec.intRange(1, maxRange).optionalFieldOf("range_blocks", defaults.rangeBlocks()).forGetter(SnapConfig::rangeBlocks),
				Codec.intRange(MIN_ANGLE_DEGREES, MAX_ANGLE_DEGREES).optionalFieldOf("angle_degrees", defaults.angleDegrees()).forGetter(SnapConfig::angleDegrees),
				Codec.intRange(1, 100).optionalFieldOf("strength_percent", defaults.strengthPercent()).forGetter(SnapConfig::strengthPercent),
				Codec.intRange(1, 100).optionalFieldOf("ramp_up_percent", defaults.rampUpPercent()).forGetter(SnapConfig::rampUpPercent),
				Codec.intRange(1, 100).optionalFieldOf("ramp_down_percent", defaults.rampDownPercent()).forGetter(SnapConfig::rampDownPercent)
		).apply(instance, SnapConfig::new));
	}
}
