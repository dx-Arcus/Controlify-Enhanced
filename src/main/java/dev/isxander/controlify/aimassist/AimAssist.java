/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.aimassist;

import dev.isxander.controlify.Controlify;
import dev.isxander.controlify.config.settings.AimAssistSettings;
import dev.isxander.controlify.config.settings.TargetLockSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector2d;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Controller aim assist. Slows the look input down while the crosshair is near a valid target,
 * which is what fixes most controller misses: overshooting, rather than being wildly off.
 * <p>
 * The pull only runs while something is already moving: the look stick, the player, or the target.
 * A standstill opposite a standing mob produces nothing, so the camera never drifts out from under
 * the player. It never widens a hitbox and never changes where an attack lands, so the player's own
 * aim still decides the outcome.
 * <p>
 * The snaps in {@link AimSnap} are the exception, and only when switched on: they turn the camera
 * onto a target by themselves, on a swing or on starting to aim. So is Trajectory Aim
 * ({@link TrajectoryAim}), for a bow or crossbow: once switched on, it takes the crosshair to where
 * the shot has to go and holds it there, stick moving or not, as the mob, the player and the draw
 * move it (tl92) - and keeps that mob for the whole draw, wherever the crosshair is taken, until the
 * stick is pushed hard away from the point for a quarter of a second, which lets it go (tl93). It
 * takes a mob only once the player has settled the crosshair on it - the stick eased off, the mob in
 * the cone for a few ticks - so panning past mobs with the bow drawn takes none of them (tl94).
 */
public final class AimAssist {
	/**
	 * Every strength, cone, distance and speed setting is a number the player sets directly rather
	 * than a Low/Medium/High step, so strength and speed are percentages mapped onto the ceilings
	 * below. The defaults in the config are where four rounds of in-game tuning left them: 50%
	 * strength reproduces exactly the melee feel those rounds settled on.
	 */
	private static final double MAX_PULL = 1.94;
	private static final double MAX_FOLLOW = 1.40;
	private static final double MAX_GAIN = 0.90;

	/**
	 * Fraction of the remaining angle a pull closes each tick when the Locked settings are not in
	 * use: no target locked, or the weapon's Override switch off (tl89).
	 */
	private static final double DEFAULT_GAIN = 0.45;

	/**
	 * Look impulse (degrees this tick) at which the stick alone drives the pull at full strength.
	 */
	private static final double FULL_PULL_INPUT = 0.25;

	/**
	 * Rate at which a target sliding across the view, in degrees per tick, drives the pull at full
	 * strength on its own. Any deliberate strafe past a mob clears this comfortably, so tracking
	 * works with the look stick untouched, while a standstill still leaves the camera alone.
	 */
	private static final double FULL_PULL_SWING = 0.5;

	/**
	 * Bows deliberately get no follow. They tested well as they are, they are rarely fired at the
	 * ranges where the shortfall appears, and a drawn bow wants the player's own fine control.
	 */
	private static final double BOW_FOLLOW = 0.0;

	/**
	 * Ceiling on the follow, so sprinting past a mob at arm's length cannot whip the camera round.
	 * A locked target's strength past what melee's 100% gives raises it - see {@link #followCeilingFor}.
	 */
	private static final double MAX_FOLLOW_RATE = 6.0;

	/**
	 * Locked Strength and Locked Speed reach twice as far as the melee and bow settings. Their
	 * sliders still read 0 to 100%, but each percent counts double here: 50% locked is what 100%
	 * does for melee, and 100% locked is twice that. Donny found both too weak even at 100% and
	 * asked for exactly this on 27 Sep - the numbers behind the slider doubled, the slider kept.
	 */
	private static final int LOCKED_SCALE = 2;

	/** The furthest any strength or speed reaches once scaled: a locked slider at 100%. */
	private static final int MAX_SCALED_PERCENT = 100 * LOCKED_SCALE;

	/**
	 * Where on a mob the assist aims, as a fraction of its eye height. Aiming at the centre of the
	 * bounding box put the crosshair around a zombie's waist, and dragged it back down whenever the
	 * player was already lined up on the head. Measuring from the eyes rather than the box keeps
	 * this at roughly the same spot on mobs of very different proportions.
	 */
	private static final double AIM_HEIGHT_FACTOR = 0.80;

	/** How far outside the cone a locked bow target may drift before it is given up. */
	private static final double BOW_LOCK_TOLERANCE = 1.5;

	/**
	 * A mob Trajectory Aim is holding the crosshair on is not given up for drifting at all: its point
	 * can move a good few degrees in a tick - a mob turning round puts the lead on the other side of
	 * it, a Live draw coming into reach lifts the point away - and the crosshair falling behind it
	 * was exactly what the hold is for. tl92 let it go three cones out, and Donny watched the assist
	 * lose a mob it could not keep up with and never try for it again (28 Sep). It is kept for the
	 * whole draw while it is in range and in sight, and let go only by the player: the look stick
	 * pushed at least this far of its full travel, away from the point, for {@link #LET_GO_TICKS}
	 * ticks in a row. Then nothing is picked again until the stick comes back under
	 * {@link #LET_GO_RELEASE}, so the camera is the player's to move away with. A push short of that
	 * moves the crosshair a little off the point and the pull holds the rest. The same push answers
	 * for a locked mob as Hard Push Away says (tl104): nothing, the lock dropped, or the help paused.
	 */
	private static final double LET_GO_PUSH = 0.7;
	private static final int LET_GO_TICKS = 5;
	private static final double LET_GO_RELEASE = 0.5;

	/**
	 * With Trajectory Aim on, a mob the search finds is taken for the hold only once the player has
	 * settled on it: the look stick under {@link #LET_GO_RELEASE} and the same mob found this many
	 * ticks in a row (tl94). Donny, 28 Sep 21:58, on a flat world at night: "the bow was getting aim
	 * compensation and aim assist on random targets while drawing the bow and just panning left and
	 * right on screen, some of them were far away so the compensation would kick in and throw your
	 * camera up really high". Panning takes the stick past half, and a mob crossing the cone on the
	 * way is gone again before four ticks are up; stopping on one takes it in a fifth of a second.
	 * A locked mob is chosen by the lock and needs no settling; without Trajectory Aim the bow's
	 * gentle help picks at once, as it always has.
	 */
	private static final int PICK_TICKS = 4;

