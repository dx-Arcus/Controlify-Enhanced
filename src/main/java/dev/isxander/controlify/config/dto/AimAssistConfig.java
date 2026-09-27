/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.config.dto;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.isxander.controlify.aimassist.AimAssistMode;
import dev.isxander.controlify.aimassist.AimAssistTargets;

import java.util.List;

/**
 * Serialised aim assist settings. Kept as its own section of the config rather than more fields
 * on {@link GlobalConfig}, which is already at the 16-field limit a single record codec allows.
 * <p>
 * Strength and speed are percentages; cones are tenths of a degree, so half-degree steps survive
 * a whole number; distances are blocks. The field names carry those units because these replaced
 * an earlier Low/Medium/High enum, and an old config's "medium" would otherwise sit in a field
 * that now wants a number.
 * <p>
 * All sixteen of this record's fields are in use since tl89 added the two lock override switches.
 * The two snaps nest their six settings each in a {@link SnapConfig}, which is what left room for
 * them; anything more goes in a section of its own.
 */
public record AimAssistConfig(
		AimAssistMode mode,
		AimAssistTargets targets,
		int meleeStrengthPercent,
		int meleeConeTenths,
		int meleeDistanceBlocks,
		int bowStrengthPercent,
		int bowConeTenths,
		int bowDistanceBlocks,
		List<String> customTargets,
		TargetLockConfig targetLock,
		boolean swingTiming,
		SnapConfig meleeSnap,
		SnapConfig rangedSnap,
		boolean targetPlayers,
		boolean lockOverridesMelee,
		boolean lockOverridesBow
) {
	public static final int MIN_CONE_TENTHS = 5;
	public static final int MAX_MELEE_CONE_TENTHS = 250;
	public static final int MAX_BOW_CONE_TENTHS = 150;
	public static final int MAX_MELEE_DISTANCE = 64;
	public static final int MAX_BOW_DISTANCE = 128;

	/** The defaults are the values four rounds of in-game tuning settled on. */
	public static final AimAssistConfig DEFAULT = new AimAssistConfig(
			AimAssistMode.OFF,
			AimAssistTargets.HOSTILE,
			50,
			60,
			16,
			54,
			30,
			35,
			List.of(),
			TargetLockConfig.DEFAULT,
			false,
			SnapConfig.MELEE_DEFAULT,
			SnapConfig.RANGED_DEFAULT,
			false,
			true,
			true
	);

	public static final Codec<AimAssistConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			AimAssistMode.CODEC.optionalFieldOf("mode", DEFAULT.mode()).forGetter(AimAssistConfig::mode),
			AimAssistTargets.CODEC.optionalFieldOf("targets", DEFAULT.targets()).forGetter(AimAssistConfig::targets),
			Codec.intRange(0, 100).optionalFieldOf("melee_strength_percent", DEFAULT.meleeStrengthPercent()).forGetter(AimAssistConfig::meleeStrengthPercent),
			Codec.intRange(MIN_CONE_TENTHS, MAX_MELEE_CONE_TENTHS).optionalFieldOf("melee_cone_tenths", DEFAULT.meleeConeTenths()).forGetter(AimAssistConfig::meleeConeTenths),
			Codec.intRange(1, MAX_MELEE_DISTANCE).optionalFieldOf("melee_distance_blocks", DEFAULT.meleeDistanceBlocks()).forGetter(AimAssistConfig::meleeDistanceBlocks),
			Codec.intRange(0, 100).optionalFieldOf("bow_strength_percent", DEFAULT.bowStrengthPercent()).forGetter(AimAssistConfig::bowStrengthPercent),
			Codec.intRange(MIN_CONE_TENTHS, MAX_BOW_CONE_TENTHS).optionalFieldOf("bow_cone_tenths", DEFAULT.bowConeTenths()).forGetter(AimAssistConfig::bowConeTenths),
			Codec.intRange(1, MAX_BOW_DISTANCE).optionalFieldOf("bow_distance_blocks", DEFAULT.bowDistanceBlocks()).forGetter(AimAssistConfig::bowDistanceBlocks),
			Codec.list(Codec.STRING).optionalFieldOf("custom_targets", DEFAULT.customTargets()).forGetter(AimAssistConfig::customTargets),
			TargetLockConfig.CODEC.optionalFieldOf("target_lock", DEFAULT.targetLock()).forGetter(AimAssistConfig::targetLock),
			Codec.BOOL.optionalFieldOf("swing_timing", DEFAULT.swingTiming()).forGetter(AimAssistConfig::swingTiming),
			SnapConfig.codec(SnapConfig.MELEE_DEFAULT, MAX_MELEE_DISTANCE).optionalFieldOf("melee_snap", DEFAULT.meleeSnap()).forGetter(AimAssistConfig::meleeSnap),
			SnapConfig.codec(SnapConfig.RANGED_DEFAULT, MAX_BOW_DISTANCE).optionalFieldOf("ranged_snap", DEFAULT.rangedSnap()).forGetter(AimAssistConfig::rangedSnap),
			Codec.BOOL.optionalFieldOf("target_players", DEFAULT.targetPlayers()).forGetter(AimAssistConfig::targetPlayers),
			Codec.BOOL.optionalFieldOf("lock_overrides_melee", DEFAULT.lockOverridesMelee()).forGetter(AimAssistConfig::lockOverridesMelee),
			Codec.BOOL.optionalFieldOf("lock_overrides_bow", DEFAULT.lockOverridesBow()).forGetter(AimAssistConfig::lockOverridesBow)
	).apply(instance, AimAssistConfig::new));
}
