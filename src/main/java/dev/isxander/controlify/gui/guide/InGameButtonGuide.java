/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.gui.guide;

import dev.isxander.controlify.config.settings.profile.GenericControllerSettings;
import dev.isxander.controlify.contextual.ContextualDomains;
import dev.isxander.controlify.controller.ControllerEntity;
import dev.isxander.controlify.api.contextual.InGameContext;
import dev.isxander.controlify.mixins.feature.guide.ingame.MinecraftAccessor;
import dev.isxander.controlify.touch.TouchPad;
import dev.isxander.controlify.utils.MinecraftUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public class InGameButtonGuide {
	private final ControllerEntity controller;
	private final Minecraft minecraft;
	private final GuideInstanceImpl<InGameContext> guideInstance;

	@SuppressWarnings("unchecked")
	public InGameButtonGuide(ControllerEntity controller, Minecraft minecraft) {
		this.controller = controller;
		this.minecraft = minecraft;
		this.guideInstance = (GuideInstanceImpl<InGameContext>) ContextualDomains.INSTANCE.inGame().createGuideInstance(minecraft.font);
	}

	public void extractRenderState(GuiGraphicsExtractor graphics, float tickDelta) {
		boolean debugOpen = minecraft.getDebugOverlay().showDebugScreen();
		//? if >=26.2 {
		boolean hideGui = minecraft.gui.hud.isHidden();
		//?} else {
		/*boolean hideGui = minecraft.options.hideGui;
		*///?}
		boolean screenOpen = MinecraftUtil.getScreen() != null;
		GenericControllerSettings.GuideSettings settings = controller.settings().generic.guide;

		// Hidden under touch controls (tl113): the touch buttons are on screen, and these glyphs name a pad's.
		if (!debugOpen && !hideGui && !screenOpen && settings.showIngameGuide && !TouchPad.active()) {
			this.guideInstance.extractRenderState(
					graphics, settings.ingameGuideBottom, true, settings.ingameGuiScale,
					settings.ingameGuideOffsetLeftX, settings.ingameGuideOffsetLeftY,
					settings.ingameGuideOffsetRightX, settings.ingameGuideOffsetRightY
			);
		}
	}

	public void tick() {
		GenericControllerSettings.GuideSettings settings = controller.settings().generic.guide;

		if (settings.showIngameGuide) {
			this.guideInstance.update(
					InGameContext.create(minecraft, controller)
			);
		}
	}
}
