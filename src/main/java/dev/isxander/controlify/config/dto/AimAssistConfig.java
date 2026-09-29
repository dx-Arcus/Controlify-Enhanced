/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.config.dto;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.isxander.controlify.aimassist.AimAssistMode;
import dev.isxander.controlify.aimassist.AimAssistTargets;
import dev.isxander.controlify.aimassist.LagCompensationMode;
import dev.isxander.controlify.aimassist.TrajectoryAimMode;

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
 * A record codec takes at most sixteen fields, and tl89's two lock override switches used the last.
 * The two snaps nest their six settings each in a {@link SnapConfig}, which is what left room for
 * them. tl91's Trajectory Aim is a seventeenth, tl92's Lag Compensation and Lag Allowance the
 * eighteenth and nineteenth, tl93's Live Start Angle the twentieth, and tl95's Lock-On Only the
 * twenty-first: they are read and written together with {@code lock_overrides_bow} as one of the
 * sixteen (the {@code Tail} below), at the same level as every other key, so the file keeps its
 * shape and a config saved before them loads as it was. More goes there the same way.
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
		boolean lockOverridesBow,
		TrajectoryAimMode trajectoryAim,
		LagCompensationMode lagCompensation,
		int lagAllowanceMs,
		int liveStartDegrees,
		boolean trajectoryLockedOnly
) {
	public static final int MIN_CONE_TENTHS = 5;
	public static final int MAX_MELEE_CONE_TENTHS = 250;
	public static final int MAX_BOW_CONE_TENTHS = 150;
	public static final int MAX_MELEE_DISTANCE = 64;
	/**
	 * The Bow group's Distance, and Ranged Snap's range, go up to this: 500 since tl96 (Donny, 29 Sep:
	 * "increase the max distance the aim assist works at for the bow up to 500 blocks"), 128 before.
	 */
	public static final int MAX_BOW_DISTANCE = 500;
	/** Lag Allowance, in milliseconds: up to a second, twenty ticks, beyond any ping worth shooting on. */
	public static final int MAX_LAG_ALLOWANCE_MS = 1000;
	/**
	 * Live Start Angle, in degrees: how close to the full-draw point Live's own point has to come
	 * before Live aims for it (tl93). Donny's range, 5 to 45; at 45 Live takes over as soon as its
	 * shot can reach the mob at all, as it did before.
	 */
	public static final int MIN_LIVE_START_DEGREES = 5;
	public static final int MAX_LIVE_START_DEGREES = 45;

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
			true,
			TrajectoryAimMode.OFF,
			LagCompensationMode.OFF,
			// The game's own display delay and a small ping: what Auto gives on a good connection.
			200,
			// Live's last move onto its own point is at most this: the swing up and back is gone.
			10,
			// Trajectory Aim aims only for the mob locked with Lock-On (tl95, Donny 28 Sep: "I would
			// rather trajectory aim never took a mob the cone found, only one you've locked").
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
			Tail.mapCodec(DEFAULT).forGetter(config -> new Tail(config.lockOverridesBow(), config.trajectoryAim(),
					config.lagCompensation(), config.lagAllowanceMs(), config.liveStartDegrees(), config.trajectoryLockedOnly()))
	).apply(instance, (mode, targets, meleeStrength, meleeCone, meleeDistance, bowStrength, bowCone, bowDistance,
			customTargets, targetLock, swingTiming, meleeSnap, rangedSnap, targetPlayers, lockOverridesMelee, tail) ->
			new AimAssistConfig(mode, targets, meleeStrength, meleeCone, meleeDistance, bowStrength, bowCone, bowDistance,
					customTargets, targetLock, swingTiming, meleeSnap, rangedSnap, targetPlayers, lockOverridesMelee,
					tail.lockOverridesBow(), tail.trajectoryAim(), tail.lagCompensation(), tail.lagAllowanceMs(),
					tail.liveStartDegrees(), tail.trajectoryLockedOnly())));

	/**
	 * {@code lock_overrides_bow}, {@code trajectory_aim}, {@code lag_compensation},
	 * {@code lag_allowance_ms}, {@code live_start_degrees} and {@code trajectory_locked_only}, read
	 * and written as one field of the record codec but at the same level as every other key in the
	 * section - a map codec's keys sit beside the rest rather than one level down.
	 */
	private record Tail(boolean lockOverridesBow, TrajectoryAimMode trajectoryAim, LagCompensationMode lagCompensation,
			int lagAllowanceMs, int liveStartDegrees, boolean trajectoryLockedOnly) {
		static MapCodec<Tail> mapCodec(AimAssistConfig defaults) {
			return RecordCodecBuilder.mapCodec(instance -> instance.group(
					Codec.BOOL.optionalFieldOf("lock_overrides_bow", defaults.lockOverridesBow()).forGetter(Tail::lockOverridesBow),
					TrajectoryAimMode.CODEC.optionalFieldOf("trajectory_aim", defaults.trajectoryAim()).forGetter(Tail::trajectoryAim),
					LagCompensationMode.CODEC.optionalFieldOf("lag_compensation", defaults.lagCompensation()).forGetter(Tail::lagCompensation),
					Codec.intRange(0, MAX_LAG_ALLOWANCE_MS).optionalFieldOf("lag_allowance_ms", defaults.lagAllowanceMs()).forGetter(Tail::lagAllowanceMs),
					Codec.intRange(MIN_LIVE_START_DEGREES, MAX_LIVE_START_DEGREES).optionalFieldOf("live_start_degrees", defaults.liveStartDegrees()).forGetter(Tail::liveStartDegrees),
					Codec.BOOL.optionalFieldOf("trajectory_locked_only", defaults.trajectoryLockedOnly()).forGetter(Tail::trajectoryLockedOnly)
			).apply(instance, Tail::new));
		}
	}
}
