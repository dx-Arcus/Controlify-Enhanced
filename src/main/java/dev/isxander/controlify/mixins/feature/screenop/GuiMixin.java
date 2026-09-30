/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.mixins.feature.screenop;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import dev.isxander.controlify.screenop.ComponentProcessorProvider;
import dev.isxander.controlify.screenop.keyboard.KeyboardOverlayScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(
		//? if >=26.2 {
		net.minecraft.client.gui.Gui.class
		//?} else {
		/*net.minecraft.client.Minecraft.class
		*///?}
)
public class GuiMixin {
	@Inject(method = "setScreen", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;updateTitle()V"))
	private void changeScreen(Screen screen, CallbackInfo ci) {
		ComponentProcessorProvider.REGISTRY.clearCache();
	}

	@WrapWithCondition(method = "setScreen", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;removed()V"))
	private boolean preventRemovingOldScreen(Screen oldScreen, @Local(argsOnly = true, name = "screen") Screen screen) {
		return !(screen instanceof KeyboardOverlayScreen);
	}

	//? if >=26.3 {
	/**
	 * 26.3's setScreen also clears the outgoing screen's focus, just before the removed() call above;
	 * 26.1 and 26.2 never did. Skipped for the same reason, and only then: the on-screen keyboard hands
	 * what is typed to the widget it was opened for, and an EditBox refuses input while it is not
	 * focused - so with the focus cleared, every key landed on nothing (tl106).
	 */
	@WrapWithCondition(method = "setScreen", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;clearFocus()V"))
	private boolean preventClearingOldScreenFocus(Screen oldScreen, @Local(argsOnly = true, name = "screen") Screen screen) {
		return !(screen instanceof KeyboardOverlayScreen);
	}
	//?}
}
