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
 * Lag Compensation for Trajectory Aim (tl92, Donny 28 Sep): how much longer a moving mob is taken to
 * keep moving before a shot starts, on top of the shot's own flight. What the client shows of a mob
 * is behind where the server has it - by the round trip to the server and by the game's own delay in
 * showing a mob's movement - and the server is where the arrow flies and lands.
 * {@link TrajectoryAim#lagTicks} works the allowance out.
 */
public enum LagCompensationMode implements NameableEnum, StringRepresentable {
	/** No allowance: a mob is taken to be where it is shown when the shot starts. The default. */
	OFF,
	/** The allowance is the Lag Allowance setting, in milliseconds. */
	MANUAL,
	/** The allowance is the ping the server reports, plus the game's own display delay. */
	AUTO;

	public static final Codec<LagCompensationMode> CODEC = StringRepresentable.fromEnum(LagCompensationMode::values);

	private final Component displayName;

	LagCompensationMode() {
		this.displayName = Component.translatable("controlify.aim_assist.lag_compensation." + this.name().toLowerCase());
	}

	/** Whether it allows for anything at all. */
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
