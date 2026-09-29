/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.aimassist;

import dev.isxander.controlify.config.settings.AimAssistSettings;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.IntSupplier;

/**
 * Trajectory Aim (tl91, Donny's request of 28 Sep): where to point a drawn bow or a loaded crossbow
 * so the shot lands on a mob - above it, to allow for the drop over the distance, and ahead of it if
 * it is moving - for aim assist to help the crosshair onto instead of onto the mob itself.
 * <p>
 * It follows how the game flies a shot, read out of the 26.3 jar and the same in 26.1 and 26.2. An
 * arrow starts 0.1 below the eyes, and each tick it moves by its motion, keeps 0.99 of that motion,
 * and falls 0.05 a tick faster (AbstractArrow.tick). A bow shoots at three times its power, which
 * rises with the draw (BowItem.getPowerForTime), and adds the shooter's own movement - sideways
 * always, up and down only off the ground (Projectile.shootFromRotation). A crossbow shoots an arrow
 * at 3.15, or a rocket at 1.6 from 0.15 below the eyes that flies straight, and adds none of the
 * shooter's movement (CrossbowItem). The game adds a little random spread to every shot, which
 * nothing can allow for.
 * <p>
 * Between ticks a shot flies in a straight line, so where it is at any moment, whole tick or not,
 * can be worked out exactly - {@link #solve}. It aims for the middle of the mob, where a shot has
 * the most room to land, and ahead of a moving mob by its movement a tick - averaged over its last
 * few ticks ({@link MotionAverage}, tl93), since read over one tick it is rough and the shot's whole
 * flight multiplies it - for as long as the shot will be flying: sideways always, up and down only
 * for a mob that flies ({@link TargetLock#isFlying}), so one jumping does not make the aim bounce.
 * <p>
 * What the client shows of a mob is behind where the server has it, and the server is where the shot
 * flies (tl92, Donny 28 Sep). A move packet takes half the round trip to arrive, the release of the
 * bow takes the other half to get there, and between packets the client replays a mob's movement
 * three ticks late (SteppedInterpolationHandler in 26.3, and the three-step lerp before it, both
 * over a tracker that sends every three ticks - EntityType's updateInterval). So with Lag
 * Compensation on, a mob is taken to keep moving for that long before the shot starts:
 * {@link #lagTicks}.
 */
public final class TrajectoryAim {
	/** How much faster an arrow falls each tick: AbstractArrow.getDefaultGravity. */
	static final double ARROW_GRAVITY = 0.05;
	/** How much of its motion an arrow keeps each tick in the air: AbstractArrow's INERTIA, a float. */
	static final double ARROW_DRAG = 0.99f;
	/** An arrow starts this far below the eyes: AbstractArrow's constructor, getEyeY() - 0.1f. */
	static final double ARROW_SPAWN_DROP = 0.1f;
	/** BowItem.releaseUsing shoots at three times the bow's power. */
	static final double BOW_SPEED_PER_POWER = 3.0;
	/** BowItem.releaseUsing lets nothing go below this power. */
	static final double MIN_BOW_POWER = 0.1;
	/** How much slower a player in the air rises each tick: LivingEntity.DEFAULT_BASE_GRAVITY (tl94). */
	static final double PLAYER_GRAVITY = 0.08;
	/** How much of their vertical motion a player in the air keeps each tick, after gravity: LivingEntity.travelInAir's 0.98f. */
	static final double PLAYER_VERTICAL_DRAG = 0.98f;
	/** CrossbowItem's ARROW_POWER. */
	static final double CROSSBOW_ARROW_SPEED = 3.15f;
	/** CrossbowItem's FIREWORK_POWER. A rocket from a crossbow flies straight: no drop, no drag. */
	static final double CROSSBOW_ROCKET_SPEED = 1.6f;
	/** CrossbowItem.createProjectile starts a rocket at getEyeY() - 0.15f. */
	static final double ROCKET_SPAWN_DROP = 0.15f;

	/** A tick is a twentieth of a second. */
	static final double MS_PER_TICK = 50;
	/**
	 * How far behind the server what the client shows of a mob is, before any network delay: the three
	 * ticks the client replays a mob's movement late, and the half tick, on average, between the
	 * release reaching the server and the server tick that fires the shot. 175 ms - there in
	 * singleplayer too.
	 */
	static final double DISPLAY_DELAY_TICKS = 3.5;