	/**
	 * The point the crosshair is held on is the one for a tick from now (tl94). The server fires a
	 * shot with what the client last sent of the player - position, movement and where they were
	 * looking - and the client sends those once a tick, after its entities have moved for the tick
	 * and before the frames after the tick turn the camera (Minecraft.tick: handleKeybinds, then
	 * tickEntities, then LocalPlayer.sendChanges; runTick: the ticks, then the look is applied at
	 * MouseHandler.handleAccumulatedMovement - read in the 26.3 jar). A release is sent at the top of
	 * a tick, before that tick's packet, so the server fires it from the packet before: the position
	 * after that tick's move, that move as the carried motion, and the camera as it stood at the
	 * start of that tick. That camera is where the look tick before put it: the pull towards the point
	 * this many ticks on, and the follow carrying it one tick further ({@link #heading}) - two moves
	 * on from that look tick's view, which is exactly the packet's position and move. Held for now,
	 * as tl93 did, the camera in each packet was a move behind its position. A mob walking, that is a
	 * fraction of a degree; a player in the air, whose carried motion changes by 0.08 a tick, or a
	 * Live draw still rising, it was degrees - the miss Donny saw (28 Sep 21:58, "shots still miss
	 * most times if you are jumping or falling while you shoot"). What no tick ahead can know is the
	 * jump itself, and the landing: the first tick of each is a surprise the pull then closes.
	 */
	private static final int HOLD_TICKS_AHEAD = 1;

	/** Entity hitboxes are grown by this much when testing whether the crosshair is actually on one. */
	private static final double RAY_HITBOX_PADDING = 0.3;

	/**
	 * How far past a mob's outline, in degrees, the slowdown starts resisting a turn made towards
	 * it. Inside this the slowdown is what stops the crosshair sailing past; outside it, resisting
	 * a deliberate turn onto a mob is just a cap on how fast the player is allowed to come round.
	 * Eased out over twice this angle so the slowdown arrives rather than hits a wall, which also
	 * leaves a little cushion for a high sensitivity to overshoot into.
	 */
	private static final double TURN_IN_CUSHION = 2.0;

	/**
	 * With the cone ignored, the pull has a second job: bringing the camera round to a target that
	 * may be anywhere, including behind the player. The tuned cap is about a degree a tick, which is
	 * right for the last of the correction and hopeless for a turn — nine seconds to come about. So
	 * past {@link #SWEEP_FULL_ANGLE} the ceiling is raised to this many degrees a tick at full Speed,
	 * easing back down to the tuned cap as the crosshair arrives, where the settle should feel the
	 * same as it always did.
	 */
	private static final double MAX_SWEEP_RATE = 12.0;
	private static final double SWEEP_FULL_ANGLE = 45.0;

	/**
	 * With Trajectory Aim on, the crosshair is taken to where the shot has to go and held there
	 * (tl92, Donny 28 Sep: "be constantly tracking the position for the arrow to hit the mob, and be
	 * actively moving your crosshair there"). The point moves with every step the mob or the player
	 * takes and, on Live, with every tick of the draw - the follow carries all of that, and the pull
	 * closes whatever is left at up to this many degrees a tick at full Strength, in place of the
	 * tuned cap, which at a degree a tick trailed a Live point by a second. The same rate the sweep
	 * to a locked mob reaches at full Speed.
	 */
	private static final double MAX_TRAJECTORY_RATE = 12.0;

	/** What the assist did on the most recent look tick, for the Dev Functions readout. */
	public record Debug(@Nullable Entity target, double angle, double multiplier, boolean bowMode, boolean active,
						Counts counts, AimAssistTargets targets, double pull, boolean locked) {
		public static final Debug INACTIVE = new Debug(null, 0, 1, false, false, new Counts(), AimAssistTargets.HOSTILE, 0, false);
	}

	/** Where candidates were lost during the last search, so a failure can be traced to one stage. */
	public static final class Counts {
		public int nearby;
		public int eligible;
		public int tooFar;
		public int outsideCone;
		public int losBlocked;
		public double bestAngle = -1;
	}

	private static Debug lastDebug = Debug.INACTIVE;
	private static @Nullable Entity lockedBowTarget;
	/** Ticks in a row the stick has been pushed hard away from a held point (tl93). */
	private static int ticksPushingAway;
	/** A held mob has been let go and the stick not yet eased off, so nothing is picked (tl93). */
	private static boolean letGo;
	/**
	 * The locked mob a hard push has taken the help off - Hard Push Away on Pause the Help - until
	 * the stick eases back under {@link #LET_GO_RELEASE} (tl104). The lock itself is untouched.
	 */
	private static @Nullable Entity helpPausedOn;
	/** The mob the search has been finding, and for how many ticks in a row, while the player settles on it (tl94). */
	private static @Nullable Object pendingPick;
	private static int pendingTicks;
	/**
	 * Where the held point was expected to be this tick - last tick's next - and for which mob, so
	 * the point's own unexpected move can be carried in full (tl94).
	 */
	private static @Nullable Vec3 expectedPoint;
	private static @Nullable Entity expectedTarget;
	private static Counts lastCounts = new Counts();
	/** Whether a projectile was being aimed last tick, so Ranged Snap goes off only as aiming starts. */
	private static boolean wasAimingProjectile;

	private AimAssist() {
	}

	public static Debug debug() {
		return lastDebug;
	}

