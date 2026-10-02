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

/** Which entities aim assist is allowed to pull towards. */
public enum AimAssistTargets implements NameableEnum, StringRepresentable {
	/** Monsters, plus anything currently aggressive, so an angry wolf pack counts. */
	HOSTILE,
	/** Every living mob, friendly ones included. */
	ALL_MOBS,
	/** Only the entity types on the player's own list. */
	CUSTOM;

	public static final Codec<AimAssistTargets> CODEC = StringRepresentable.fromEnum(AimAssistTargets::values);

	private final Component displayName =
			Component.translatable("controlify.aim_assist.targets." + this.name().toLowerCase(Locale.ROOT));

	@Override
	public Component getDisplayName() {
		return displayName;
	}

	@Override
	public @NonNull String getSerializedName() {
		return this.name().toLowerCase(Locale.ROOT);
	}
}