	/** The longest flight looked along, in ticks: ten seconds, far past anything aim assist reaches. */
	static final double MAX_FLIGHT_TICKS = 200;
	/** Step along a flight while looking for where it first reaches the mob, in ticks. */
	private static final double SEARCH_STEP = 0.25;
	/** Halvings of that step once found, which leaves it within a millionth of a tick. */
	private static final int REFINE_STEPS = 40;
	/**
	 * How much of its motion an arrow still has after each whole tick of a flight, worked out once.
	 * Aim assist looks along a flight for every mob in range each tick, and working this out afresh
	 * at every step was two thirds of that work.
	 */
	private static final double[] ARROW_KEPT = new double[(int) MAX_FLIGHT_TICKS + 2];

	static {
		ARROW_KEPT[0] = 1;
		for (int n = 1; n < ARROW_KEPT.length; n++) {
			ARROW_KEPT[n] = ARROW_KEPT[n - 1] * ARROW_DRAG;
		}
	}

	private TrajectoryAim() {
	}

	/**
	 * How a shot flies: how fast it leaves, how much faster it falls each tick, how much of its
	 * motion it keeps each tick, how far below the eyes it starts, and whether it carries the
	 * shooter's own movement.
	 */
	record Shot(double speed, double gravity, double drag, double spawnDrop, boolean carriesShooterMotion) {
		/** A bow's arrow at a power: the speed is worked out in floats, as BowItem.releaseUsing does. */
		static Shot bow(float power) {
			return new Shot(power * (float) BOW_SPEED_PER_POWER, ARROW_GRAVITY, ARROW_DRAG, ARROW_SPAWN_DROP, true);
		}
	}

	/** Which way to shoot, as a unit direction, and how many ticks the shot takes to get there. */
	record Launch(double x, double y, double z, double ticks) {
	}

	/**
	 * Where to point: a unit direction from the eyes, and whether it is for the bow as drawn right
	 * now, rather than for a full draw or a crossbow. Live aim that has only just come into reach
	 * jumps away from the full-draw aim, and that tells aim assist not to carry the camera across the
	 * jump in one tick.
	 */
	record Aim(Vec3 direction, boolean live) {
	}

	/**
	 * What Trajectory Aim is doing this tick: its mode; how many ticks a moving mob is taken to keep
	 * moving before the shot starts - Lag Compensation's allowance, 0 with it off; and Live Start
	 * Angle, how close to the full-draw point, in degrees, the point for the draw as it is has to
	 * come before Live aims for it (tl93).
	 */
	record Setup(TrajectoryAimMode mode, double lagTicks, double liveStartDegrees, boolean jumping) {
		static final Setup OFF = new Setup(TrajectoryAimMode.OFF, 0, 0, false);

		boolean isOn() {
			return mode.isOn();
		}
	}

	/**
	 * The setup for this tick from the settings: off unless a bow is drawn or a crossbow loaded
	 * ({@code bowMode}) and Trajectory Aim is on - and, with Lock-On Only on, unless the mob in
	 * question is the one locked with Lock-On ({@code locked}, tl95: Donny, 28 Sep 23:04, "I would
	 * rather trajectory aim never took a mob the cone found, only one you've locked with lock on");
	 * then the mode, with Lag Compensation's allowance from the ping the server reports for
	 * {@code player}, Live Start Angle, and whether the jump button is down this tick (tl94) - the
	 * player's tick, which comes after the look, will make the jump, and the shot a tick on carries
	 * it. Off, the bow's ordinary help is what the mob gets, onto the mob itself.
	 */
	static Setup setup(AimAssistSettings settings, boolean bowMode, LocalPlayer player, boolean jumping, boolean locked) {
		if (!bowMode || !settings.trajectoryAim.isOn() || (settings.trajectoryLockedOnly && !locked)) {
			return Setup.OFF;
		}
		return new Setup(settings.trajectoryAim, lagTicks(settings.lagCompensation, settings.lagAllowanceMs, () -> pingMs(player)),
				settings.liveStartDegrees, jumping);
	}

	/**
	 * Lag Compensation's allowance in ticks: nothing while off; Lag Allowance as set, for Manual; the
	 * ping the server reports plus {@link #DISPLAY_DELAY_TICKS}, for Auto. Kept free of anything of
	 * the game's so it can be checked on its own.
	 */
	static double lagTicks(LagCompensationMode mode, int manualMs, IntSupplier pingMs) {
		return switch (mode) {
			case OFF -> 0;
			case MANUAL -> manualMs / MS_PER_TICK;
			case AUTO -> DISPLAY_DELAY_TICKS + Math.max(0, pingMs.getAsInt()) / MS_PER_TICK;
		};
	}