	/**
	 * Scales {@code lookImpulse} down while the crosshair is near a target. Called from the look
	 * handler before Controlify's look event fires, so mods listening to that event (Zoomify's
	 * zoom sensitivity, for one) still scale the result as they always have.
	 *
	 * @param assistTurn where the turns the assist makes by itself go - a snap's, and Trajectory
	 *                   Aim's hold on its point (tl92): the look handler adds them after the event,
	 *                   so a zoom's sensitivity leaves them whole and they land where they are meant to
	 * @param stickPush  how far the look stick is pushed, 0 to 1 of its full travel, whatever the
	 *                   sensitivity makes of it (tl93): a hard push away from a held point is the
	 *                   player letting the mob go
	 * @param jumping    whether the jump button is down this tick (tl94): the player's tick, after
	 *                   this, will make the jump, and Trajectory Aim's point allows for it
	 * @param swung      whether a swing was made this tick - pressed, or by Swing Timing Assist -
	 *                   which is what sets off Melee Snap
	 */
	public static void apply(Vector2d lookImpulse, Vector2d assistTurn, double stickPush, boolean jumping, boolean swung) {
		AimAssistSettings settings = Controlify.instance().config().getSettings().aimAssistSettings();
		LocalPlayer player = Minecraft.getInstance().player;
		MotionAverage.tick();

		if (player == null || settings.mode == AimAssistMode.OFF || !settings.mode.canAimAssist()) {
			off();
			return;
		}

		TargetLockSettings lock = settings.targetLock;
		boolean lockRunning = TargetLock.active();

		// Marker only means exactly that: the lock, the arrow and the compass, and no aim help at all.
		if (lockRunning && !lock.mode.assistsLockedTarget()) {
			off();
			return;
		}
		Entity heldTarget = lockRunning ? TargetLock.locked() : null;

		boolean bowMode = isAimingProjectile(player);
		if (!bowMode) {
			dropBowTarget();
			// A push counted, or a letting go, during a draw ends with the draw (tl93); in melee a
			// locked mob can be pushed away from too (tl104), so the count lives on from tick to tick.
			if (wasAimingProjectile) {
				endPush();
			}
		}
		// Trajectory Aim (tl91) only has a shot to work out while a bow is drawn or a crossbow loaded;
		// its setup for the tick carries Lag Compensation's allowance too (tl92). With Lock-On Only
		// on it has a shot to work out only for the locked mob (tl95): with nothing locked it is off,
		// and the bow's ordinary help onto the mob itself is what the search's mob gets.
		TrajectoryAim.Setup trajectory = TrajectoryAim.setup(settings, bowMode, player, jumping, heldTarget != null);

		// Melee Snap on a swing, Ranged Snap as aiming starts. While one is turning the camera it has
		// it to itself; everything below picks up again once it lands.
		boolean startedAiming = bowMode && !wasAimingProjectile;
		wasAimingProjectile = bowMode;
		if (swung && !bowMode) {
			AimSnap.start(AimSnap.Kind.MELEE, player, settings, heldTarget);
		}
		if (startedAiming) {
			AimSnap.start(AimSnap.Kind.RANGED, player, settings, heldTarget);
		}
		if (AimSnap.step(player, settings, bowMode, lookImpulse, assistTurn)) {
			Entity snapTarget = AimSnap.target();
			lastDebug = new Debug(snapTarget, snapTarget == null ? 0 : angleTo(player, snapTarget), 1, bowMode, true,
					lastCounts, settings.targets, lookImpulse.length(), heldTarget != null);
			return;
		}

		// While a mob is locked the Locked settings take over from the weapon's own - for melee and
		// for the bow each, while its Override switch is on (tl89). Off, the lock still decides which
		// mob, and the weapon's own Strength, Crosshair Cone and Distance help with it.
		boolean useLocked = usesLockedSettings(heldTarget != null, bowMode, settings);
		double cone = (bowMode ? settings.bowConeTenths : settings.meleeConeTenths) / 10.0;
		double range = useLocked
				? lock.lockedRangeBlocks
				: (bowMode ? settings.bowDistanceBlocks : settings.meleeDistanceBlocks);
		int strength = useLocked
				? lock.lockedStrengthPercent * LOCKED_SCALE
				: (bowMode ? settings.bowStrengthPercent : settings.meleeStrengthPercent);
		int lockedSpeed = lock.lockedSpeedPercent * LOCKED_SCALE;

		// A held target skips the search entirely. That is the whole point of the lock: the mob you
		// chose keeps the assist, and the zombie wandering past does not get to take it.
		Entity target;
		if (heldTarget != null) {
			target = heldTarget;
			// A hard push has taken the help off this mob, and the stick has not eased off yet: the
			// lock, marker and compass stay, the camera is the player's (tl104).
			if (helpPausedOn == target && stickPush > LET_GO_RELEASE) {
				lastDebug = nothing(settings, bowMode, 0, true);
				return;
			}
			helpPausedOn = null;
			// A lock held is the player's choice of mob, whatever was let go of before it.
			letGo = false;
			if (player.distanceTo(target) > range) {
				lastDebug = nothing(settings, bowMode, 0, true);
				return;
			}
		} else {
			helpPausedOn = null;
			// A mob let go with a hard push stays let go, and nothing else is picked, until the stick
			// eases off: the camera is the player's to move away with (tl93).
			if (letGo && stickPush > LET_GO_RELEASE) {
				lastDebug = nothing(settings, bowMode, 0, false);
				return;
			}
			letGo = false;
			target = findTarget(player, settings, cone, range, bowMode, trajectory);
			if (target == null) {
				settled(null, stickPush);
				lastDebug = nothing(settings, bowMode, 0, false);
				return;
			}
			if (bowMode) {
				// A mob the hold would take is taken only once the player has settled on it (tl94);
				// the mob already kept for this draw is kept.
				if (target != lockedBowTarget && trajectory.isOn() && !settled(target, stickPush)) {
					lastDebug = nothing(settings, bowMode, 0, false);
					return;
				}
				lockedBowTarget = target;
			}
		}

		// With Trajectory Aim on, the crosshair is helped onto where the shot has to go rather than
		// onto the mob, and counts as on it anywhere on the way from the one to the other.
		TrajectoryAim.Aim aim = TrajectoryAim.aim(player, target, trajectory, HOLD_TICKS_AHEAD);
		double onMob = angularOffset(player, target, range);
		double angle = aim != null ? trajectoryOffset(player, target, aim, onMob) : onMob;
		// 1 while the crosshair is on the target, easing to 0 at the edge of the cone. The square
		// root keeps the assist meaningful across most of the cone instead of only dead centre.
		// A lock set to override the cone holds full strength from any angle, which is the setting
		// that turns this from help near where you are aiming into outright tracking.
		boolean ignoreCone = useLocked && lock.overrideCone;
		double coneProximity = Math.sqrt(Mth.clamp(1 - (angle / cone), 0, 1));

		// Two different questions, which were sharing one answer. The pull asks how much help to
		// give getting to the target; ignoring the cone means all of it, from any angle. The
		// slowdown asks how much to resist the camera, and that is a settling aid — it has no
		// business touching the camera while the crosshair is nowhere near the mob. Sharing the
		// number meant switching the cone off also capped how fast the player could turn at all.
		// Trajectory Aim holds the crosshair on the point the same way: the cone picks the mob, and
		// from then on the crosshair is taken to the point from wherever it is and kept there, or the
		// mob's every step and the draw's every tick would ease it out of the cone and the help off.
		// With a point held there is no slowdown at all (tl93): the pull is what holds the crosshair,
		// and the stick slowed near the point on top of that - and by the game's own reduced aiming
		// sensitivity with a bow drawn - was what Donny felt as the aim going sticky while he drew.
		boolean holdsPoint = aim != null;
		double pullProximity = ignoreCone || holdsPoint ? 1 : coneProximity;
		double slowProximity = ignoreCone
				? Math.sqrt(Mth.clamp(1 - (angle / (TURN_IN_CUSHION * 2)), 0, 1))
				: holdsPoint ? 0 : coneProximity;

		double multiplier = 1 - (1 - slowdownFor(strength)) * slowProximity;

		// Where the target sits next tick if everyone keeps moving as they are. The change in bearing
		// is how fast it is sliding across the view, which is the rate the camera has to match just
		// to stay pointed at it.
		Heading heading = heading(player, target, aim, trajectory, HOLD_TICKS_AHEAD);
		Vec3 toTarget = heading.now();
		Vec3 nextToTarget = heading.next();
		double yawNow = yawOf(toTarget);
		double pitchNow = pitchOf(toTarget);
		double yawDrift = Mth.wrapDegrees(yawOf(nextToTarget) - yawNow);
		double pitchDrift = pitchOf(nextToTarget) - pitchNow;
		double swing = Math.hypot(yawDrift, pitchDrift);

		// Magnetism only ever helps a turn that is already happening, and it can be happening for
		// two reasons: the stick is being pushed, or the target is sliding across the view because
		// the player or the mob is moving. Strafing past a zombie is the second kind, which is why
		// the stick alone is not enough to gate this. Standing still with nothing moving is neither,
		// and contributes nothing, so the camera never drifts on its own.
		double stickStrength = Mth.clamp(lookImpulse.length() / FULL_PULL_INPUT, 0, 1);
		double trackingStrength = Mth.clamp(swing / FULL_PULL_SWING, 0, 1);
		// Ignoring the cone means holding the target whatever is happening, including a standoff
		// where neither the player nor the mob is moving, so the gate comes off entirely. So does
		// Trajectory Aim: it moves the camera by itself, which is why it is off until switched on.
		double inputStrength = ignoreCone || holdsPoint ? 1 : Math.max(stickStrength, trackingStrength);
		double pullCap = pullFor(strength);
		double gain = useLocked ? gainFor(lockedSpeed) : DEFAULT_GAIN;
		double pullScale = pullProximity * inputStrength;

		// Speed drives the sweep, which is what it reads as on the slider: how fast the camera comes
		// round to the target. Strength still decides how hard it holds once it is there. Once the
		// gain is as high as it can usefully go, more Speed lets the pull itself turn faster instead
		// (speedBoost) - twice as fast at 100% on the slider.
		double effectiveCap = useLocked ? pullCap * speedBoost(lockedSpeed) : pullCap;
		if (holdsPoint) {
			effectiveCap = Math.max(effectiveCap, trajectoryRateFor(strength));
		}
		if (ignoreCone) {
			double sweepBand = SWEEP_FULL_ANGLE - TURN_IN_CUSHION * 2;
			double reach = Mth.clamp((angle - TURN_IN_CUSHION * 2) / sweepBand, 0, 1);
			double sweepRate = MAX_SWEEP_RATE * Mth.clamp(lockedSpeed, 0, MAX_SCALED_PERCENT) / 100.0;
			effectiveCap = Mth.lerp(reach, effectiveCap, Math.max(effectiveCap, sweepRate));
		}

		double yawError = Mth.wrapDegrees(yawNow - player.getYRot());
		double pitchError = pitchNow - player.getXRot();

		// The stick pushed hard away from a held point, for long enough to mean it, lets the mob go
		// (tl93). A locked mob answers to the same push as Hard Push Away says (tl104): Off leaves
		// it the lock's, with the bind for letting go; Drop the Lock clears the lock outright, as
		// holding the bind does; Pause the Help keeps the lock and takes the help off the mob until
		// the stick eases. A locked mob's slowdown can hold the crosshair on it against any push -
		// at full Locked Strength the stick moves nothing - so on a locked mob a hard push while the
		// crosshair is on it counts too, unless it is going with the mob across the view: there is
		// no "away" from dead on, and a push that is not keeping up with the mob is the player
		// asking for the camera back.
		boolean pushLetsGo = heldTarget != null ? lock.pushAway != PushAwayMode.OFF : holdsPoint;
		if (pushLetsGo) {
			boolean pushingAway = stickPush >= LET_GO_PUSH
					&& (lookImpulse.x * yawError + lookImpulse.y * pitchError < 0
					|| heldTarget != null && angle == 0 && lookImpulse.x * yawDrift + lookImpulse.y * pitchDrift <= 0);
			ticksPushingAway = pushingAway ? ticksPushingAway + 1 : 0;
			if (ticksPushingAway >= LET_GO_TICKS) {
				if (heldTarget != null && lock.pushAway == PushAwayMode.PAUSE_HELP) {
					ticksPushingAway = 0;
					helpPausedOn = target;
					lastDebug = nothing(settings, bowMode, angle, true);
					return;
				}
				if (heldTarget != null) {
					TargetLock.clear();
				}
				dropBowTarget();
				endPush();
				letGo = true;
				lastDebug = nothing(settings, bowMode, angle, heldTarget != null);
				return;
			}
		} else {
			ticksPushingAway = 0;
		}

		// The held point's own move since the last look tick that the follow did not carry - the
		// jump button seen, a landing, the draw reaching where Live takes over - is carried in full
		// with the follow below, not left to the pull to close at its gain over several ticks: the
		// server fires from the camera as each packet has it, and a point that moved for a reason
		// the client already knows should have the camera on it by the next (tl94). What the pull
		// closes is what is left: the stick's push, and a mob just taken.
		double moveYaw = 0;
		double movePitch = 0;
		if (holdsPoint && expectedPoint != null && expectedTarget == target) {
			moveYaw = Mth.wrapDegrees(yawNow - yawOf(expectedPoint));
			movePitch = pitchNow - pitchOf(expectedPoint);
			yawError -= moveYaw;
			pitchError -= movePitch;
		}
		expectedPoint = holdsPoint ? nextToTarget : null;
		expectedTarget = holdsPoint ? target : null;

		// Cap the combined pull rather than each axis: capping them separately let a diagonal
		// pull reach 1.41x the configured cap, which is why High felt heavier than its number.
		double yawPull = yawError * gain;
		double pitchPull = pitchError * gain;
		double pullLength = Math.hypot(yawPull, pitchPull);
		if (pullLength > effectiveCap && pullLength > 0) {
			double scale = effectiveCap / pullLength;
			yawPull *= scale;
			pitchPull *= scale;
		}
		yawPull *= pullScale;
		pitchPull *= pullScale;

		// The pull's cap is a fixed number of degrees per tick, but the rate a mob slides across the
		// view goes up as you close in: at two blocks a slow sidestep moves it faster than any of the
		// three levels can follow, which is why the crosshair felt anchored at range and loose in a
		// mob's face. Make up the part of that the cap cannot reach. Further out there is no
		// shortfall and this is exactly zero, so the feel at range is untouched.
		double follow = bowMode && !useLocked ? BOW_FOLLOW : followFor(strength);
		double followRate = Math.min(Math.max(0, swing - pullCap) * follow, followCeilingFor(strength)) * pullProximity;
		// Trajectory Aim's point moves as the bow draws and as the mob moves, and the crosshair moves
		// with it - all of that movement, up to the follow's ceiling, wherever the crosshair is - so
		// the pull only has to close how far off the point it is. Strength at 0 still does nothing.
		double carryYaw = yawDrift;
		double carryPitch = pitchDrift;
		double carry = swing;
		if (holdsPoint) {
			carryYaw += moveYaw;
			carryPitch += movePitch;
			carry = Math.hypot(carryYaw, carryPitch);
			followRate = strength > 0 ? Math.min(carry, followCeilingFor(strength)) : 0;
		}
		double followYaw = 0;
		double followPitch = 0;
		if (followRate > 0 && carry > 1.0e-4) {
			followYaw = carryYaw / carry * followRate;
			followPitch = carryPitch / carry * followRate;
		}

		// Slowdown should only ever resist aim leaving a target, never aim arriving at one. Scaling
		// the whole input meant a turn made straight towards the locked mob was capped at the
		// assist's own speed, so coming round onto something behind you fought the setting meant to
		// be helping. Input closing the gap is left alone until the crosshair is nearly there.
		double closing = lookImpulse.x * yawError + lookImpulse.y * pitchError;
		double exemption = closing > 0
				? Mth.clamp((angle - TURN_IN_CUSHION) / TURN_IN_CUSHION, 0, 1)
				: 0;

		lookImpulse.mul(Mth.lerp(exemption, multiplier, 1.0));
		// A held point is the assist's own turn, kept out of the look event's reach; the rest is help
		// with the player's look, and goes through it as it always has.
		if (holdsPoint) {
			assistTurn.add(yawPull + followYaw, pitchPull + followPitch);
		} else {
			lookImpulse.add(yawPull + followYaw, pitchPull + followPitch);
		}

		lastDebug = new Debug(target, angle, multiplier, bowMode, true, lastCounts, settings.targets,
				Math.hypot(yawPull + followYaw, pitchPull + followPitch), heldTarget != null);
	}

