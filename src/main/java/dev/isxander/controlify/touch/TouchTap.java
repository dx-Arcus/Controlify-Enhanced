/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.touch;

import com.mojang.blaze3d.platform.Window;
import dev.isxander.sdl.SdlGamepad;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3fc;

import java.util.List;

/**
 * Joystick &amp; tap to interact (tl118): what a finger on the world does in tap mode, when it is not the stick, a
 * button or the look. Bedrock's tap mode, as Donny played it on his phone (1 Oct, 15:50).
 *
 * <p>A finger that lands on the world is undecided until it moves or waits. Moved further than {@link #SLOP} from
 * where it landed, it is a look, and the move so far turns the camera at once. Lifted before {@link #HOLD_NANOS}, it
 * is a tap: on a mob, the right trigger - attack; anywhere else, the left - use, which the game makes a use of the
 * block, else of the item, as a controller's use button is. Held that long without moving, it is a hold: with an
 * item in the main hand that has a use of its own - one that takes time, as food, a bow or a shield, or that the
 * item does by itself, as a snowball, a bucket or a fishing rod ({@link #holdUses}) - the left trigger, held for as
 * long as the finger is, the finger turning the camera as well, to aim; with anything else, on a block, the right
 * trigger, held - breaking it, and as the finger slides, the next; on a mob, one attack; on nothing, nothing - and
 * after either of those last two the finger looks.
 *
 * <p>Where the game acts is the point under the finger: while a finger is down or a tap's press lasts, the game's
 * pick ({@code LocalPlayer.raycastHitResult}, which {@code feature.touch.LocalPlayerMixin} replaces in tap mode)
 * runs along the ray through that point instead of the middle of the screen, so the block outline, the attack and
 * the use all follow the finger. With no finger it finds nothing: no outline, as Bedrock shows none. What the game
 * aims by the camera rather than by its pick - a throw, a bucket, a boat, an arrow - still goes where the camera
 * looks, as Bedrock's do.
 *
 * <p>A hold shows a ring round the finger ({@link TouchPad#holdRing}), bright as far as the block has broken.
 */
public final class TouchTap {
	/** How long a finger must stay on the world without moving before it is a hold: Bedrock's press and hold. */
	static final long HOLD_NANOS = 300_000_000L;

	/** How far a finger may move from where it landed, as a fraction of the window's height, and still tap or hold. */
	static final float SLOP = 0.02f;

	private static final int USE = SdlGamepad.SDL_GAMEPAD_AXIS_LEFT_TRIGGER;
	private static final int ATTACK = SdlGamepad.SDL_GAMEPAD_AXIS_RIGHT_TRIGGER;

	/** What a finger on the world is doing. */
	enum Phase {
		/** Down, and not yet moved past {@link #SLOP} nor held for {@link #HOLD_NANOS}: a tap, a hold or a look yet. */
		PENDING,
		/** Held on a block: the right trigger held, the block under the finger breaking. */
		BREAK,
		/** Held with an item a hold uses: the left trigger held, the finger turning the camera as well. */
		USE
	}

	/**
	 * What a finger touches, and what the player holds: the game's own answers in play ({@link #GAME}), set ones in
	 * tests.
	 */
	interface Target {
		/** What the game would act on at this point of the window, 0 to 1 across and down. */
		HitResult.Type at(float x, float y);

		/** Whether a hold uses the item in the main hand rather than breaks with it. */
		boolean holdUses();
	}

	/** The finger on the world being read as a tap or a hold, or null. */
	private static @Nullable TouchPad.FingerKey finger;
	private static Phase phase = Phase.PENDING;
	private static float landX;
	private static float landY;
	private static long landNanos;
	/** Where the finger is, and where it was the frame before. */
	private static float fingerX;
	private static float fingerY;
	private static float lastX;
	private static float lastY;

	/** A tap's press: the trigger it pulls (-1 for none), when, where it aims, and whether it was held this frame. */
	private static int pressAxis = -1;
	private static long pressNanos;
	private static float pressX;
	private static float pressY;
	private static boolean pressing;

	/** A finger that has stopped tapping to look, for the pad to take as its look, and where it landed. */
	private static @Nullable TouchPad.FingerKey toLook;
	private static float toLookX;
	private static float toLookY;

	/** How far the block under a held finger has broken, 0 to 1, as the game said this frame. */
	private static float breakProgress;

