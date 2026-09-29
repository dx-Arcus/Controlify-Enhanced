/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.aimassist;

import dev.isxander.controlify.config.settings.AimAssistSettings;
import dev.isxander.controlify.config.settings.SnapSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector2d;
import org.jspecify.annotations.Nullable;

/**
 * Melee Snap and Ranged Snap: a quick turn of the camera onto a target, set off by a swing at
 * nothing or by starting to aim a bow or crossbow.
 * <p>
 * Everything else in aim assist helps a turn the player is already making. A snap makes the turn
 * itself. Donny asked for that knowingly on 27 Sep, so both are off until switched on, and like the
 * rest of aim assist they only run where the Aim Assist setting allows, and not at all in Marker
 * only.
 * <p>
 * The target is the locked mob, if one is held and within Snap Range. Otherwise it is the hostile
 * mob nearest the crosshair within Snap Angle and Snap Range - hostile by the rule Target: Hostile
 * uses, whatever Target is set to, with other players as well while Target Players is on -
 * measured the way Crosshair Cone measures, to the nearest edge.
 * <p>
 * With Trajectory Aim on (tl91), Ranged Snap turns to where the shot has to go to land on that
 * mob rather than onto the mob itself - the point aim assist then carries on helping onto.
 * <p>
 * The turn speeds up and slows down rather than jumping: Strength is its top speed, Ramp Up how
 * soon it gets there, Ramp Down how gently it comes in to land ({@link #nextSpeed}). While a snap
 * runs it has the camera to itself, and the rest of aim assist takes over once it lands. Pushing
 * the look stick away from the target ends it early. The swing that set it off is neither held
 * back nor moved: it goes off where the player was aiming, and the snap lines up the next one.
 */
public final class AimSnap {
	/** Top speed at 100% Strength, in degrees per tick - 600 a second. */
	static final double MAX_SPEED = 30.0;
	/** Ticks to reach top speed, or to stop from it, at 1% Ramp Up or Ramp Down. At 100% it is one tick. */
	static final double MAX_RAMP_TICKS = 10.0;
	/** Close enough to the aim point to call a snap landed, in degrees. */
	static final double LANDED_DEGREES = 0.5;
	/** A snap still going after this many ticks - two seconds - is given up, whatever the reason. */
	static final int MAX_TICKS = 40;
	/** Look input away from the target, in degrees this tick, that takes the camera back from a snap. */
	static final double TAKEOVER_INPUT = 1.0;
	/** How far past Snap Range a target may move while a snap is under way. */
	static final double RANGE_SLACK = 1.25;

	enum Kind {
		MELEE,
		RANGED
	}

	private static @Nullable Entity target;
	private static Kind kind = Kind.MELEE;
	private static double speed;
	private static int ticks;
	private static int lastTick;

	private AimSnap() {
	}

	/** The mob a snap is turning onto, or null while none is running. */
	static @Nullable Entity target() {
		return target;
	}

	static void stop() {
		target = null;
		speed = 0;
		ticks = 0;
	}

	/**
	 * Starts a snap of this kind, if it is switched on and there is something to turn to. A swing
	 * only sets one off when it would hit nothing: a swing at a block is mining, and a swing at a
	 * mob already lands, so neither is ever pulled away. A snap of the same kind already running is
	 * left to finish.
	 */
	static void start(Kind kind, LocalPlayer player, AimAssistSettings settings, @Nullable Entity locked) {
		SnapSettings snap = settingsFor(kind, settings);
		if (!snap.enabled || player.isSpectator()) {
			return;
		}
		if (kind == Kind.MELEE) {
			HitResult hit = Minecraft.getInstance().hitResult;
			if (hit != null && hit.getType() != HitResult.Type.MISS) {
				return;
			}
		}
		if (target != null && AimSnap.kind == kind) {
			return;
		}
		Entity pick = pick(player, snap, locked, settings);
		if (pick == null) {
			return;
		}
		target = pick;
		AimSnap.kind = kind;
		speed = 0;
		ticks = 0;
		lastTick = player.tickCount;
	}

	/**
	 * Turns the camera one tick's worth towards the target, writing the turn into
	 * {@code assistTurn} and clearing the look input in {@code lookImpulse}, which it takes the place
	 * of (tl92: the turn used to be written over the look input itself, and a zoom's sensitivity
	 * then scaled it down with the rest). Returns false, leaving both alone, when no snap is
	 * running - including when this tick ended one.
	 */
	static boolean step(LocalPlayer player, AimAssistSettings settings, boolean aimingProjectile, Vector2d lookImpulse,
			Vector2d assistTurn) {
		Entity snapTarget = target;
		if (snapTarget == null) {
			return false;
		}
		SnapSettings snap = settingsFor(kind, settings);
		// A gap in the ticks means the look was not being handled - a screen was open - and a snap
		// carrying on afterwards would be a turn nobody asked for.
		boolean resumed = player.tickCount - lastTick > 1;
		boolean weaponChanged = (kind == Kind.RANGED) != aimingProjectile;
		if (!snap.enabled || resumed || weaponChanged || ticks >= MAX_TICKS
				|| !snapTarget.isAlive() || snapTarget.isRemoved() || snapTarget.level() != player.level()
				|| player.distanceTo(snapTarget) > snap.rangeBlocks * RANGE_SLACK) {
			stop();
			return false;
		}
		lastTick = player.tickCount;
		ticks++;

		// A ranged snap turns to Trajectory Aim's point for the mob - only the locked mob's, with
		// Lock-On Only on (tl95), the way the hold that takes over from it does.
		boolean locked = TargetLock.active() && TargetLock.locked() == snapTarget;
		TrajectoryAim.Setup trajectory = TrajectoryAim.setup(settings, kind == Kind.RANGED, player, false, locked);
		AimAssist.Heading heading = AimAssist.heading(player, snapTarget,
				TrajectoryAim.aim(player, snapTarget, trajectory, 0), trajectory, 0);
		Vec3 toTarget = heading.now();
		double yawError = Mth.wrapDegrees(AimAssist.yawOf(toTarget) - player.getYRot());
		double pitchError = AimAssist.pitchOf(toTarget) - player.getXRot();
		double remaining = Math.hypot(yawError, pitchError);
		if (remaining <= LANDED_DEGREES) {
			stop();
			return false;
		}
		// The look stick pushed away from the target is the player taking the camera back.
		double away = -(lookImpulse.x * yawError + lookImpulse.y * pitchError) / remaining;
		if (away > TAKEOVER_INPUT) {
			stop();
			return false;
		}

		// How far the target will slide across the view by next tick if both keep moving as they
		// are - the same estimate aim assist's follow uses.
		Vec3 nextToTarget = heading.next();
		double yawDrift = Mth.wrapDegrees(AimAssist.yawOf(nextToTarget) - AimAssist.yawOf(toTarget));
		double pitchDrift = AimAssist.pitchOf(nextToTarget) - AimAssist.pitchOf(toTarget);

		double top = topSpeed(snap.strengthPercent);
		Turn turn = turn(yawError, pitchError, yawDrift, pitchDrift, speed,
				top, top / rampTicks(snap.rampUpPercent), top / rampTicks(snap.rampDownPercent));
		speed = turn.speed();
		lookImpulse.set(0, 0);
		assistTurn.set(turn.yaw(), turn.pitch());
		return true;
	}