	/** Aim assist is not running this tick: nothing kept, no snap, the readout inactive. */
	private static void off() {
		dropBowTarget();
		endPush();
		helpPausedOn = null;
		AimSnap.stop();
		lastDebug = Debug.INACTIVE;
	}

	/** The readout for a tick that helped with nothing: no target, the look input left as it was. */
	private static Debug nothing(AimAssistSettings settings, boolean bowMode, double angle, boolean locked) {
		return new Debug(null, angle, 1, bowMode, true, lastCounts, settings.targets, 0, locked);
	}

	/** Forgets the mob chosen for this draw and any settling. */
	private static void dropBowTarget() {
		lockedBowTarget = null;
		settled(null, 0);
		expectedPoint = null;
		expectedTarget = null;
	}

	/** Forgets a push being counted and a letting go in progress: nothing to push away from any more. */
	private static void endPush() {
		letGo = false;
		ticksPushingAway = 0;
	}

	/**
	 * Whether the player has settled on {@code pick}, the mob the search found this tick - null for
	 * none: the stick under {@link #LET_GO_RELEASE}, and the same mob {@link #PICK_TICKS} ticks in a
	 * row (tl94). The stick pushed past half, or a different mob, starts the count over. Takes any
	 * object, and nothing of the world, so it can be checked on its own.
	 */
	static boolean settled(@Nullable Object pick, double stickPush) {
		if (pick == null || stickPush > LET_GO_RELEASE) {
			pendingPick = null;
			pendingTicks = 0;
			return false;
		}
		pendingTicks = pick == pendingPick ? pendingTicks + 1 : 1;
		pendingPick = pick;
		return pendingTicks >= PICK_TICKS;
	}