	/** Whether an item's class has a use of its own - one that overrides {@code Item.use} - worked out once a class. */
	private static final ClassValue<Boolean> OWN_USE = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			try {
				return type.getMethod("use", Level.class, Player.class, InteractionHand.class).getDeclaringClass() != Item.class;
			} catch (ReflectiveOperationException | RuntimeException e) {
				return false;
			}
		}
	};

	/** The game's own answers: the game's pick through the point, and the player's main hand. */
	static final Target GAME = new Target() {
		@Override
		public HitResult.Type at(float x, float y) {
			Minecraft minecraft = Minecraft.getInstance();
			Entity camera = minecraft.getCameraEntity();
			if (minecraft.player == null || camera == null || minecraft.level == null) {
				return HitResult.Type.MISS;
			}
			return pickAt(minecraft.player, camera, minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true), x, y).getType();
		}

		@Override
		public boolean holdUses() {
			LocalPlayer player = Minecraft.getInstance().player;
			return player != null && TouchTap.holdUses(player.getMainHandItem(), player);
		}
	};

	private TouchTap() {
	}

	/** Whether no finger is being read as a tap or a hold, so a finger that lands on the world can be. */
	static boolean free() {
		return finger == null;
	}

	/** Whether this finger is the one being read as a tap or a hold. */
	static boolean owns(TouchPad.FingerKey key) {
		return key.equals(finger);
	}

	/** A finger has landed on the world: it is ours, undecided, until it lifts, moves or is held. */
	static void land(TouchInput.Finger landed, long nowNanos) {
		finger = TouchPad.FingerKey.of(landed);
		phase = Phase.PENDING;
		landX = fingerX = lastX = landed.x();
		landY = fingerY = lastY = landed.y();
		landNanos = nowNanos;
	}

	/**
	 * Every frame in tap mode, after fingers are handed out: the finger followed, and a lift, a move past
	 * {@link #SLOP} or a wait past {@link #HOLD_NANOS} turned into what it is; a tap's press ended once it has lasted
	 * {@link TouchButtons#MIN_PRESS_NANOS}.
	 */
	static void update(List<TouchInput.Finger> fingers, TouchPad.View view, Target target) {
		long now = view.nanos();
		pressing = pressAxis >= 0 && now - pressNanos < TouchButtons.MIN_PRESS_NANOS;
		if (finger == null) {
			return;
		}
		TouchInput.Finger current = null;
		for (TouchInput.Finger candidate : fingers) {
			if (finger.equals(TouchPad.FingerKey.of(candidate))) {
				current = candidate;
				break;
			}
		}
		if (current == null) {
			lifted(now, target);
			return;
		}
		lastX = fingerX;
		lastY = fingerY;
		fingerX = current.x();
		fingerY = current.y();
		if (phase != Phase.PENDING) {
			return;
		}
		float dx = (fingerX - landX) * view.aspect();
		float dy = fingerY - landY;
		if (dx * dx + dy * dy > SLOP * SLOP) {
			look(landX, landY);
		} else if (now - landNanos >= HOLD_NANOS) {
			hold(now, target);
		}
	}

	/** The finger has lifted: a tap if it was still undecided; a hold lets its trigger go with it. */
	private static void lifted(long now, Target target) {
		// A tap while the Pick Block button is armed picks there instead (tl128).
		if (phase == Phase.PENDING && !TouchPick.tapped(fingerX, fingerY)) {
			press(target.at(fingerX, fingerY) == HitResult.Type.ENTITY ? ATTACK : USE, now, fingerX, fingerY);
		}
		finger = null;
		phase = Phase.PENDING;
	}

	/** The finger has waited long enough without moving: what it holds decides what the hold does. */
	private static void hold(long now, Target target) {
		if (target.holdUses()) {
			phase = Phase.USE;
			return;
		}
		switch (target.at(fingerX, fingerY)) {
			case BLOCK -> phase = Phase.BREAK;
			case ENTITY -> {
				press(ATTACK, now, fingerX, fingerY);
				look(fingerX, fingerY);
			}
			default -> look(fingerX, fingerY);
		}
	}

	/** A tap's press: this trigger, for {@link TouchButtons#MIN_PRESS_NANOS}, aimed at this point. */
	private static void press(int axis, long now, float x, float y) {
		pressAxis = axis;
		pressNanos = now;
		pressX = x;
		pressY = y;
		pressing = true;
	}

	/**
	 * The finger is a look from now, handed to the pad with where it is to turn the camera from: where it landed, when
	 * it moved past {@link #SLOP}, so the move so far turns the camera at once; where it is, after a hold, which turns
	 * nothing it was held still for.
	 */
	private static void look(float fromX, float fromY) {
		toLook = finger;
		toLookX = fromX;
		toLookY = fromY;
		finger = null;
		phase = Phase.PENDING;
	}

	/** The finger that stopped tapping to look, once - for the pad to take as its look - or null. */
	static @Nullable TouchPad.FingerKey takeLook() {
		TouchPad.FingerKey key = toLook;
		toLook = null;
		return key;
	}

	/** Where the finger handed over by {@link #takeLook} turns the camera from: across, then down. */
	static float lookFromX() {
		return toLookX;
	}

	static float lookFromY() {
		return toLookY;
	}

	/** Whether a trigger - an SDL gamepad axis - is pulled: by a hold, or by a tap's press. */
	static boolean trigger(int axis) {
		boolean held = finger != null && ((axis == USE && phase == Phase.USE) || (axis == ATTACK && phase == Phase.BREAK));
		return held || (pressing && pressAxis == axis);
	}

	/**
	 * Where the game acts, as fractions of the window, across then down: the middle of the screen while the interact
	 * button pulls use, so it acts on the mob it names (tl119, {@link TouchInteract}); a tap's point while its press
	 * lasts - before the finger, so a finger landing in that time cannot take the press somewhere else - else the
	 * finger while one is down; null for nowhere.
	 */
	static float[] aim() {
		if (TouchInteract.pressed()) {
			return TouchInteract.centre();
		}
		if (pressing) {
			return new float[] {pressX, pressY};
		}
		return finger != null ? new float[] {fingerX, fingerY} : null;
	}

	/** How far a finger holding to use moved since the last frame, to turn the camera by - or null. */
	static float[] drag() {
		if (finger == null || phase != Phase.USE || (fingerX == lastX && fingerY == lastY)) {
			return null;
		}
		return new float[] {fingerX - lastX, fingerY - lastY};
	}

	/** Where the hold ring goes and how much of it is bright - across, down, then 0 to 1 - or null while nothing is held. */
	static float[] ring() {
		if (finger == null || phase == Phase.PENDING) {
			return null;
		}
		return new float[] {fingerX, fingerY, phase == Phase.BREAK ? breakProgress : 0f};
	}

	/** How far the block under the finger has broken, 0 to 1, as the game says this frame. */
	static void breakProgress(float progress) {
		breakProgress = progress;
	}

	/** Forgets the finger and any press: a screen is up, or touch controls went off. */
	static void letGo() {
		finger = null;
		phase = Phase.PENDING;
		pressAxis = -1;
		pressing = false;
		toLook = null;
		breakProgress = 0f;
	}

	/** For tests: what the finger is doing. */
	static Phase phase() {
		return phase;
	}

	/**
	 * Whether a hold uses this item rather than breaks with it: an item whose use takes time - food, drink, a bow, a
	 * crossbow, a trident, a shield, a spyglass, a horn, a brush - or whose class has a use of its own, which it does
	 * in the air: a throw, a bucket, a boat, a fishing rod, a spawn egg, a firework.
	 */
	static boolean holdUses(ItemStack stack, LocalPlayer player) {
		return !stack.isEmpty() && (stack.getUseDuration(player) > 0 || OWN_USE.get(stack.getItem().getClass()));
	}

	/**
	 * The game's pick in tap mode, for {@code LocalPlayer.raycastHitResult}: null outside it, so the game picks along
	 * the middle of the screen as ever; in it, along the ray through {@link #aim()}, or nothing when there is none.
	 */
	public static @Nullable HitResult pick(LocalPlayer player, Entity cameraEntity, float partialTicks) {
		if (!TouchPad.tapMode()) {
			return null;
		}
		float[] point = aim();
		if (point == null) {
			Vec3 eye = cameraEntity.getEyePosition(partialTicks);
			return BlockHitResult.miss(eye, Direction.getApproximateNearest(cameraEntity.getViewVector(partialTicks)), BlockPos.containing(eye));
		}
		return pickAt(player, cameraEntity, partialTicks, point[0], point[1]);
	}

	/**
	 * What the game would act on through a point of the window, 0 to 1 across and down. In first person the ray starts
	 * at the eye and turns with the player's view, as the game's own does; behind or in front of the player it starts
	 * at the camera, along its own axes, so it goes through what is under the finger on screen. Either way the reach is
	 * measured from the eye, as the server measures it.
	 */
	static HitResult pickAt(LocalPlayer player, Entity cameraEntity, float partialTicks, float x, float y) {
		Minecraft minecraft = Minecraft.getInstance();
		//? if >=26.2 {
		Camera camera = minecraft.gameRenderer.mainCamera();
		//?} else {
		/*Camera camera = minecraft.gameRenderer.getMainCamera();
		*///?}
		Window window = minecraft.getWindow();
		double aspect = (double) window.getWidth() / Math.max(1, window.getHeight());
		double tanHalfFov = Math.tan(Math.toRadians(camera.getFov()) / 2);
		Vec3 eye = cameraEntity.getEyePosition(partialTicks);
		Vec3 from;
		Vec3 forward;
		Vec3 up;
		Vec3 left;
		if (minecraft.options.getCameraType().isFirstPerson()) {
			Vec3[] axes = axes(cameraEntity.getViewXRot(partialTicks), cameraEntity.getViewYRot(partialTicks));
			from = eye;
			forward = axes[0];
			up = axes[1];
			left = axes[2];
		} else {
			from = camera.position();
			forward = vec(camera.forwardVector());
			up = vec(camera.upVector());
			left = vec(camera.leftVector());
		}
		Vec3 direction = ray(forward, up, left, x, y, tanHalfFov, aspect);
		return pick(cameraEntity, from, direction, eye, player.blockInteractionRange(), player.entityInteractionRange());
	}

	/**
	 * A first-person camera's axes for a view's pitch and yaw, in degrees - forward, up and left - as the camera turns
	 * its own ({@code Camera.setRotation}: yaw about the vertical, then pitch about the camera's left), worked out in
	 * full precision rather than through the game's table of sines, which is good to a few ten-thousandths.
	 */
	static Vec3[] axes(float xRot, float yRot) {
		double pitch = Math.toRadians(xRot);
		double yaw = Math.toRadians(yRot);
		double sinPitch = Math.sin(pitch);
		double cosPitch = Math.cos(pitch);
		double sinYaw = Math.sin(yaw);
		double cosYaw = Math.cos(yaw);
		Vec3 forward = new Vec3(-sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch);
		Vec3 up = new Vec3(-sinYaw * sinPitch, cosPitch, cosYaw * sinPitch);
		Vec3 left = new Vec3(cosYaw, 0, sinYaw);
		return new Vec3[] {forward, up, left};
	}

	/**
	 * The ray through a point of the window, 0 to 1 across and down, for a camera with these axes, half its field of
	 * view's tangent - the height's - and the window's aspect: the camera projects a point at a unit ahead to
	 * {@code (1 - left / (tan * aspect)) / 2} across and {@code (1 - up / tan) / 2} down, so this is that backwards.
	 */
	static Vec3 ray(Vec3 forward, Vec3 up, Vec3 left, float x, float y, double tanHalfFov, double aspect) {
		return forward
				.add(left.scale((1 - 2 * x) * tanHalfFov * aspect))
				.add(up.scale((1 - 2 * y) * tanHalfFov))
				.normalize();
	}

	/**
	 * What the world says along a segment, as the game's pick asks it: the first block it meets, as {@code Level.clip}
	 * finds it, and the nearest pickable mob it passes through, as {@code ProjectileUtil.getEntityHitResult} does.
	 */
	interface World {
		HitResult clip(Vec3 from, Vec3 to);

		@Nullable EntityHitResult entity(Vec3 from, Vec3 to, AABB box, double maxDistanceSq);
	}

	/** The game's pick along a ray from anywhere, in the camera entity's own world. */
	static HitResult pick(Entity cameraEntity, Vec3 from, Vec3 direction, Vec3 eye, double blockRange, double entityRange) {
		return pick(new World() {
			@Override
			public HitResult clip(Vec3 start, Vec3 end) {
				return cameraEntity.level().clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, cameraEntity));
			}

			@Override
			public @Nullable EntityHitResult entity(Vec3 start, Vec3 end, AABB box, double maxDistanceSq) {
				return ProjectileUtil.getEntityHitResult(cameraEntity, start, end, box, EntitySelector.CAN_BE_PICKED, maxDistanceSq);
			}
		}, from, direction, eye, blockRange, entityRange);
	}

	/**
	 * The game's pick ({@code LocalPlayer.pick}) along a ray from anywhere: the first block it meets, or a mob before
	 * it, then held to the reach measured from the eye, past which it is nothing. The ray reaches as far past the eye
	 * as the camera is behind it, so from a camera behind the player it still reaches as far beyond the player.
	 */
	static HitResult pick(World world, Vec3 from, Vec3 direction, Vec3 eye, double blockRange, double entityRange) {
		double reach = Math.max(blockRange, entityRange) + from.distanceTo(eye);
		Vec3 to = from.add(direction.scale(reach));
		HitResult block = world.clip(from, to);
		double blockDistanceSq = block.getLocation().distanceToSqr(from);
		Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
		AABB box = new AABB(from, end).inflate(1.0);
		EntityHitResult entity = world.entity(from, end, box, from.distanceToSqr(end));
		if (entity != null && entity.getLocation().distanceToSqr(from) < blockDistanceSq) {
			return within(entity, eye, entityRange);
		}
		return within(block, eye, blockRange);
	}

	/** A hit as it is if it is within this range of the eye, else nothing there ({@code LocalPlayer.filterHitResult}). */
	private static HitResult within(HitResult hit, Vec3 eye, double range) {
		Vec3 location = hit.getLocation();
		if (location.closerThan(eye, range)) {
			return hit;
		}
		return BlockHitResult.miss(location, Direction.getApproximateNearest(location.x - eye.x, location.y - eye.y, location.z - eye.z),
				BlockPos.containing(location));
	}

	private static Vec3 vec(Vector3fc v) {
		return new Vec3(v.x(), v.y(), v.z());
	}
}
