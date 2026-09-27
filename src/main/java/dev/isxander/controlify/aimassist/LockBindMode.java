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
 * Which mob the Lock Target bind picks. The bind itself is the same whichever is chosen - tap to
 * lock or move on, hold to let go - and only one is ever in use. Separate from {@link TargetLockMode},
 * which decides how a lock can come about at all, so the bind behaves the same way in every mode.
 */
public enum LockBindMode implements NameableEnum, StringRepresentable {
	/** The nearest mob within Locked Range, on screen first. Each tap moves to the next nearest. */
	PROXIMITY,
	/**
	 * The mob closest to the crosshair, within F.O.V Angle and F.O.V Range, with any within F.O.V
	 * Priority Range first. A tap while the locked mob is still the first in line moves to the next
	 * one instead.
	 */
	FOV;

	public static final Codec<LockBindMode> CODEC = StringRepresentable.fromEnum(LockBindMode::values);

	private final Component displayName;

	LockBindMode() {
		this.displayName = Component.translatable("controlify.target_lock.bind_mode." + this.name().toLowerCase());
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