	/**
	 * Whether a locked mob's aim help uses the Locked settings - Locked Strength, Range and Speed, and
	 * Ignore Crosshair Cone - rather than the weapon's own: only while a mob is locked, and only for a
	 * weapon whose Override switch is on (tl89). Kept apart, with nothing of the world in it, so it can
	 * be checked on its own.
	 */
	static boolean usesLockedSettings(boolean locked, boolean bowMode, AimAssistSettings settings) {
		return locked && (bowMode ? settings.lockOverridesBow : settings.lockOverridesMelee);
	}

	/** Bearing of a direction, in Minecraft's yaw convention. */
	static double yawOf(Vec3 direction) {
		return Math.toDegrees(Math.atan2(-direction.x, direction.z));
	}

	/** Elevation of a direction, in Minecraft's pitch convention. */
	static double pitchOf(Vec3 direction) {
		double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
		return Math.toDegrees(-Math.atan2(direction.y, horizontal));
	}

	/**
	 * How far something actually moved over the last tick. Taken from the positions rather than
	 * {@code getDeltaMovement}, which the client only refreshes for other entities when the server
	 * sends a velocity packet, and so reads zero for most of a walking mob's life.
	 */
	static Vec3 tickMotion(Entity entity) {
		return new Vec3(entity.getX() - entity.xOld, entity.getY() - entity.yOld, entity.getZ() - entity.zOld);
	}

