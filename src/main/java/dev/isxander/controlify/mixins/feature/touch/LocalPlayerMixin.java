/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.mixins.feature.touch;

import dev.isxander.controlify.touch.TouchTap;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The game's pick in tap mode (tl118): what the player acts on is under the finger, not in the middle of the screen
 * ({@link TouchTap#pick}). {@code Minecraft.pick}, every tick and every frame, is this method's only caller, so the
 * block outline, an attack and a use all follow the finger; with no finger down it finds nothing. Out of tap mode the
 * game's own pick runs untouched.
 */
@Mixin(LocalPlayer.class)
public class LocalPlayerMixin {
	@Inject(method = "raycastHitResult", at = @At("HEAD"), cancellable = true)
	private void controlify$pickUnderTheFinger(float partialTicks, Entity cameraEntity, CallbackInfoReturnable<HitResult> cir) {
		HitResult hit = TouchTap.pick((LocalPlayer) (Object) this, cameraEntity, partialTicks);
		if (hit != null) {
			cir.setReturnValue(hit);
		}
	}
}
