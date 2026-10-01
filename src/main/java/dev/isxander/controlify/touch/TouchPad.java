/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.touch;

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
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jetbrains.annotations.Nullable;
//? if >=26.3 {
import org.lwjgl.sdl.SDLHints;
//?}

import java.util.List;

/**
 * Touch controls, the pad (tl111): a floating stick under the left thumb and a look region under the
 * right, Bedrock's "Joystick & Aim Crosshair" scheme without its buttons yet.
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

	/** A swipe across the whole window turns the camera this far, in degrees. */
	static final float LOOK_DEGREES_PER_WIDTH = 180f;

	/** Where the stick rests, as fractions of the window, until a finger lands in its zone. */
	static final float REST_X = 0.17f;
	static final float REST_Y = 0.70f;

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

	private static final int RING = 0x60FFFFFF;
	private static final int RING_HELD = 0xA0FFFFFF;
	private static final int KNOB = 0xA0FFFFFF;
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
	private record FingerKey(long touchId, long fingerId) {
		static FingerKey of(TouchInput.Finger finger) {
			return new FingerKey(finger.touchId(), finger.fingerId());
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
	 * into the stick and the look.
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
			return;
		}
		Turn turn = readFingers(TouchInput.fingers(), (float) minecraft.getWindow().getWidth() / minecraft.getWindow().getHeight());
		if (turn.any()) {
			minecraft.player.turn(turn.yawDegrees() * TURN_UNITS_PER_DEGREE, turn.pitchDegrees() * TURN_UNITS_PER_DEGREE);
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
	 * Assigns fingers to the stick and the look, moves the stick, and says how far to turn the camera.
	 * Positions are fractions of the window; {@code aspect} is its width over its height.
	 */
	static Turn readFingers(List<TouchInput.Finger> fingers, float aspect) {
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
			if (key.equals(stickFinger) || key.equals(lookFinger)) {
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

		if (look == null) {
			lookFinger = null;
			return Turn.NONE;
		}
		float yaw = (look.x() - lookX) * LOOK_DEGREES_PER_WIDTH;
		float pitch = (look.y() - lookY) * LOOK_DEGREES_PER_WIDTH / aspect;
		lookX = look.x();
		lookY = look.y();
		return new Turn(yaw, pitch);
	}

	/** Hands the stick's deflection and the sprint to the pad, each only when it changed. */
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
		if (sprint != sprinting) {
			if (attached) {
				sdl.joystick().SDL_SetJoystickVirtualButton(pad, SdlGamepad.SDL_GAMEPAD_BUTTON_LEFT_STICK, sprint);
			}
			sprinting = sprint;
		}
	}

	/** Forgets both fingers and centres the stick. */
	private static void letGo() {
		stickFinger = null;
		lookFinger = null;
		writeStick(0f, 0f, false);
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

	/** The HUD layer: the stick's base where it rests or where the thumb landed, and its knob. */
	public static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (!active || MinecraftUtil.getScreen() != null) {
			return;
		}
		int width = graphics.guiWidth();
		int height = graphics.guiHeight();
		int radius = Math.round(STICK_RADIUS * height);
		boolean held = stickFinger != null;
		int centreX = Math.round((held ? anchorX : REST_X) * width);
		int centreY = Math.round((held ? anchorY : REST_Y) * height);
		ring(graphics, centreX, centreY, radius, held ? RING_HELD : RING);
		int knobX = centreX + Math.round(stickX * radius);
		int knobY = centreY + Math.round(stickY * radius);
		disc(graphics, knobX, knobY, Math.max(2, Math.round(radius * 0.35f)), sprinting ? KNOB_SPRINT : KNOB);
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

	/** For tests: forgets the fingers and the stick. */
	static void reset() {
		stickFinger = null;
		lookFinger = null;
		stickX = 0f;
		stickY = 0f;
		sprinting = false;
	}
}