	/**
	 * Which way the crosshair is being helped: now, and a tick from now if everyone keeps moving as
	 * they are - the difference between the two is how fast the target is sliding across the view.
	 * Onto the mob itself, or with Trajectory Aim on - {@code aim} not null - onto where the shot has
	 * to go, {@code aim} being the point {@code ticksAhead} ticks on ({@link #HOLD_TICKS_AHEAD} for
	 * the hold, 0 for a snap).
	 */
	record Heading(Vec3 now, Vec3 next) {
	}

	static Heading heading(LocalPlayer player, Entity target, TrajectoryAim.Aim aim, TrajectoryAim.Setup trajectory, int ticksAhead) {
		if (aim != null) {
			// A tick from now the bow is drawn further too. Live aim that has only just come into
			// reach jumps away from the full-draw aim; the pull closes that gap rather than the
			// camera being carried across it in one tick.
			TrajectoryAim.Aim next = TrajectoryAim.aim(player, target, trajectory, ticksAhead + 1);
			return new Heading(aim.direction(),
					next != null && next.live() == aim.live() ? next.direction() : aim.direction());
		}
		Vec3 toTarget = aimPoint(target).subtract(player.getEyePosition());
		return new Heading(toTarget, toTarget.add(tickMotion(target)).subtract(tickMotion(player)));
	}

	/** Drawing a bow: using an item that draws the way a bow does. */
	static boolean isDrawingBow(LocalPlayer player) {
		return player.isUsingItem() && player.getUseItem().getUseAnimation() == ItemUseAnimation.BOW;
	}

	/**
	 * True while the player is lining up a projectile shot: drawing a bow, or holding a loaded
	 * crossbow. Charging a crossbow is a reload rather than a shot, so it keeps melee assist.
	 */
	private static boolean isAimingProjectile(LocalPlayer player) {
		if (player.isUsingItem()) {
			ItemUseAnimation animation = player.getUseItem().getUseAnimation();
			if (animation == ItemUseAnimation.BOW) {
				return true;
			}
			if (animation == ItemUseAnimation.CROSSBOW) {
				return false;
			}
		}
		return isLoadedCrossbow(player.getMainHandItem()) || isLoadedCrossbow(player.getOffhandItem());
	}

	/** A crossbow with something loaded in it - the one thing that shoots without being drawn. */
	static boolean isLoadedCrossbow(ItemStack stack) {
		ChargedProjectiles charged = stack.get(DataComponents.CHARGED_PROJECTILES);
		return charged != null && !charged.isEmpty();
	}

	private static @Nullable Entity findTarget(LocalPlayer player, AimAssistSettings settings, double cone, double range, boolean bowMode,
			TrajectoryAim.Setup trajectory) {
		// A target chosen while drawing stays chosen, so a mob wandering across the view can't
		// steal the assist halfway through a shot. With Trajectory Aim holding the crosshair on its
		// point it is kept for the whole draw, wherever the crosshair is, while it is within Distance
		// and in sight - the stick pushed hard away is what lets it go (tl93, in apply). Without a
		// point, as it always was: given up a cone and a half out.
		// What is being shot, and from where, is the same for every mob this tick: read once.
		TrajectoryAim.Draw draw = TrajectoryAim.draw(player, trajectory, HOLD_TICKS_AHEAD);
		if (bowMode && lockedBowTarget != null
				&& isEligible(player, lockedBowTarget, settings)
				&& player.hasLineOfSight(lockedBowTarget)) {
			if (TrajectoryAim.aim(draw, lockedBowTarget, trajectory) != null) {
				if (player.getEyePosition().distanceTo(aimPoint(lockedBowTarget)) <= range) {
					return lockedBowTarget;
				}
			} else if (angleTo(player, lockedBowTarget) <= cone * BOW_LOCK_TOLERANCE) {
				return lockedBowTarget;
			}
		}

		Vec3 eye = player.getEyePosition();
		Vec3 view = player.getViewVector(1.0f);
		Vec3 rayEnd = eye.add(view.scale(range));
		Counts counts = new Counts();
		lastCounts = counts;
		// One pass over everything in reach: what is there is counted, and eligibility decided, as it goes.
		List<Entity> nearby = player.level().getEntities(player, player.getBoundingBox().inflate(range), entity -> true);
		counts.nearby = nearby.size();

		Entity bestByAngle = null;
		double bestAngle = Double.MAX_VALUE;
		Entity underCrosshair = null;
		double underCrosshairDistance = Double.MAX_VALUE;

		for (Entity candidate : nearby) {
			if (!isEligible(player, candidate, settings)) {
				continue;
			}
			counts.eligible++;
			double distance = eye.distanceTo(aimPoint(candidate));
			if (distance > range) {
				counts.tooFar++;
				continue;
			}

			// Whether the look ray actually passes through this hitbox, and otherwise how far off it
			// is; with Trajectory Aim on, measured to the way from the mob to where its shot has to go.
			TrajectoryAim.Aim aim = TrajectoryAim.aim(draw, candidate, trajectory);
			AABB box = candidate.getBoundingBox().inflate(RAY_HITBOX_PADDING);
			boolean onRay = box.clip(eye, rayEnd).isPresent();
			double onMob = onRay ? 0 : angleOff(box, eye, view);
			double angle = aim != null ? trajectoryOffset(player, candidate, aim, onMob) : onMob;
			if (counts.bestAngle < 0 || angle < counts.bestAngle) {
				counts.bestAngle = angle;
			}
			if (angle > cone) {
				counts.outsideCone++;
				continue;
			}
			if (!player.hasLineOfSight(candidate)) {
				counts.losBlocked++;
				continue;
			}

			// The look ray through the hitbox beats "nearest to the crosshair" for bows, so a zombie
			// at your elbow can't outrank the skeleton you are lined up on just by being closer in
			// angle. With Trajectory Aim on, lined up means anywhere on the way from the mob to where
			// its shot has to go.
			boolean onTarget = aim != null ? angle == 0 : onRay;
			if (onTarget && distance < underCrosshairDistance) {
				underCrosshair = candidate;
				underCrosshairDistance = distance;
			}

			if (angle < bestAngle) {
				bestAngle = angle;
				bestByAngle = candidate;
			}
		}

		if (bowMode && underCrosshair != null) {
			return underCrosshair;
		}
		return bestByAngle;
	}

