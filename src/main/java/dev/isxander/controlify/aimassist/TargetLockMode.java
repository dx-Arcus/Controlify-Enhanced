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

import java.util.Locale;

/**
 * How a target comes to be locked. Whether target lock runs at all is a separate toggle, so
 * switching it off doesn't lose the mode you picked.
 */
public enum TargetLockMode implements NameableEnum, StringRepresentable {
	/** Only the bind locks anything. Nothing is ever chosen for you. */
	KEYBIND(true, false),
	/** The bind still locks, but aim assist stays out of it: the marker and compass only. */
	MARKER_ONLY(false, false),
	/** The bind works as usual, and melee hits in either direction take the lock over. */
	LAST_HIT(true, true);

	public static final Codec<TargetLockMode> CODEC = StringRepresentable.fromEnum(TargetLockMode::values);

	private final boolean assistsLockedTarget;
	private final boolean followsLastHit;
	private final Component displayName;

	TargetLockMode(boolean assistsLockedTarget, boolean followsLastHit) {
		this.assistsLockedTarget = assistsLockedTarget;
		this.followsLastHit = followsLastHit;
		this.displayName = Component.translatable("controlify.target_lock.mode." + this.name().toLowerCase(Locale.ROOT));
	}

	/** Whether aim assist should act on the locked target, or just leave the marker on it. */
	public boolean assistsLockedTarget() {
		return assistsLockedTarget;
	}

	/** Whether a melee hit, given or taken, should take the lock over. */
	public boolean followsLastHit() {
		return followsLastHit;
	}

	@Override
	public Component getDisplayName() {
		return displayName;
	}

	@Override
	public @NonNull String getSerializedName() {
		return this.name().toLowerCase(Locale.ROOT);
	}
}
