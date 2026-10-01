/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.mixins.feature.virtualmouse;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.isxander.controlify.Controlify;
import dev.isxander.controlify.touch.TouchPad;
import dev.isxander.controlify.virtualmouse.VirtualMouseHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;handleAccumulatedMovement()V"))
	private void onUpdateMouse(boolean advanceGameTime, CallbackInfo ci) {
		Optional.ofNullable(Controlify.instance().virtualMouseHandler())
				.ifPresent(VirtualMouseHandler::updateMouse);
		TouchPad.frame();
	}

	/**
	 * Holding attack keeps mining only while the mouse is grabbed; with touch controls on it keeps mining
	 * whether the cursor is grabbed or free (tl111, TouchPad).
	 */
	@ModifyExpressionValue(method = "handleKeybinds", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"))
	private boolean keepMiningOnTouch(boolean grabbed) {
		return grabbed || TouchPad.active();
	}
}
