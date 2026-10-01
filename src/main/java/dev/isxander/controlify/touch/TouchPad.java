/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.touch;

import com.mojang.blaze3d.platform.Window;
import dev.isxander.controlify.Controlify;
import dev.isxander.controlify.controller.ControllerEntity;
import dev.isxander.controlify.controllermanager.SDLControllerManager;
import dev.isxander.controlify.utils.CUtil;
import dev.isxander.controlify.utils.MinecraftUtil;
import dev.isxander.sdl.Sdl;
import dev.isxander.sdl.SdlGamepad;
import dev.isxander.sdl.SdlJoystick;
import dev.isxander.sdl.SdlJoystickHandle;
import dev.isxander.sdl.SdlJoystickId;
import dev.isxander.sdl.SdlPointer;
import dev.isxander.sdl.SdlVirtualJoystickDesc;
import dev.isxander.sdl.SdlVirtualJoystickSensorDesc;
import dev.isxander.sdl.SdlVirtualJoystickTouchpadDesc;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.HumanoidArm;
import org.jetbrains.annotations.Nullable;
//? if >=26.3 {
import org.lwjgl.sdl.SDLHints;
//?}

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Touch controls, the pad (tl111): a floating stick under the left thumb and a look region under the
 * right, Bedrock's "Joystick & Aim Crosshair" scheme; its buttons and the hotbar are {@link TouchButtons}
 * (tl112), offered every finger first.
 *
 * <p>The stick is a real controller as far as the rest of the mod is concerned: a virtual gamepad
 * attached to Controlify's own SDL - the one its controllers come from, not the game's - of the
 * standard shape (fifteen buttons, six axes), which SDL maps by itself. Its left stick's axes are
 * written from the finger each frame and read by the controller manager as any pad's are, so
 * bindings, analog movement, the glyphs and everything downstream work untouched. Pushed past its
 * reach it also presses L3, sprint on the default binds, as Bedrock's stick sprints.
 *
 * <p>The look is not a stick. A swipe is a turn, one to one, as a mouse drag is: the finger's movement
 * since the last frame, as a fraction of the window, turns the camera {@link #LOOK_DEGREES_PER_WIDTH}
 * degrees per window width, straight through {@code LocalPlayer.turn}. Pitch is scaled by the
 * window's aspect so a finger moving a centimetre turns the same either way.
 *
 * <p>While it is on: the input mode is held at MIXED (fingers are the pad, and on a screen the mouse,
 * so neither flips the mode); SDL's touch-makes-a-mouse hint is off in the world and on while a
 * screen is up, so a finger on the look region is not also a click and a tap on a menu still is; and
 * two things that wait for the game's grab on the cursor - holding attack to keep mining, and the
 * controller's look - go ahead with or without it. The grab itself is left alone: a touchscreen's
 * fingers arrive grabbed or not. Only while the mouse stands in for a finger ({@link
 * TouchInput#mouseAsFinger}) is the cursor set free, because SDL makes finger motion out of the mouse
 * only while the cursor is free; the game's grabs are refused for as long as that lasts. Switched by
 * the Dev Functions panel's Touch Controls for now; nothing is saved.
 */
public final class TouchPad {
	/** How far from the left edge, as a fraction of the window's width, a finger landing becomes the stick. */
	static final float STICK_ZONE = 0.45f;

	/** The stick's reach - full deflection - as a fraction of the window's height. */
	static final float STICK_RADIUS = 0.11f;

	/** Pushed past its reach by this factor, the stick presses L3 as well: sprint, on the default binds. */
	static final float SPRINT_PAST = 1.3f;

	/** A swipe across the whole window turns the camera this far, in degrees (180 in tl111: too slow). */
	static final float LOOK_DEGREES_PER_WIDTH = 360f;

	/** Where the stick rests until a finger lands in its zone: this many window heights in from the left and up from the bottom. */
	static final float REST_FROM_CORNER = 0.17f;

	/** {@code Entity.turn} turns 0.15 degrees per unit it is handed. */
	private static final float TURN_UNITS_PER_DEGREE = 1f / 0.15f;

	/** What the virtual pad is called: the name SDL, and so Controlify, gives it. */
	static final String NAME = "Touchscreen";

	/** SDL's standard gamepad: the fifteen buttons SOUTH..DPAD_RIGHT and the six axes, by mask. */
	private static final int BUTTONS = 15;
	private static final int AXES = 6;
	private static final int BUTTON_MASK = (1 << BUTTONS) - 1;
	private static final int AXIS_MASK = (1 << AXES) - 1;
	private static final short AXIS_MAX = 32767;
	/** A trigger at rest: SDL's virtual gamepad starts its triggers here, and 0 would read as half pulled. */
	private static final short TRIGGER_REST = -32768;

	private static final int RING = 0x80A8A8A8;
	private static final int RING_HELD = 0xC0D0D0D0;
	private static final int KNOB = 0xB0C0C0C0;
	private static final int KNOB_SPRINT = 0xC0FFE060;

	private static boolean active;
	private static @Nullable SdlJoystickId padId;
	private static @Nullable SdlJoystickHandle pad;

	/** The finger that is the stick, and where it landed - the stick's centre - or null when none is. */
	private static @Nullable FingerKey stickFinger;
	private static float anchorX;
	private static float anchorY;
	/** The stick's deflection as last written, each axis -1..1, for drawing and for writing only on change. */
	private static float stickX;
	private static float stickY;
	private static boolean sprinting;

	/** The finger that is the look, and where it was last frame, or null when none is. */
	private static @Nullable FingerKey lookFinger;
	private static float lookX;
	private static float lookY;

	/** The pad's buttons and triggers as last written, so each is written only on change. */
	private static int buttonsWritten;
	private static boolean leftTriggerWritten;
	private static boolean rightTriggerWritten;

	/** Fingers that were down while a screen was up: nobody's until they lift, so closing a screen presses nothing. */
	private static final Set<FingerKey> HELD_OVER = new HashSet<>();

	/** The fingers down last frame: only a finger that has just landed can take a button, not one sliding onto it. */
	private static final Set<FingerKey> SEEN = new HashSet<>();

	/** What SDL's touch-makes-a-mouse hint was last set to, so it is set only on change. */
	private static @Nullable String touchMouseHint;

	/** Whether the cursor was let go of here, so it can be grabbed again when that reason ends. */
	private static boolean freed;

	/** What one frame of fingers turns the camera by, in degrees. */
	record Turn(float yawDegrees, float pitchDegrees) {
		static final Turn NONE = new Turn(0f, 0f);

		boolean any() {
			return yawDegrees != 0f || pitchDegrees != 0f;
		}
	}

	/** A finger's identity: one per device, see {@link TouchInput}. */
	record FingerKey(long touchId, long fingerId) {
		static FingerKey of(TouchInput.Finger finger) {
			return new FingerKey(finger.touchId(), finger.fingerId());
		}
	}

	/**
	 * The window a frame of fingers is read against: its size in pixels and in GUI pixels, the GUI scale,
	 * whether the hotbar is there to tap (no spectators), where the inventory slot after it is, whether the
	 * player is flying (jump and sneak show up and down), and the time.
	 */
	record View(int width, int height, int scale, int guiWidth, int guiHeight, boolean hotbar, int inventoryX, boolean flying, long nanos) {
		float aspect() {
			return (float) width / height;
		}
	}

	private TouchPad() {
	}

	/** Whether touch controls are on. */
	public static boolean active() {
		return active;
	}

	/**
	 * Whether the cursor is to be kept free of the game's grab: touch controls on and the mouse standing
	 * in for a finger, which SDL only makes motion out of while the cursor is free.
	 */
	public static boolean cursorFree() {
		return active && TouchInput.mouseAsFinger();
	}

	/**
	 * Turns touch controls on or off. On: the virtual pad is attached (once), and made the current
	 * controller as soon as the controller manager has it. Off: the stick is let go, the hint put back,
	 * the cursor grabbed again if it was set free here, and the controller selection applied again.
	 */
	public static void setActive(boolean on) {
		if (!TouchInput.SUPPORTED || on == active) {
			return;
		}
		if (on) {
			if (pad == null && !attach()) {
				return;
			}
			active = true;
			CUtil.LOGGER.log("Touch controls on");
		} else {
			active = false;
			letGo();
			hint("1");
			cursor();
			Controlify.instance().applyControllerSelection(true);
			CUtil.LOGGER.log("Touch controls off");
		}
	}

	/** Attaches the virtual pad to Controlify's SDL and opens it for writing. */
	private static boolean attach() {
		Sdl sdl = SDLControllerManager.sdl();
		if (sdl == null) {
			CUtil.LOGGER.warn("Touch: no SDL to attach the pad to");
			return false;
		}
		SdlVirtualJoystickDesc desc = new SdlVirtualJoystickDesc(
				SdlJoystick.SDL_JOYSTICK_TYPE_GAMEPAD, 0, 0, AXES, BUTTONS, 0, 0, BUTTON_MASK, AXIS_MASK, NAME,
				new SdlVirtualJoystickTouchpadDesc[0], new SdlVirtualJoystickSensorDesc[0], SdlPointer.NULL,
				userdata -> {
				},
				(userdata, playerIndex) -> {
				},
				(userdata, low, high) -> false,
				(userdata, left, right) -> false,
				(userdata, red, green, blue) -> false,
				(userdata, effect) -> false,
				(userdata, enabled) -> false,
				userdata -> {
				});
		SdlJoystickId id = sdl.joystick().SDL_AttachVirtualJoystick(desc);
		if (id == null || id.value() == 0) {
			CUtil.LOGGER.warn("Touch: SDL refused the virtual pad: {}", sdl.error().SDL_GetError());
			return false;
		}
		SdlJoystickHandle handle = sdl.joystick().SDL_OpenJoystick(id);
		if (handle == null || handle.isNull()) {
			CUtil.LOGGER.warn("Touch: SDL would not open the virtual pad: {}", sdl.error().SDL_GetError());
			sdl.joystick().SDL_DetachVirtualJoystick(id);
			return false;
		}
		padId = id;
		pad = handle;
		CUtil.LOGGER.log("Touch: the pad is SDL joystick {}", id.value());
		return true;
	}

	/** The pad's controller, once the controller manager has made it. */
	private static @Nullable ControllerEntity entity() {
		if (padId == null) {
			return null;
		}
		SDLControllerManager.SDLUniqueControllerID ucid = new SDLControllerManager.SDLUniqueControllerID(padId);
		return Controlify.instance().getControllerManager()
				.flatMap(manager -> manager.getConnectedControllers().stream()
						.filter(controller -> ucid.equals(controller.info().ucid()))
						.findFirst())
				.orElse(null);
	}

	/**
	 * Every frame, from where the game would turn the player by the mouse: the hint for where we are,
	 * the pad as the current controller, the cursor as it should be, and in the world the fingers read
	 * into the stick, the look and the buttons, and a tapped hotbar slot selected.
	 */
	public static void frame() {
		if (!active) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		boolean screen = MinecraftUtil.getScreen() != null;
		hint(screen ? "1" : "0");

		ControllerEntity entity = entity();
		if (entity != null && Controlify.instance().getCurrentController().orElse(null) != entity) {
			Controlify.instance().setCurrentController(entity, true);
		}
		cursor();

		if (screen || minecraft.player == null) {
			letGo();
			heldOver(TouchInput.fingers());
			return;
		}
		Turn turn = readFingers(TouchInput.fingers(), view(minecraft));
		int slot = TouchButtons.takeSlot();
		if (slot >= 0) {
			minecraft.player.getInventory().setSelectedSlot(slot);
		}
		if (turn.any()) {
			minecraft.player.turn(turn.yawDegrees() * TURN_UNITS_PER_DEGREE, turn.pitchDegrees() * TURN_UNITS_PER_DEGREE);
		}
	}

	/** The window as it is now; see {@link View}. */
	private static View view(Minecraft minecraft) {
		Window window = minecraft.getWindow();
		LocalPlayer player = minecraft.player;
		boolean hotbar = player != null && !player.isSpectator();
		// The offhand slot is on the right for the left-handed, the attack indicator for the right-handed
		// when it is set to the hotbar: the inventory slot moves past either, whether drawn this frame or not.
		boolean rightSideTaken = player != null
				&& (player.getMainArm() == HumanoidArm.LEFT || minecraft.options.attackIndicator().get() == AttackIndicatorStatus.HOTBAR);
		boolean flying = player != null && player.getAbilities().flying;
		int guiWidth = window.getGuiScaledWidth();
		return new View(window.getWidth(), window.getHeight(), window.getGuiScale(), guiWidth, window.getGuiScaledHeight(),
				hotbar, TouchButtons.inventoryX(guiWidth, rightSideTaken), flying, System.nanoTime());
	}

	/** While a screen is up: the fingers down now are nobody's until they lift. */
	static void heldOver(List<TouchInput.Finger> fingers) {
		for (TouchInput.Finger finger : fingers) {
			HELD_OVER.add(FingerKey.of(finger));
		}
	}

	/**
	 * Lets the cursor go while {@link #cursorFree}, and takes it back - in the world, with no screen up -
	 * once that ends. The game's own grabs are refused meanwhile (MouseHandlerMixin).
	 */
	private static void cursor() {
		Minecraft minecraft = Minecraft.getInstance();
		if (cursorFree()) {
			if (minecraft.mouseHandler.isMouseGrabbed()) {
				minecraft.mouseHandler.releaseMouse();
				freed = true;
			}
		} else if (freed) {
			freed = false;
			if (MinecraftUtil.getScreen() == null && minecraft.player != null && !minecraft.mouseHandler.isMouseGrabbed()) {
				minecraft.mouseHandler.grabMouse();
			}
		}
	}

	/**
	 * Assigns fingers to the buttons (offered each new finger first), the stick and the look; moves the
	 * stick; writes the pad's buttons and triggers; and says how far to turn the camera - by the look
	 * finger, or while there is none by a finger dragged from a button. Positions are fractions of the window.
	 */
	static Turn readFingers(List<TouchInput.Finger> fingers, View view) {
		float aspect = view.aspect();
		Set<FingerKey> down = new HashSet<>();
		for (TouchInput.Finger finger : fingers) {
			down.add(FingerKey.of(finger));
		}
		HELD_OVER.retainAll(down);
		Set<FingerKey> seen = new HashSet<>(SEEN);
		SEEN.clear();
		SEEN.addAll(down);

		TouchInput.Finger stick = null;
		TouchInput.Finger look = null;
		for (TouchInput.Finger finger : fingers) {
			FingerKey key = FingerKey.of(finger);
			if (key.equals(stickFinger)) {
				stick = finger;
			} else if (key.equals(lookFinger)) {
				look = finger;
			}
		}
		for (TouchInput.Finger finger : fingers) {
			FingerKey key = FingerKey.of(finger);
			if (key.equals(stickFinger) || key.equals(lookFinger) || HELD_OVER.contains(key) || TouchButtons.owns(key)) {
				continue;
			}
			if (!seen.contains(key) && TouchButtons.claim(finger, view)) {
				continue;
			}
			if (stick == null && finger.x() < STICK_ZONE) {
				stickFinger = key;
				anchorX = finger.x();
				anchorY = finger.y();
				stick = finger;
			} else if (look == null) {
				lookFinger = key;
				lookX = finger.x();
				lookY = finger.y();
				look = finger;
			}
		}

		if (stick == null) {
			stickFinger = null;
			writeStick(0f, 0f, false);
		} else {
			// In units of the window's height either way, so the stick is round on screen.
			float dx = (stick.x() - anchorX) * aspect / STICK_RADIUS;
			float dy = (stick.y() - anchorY) / STICK_RADIUS;
			float reach = (float) Math.sqrt(dx * dx + dy * dy);
			if (reach > 1f) {
				dx /= reach;
				dy /= reach;
			}
			writeStick(dx, dy, reach > SPRINT_PAST);
		}

		float[] drag = TouchButtons.update(fingers, view);
		long now = view.nanos();
		writeButtons(TouchButtons.buttonMask(now) | (sprinting ? 1 << SdlGamepad.SDL_GAMEPAD_BUTTON_LEFT_STICK : 0));
		writeTriggers(TouchButtons.trigger(SdlGamepad.SDL_GAMEPAD_AXIS_LEFT_TRIGGER, now), TouchButtons.trigger(SdlGamepad.SDL_GAMEPAD_AXIS_RIGHT_TRIGGER, now));

		if (look == null) {
			lookFinger = null;
			return drag == null ? Turn.NONE : turn(drag[0], drag[1], aspect);
		}
		Turn turn = turn(look.x() - lookX, look.y() - lookY, aspect);
		lookX = look.x();
		lookY = look.y();
		return turn;
	}

	/** A finger's movement, as fractions of the window, as a turn: pitch scaled by the aspect so a centimetre turns the same either way. */
	private static Turn turn(float dx, float dy, float aspect) {
		if (dx == 0f && dy == 0f) {
			return Turn.NONE;
		}
		return new Turn(dx * LOOK_DEGREES_PER_WIDTH, dy * LOOK_DEGREES_PER_WIDTH / aspect);
	}

	/** Hands the stick's deflection to the pad, each axis only when it changed; the sprint is kept for L3, which {@link #writeButtons} writes. */
	private static void writeStick(float x, float y, boolean sprint) {
		Sdl sdl = SDLControllerManager.sdl();
		boolean attached = sdl != null && pad != null;
		if (x != stickX) {
			if (attached) {
				sdl.joystick().SDL_SetJoystickVirtualAxis(pad, SdlGamepad.SDL_GAMEPAD_AXIS_LEFTX, (short) (x * AXIS_MAX));
			}
			stickX = x;
		}
		if (y != stickY) {
			if (attached) {
				sdl.joystick().SDL_SetJoystickVirtualAxis(pad, SdlGamepad.SDL_GAMEPAD_AXIS_LEFTY, (short) (y * AXIS_MAX));
			}
			stickY = y;
		}
		sprinting = sprint;
	}

	/** Hands the pad's buttons to it, as a mask of SDL's gamepad buttons, each only when it changed. */
	private static void writeButtons(int mask) {
		int changed = mask ^ buttonsWritten;
		if (changed == 0) {
			return;
		}
		Sdl sdl = SDLControllerManager.sdl();
		if (sdl != null && pad != null) {
			for (int button = 0; button < BUTTONS; button++) {
				if ((changed & 1 << button) != 0) {
					sdl.joystick().SDL_SetJoystickVirtualButton(pad, button, (mask & 1 << button) != 0);
				}
			}
		}
		buttonsWritten = mask;
	}

	/** Pulls or lets go of the two triggers, each only when it changed: full, or back to where SDL rests them. */
	private static void writeTriggers(boolean left, boolean right) {
		Sdl sdl = SDLControllerManager.sdl();
		boolean attached = sdl != null && pad != null;
		if (left != leftTriggerWritten) {
			if (attached) {
				sdl.joystick().SDL_SetJoystickVirtualAxis(pad, SdlGamepad.SDL_GAMEPAD_AXIS_LEFT_TRIGGER, left ? AXIS_MAX : TRIGGER_REST);
			}
			leftTriggerWritten = left;
		}
		if (right != rightTriggerWritten) {
			if (attached) {
				sdl.joystick().SDL_SetJoystickVirtualAxis(pad, SdlGamepad.SDL_GAMEPAD_AXIS_RIGHT_TRIGGER, right ? AXIS_MAX : TRIGGER_REST);
			}
			rightTriggerWritten = right;
		}
	}

	/** Forgets every finger, centres the stick and lets go of every button. */
	private static void letGo() {
		stickFinger = null;
		lookFinger = null;
		writeStick(0f, 0f, false);
		TouchButtons.letGo();
		writeButtons(0);
		writeTriggers(false, false);
	}

	/**
	 * SDL's {@code SDL_TOUCH_MOUSE_EVENTS}, on the game's SDL: whether a finger is also a mouse. Read
	 * live by SDL; set here only when the wanted value changes.
	 */
	private static void hint(String value) {
		if (value.equals(touchMouseHint)) {
			return;
		}
		//? if >=26.3 {
		SDLHints.SDL_SetHint(SDLHints.SDL_HINT_TOUCH_MOUSE_EVENTS, value);
		//?}
		touchMouseHint = value;
	}

	/** The HUD layer: the stick's base where it rests or where the thumb landed, and its knob; then the buttons. */
	public static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (!active || MinecraftUtil.getScreen() != null) {
			return;
		}
		draw(graphics, view(Minecraft.getInstance()));
	}

	/** {@link #render}, against a given window - for tests. */
	static void draw(GuiGraphicsExtractor graphics, View view) {
		int width = graphics.guiWidth();
		int height = graphics.guiHeight();
		int radius = Math.round(STICK_RADIUS * height);
		boolean held = stickFinger != null;
		int[] rest = held ? null : rest(view, width, height, radius);
		int centreX = held ? Math.round(anchorX * width) : rest[0];
		int centreY = held ? Math.round(anchorY * height) : rest[1];
		ring(graphics, centreX, centreY, radius, held ? RING_HELD : RING);
		int knobX = centreX + Math.round(stickX * radius);
		int knobY = centreY + Math.round(stickY * radius);
		disc(graphics, knobX, knobY, Math.max(2, Math.round(radius * 0.35f)), sprinting ? KNOB_SPRINT : KNOB);
		TouchButtons.render(graphics, view);
	}

	/**
	 * Where the stick's ring rests, in GUI pixels: {@link #REST_FROM_CORNER} in from the bottom-left corner - moved
	 * left, or if it cannot go far enough, up, just clear of the hotbar and what the game stacks over it wherever the
	 * ring would reach over them (a narrow window at a large GUI scale).
	 */
	static int[] rest(View view, int width, int height, int radius) {
		int x = Math.round(REST_FROM_CORNER * height);
		int y = height - Math.round(REST_FROM_CORNER * height);
		int hotbarLeft = width / 2 - TouchButtons.HOTBAR_HALF_WIDTH;
		int stackTop = height - TouchButtons.HUD_STACK_HEIGHT;
		if (view.hotbar() && x + radius + 2 > hotbarLeft && y + radius + 2 > stackTop) {
			int left = hotbarLeft - radius - 2;
			if (left - radius >= 1) {
				x = left;
			} else {
				y = stackTop - radius - 2;
			}
		}
		return new int[] {x, y};
	}

	/** A filled circle, a row of pixels at a time. */
	private static void disc(GuiGraphicsExtractor graphics, int cx, int cy, int r, int color) {
		for (int dy = -r; dy <= r; dy++) {
			int half = (int) Math.floor(Math.sqrt((double) r * r - (double) dy * dy));
			graphics.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
		}
	}

	/** A circle two pixels thick. */
	private static void ring(GuiGraphicsExtractor graphics, int cx, int cy, int r, int color) {
		for (int dy = -r; dy <= r; dy++) {
			int outer = (int) Math.floor(Math.sqrt((double) r * r - (double) dy * dy));
			int inner = Math.abs(dy) > r - 2 ? -1 : (int) Math.floor(Math.sqrt((double) (r - 2) * (r - 2) - (double) dy * dy));
			if (inner < 0) {
				graphics.fill(cx - outer, cy + dy, cx + outer + 1, cy + dy + 1, color);
			} else {
				graphics.fill(cx - outer, cy + dy, cx - inner, cy + dy + 1, color);
				graphics.fill(cx + inner + 1, cy + dy, cx + outer + 1, cy + dy + 1, color);
			}
		}
	}

	/** For the overlay and tests: the stick's deflection as last written, -1..1. */
	public static float stickX() {
		return stickX;
	}

	public static float stickY() {
		return stickY;
	}

	public static boolean sprinting() {
		return sprinting;
	}

	/** For tests: forgets the fingers, the stick and the buttons. */
	static void reset() {
		stickFinger = null;
		lookFinger = null;
		stickX = 0f;
		stickY = 0f;
		sprinting = false;
		buttonsWritten = 0;
		leftTriggerWritten = false;
		rightTriggerWritten = false;
		HELD_OVER.clear();
		SEEN.clear();
		TouchButtons.letGo();
	}

	/** For tests: the pad's buttons and triggers as last written. */
	static int buttonsWritten() {
		return buttonsWritten;
	}

	static boolean leftTrigger() {
		return leftTriggerWritten;
	}

	static boolean rightTrigger() {
		return rightTriggerWritten;
	}
}