	/**
	 * The ping the server reports for this player, in milliseconds - the number beside the name in
	 * the tab list, which the server measures on its keep-alives every 15 seconds and sends out every
	 * 30. 0 until it has said, and in singleplayer, where the server never measures its own player.
	 */
	static int pingMs(LocalPlayer player) {
		PlayerInfo info = player.connection == null ? null : player.connection.getPlayerInfo(player.getUUID());
		return info == null ? 0 : info.getLatency();
	}

	/**
	 * Where a mob's middle is taken to be as the shot starts: on from where it is shown by its
	 * movement over the lag allowance, and over the tick ahead if one is asked for.
	 */
	static Vec3 startingPoint(Vec3 middle, Vec3 lead, double lagTicks, int ticksAhead) {
		return middle.add(lead.scale(lagTicks + ticksAhead));
	}

	/**
	 * Where to point to land a shot on {@code target} with what {@code player} is aiming - a drawn
	 * bow, or else a loaded crossbow - or null when Trajectory Aim is off, nothing is being aimed,
	 * or no shot can reach it. With {@code ticksAhead} at 1, where that will be a tick from now, as
	 * the bow draws on and everyone keeps moving as they are.
	 */
	static @Nullable Aim aim(LocalPlayer player, Entity target, Setup setup, int ticksAhead) {
		if (!setup.isOn()) {
			return null;
		}
		Shot full;
		Shot live = null;
		if (AimAssist.isDrawingBow(player)) {
			full = Shot.bow(1);
			if (setup.mode() == TrajectoryAimMode.LIVE) {
				float power = BowItem.getPowerForTime(player.getTicksUsingItem() + ticksAhead);
				if ((double) power >= MIN_BOW_POWER) {
					live = Shot.bow(power);
				}
			}
		} else {
			ItemStack crossbow = loadedCrossbow(player);
			if (crossbow == null) {
				return null;
			}
			ChargedProjectiles charged = crossbow.get(DataComponents.CHARGED_PROJECTILES);
			full = charged != null && charged.contains(Items.FIREWORK_ROCKET)
					? new Shot(CROSSBOW_ROCKET_SPEED, 0, 1, ROCKET_SPAWN_DROP, false)
					: new Shot(CROSSBOW_ARROW_SPEED, ARROW_GRAVITY, ARROW_DRAG, ARROW_SPAWN_DROP, false);
		}

		Vec3 targetMotion = MotionAverage.motion(target);
		Vec3 lead = TargetLock.isFlying(target) ? targetMotion : new Vec3(targetMotion.x, 0, targetMotion.z);
		Vec3 middle = startingPoint(target.getBoundingBox().getCenter(), lead, setup.lagTicks(), ticksAhead);
		Shooter shooter = ahead(player.getEyePosition(), AimAssist.tickMotion(player), player.onGround(),
				setup.jumping() ? jumpPower(player) : 0, player.getBoundingBox(),
				(box, dy) -> Entity.collideBoundingBox(player, new Vec3(0, dy, 0), box, player.level(), List.of()).y, ticksAhead);
		return aimFrom(shooter.eye(), shooter.motion(), shooter.onGround(), 0, middle, lead, live, full, setup.liveStartDegrees());
	}

	/** Where the shooter's eyes will be, how they will be moving, and whether they will be on the ground, some ticks on. */
	record Shooter(Vec3 eye, Vec3 motion, boolean onGround) {
	}

	/** How far down a box may move before the ground stops it: the movement's y after collision, {@code dy} itself if nothing is hit. */
	interface Floor {
		double fall(AABB box, double dy);
	}

	/**
	 * How high the player will jump this tick if they do: the jump strength attribute, 0.42 for a
	 * player, plus Jump Boost. LivingEntity.getJumpPower, less the block's own factor (honey), which
	 * is protected.
	 */
	static double jumpPower(LocalPlayer player) {
		return player.getAttributeValue(Attributes.JUMP_STRENGTH) + player.getJumpBoostPower();
	}

