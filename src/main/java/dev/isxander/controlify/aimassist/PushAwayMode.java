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
 * What a hard push of the look stick away from a locked mob does (tl104). The push is the one
 * Trajectory Aim already answers to on a mob it holds without a lock: at least
 * {@code AimAssist.LET_GO_PUSH} of the stick's travel, against the mob, for
 * {@code AimAssist.LET_GO_TICKS} ticks in a row. The bind, held, and the Letting Go ranges let a
 * lock go as before whichever this is set to.
 */
public enum PushAwayMode implements NameableEnum, StringRepresentable {
	/** The push does nothing to a lock: the lock and its help stay. What every build before tl104 did. */
	OFF,
	/** The push drops the lock outright - the marker and the compass with it, as holding the bind does. */
	DROP_LOCK,
	/**
	 * The push keeps the lock, marker and compass, and takes the aim help off the mob until the
	 * stick eases back under {@code AimAssist.LET_GO_RELEASE}; then the help is back on it.
	 */
	PAUSE_HELP;

	public static final Codec<PushAwayMode> CODEC = StringRepresentable.fromEnum(PushAwayMode::values);

	private final Component displayName;

	PushAwayMode() {
		this.displayName = Component.translatable("controlify.target_lock.push_away." + this.name().toLowerCase());
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
