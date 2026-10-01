/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.mixins.feature.touch;

import dev.isxander.controlify.touch.TouchPad;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No crosshair in tap mode (tl118): a finger picks what the game acts on there, as in Bedrock's tap mode, which shows
 * none. The attack indicator the crosshair carries goes with it; one set to the hotbar stays.
 */
@Mixin(
		//? if >=26.2 {
		net.minecraft.client.gui.Hud.class
		//?} else {
		/*net.minecraft.client.gui.Gui.class
		*///?}
)
public class HudMixin {
	@Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
	private void controlify$noCrosshairInTapMode(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (TouchPad.tapMode()) {
			ci.cancel();
		}
	}
}