	/**
	 * The shooter {@code ticksAhead} ticks on, moving as they are: on the ground, straight on by their
	 * movement each tick, nothing up or down (the tick they landed on still carries the last of the
	 * fall in its move, which the ground has stopped) - or up by {@code jump} on the first tick if the
	 * jump button is down, as LivingEntity.jumpFromGround makes the move; in the air, with gravity
	 * taking {@link #PLAYER_GRAVITY} off their rise each tick and {@link #PLAYER_VERTICAL_DRAG} of
	 * what is left kept, as the game moves them (LivingEntity.travelInAir), and a fall stopped where
	 * the ground stops it ({@code floor}, the game's own collision) - tl94. A shot fired then starts
	 * from those eyes and carries that tick's movement, which is what the server has of the player
	 * when the release reaches it. The world comes in only through {@code floor}, so it can be
	 * checked on its own.
	 */
	static Shooter ahead(Vec3 eye, Vec3 motion, boolean onGround, double jump, AABB box, Floor floor, int ticksAhead) {
		Vec3 offset = Vec3.ZERO;
		for (int i = 0; i < ticksAhead; i++) {
			if (onGround && jump > 0) {
				motion = new Vec3(motion.x, jump, motion.z);
				onGround = false;
				jump = 0;
			} else if (onGround) {
				motion = new Vec3(motion.x, 0, motion.z);
			} else {
				motion = new Vec3(motion.x, (motion.y - PLAYER_GRAVITY) * PLAYER_VERTICAL_DRAG, motion.z);
				if (motion.y < 0) {
					double dy = floor.fall(box.move(offset), motion.y);
					if (dy > motion.y) {
						motion = new Vec3(motion.x, dy, motion.z);
						onGround = true;
					}
				}
			}
			offset = offset.add(motion);
		}
		return new Shooter(eye.add(offset), motion, onGround);
	}

	/**
	 * {@link #aim} once everything has been read from the world: the eyes, the shooter's movement over
	 * the last tick and whether they are on the ground, the mob's middle as the shot starts and its
	 * movement each tick, and the shots to try. The full-draw shot decides whether the mob can be
	 * reached at all. The live one, when there is one, is aimed for only once its point has come
	 * within {@code liveStartDegrees} of the full-draw point (tl93, Donny 28 Sep: a weak early draw
	 * wants a lob far above the mob, and the crosshair swung up to it and back down as the draw went
	 * on; now it waits on the full-draw point until Live's is nearly there, and only ever makes that
	 * last move).
	 */
	static @Nullable Aim aimFrom(Vec3 eye, Vec3 playerMotion, boolean onGround, int ticksAhead, Vec3 middle, Vec3 lead,
			@Nullable Shot live, Shot full, double liveStartDegrees) {
		Vec3 fullDirection = direction(eye, playerMotion, onGround, ticksAhead, middle, lead, full);
		if (fullDirection == null) {
			return null;
		}
		if (live != null) {
			Vec3 liveDirection = direction(eye, playerMotion, onGround, ticksAhead, middle, lead, live);
			if (liveDirection != null && angleBetween(liveDirection, fullDirection) <= liveStartDegrees) {
				return new Aim(liveDirection, true);
			}
		}
		return new Aim(fullDirection, false);
	}

	/** Angle between two unit directions, in degrees. */
	static double angleBetween(Vec3 a, Vec3 b) {
		return Math.toDegrees(Math.acos(Mth.clamp(a.dot(b), -1, 1)));
	}

	private static @Nullable Vec3 direction(Vec3 eye, Vec3 playerMotion, boolean onGround, int ticksAhead, Vec3 middle, Vec3 lead,
			Shot shot) {
		Vec3 start = new Vec3(eye.x, eye.y - shot.spawnDrop(), eye.z).add(playerMotion.scale(ticksAhead));
		Vec3 carried = shot.carriesShooterMotion()
				? new Vec3(playerMotion.x, onGround ? 0 : playerMotion.y, playerMotion.z)
				: Vec3.ZERO;
		Vec3 toMiddle = middle.subtract(start);
		Launch launch = solve(toMiddle.x, toMiddle.y, toMiddle.z, lead.x, lead.y, lead.z,
				carried.x, carried.y, carried.z, shot.speed(), shot.gravity(), shot.drag());
		return launch == null ? null : new Vec3(launch.x(), launch.y(), launch.z());
	}

	/** A loaded crossbow in the main hand, or else the off hand - the one a press of use shoots. */
	private static @Nullable ItemStack loadedCrossbow(LocalPlayer player) {
		for (ItemStack stack : new ItemStack[]{player.getMainHandItem(), player.getOffhandItem()}) {
			ChargedProjectiles charged = stack.get(DataComponents.CHARGED_PROJECTILES);
			if (charged != null && !charged.isEmpty()) {
				return stack;
			}
		}
		return null;
	}

