/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.mixins.feature.anticheat;

import dev.isxander.controlify.Controlify;
import dev.isxander.controlify.config.settings.ControlifySettings;
import dev.isxander.controlify.gui.AntiCheatWarning;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The anti-cheat warning on the multiplayer screen (tl91, Donny 28 Sep): a red line under the title
 * while any setting is on that would run on a server and that anti-cheats kick or ban for -
 * {@link AntiCheatWarning} decides which. The server list starts below the line instead of under the
 * title. Drawn only, so a controller never lands on it.
 */
@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenWarningMixin extends Screen {
	/** Space above and below the line, in GUI pixels. */
	@Unique private static final int CONTROLIFY$GAP = 4;
	/** The line wraps before it gets wider than this... */
	@Unique private static final int CONTROLIFY$MAX_WIDTH = 440;
	/** ...or than the screen less this much either side. */
	@Unique private static final int CONTROLIFY$SIDE_MARGIN = 20;

	@Shadow
	protected ServerSelectionList serverSelectionList;

	@Unique private @Nullable MultiLineTextWidget controlify$antiCheatWarning;

	protected JoinMultiplayerScreenWarningMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void controlify$addAntiCheatWarning(CallbackInfo ci) {
		this.controlify$antiCheatWarning = this.addRenderableOnly(
				new MultiLineTextWidget(Component.empty(), this.font).setCentered(true));
		controlify$placeAntiCheatWarning();
	}

	// Coming back from another screen, or a resize, lays the screen out again without init.
	@Inject(method = "repositionElements", at = @At("TAIL"))
	private void controlify$moveAntiCheatWarning(CallbackInfo ci) {
		controlify$placeAntiCheatWarning();
	}

	@Unique private void controlify$placeAntiCheatWarning() {
		MultiLineTextWidget warning = this.controlify$antiCheatWarning;
		if (warning == null || this.serverSelectionList == null) {
			return;
		}
		ControlifySettings settings = Controlify.instance().config().getSettings();
		Component line = AntiCheatWarning.line(settings.aimAssistSettings(), settings.globalSettings());
		warning.visible = line != null;
		if (line == null) {
			return;
		}
		warning.setMessage(line);
		warning.setMaxWidth(Math.min(this.width - 2 * CONTROLIFY$SIDE_MARGIN, CONTROLIFY$MAX_WIDTH));
		// Where the list began, just under the title; the list now begins below the line.
		int top = this.serverSelectionList.getY();
		warning.setPosition((this.width - warning.getWidth()) / 2, top + CONTROLIFY$GAP);
		int room = warning.getHeight() + 2 * CONTROLIFY$GAP;
		this.serverSelectionList.updateSizeAndPosition(this.width, this.serverSelectionList.getHeight() - room, top + room);
	}
}
