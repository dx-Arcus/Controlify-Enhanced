/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.mixins.feature.ui;

import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(
		//? if >=26.4 {
		Screen.class
		//?} else {
		/*AbstractSelectionList.class
		*///?}
)
public interface AbstractSelectionListAccessor {
	@Accessor("MENU_LIST_BACKGROUND")
	static Identifier controlify$getMenuListBackground() {
		throw new AssertionError();
	}

	@Accessor("INWORLD_MENU_LIST_BACKGROUND")
	static Identifier controlify$getInWorldMenuListBackground() {
		throw new AssertionError();
	}
}
