/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.mixins.feature.touch;

import net.minecraft.client.renderer.LevelRenderer;
//? if >=26.3 {
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.isxander.controlify.touch.TouchPad;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import org.spongepowered.asm.mixin.injection.At;
//?}
import org.spongepowered.asm.mixin.Mixin;

/**
 * Outline Selection (tl136): the block outline under touch controls in Joystick &amp; Aim Crosshair mode, while the
 * setting is on - a light grey line twice the game's width ({@link TouchPad#OUTLINE_SELECTION_COLOR},
 * {@link TouchPad#OUTLINE_SELECTION_WIDTH}) in place of its faint black one. The game draws the outline in
 * {@code submitBlockOutline} with one call to {@code submitHitOutline}, or two with its own High Contrast Block
 * Outlines - a wide black line under a bright one; the second call (ordinal 1) is the line itself, and with high
 * contrast on it is left as the game has it.
 * <p>
 * Empty on the versions before 26.3, where there are no touch controls.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
	//? if >=26.3 {
	@WrapOperation(
			method = "submitBlockOutline",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/LevelRenderer;submitHitOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/state/level/BlockOutlineRenderState;IFZ)V",
					ordinal = 1
			)
	)
	private void controlify$touchOutlineSelection(LevelRenderer self, PoseStack poseStack, SubmitNodeCollector collector,
			RenderType renderType, BlockOutlineRenderState state, int color, float width, boolean afterTerrain, Operation<Void> original) {
		if (TouchPad.outlineSelection(state.highContrast())) {
			color = TouchPad.OUTLINE_SELECTION_COLOR;
			width *= TouchPad.OUTLINE_SELECTION_WIDTH;
		}
		original.call(self, poseStack, collector, renderType, state, color, width, afterTerrain);
	}
	//?}
}
