/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.mixins.feature.touch;

import dev.isxander.controlify.touch.TouchInteract;
import dev.isxander.controlify.touch.TouchPad;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The HUD under touch controls. No crosshair in tap mode (tl118): a finger picks what the game acts on there, as in
 * Bedrock's tap mode, which shows none; the attack indicator the crosshair carries goes with it, one set to the hotbar
 * stays. And while the interact button shows (tl119), the held item's name and the action bar's message move up over
 * it, as far as {@link TouchInteract#lift} says - nothing while it does not.
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

	/** The held item's name, drawn on its backdrop at this height: up over the interact button. */
	@ModifyArg(
			method = "extractSelectedItemName",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;textWithBackdrop(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)V"),
			index = 3
	)
	private int controlify$itemNameOverTheInteractButton(int y) {
		return y - TouchInteract.lift();
	}

	/** The action bar's message, its line moved to this height: up over the interact button as far as the name. */
	@ModifyArg(
			method = "extractOverlayMessage",
			at = @At(value = "INVOKE", target = "Lorg/joml/Matrix3x2fStack;translate(FF)Lorg/joml/Matrix3x2f;"),
			index = 1
	)
	private float controlify$actionBarOverTheInteractButton(float y) {
		return y - TouchInteract.lift();
	}
}
