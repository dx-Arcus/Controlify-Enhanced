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
 * When the touch controls are on (tl120): by themselves, going by what the player last played with, as Bedrock
 * switches between touch and the mouse and keyboard; or always; or never.
 */
public enum TouchControls implements NameableEnum, StringRepresentable {
	/**
	 * On at the first finger on a touchscreen ({@link TouchInput#touchscreen}); off at a click or scroll of the mouse,
	 * a key pressed in the world, or a controller's buttons or sticks ({@link TouchPad#turn}). On a computer with no
	 * touchscreen they never come on.
	 */
	AUTOMATIC,
	/** Always on: to try them with the mouse standing in for a finger (Mouse as Finger). */
	ON,
	/** Never on. */
	OFF;

	public static final Codec<TouchControls> CODEC = StringRepresentable.fromEnum(TouchControls::values);

	private final Component displayName;

	TouchControls() {
		this.displayName = Component.translatable("controlify.touch.controls." + this.name().toLowerCase(Locale.ROOT));
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
