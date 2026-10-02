/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.touch;

import com.mojang.serialization.Codec;
import dev.isxander.yacl3.api.NameableEnum;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import org.jspecify.annotations.NonNull;

import java.util.Locale;

/**
 * When the joystick is drawn (tl131), as Bedrock's Touch page has it. It works the same whether drawn or not; the D-pad
 * is always drawn.
 */
public enum JoystickVisibility implements NameableEnum, StringRepresentable {
	/** Always, at rest and in use. */
	ALWAYS_VISIBLE,
	/** Never. */
	ALWAYS_HIDDEN,
	/** Only while a thumb is on it. */
	HIDDEN_WHEN_UNUSED;

	public static final Codec<JoystickVisibility> CODEC = StringRepresentable.fromEnum(JoystickVisibility::values);

	private final Component displayName;

	JoystickVisibility() {
		this.displayName = Component.translatable("controlify.touch.joystick_visibility." + this.name().toLowerCase(Locale.ROOT));
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
