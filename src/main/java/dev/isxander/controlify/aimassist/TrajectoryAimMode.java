/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.aimassist;

import com.mojang.serialization.Codec;
import dev.isxander.yacl3.api.NameableEnum;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import org.jspecify.annotations.NonNull;

/**
 * Trajectory Aim: whether bow aim assist helps the crosshair onto the mob itself, or onto where a
 * shot has to go to land on it - and if so, for which draw. {@link TrajectoryAim} works it out.
 * Donny's selector, 28 Sep: Off, Full Draw and Live, so both can be tried in game.
 */
public enum TrajectoryAimMode implements NameableEnum, StringRepresentable {
	/** Bow aim assist helps onto the mob itself, as it always has. The default. */
	OFF,
	/**
	 * Aims for a fully drawn bow the whole time, so the crosshair holds still while drawing. An arrow
	 * let go early falls short.
	 */
	FULL_DRAW,
	/**
	 * Aims for the bow as drawn right now, so an arrow let go at any moment lands. Until the bow is
	 * drawn far enough to reach the mob at all, it aims for a full draw instead.
	 */
	LIVE;

	public static final Codec<TrajectoryAimMode> CODEC = StringRepresentable.fromEnum(TrajectoryAimMode::values);

	private final Component displayName;

	TrajectoryAimMode() {
		this.displayName = Component.translatable("controlify.aim_assist.trajectory_aim." + this.name().toLowerCase());
	}

	/** Whether it does anything at all. */
	public boolean isOn() {
		return this != OFF;
	}

	@Override
	public Component getDisplayName() {
		return displayName;
	}

	@Override
	public @NonNull String getSerializedName() {
		return this.name().toLowerCase();
	}
}