	/** One tick of a snap: the turn to make, in degrees of yaw and pitch, and the speed it was made at. */
	record Turn(double yaw, double pitch, double speed) {
	}

	/**
	 * One tick of a snap, worked out from how far off the target is and how fast it is sliding
	 * across the view. The camera moves with the target's slide, and on top of that closes the gap
	 * at the snap's own speed - so a mob on the move is caught just as a standing one is, instead of
	 * the snap trailing a step behind it until it gives up.
	 * <p>
	 * Kept free of anything Minecraft-specific so the whole turn can be exercised on its own.
	 */
	static Turn turn(double yawError, double pitchError, double yawDrift, double pitchDrift, double speed,
			double top, double rampUp, double rampDown) {
		double remaining = Math.hypot(yawError, pitchError);
		double next = nextSpeed(speed, remaining, top, rampUp, rampDown);
		double close = Math.min(next, remaining);
		double yaw = yawDrift;
		double pitch = pitchDrift;
		if (remaining > 0) {
			yaw += yawError / remaining * close;
			pitch += pitchError / remaining * close;
		}
		return new Turn(yaw, pitch, next);
	}

	/**
	 * The locked mob if it is within Snap Range. Otherwise the hostile mob nearest the crosshair
	 * within Snap Angle and Snap Range that can be seen, and the closer of two that are level -
	 * other players among them while Target Players is on.
	 */
	private static @Nullable Entity pick(LocalPlayer player, SnapSettings snap, @Nullable Entity locked,
			AimAssistSettings settings) {
		double range = snap.rangeBlocks;
		if (locked != null && locked.isAlive() && !locked.isRemoved() && locked.level() == player.level()
				&& player.distanceTo(locked) <= range) {
			return locked;
		}

		Vec3 eye = player.getEyePosition();
		AABB searchBox = player.getBoundingBox().inflate(range);
		Entity best = null;
		double bestAngle = Double.MAX_VALUE;
		double bestDistance = Double.MAX_VALUE;
		for (Entity candidate : player.level().getEntities(player, searchBox, entity -> AimAssist.isHostile(player, entity, settings))) {
			double distance = eye.distanceTo(AimAssist.aimPoint(candidate));
			if (distance > range) {
				continue;
			}
			double angle = AimAssist.angularOffset(player, candidate, range);
			if (angle > snap.angleDegrees || !player.hasLineOfSight(candidate)) {
				continue;
			}
			if (angle < bestAngle || (angle == bestAngle && distance < bestDistance)) {
				best = candidate;
				bestAngle = angle;
				bestDistance = distance;
			}
		}
		return best;
	}

	private static SnapSettings settingsFor(Kind kind, AimAssistSettings settings) {
		return kind == Kind.MELEE ? settings.meleeSnap : settings.rangedSnap;
	}

	/**
	 * The snap's speed for the coming tick, in degrees: up by one ramp-up step, never past the
	 * top speed, and never faster than it could still slow down from, a ramp-down step a tick,
	 * before reaching a target {@code remaining} degrees away. That last limit is what makes it
	 * ease in to land rather than overshoot; a target that moves just changes {@code remaining}
	 * from one tick to the next.
	 * <p>
	 * Kept free of anything Minecraft-specific so the profile can be exercised on its own.
	 */
	static double nextSpeed(double speed, double remaining, double top, double rampUp, double rampDown) {
		double stoppable = Math.sqrt(2 * rampDown * Math.max(0, remaining));
		return Math.max(0, Math.min(Math.min(speed + rampUp, top), stoppable));
	}

	/** Top speed for a Strength percentage, in degrees per tick. */
	static double topSpeed(int strengthPercent) {
		return MAX_SPEED * Math.min(Math.max(strengthPercent, 1), 100) / 100.0;
	}

	/** Ticks to go from standing to top speed, or back, for a ramp percentage: 100% is one tick. */
	static double rampTicks(int rampPercent) {
		return 1 + (MAX_RAMP_TICKS - 1) * (1 - Math.min(Math.max(rampPercent, 1), 100) / 100.0);
	}
}