	static boolean isEligible(LocalPlayer player, Entity entity, AimAssistSettings settings) {
		return isEligible(player, entity, settings.targets, settings.customTargets, playersAllowed(settings));
	}

	/**
	 * Whether a mob is hostile by the rule Target: Hostile uses - a monster, or a neutral mob that is
	 * angry - whatever Target is actually set to. It is what the snaps look for. Other players count
	 * as well while Target Players is on, as they do everywhere else.
	 */
	static boolean isHostile(LocalPlayer player, Entity entity, AimAssistSettings settings) {
		return isEligible(player, entity, AimAssistTargets.HOSTILE, List.of(), playersAllowed(settings));
	}

	/**
	 * Whether other players may be targeted at all: only with Target Players on, and only where the
	 * Aim Assist setting allows aim assist. That holds in Marker only too, which otherwise runs
	 * anywhere - a marker and compass bar on a mob are an overlay, but on a player they are an
	 * advantage over that player.
	 */
	private static boolean playersAllowed(AimAssistSettings settings) {
		return settings.targetPlayers && settings.mode.canAimAssist();
	}

	private static boolean isEligible(LocalPlayer player, Entity entity, AimAssistTargets targets, List<String> customTargets,
			boolean players) {
		if (entity == player || entity == player.getVehicle() || !entity.isAlive()) {
			return false;
		}
		if (entity.isSpectator() || entity.isInvisibleTo(player)) {
			return false;
		}
		// Other players only with Target Players on, and then whatever Target is set to - Donny's
		// switch, 27 Sep. Never one the game itself would not let you hurt: a teammate while the
		// team has friendly fire off, by the game's own rule for it.
		if (entity instanceof Player other) {
			return players && player.canHarmPlayer(other);
		}

		// Armour stands, boats and the like only count if the player has explicitly listed them.
		boolean isMob = entity instanceof LivingEntity && !(entity instanceof ArmorStand);

		return switch (targets) {
			case HOSTILE -> isMob && (entity.getType().getCategory() == MobCategory.MONSTER || isAngry(entity));
			case ALL_MOBS -> isMob;
			case CUSTOM -> customTargets.contains(typeId(entity));
		};
	}

	/**
	 * Whether a normally peaceful mob is currently fighting, so an angry wolf pack or a swarm of
	 * bees counts as hostile. The client only sees the mob's synced aggressive flag, not its
	 * actual AI target, so this catches the mobs that bother to sync it and no more.
	 */
	private static boolean isAngry(Entity entity) {
		return entity instanceof Mob mob && mob.isAggressive();
	}

