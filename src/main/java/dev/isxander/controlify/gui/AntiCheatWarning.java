/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.gui;

import dev.isxander.controlify.aimassist.AimAssistMode;
import dev.isxander.controlify.config.settings.AimAssistSettings;
import dev.isxander.controlify.config.settings.GlobalSettings;
import dev.isxander.controlify.config.settings.TouchSettings;
import dev.isxander.controlify.reacharound.ReachAroundMode;
import dev.isxander.controlify.touch.TouchInput;
import dev.isxander.controlify.touch.TouchMode;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The red line on the multiplayer screen (tl91, Donny 28 Sep): one more reminder, before joining a
 * server, of the settings that would run there and that anti-cheats kick or ban for.
 * <p>
 * What counts is what carries a warning of its own on its settings screen, and only where it would
 * run on a server at all. Aim Assist set to Everywhere, and with it - since none of them runs on a
 * server without it - the snaps, Swing Timing Assist, Trajectory Aim, Target Players, and Ignore
 * Crosshair Cone while Target Lock is on, helping aim, with an Override switch on. Block Reach Around
 * set to Everywhere, since its other settings keep it off servers. Analog movement forced on
 * every server. And touch controls set to tap to interact (tl118), where the game acts on what is
 * under a finger rather than what the player faces - which anti-cheats that check where a player
 * looks can take for cheating - on 26.3, the only version with touch.
 */
public final class AntiCheatWarning {
	private AntiCheatWarning() {
	}

	/** The settings that are on, in the order the line names them, each by its name on its screen - touch's left out. */
	public static List<Component> risky(AimAssistSettings aim, GlobalSettings global) {
		return risky(aim, global, null);
	}

	/** The settings that are on, in the order the line names them, each by its name on its screen. */
	public static List<Component> risky(AimAssistSettings aim, GlobalSettings global, @Nullable TouchSettings touch) {
		List<Component> risky = new ArrayList<>();
		if (aim.mode == AimAssistMode.EVERYWHERE) {
			risky.add(set("controlify.gui.aim_assist.mode", aim.mode.getDisplayName()));
			if (aim.meleeSnap.enabled) {
				risky.add(Component.translatable("controlify.gui.aim_assist.melee_snap"));
			}
			if (aim.rangedSnap.enabled) {
				risky.add(Component.translatable("controlify.gui.aim_assist.ranged_snap"));
			}
			if (aim.swingTiming) {
				risky.add(Component.translatable("controlify.gui.aim_assist.swing_timing"));
			}
			if (aim.trajectoryAim.isOn()) {
				risky.add(Component.translatable("controlify.gui.aim_assist.trajectory_aim"));
			}
			if (aim.targetPlayers) {
				risky.add(Component.translatable("controlify.gui.aim_assist.target_players"));
			}
			// Only where it can take effect: a lock that helps aim, for a weapon whose Override is on.
			if (aim.targetLock.enabled && aim.targetLock.overrideCone && aim.targetLock.mode.assistsLockedTarget()
					&& (aim.lockOverridesMelee || aim.lockOverridesBow)) {
				risky.add(Component.translatable("controlify.gui.target_lock.override_cone"));
			}
		}
		if (global.reachAround == ReachAroundMode.EVERYWHERE) {
			risky.add(set("controlify.gui.reach_around", global.reachAround.getDisplayName()));
		}
		if (global.analogueMovementDefaultEnabled && !global.alwaysKeyboardMovement) {
			risky.add(Component.translatable("controlify.gui.analogue_movement_default_enabled"));
		}
		if (touch != null && TouchInput.SUPPORTED && touch.mode == TouchMode.TAP) {
			risky.add(set("controlify.touch.mode", touch.mode.getDisplayName()));
		}
		return risky;
	}

	/** The whole line, in red, or null while nothing that counts is on - touch's left out. */
	public static @Nullable Component line(AimAssistSettings aim, GlobalSettings global) {
		return line(aim, global, null);
	}

	/** The whole line, in red, or null while nothing that counts is on. */
	public static @Nullable Component line(AimAssistSettings aim, GlobalSettings global, @Nullable TouchSettings touch) {
		List<Component> risky = risky(aim, global, touch);
		if (risky.isEmpty()) {
			return null;
		}
		String key = risky.size() == 1 ? "controlify.multiplayer_warning.one" : "controlify.multiplayer_warning.many";
		return Component.translatable(key, ComponentUtils.formatList(risky, Component.literal(", ")))
				.withStyle(ChatFormatting.RED);
	}

	/** A setting that is on by being set to something: "Aim Assist (Everywhere)". */
	private static Component set(String nameKey, Component value) {
		return Component.translatable("controlify.multiplayer_warning.setting", Component.translatable(nameKey), value);
	}
}
