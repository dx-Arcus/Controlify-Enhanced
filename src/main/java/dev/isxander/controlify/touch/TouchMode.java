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

/**
 * How the touch controls play (tl118): Bedrock's control modes, by its names.
 */
public enum TouchMode implements NameableEnum, StringRepresentable {
	/**
	 * Joystick &amp; aim crosshair: the stick floats under the left thumb, a drag anywhere else looks, and the
	 * attack and use buttons act on what the crosshair is on. Every build before tl118 played this way.
	 */
	CROSSHAIR,
	/**
	 * Joystick &amp; tap to interact: no crosshair; the stick takes only a finger on its ring, and a finger on
	 * the world taps, holds or drags - a tap uses a block or attacks a mob, a hold breaks a block or uses the
	 * item in hand, a drag looks ({@link TouchPad}). Attack and use have no buttons.
	 */
	TAP;

	public static final Codec<TouchMode> CODEC = StringRepresentable.fromEnum(TouchMode::values);

	private final Component displayName;

	TouchMode() {
		this.displayName = Component.translatable("controlify.touch.mode." + this.name().toLowerCase());
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