	/**
	 * Which way to shoot at {@code speed} so the shot reaches a point {@code (dx, dy, dz)} from where
	 * it starts, when that point moves {@code (ux, uy, uz)} each tick and the shot also carries
	 * {@code (sx, sy, sz)} of the shooter's movement - or null when no direction gets there within
	 * {@link #MAX_FLIGHT_TICKS}. Of the two ways there, it is the flatter and quicker one.
	 * <p>
	 * Shot along a unit direction {@code d}, after {@code t} ticks the shot has come
	 * {@code (speed d + s) carried(t)} and dropped {@code gravity dropped(t)}. The point is then at
	 * {@code D + u t}. They meet when {@code speed d carried(t)} equals
	 * {@code W(t) = D + u t - s carried(t) + gravity dropped(t) up}: at the first {@code t} where
	 * {@code |W(t)|} comes down to {@code speed carried(t)}, with {@code d} that {@code W(t)} made
	 * a unit. Kept free of anything of the game's so it can be checked on its own.
	 */
	static @Nullable Launch solve(double dx, double dy, double dz, double ux, double uy, double uz,
			double sx, double sy, double sz, double speed, double gravity, double drag) {
		if (speed <= 0 || Math.sqrt(dx * dx + dy * dy + dz * dz) < 1.0e-9) {
			return null;
		}
		for (double t = SEARCH_STEP; t <= MAX_FLIGHT_TICKS; t += SEARCH_STEP) {
			if (shortfall(t, dx, dy, dz, ux, uy, uz, sx, sy, sz, speed, gravity, drag) > 0) {
				continue;
			}
			double before = t - SEARCH_STEP;
			double reached = t;
			for (int i = 0; i < REFINE_STEPS; i++) {
				double mid = (before + reached) / 2;
				if (shortfall(mid, dx, dy, dz, ux, uy, uz, sx, sy, sz, speed, gravity, drag) > 0) {
					before = mid;
				} else {
					reached = mid;
				}
			}
			double c = carried(reached, drag);
			double wx = dx + ux * reached - sx * c;
			double wy = dy + uy * reached - sy * c + gravity * dropped(reached, drag);
			double wz = dz + uz * reached - sz * c;
			double length = Math.sqrt(wx * wx + wy * wy + wz * wz);
			if (length < 1.0e-9) {
				return null;
			}
			return new Launch(wx / length, wy / length, wz / length, reached);
		}
		return null;
	}

	/** How much further the point still is at {@code t} ticks than a shot at this speed can have come: {@code |W(t)| - speed carried(t)}. */
	private static double shortfall(double t, double dx, double dy, double dz, double ux, double uy, double uz,
			double sx, double sy, double sz, double speed, double gravity, double drag) {
		double c = carried(t, drag);
		double wx = dx + ux * t - sx * c;
		double wy = dy + uy * t - sy * c + gravity * dropped(t, drag);
		double wz = dz + uz * t - sz * c;
		return Math.sqrt(wx * wx + wy * wy + wz * wz) - speed * c;
	}

	/**
	 * How far a shot has come after {@code t} ticks, as a multiple of the motion it left with: whole
	 * ticks {@code 1 + drag + drag^2 + ...}, and a straight line from one to the next.
	 */
	static double carried(double t, double drag) {
		int n = (int) Math.floor(t);
		double part = t - n;
		if (drag >= 1) {
			return t;
		}
		double kept = kept(drag, n);
		return (1 - kept) / (1 - drag) + part * kept;
	}

	/**
	 * How far a shot has dropped after {@code t} ticks, as a multiple of how much faster it falls
	 * each tick. The drop joins the motion one tick after it is added, and the drag works on it from
	 * then on like the rest - which is what AbstractArrow.tick does: move, then drag, then drop.
	 */
	static double dropped(double t, double drag) {
		int n = (int) Math.floor(t);
		double part = t - n;
		if (drag >= 1) {
			return n * (n - 1) / 2.0 + part * n;
		}
		double kept = kept(drag, n);
		double whole = (1 - kept) / (1 - drag);
		return ((n - whole) + part * (1 - kept)) / (1 - drag);
	}

	/** {@code drag} to the power {@code n}: from the table for an arrow in the air, which is nearly always. */
	private static double kept(double drag, int n) {
		return drag == ARROW_DRAG && n >= 0 && n < ARROW_KEPT.length ? ARROW_KEPT[n] : Math.pow(drag, n);
	}
}
