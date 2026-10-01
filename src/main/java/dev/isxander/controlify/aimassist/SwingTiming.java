/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.aimassist;

import dev.isxander.controlify.Controlify;
import dev.isxander.controlify.api.bind.InputBinding;
import dev.isxander.controlify.bindings.KeyMappingHandle;
import dev.isxander.controlify.config.settings.AimAssistSettings;
import dev.isxander.controlify.touch.TouchPad;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.HitResult;

/**
 * Swing Timing Assist: while attack is held, swings again each time the weapon in hand has
 * recharged, for as long as it stays held.
 * <p>
 * The rhythm counts from the swing the press made, in the weapon's own recharge time - 12.5 ticks
 * for a sword, which the game can only honour as 12 and 13 in turn, since it acts on whole ticks.
 * No swing goes before the game itself counts the weapon recharged either, so a weapon switched
 * part way cannot bring one in early. A beat that finds the crosshair on a block is let pass:
 * holding attack on a block is mining, and a swing there would start it over.
 * <p>
 * Each swing it makes is a press of the attack key as far as the game can tell, so it lands or
 * misses exactly as a pressed one would - and sets off Melee Snap the same way, which was Donny's
 * answer when asked (27 Sep). Off until switched on, and only where the Aim Assist setting allows.
 */
public final class SwingTiming {
	private static final Rhythm RHYTHM = new Rhythm();
	private static boolean running;
	private static int lastTick;

	private SwingTiming() {
	}

	/**
	 * Called every look tick with the attack bind. Returns true on a tick it swings, so Melee Snap
	 * can follow the swing the way it follows a pressed one.
	 */
	public static boolean tick(InputBinding attack) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		AimAssistSettings settings = Controlify.instance().config().getSettings().aimAssistSettings();
		// Not in tap mode, where aim assist is off (tl118): a finger picks what is attacked there.
		if (player == null || !settings.swingTiming || settings.mode == AimAssistMode.OFF
				|| !settings.mode.canAimAssist() || player.isSpectator() || TouchPad.tapMode()) {
			running = false;
			return false;
		}
		// A gap in the ticks means the look was not being handled - a screen was open, say - so any
		// rhythm from before it is over.
		boolean resumed = player.tickCount - lastTick > 1;
		lastTick = player.tickCount;

		float recharge = player.getCurrentItemAttackStrengthDelay();
		if (attack.justPressed()) {
			// The press makes its own swing; the rhythm counts from it.
			running = true;
			RHYTHM.start(recharge);
			return false;
		}
		if (!running || resumed || !attack.digitalNow()) {
			running = false;
			return false;
		}
		if (!RHYTHM.tick(recharge, player.getAttackStrengthScale(0.5f))) {
			return false;
		}
		HitResult hit = minecraft.hitResult;
		if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
			return false;
		}
		((KeyMappingHandle) minecraft.options.keyAttack).controlify$setPressed(true);
		return true;
	}

	/**
	 * The beat itself. It counts the weapon's recharge time down from each beat, carrying the half
	 * ticks over so a 12.5-tick sword swings on 13 and 12 in turn, and holds a beat that is due
	 * until the game's own count - the one the damage comes from, with the half tick it adds - says
	 * the weapon has recharged. After such a wait it counts afresh from the swing that follows.
	 * <p>
	 * Kept free of anything Minecraft-specific so the rhythm can be exercised on its own.
	 */
	static final class Rhythm {
		private double untilNext;
		private boolean waited;

		/** The press swung; the next beat is one recharge away. */
		void start(float recharge) {
			untilNext = recharge;
			waited = false;
		}

		/** One tick. True when a swing is due now. */
		boolean tick(float recharge, float attackStrength) {
			untilNext -= 1;
			if (untilNext > 0) {
				return false;
			}
			if (attackStrength < 1.0f) {
				untilNext = 0;
				waited = true;
				return false;
			}
			untilNext = waited ? recharge : untilNext + recharge;
			waited = false;
			return true;
		}
	}
}
