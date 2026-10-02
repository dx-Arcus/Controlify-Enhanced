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
 * How big the top row is - chat, pause and the camera perspective button (tl135) - as Bedrock's Touch page has it:
 * Medium half again Small's size and Big twice it, as measured off Donny's phone (2 Oct 04:43: each button's face 84.5,
 * 126.5 and 170 pixels across on a 1440-high screen).
 */
public enum TopButtonSize implements NameableEnum, StringRepresentable {
	SMALL(1f),
	MEDIUM(1.5f),
	BIG(2f);

	public static final Codec<TopButtonSize> CODEC = StringRepresentable.fromEnum(TopButtonSize::values);

	private final float scale;
	private final Component displayName;

	TopButtonSize(float scale) {
		this.scale = scale;
		this.displayName = Component.translatable("controlify.touch.top_button_size." + this.name().toLowerCase(Locale.ROOT));
	}

	/** The top row's size, as a multiple of Small's. */
	public float scale() {
		return scale;
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