	static String typeId(Entity entity) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
	}

	/** The point the magnetism pulls towards: just below the head, around the top of the chest. */
	static Vec3 aimPoint(Entity entity) {
		AABB box = entity.getBoundingBox();
		// Clamped into the hitbox, since a few entities sit their eyes at or above their own box.
		double y = Mth.clamp(box.minY + entity.getEyeHeight() * AIM_HEIGHT_FACTOR, box.minY, box.maxY);
		return new Vec3((box.minX + box.maxX) / 2, y, (box.minZ + box.maxZ) / 2);
	}

	private static double angleTo(LocalPlayer player, Entity entity) {
		return angleBetween(player.getViewVector(1.0f), aimPoint(entity).subtract(player.getEyePosition()));
	}

	/**
	 * How far off the crosshair an entity is, measured to the nearest part of its hitbox rather
	 * than its centre. A zombie three blocks away is over a metre wide, so measuring to the centre
	 * reports several degrees off even when the crosshair is plainly on it.
	 *
	 * <p>
	 * Package-private so target lock's F.O.V Lock measures its angle exactly the way Crosshair Cone
	 * does, rather than by a second calculation that could drift from this one.
	 *
	 * @return 0 when the look ray passes through the hitbox
	 */
	static double angularOffset(LocalPlayer player, Entity entity, double range) {
		Vec3 eye = player.getEyePosition();
		Vec3 view = player.getViewVector(1.0f);
		AABB box = entity.getBoundingBox().inflate(RAY_HITBOX_PADDING);
		return box.clip(eye, eye.add(view.scale(range))).isPresent() ? 0 : angleOff(box, eye, view);
	}

	/**
	 * {@link #angularOffset} for a look ray that misses the padded box: the angle from the ray to the
	 * nearest of the box's corners, the midpoints of its vertical edges, and its centre.
	 */
	private static double angleOff(AABB box, Vec3 eye, Vec3 view) {
		double viewLength = view.length();
		Vec3 centre = box.getCenter();
		double best = angleBetween(view, viewLength, centre.x - eye.x, centre.y - eye.y, centre.z - eye.z);
		double midY = (box.minY + box.maxY) / 2;
		for (int corner = 0; corner < 12; corner++) {
			double x = (corner & 1) == 0 ? box.minX : box.maxX;
			double y = corner < 4 ? box.minY : corner < 8 ? midY : box.maxY;
			double z = (corner & 2) == 0 ? box.minZ : box.maxZ;
			best = Math.min(best, angleBetween(view, viewLength, x - eye.x, y - eye.y, z - eye.z));
		}
		return best;
	}

	/**
	 * How far off the crosshair a mob is with Trajectory Aim on: nothing anywhere on the way from the
	 * mob to where its shot has to go - the arc the crosshair travels from one to the other, as wide
	 * as the mob - and otherwise the nearer of the two. So Crosshair Cone reaches out from both, and a
	 * crosshair already on the point, however far above the mob, is still on it. {@code onMob} is the
	 * mob's own {@link #angularOffset}, already worked out.
	 */
	static double trajectoryOffset(LocalPlayer player, Entity entity, TrajectoryAim.Aim aim, double onMob) {
		if (onMob == 0) {
			return 0;
		}
		Vec3 toMob = aimPoint(entity).subtract(player.getEyePosition());
		double halfWidth = entity.getBbWidth() / 2 + RAY_HITBOX_PADDING;
		double distance = toMob.length();
		double width = distance > halfWidth ? Math.toDegrees(Math.asin(halfWidth / distance)) : 90;
		double onTheWay = Math.max(0, angleToArc(player.getViewVector(1.0f), toMob, aim.direction()) - width);
		return Math.min(onMob, onTheWay);
	}

	/**
	 * Angle, in degrees, from {@code view} to the nearest point of the shorter arc between two
	 * directions: straight across to it where {@code view} lies alongside the arc, otherwise to the
	 * nearer end.
	 */
	static double angleToArc(Vec3 view, Vec3 from, Vec3 to) {
		Vec3 v = view.normalize();
		Vec3 a = from.normalize();
		Vec3 b = to.normalize();
		Vec3 normal = a.cross(b);
		double sine = normal.length();
		if (sine < 1.0e-9) {
			return angleBetween(v, a);
		}
		normal = normal.scale(1 / sine);
		Vec3 alongside = v.subtract(normal.scale(v.dot(normal)));
		if (alongside.lengthSqr() > 1.0e-12
				&& a.cross(alongside).dot(normal) >= 0
				&& alongside.cross(b).dot(normal) >= 0) {
			return Math.toDegrees(Math.asin(Mth.clamp(Math.abs(v.dot(normal)), 0, 1)));
		}
		return Math.min(angleBetween(v, a), angleBetween(v, b));
	}

	/** Angle between two vectors, in degrees. */
	private static double angleBetween(Vec3 a, Vec3 b) {
		return angleBetween(a, a.length(), b.x, b.y, b.z);
	}

	/** The same, for {@code a} of length {@code aLength} and a second vector given by its parts. */
	private static double angleBetween(Vec3 a, double aLength, double bx, double by, double bz) {
		double lengths = aLength * Math.sqrt(bx * bx + by * by + bz * bz);
		if (lengths == 0) {
			return 180;
		}
		return Math.toDegrees(Math.acos(Mth.clamp((a.x * bx + a.y * by + a.z * bz) / lengths, -1, 1)));
	}

	/*
	 * The percentages below are on melee's scale, where 100 is melee's 100%. A locked target's
	 * arrive doubled (LOCKED_SCALE), so they run on to 200. Three stop at 100, where going on
	 * would make things worse rather than stronger: the slowdown, the gain and the follow's share.
	 * The pull's ceiling, the follow's ceiling and the speed boost carry on to 200.
	 */

	/**
	 * Strength: how far the look input is scaled down at the centre of the cone. Stops at 100,
	 * where it is total: past it the look input would be multiplied by a negative number and the
	 * stick pushed backwards.
	 */
	private static double slowdownFor(int percent) {
		return 1 - Mth.clamp(percent, 0, 100) / 100.0;
	}

	/** Strength: the ceiling on the pull, in degrees per tick. Twice melee's at a locked 100%. */
	private static double pullFor(int percent) {
		return MAX_PULL * Mth.clamp(percent, 0, MAX_SCALED_PERCENT) / 100.0;
	}

	/**
	 * Strength: how much of the close-range shortfall is made up. Stops at 100: past it the
	 * crosshair would be carried ahead of a mob sliding across the view rather than onto it. A
	 * stronger lock raises the follow's ceiling instead - {@link #followCeilingFor}.
	 */
	private static double followFor(int percent) {
		return MAX_FOLLOW * Mth.clamp(percent, 0, 100) / 100.0;
	}

	/**
	 * Strength: the ceiling on the follow, in degrees per tick. {@link #MAX_FOLLOW_RATE} up to 100;
	 * past it, only reached by a locked target, it rises in step with the pull, to twice at a locked
	 * 100%. With the follow's share held at its 100 value the two together still never carry the
	 * crosshair past a mob sliding across the view.
	 */
	private static double followCeilingFor(int percent) {
		return MAX_FOLLOW_RATE * Math.max(1, Mth.clamp(percent, 0, MAX_SCALED_PERCENT) / 100.0);
	}

	/**
	 * Speed: how much of the angle still to go the pull closes each tick. Stops at 100, where it
	 * closes 90% of it: closing more than all of it would overshoot, which reads as shaking.
	 */
	private static double gainFor(int percent) {
		return MAX_GAIN * Mth.clamp(percent, 0, 100) / 100.0;
	}

	/**
	 * Speed past 100 - a locked target's only: how many times faster than Strength alone allows
	 * the pull may turn. Twice at a locked 100%; 1 up to 100, where Speed is the gain alone.
	 */
	private static double speedBoost(int percent) {
		return Math.max(1, Mth.clamp(percent, 0, MAX_SCALED_PERCENT) / 100.0);
	}

	/** How fast the pull may take the crosshair to Trajectory Aim's point, in degrees a tick. */
	private static double trajectoryRateFor(int percent) {
		return MAX_TRAJECTORY_RATE * Mth.clamp(percent, 0, MAX_SCALED_PERCENT) / 100.0;
	}
}
