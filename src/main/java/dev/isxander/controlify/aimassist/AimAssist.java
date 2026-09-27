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
 * onto a target by themselves, on a swing or on starting to aim.
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
	 * @param swung whether a swing was made this tick - pressed, or by Swing Timing Assist - which
	 *              is what sets off Melee Snap
	 */
	public static void apply(Vector2d lookImpulse, boolean swung) {
		AimAssistSettings settings = Controlify.instance().config().getSettings().aimAssistSettings();
		LocalPlayer player = Minecraft.getInstance().player;

		if (player == null || settings.mode == AimAssistMode.OFF || !settings.mode.canAimAssist()) {
			lockedBowTarget = null;
			AimSnap.stop();
			lastDebug = Debug.INACTIVE;
			return;
		}

		TargetLockSettings lock = settings.targetLock;
		boolean lockRunning = TargetLock.active();

		// Marker only means exactly that: the lock, the arrow and the compass, and no aim help at all.
		if (lockRunning && !lock.mode.assistsLockedTarget()) {
			lockedBowTarget = null;
			AimSnap.stop();
			lastDebug = Debug.INACTIVE;
			return;
		}
		Entity heldTarget = lockRunning ? TargetLock.locked() : null;

		boolean bowMode = isAimingProjectile(player);
		if (!bowMode) {
			lockedBowTarget = null;
		}

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
		if (AimSnap.step(player, settings, bowMode, lookImpulse)) {
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
			if (player.distanceTo(target) > range) {
				lastDebug = new Debug(null, 0, 1, bowMode, true, lastCounts, settings.targets, 0, true);
				return;
			}
		} else {
			target = findTarget(player, settings, cone, range, bowMode);
			if (target == null) {
				lastDebug = new Debug(null, 0, 1, bowMode, true, lastCounts, settings.targets, 0, false);
				return;
			}
			if (bowMode) {
				lockedBowTarget = target;
			}
		}

		double angle = angularOffset(player, target, range);
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
		double pullProximity = ignoreCone ? 1 : coneProximity;
		double slowProximity = ignoreCone
				? Math.sqrt(Mth.clamp(1 - (angle / (TURN_IN_CUSHION * 2)), 0, 1))
				: coneProximity;

		double multiplier = 1 - (1 - slowdownFor(strength)) * slowProximity;

		Vec3 toTarget = aimPoint(target).subtract(player.getEyePosition());

		// Where the target sits next tick if everyone keeps moving as they are. The change in bearing
		// is how fast it is sliding across the view, which is the rate the camera has to match just
		// to stay pointed at it.
		Vec3 nextToTarget = toTarget.add(tickMotion(target)).subtract(tickMotion(player));
		double yawDrift = Mth.wrapDegrees(yawOf(nextToTarget) - yawOf(toTarget));
		double pitchDrift = pitchOf(nextToTarget) - pitchOf(toTarget);
		double swing = Math.hypot(yawDrift, pitchDrift);

		// Magnetism only ever helps a turn that is already happening, and it can be happening for
		// two reasons: the stick is being pushed, or the target is sliding across the view because
		// the player or the mob is moving. Strafing past a zombie is the second kind, which is why
		// the stick alone is not enough to gate this. Standing still with nothing moving is neither,
		// and contributes nothing, so the camera never drifts on its own.
		double stickStrength = Mth.clamp(lookImpulse.length() / FULL_PULL_INPUT, 0, 1);
		double trackingStrength = Mth.clamp(swing / FULL_PULL_SWING, 0, 1);
		// Ignoring the cone means holding the target whatever is happening, including a standoff
		// where neither the player nor the mob is moving, so the gate comes off entirely.
		double inputStrength = ignoreCone ? 1 : Math.max(stickStrength, trackingStrength);
		double pullCap = pullFor(strength);
		double gain = useLocked ? gainFor(lockedSpeed) : DEFAULT_GAIN;
		double pullScale = pullProximity * inputStrength;

		// Speed drives the sweep, which is what it reads as on the slider: how fast the camera comes
		// round to the target. Strength still decides how hard it holds once it is there. Once the
		// gain is as high as it can usefully go, more Speed lets the pull itself turn faster instead
		// (speedBoost) - twice as fast at 100% on the slider.
		double effectiveCap = useLocked ? pullCap * speedBoost(lockedSpeed) : pullCap;
		if (ignoreCone) {
			double sweepBand = SWEEP_FULL_ANGLE - TURN_IN_CUSHION * 2;
			double reach = Mth.clamp((angle - TURN_IN_CUSHION * 2) / sweepBand, 0, 1);
			double sweepRate = MAX_SWEEP_RATE * Mth.clamp(lockedSpeed, 0, MAX_SCALED_PERCENT) / 100.0;
			effectiveCap = Mth.lerp(reach, effectiveCap, Math.max(effectiveCap, sweepRate));
		}

		double yawError = Mth.wrapDegrees(yawOf(toTarget) - player.getYRot());
		double pitchError = pitchOf(toTarget) - player.getXRot();

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
		double followYaw = 0;
		double followPitch = 0;
		if (followRate > 0 && swing > 1.0e-4) {
			followYaw = yawDrift / swing * followRate;
			followPitch = pitchDrift / swing * followRate;
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
		lookImpulse.add(yawPull + followYaw, pitchPull + followPitch);

		lastDebug = new Debug(target, angle, multiplier, bowMode, true, lastCounts, settings.targets,
				Math.hypot(yawPull + followYaw, pitchPull + followPitch), heldTarget != null);
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
	 * True while the player is lining up a projectile shot: drawing a bow, or holding a loaded
	 * crossbow. Charging a crossbow is a reload rather than a shot, so it keeps melee assist.
	 */
	private static boolean isAimingProjectile(LocalPlayer player) {
		if (player.isUsingItem()) {
			boolean drawingBow = switch (player.getUseItem().getUseAnimation()) {
				case BOW -> true;
				default -> false;
			};
			if (drawingBow) {
				return true;
			}
			boolean chargingCrossbow = switch (player.getUseItem().getUseAnimation()) {
				case CROSSBOW -> true;
				default -> false;
			};
			if (chargingCrossbow) {
				return false;
			}
		}
		return isLoadedCrossbow(player.getMainHandItem()) || isLoadedCrossbow(player.getOffhandItem());
	}

	private static boolean isLoadedCrossbow(ItemStack stack) {
		ChargedProjectiles charged = stack.get(DataComponents.CHARGED_PROJECTILES);
		return charged != null && !charged.isEmpty();
	}

	private static @Nullable Entity findTarget(LocalPlayer player, AimAssistSettings settings, double cone, double range, boolean bowMode) {
		// A target chosen while drawing stays chosen, so a mob wandering across the view can't
		// steal the assist halfway through a shot.
		if (bowMode && lockedBowTarget != null
				&& isEligible(player, lockedBowTarget, settings)
				&& angleTo(player, lockedBowTarget) <= cone * BOW_LOCK_TOLERANCE
				&& player.hasLineOfSight(lockedBowTarget)) {
			return lockedBowTarget;
		}

		Vec3 eye = player.getEyePosition();
		Vec3 view = player.getViewVector(1.0f);
		AABB searchBox = player.getBoundingBox().inflate(range);
		Counts counts = new Counts();
		lastCounts = counts;
		counts.nearby = player.level().getEntities(player, searchBox, entity -> true).size();
		List<Entity> candidates = player.level().getEntities(player, searchBox, entity -> isEligible(player, entity, settings));
		counts.eligible = candidates.size();

		Entity bestByAngle = null;
		double bestAngle = Double.MAX_VALUE;
		Entity underCrosshair = null;
		double underCrosshairDistance = Double.MAX_VALUE;

		for (Entity candidate : candidates) {
			Vec3 point = aimPoint(candidate);
			double distance = eye.distanceTo(point);
			if (distance > range) {
				counts.tooFar++;
				continue;
			}

			double angle = angularOffset(player, candidate, range);
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

			// Whether the look ray actually passes through this hitbox. For bows this beats
			// "nearest to the crosshair", so a zombie at your elbow can't outrank the skeleton
			// you are lined up on just by being closer in angle.
			boolean onTarget = candidate.getBoundingBox()
					.inflate(RAY_HITBOX_PADDING)
					.clip(eye, eye.add(view.scale(range)))
					.isPresent();
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

		if (box.clip(eye, eye.add(view.scale(range))).isPresent()) {
			return 0;
		}

		double best = angleBetween(view, box.getCenter().subtract(eye));
		for (double x : new double[]{box.minX, box.maxX}) {
			for (double y : new double[]{box.minY, (box.minY + box.maxY) / 2, box.maxY}) {
				for (double z : new double[]{box.minZ, box.maxZ}) {
					best = Math.min(best, angleBetween(view, new Vec3(x, y, z).subtract(eye)));
				}
			}
		}
		return best;
	}

	/** Angle between two vectors, in degrees. */
	private static double angleBetween(Vec3 a, Vec3 b) {
		double lengths = a.length() * b.length();
		if (lengths == 0) {
			return 180;
		}
		return Math.toDegrees(Math.acos(Mth.clamp(a.dot(b) / lengths, -1, 1)));
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
}
