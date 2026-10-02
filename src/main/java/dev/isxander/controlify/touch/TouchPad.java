/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.touch;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.isxander.controlify.Controlify;
import dev.isxander.controlify.config.ConfigManager;
import dev.isxander.controlify.config.dto.TouchConfig;
import dev.isxander.controlify.config.dto.TouchLayoutConfig;
import dev.isxander.controlify.config.settings.TouchSettings;
import dev.isxander.controlify.controller.ControllerEntity;
import dev.isxander.controlify.controllermanager.SDLControllerManager;
import dev.isxander.controlify.screenop.ScreenProcessor;
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
import net.minecraft.client.gui.screens.Screen;
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
 * (tl112), offered every finger first. In tap mode (tl118, {@link TouchMode}), Bedrock's "Joystick &amp; Tap
 * to Interact": no crosshair, the stick taking only a finger that lands on its ring, and a finger on the
 * world tapping, holding or looking ({@link TouchTap}), with a ring round a finger held there. In either, the
 * interact button over the hotbar while what is in front of the player can be traded with, ridden, sheared and
 * the like ({@link TouchInteract}, tl119), offered every finger before the buttons.
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
 * degrees per window width at the middle camera sensitivity, faster or slower as the player sets it ({@link #lookSpeed},
 * tl123), straight through {@code LocalPlayer.turn}. Pitch is scaled by the
 * window's aspect so a finger moving a centimetre turns the same either way.
 *
 * <p>While it is on: the input mode is held at MIXED (fingers are the pad, and on a screen the mouse,
 * so neither flips the mode); SDL's touch-makes-a-mouse hint is off in the world and on while a
 * screen is up, so a finger on the look region is not also a click and a tap on a menu still is; and
 * two things that wait for the game's grab on the cursor - holding attack to keep mining, and the
 * controller's look - go ahead with or without it. The grab itself is left alone: a touchscreen's
 * fingers arrive grabbed or not. Only while the mouse stands in for a finger ({@link
 * TouchInput#mouseAsFinger}) is the cursor set free, because SDL makes finger motion out of the mouse
 * only while the cursor is free; the game's grabs are refused for as long as that lasts. On a screen the
 * pad lets go, the screens' controller glyphs are not drawn, and a close button stands in for Esc
 * ({@link #renderScreen}, {@link #closeTapped}, tl116). Where the stick rests and the buttons sit, and how
 * big each is, the player sets in the glyph editor's Touch tab, and that is saved ({@link Layout}, tl117); so
 * is the mode (tl118), set on the touch settings screen (tl122; the Dev Functions panel's button for it gone
 * since tl126).
 *
 * <p>On by themselves (tl120, {@link TouchControls}): at the first finger on a touchscreen, which does nothing
 * else, and off again at a click or scroll of the mouse, a key pressed in the world or a controller's input - or
 * always on, or never, as the touch settings screen's Touch Controls sets it (tl122; the Dev Functions panel's
 * button for it gone since tl125), saved. While they are off but would
 * come on at a touch, SDL's touch-makes-a-mouse hint is off in the world as well, so that touch is not also a
 * click there; on a screen a tap still clicks, as ever.
 */
public final class TouchPad {
	/** How far from the left edge, as a fraction of the window's width, a finger landing becomes the stick - as does one landing on its ring where it rests ({@link #onRest}). */
	static final float STICK_ZONE = 0.45f;

	/** The stick's reach - full deflection - as a fraction of the window's height. */
	static final float STICK_RADIUS = 0.11f;

	/** Pushed past its reach by this factor, the stick presses L3 as well: sprint, on the default binds. */
	static final float SPRINT_PAST = 1.3f;

	/** A swipe across the whole window turns the camera this far, in degrees (180 in tl111: too slow). */
	static final float LOOK_DEGREES_PER_WIDTH = 360f;

	/** Where the stick rests until a finger lands in its zone: this many window heights in from the left and up from the bottom, at its default size. */
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
	/** The hold ring round a finger held on the world (tl118), and the part of it the block's breaking has reached. */
	private static final int HOLD_RING = 0x90FFFFFF;
	private static final int HOLD_DONE = 0xFFFFFFFF;

	/** The hold ring's radius, as a fraction of the window's height. */
	static final float HOLD_RING_RADIUS = 0.05f;

	private static boolean active;
	/** Whether the pad could not be attached when the touch controls last tried to come on: tried again only when asked, or at a touch. */
	private static boolean refused;
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
	 * Where the player put the touch controls and how big they made them (tl117, the glyph editor's Touch
	 * tab): the stick's resting place and the five action buttons, each moved by a fraction of the window's
	 * height - right and down positive - and each sized by a fraction of its default size.
	 */
	public record Layout(float stickX, float stickY, float stickSize, float buttonsX, float buttonsY, float buttonSize) {
		/** Nothing moved, nothing resized: exactly where tl115 put everything. */
		public static final Layout DEFAULT = new Layout(0f, 0f, 1f, 0f, 0f, 1f);

		public static Layout of(TouchSettings settings) {
			return new Layout(settings.stickOffsetX, settings.stickOffsetY, TouchSettings.size(settings.stickSize),
					settings.buttonsOffsetX, settings.buttonsOffsetY, TouchSettings.size(settings.buttonSize));
		}

		/** A scheme's layout as saved (tl129). */
		public static Layout of(TouchLayoutConfig saved) {
			return new Layout(saved.stickOffsetX(), saved.stickOffsetY(), TouchSettings.size(saved.stickSize()),
					saved.buttonsOffsetX(), saved.buttonsOffsetY(), TouchSettings.size(saved.buttonSize()));
		}

		/** This layout to save. */
		public TouchLayoutConfig saved() {
			return new TouchLayoutConfig(stickX, stickY, stickSize, buttonsX, buttonsY, buttonSize);
		}
	}

	/**
	 * The window a frame of fingers is read against: its size in pixels and in GUI pixels, the GUI scale,
	 * whether the hotbar is there to tap (no spectators), where the inventory slot after it is, whether the
	 * player is flying (jump and sneak show up and down), the time, the player's layout, the mode they
	 * play in (tl118), and the interact button if there is one this frame (tl119).
	 */
	record View(int width, int height, int scale, int guiWidth, int guiHeight, boolean hotbar, int inventoryX, boolean flying, long nanos, Layout layout, TouchMode mode,
			TouchInteract.@Nullable Shown interact) {
		/** The same window with the default layout. */
		View(int width, int height, int scale, int guiWidth, int guiHeight, boolean hotbar, int inventoryX, boolean flying, long nanos) {
			this(width, height, scale, guiWidth, guiHeight, hotbar, inventoryX, flying, nanos, Layout.DEFAULT);
		}

		/** The same window with this layout, in the mode every build before tl118 played. */
		View(int width, int height, int scale, int guiWidth, int guiHeight, boolean hotbar, int inventoryX, boolean flying, long nanos, Layout layout) {
			this(width, height, scale, guiWidth, guiHeight, hotbar, inventoryX, flying, nanos, layout, TouchMode.CROSSHAIR);
		}

		/** The same window in this mode, with no interact button, as every build before tl119 had. */
		View(int width, int height, int scale, int guiWidth, int guiHeight, boolean hotbar, int inventoryX, boolean flying, long nanos, Layout layout, TouchMode mode) {
			this(width, height, scale, guiWidth, guiHeight, hotbar, inventoryX, flying, nanos, layout, mode, null);
		}

		/** Whether a tap on the world uses and attacks, and a hold breaks (tl118) - in D-pad mode too (tl121). */
		boolean tap() {
			return mode != TouchMode.CROSSHAIR;
		}

		float aspect() {
			return (float) width / height;
		}

		/** The stick's reach - and its ring's radius - as a fraction of the window's height. */
		float stickRadius() {
			return STICK_RADIUS * layout.stickSize();
		}
	}

	private TouchPad() {
	}

	/** Whether touch controls are on. */
	public static boolean active() {
		return active;
	}

	/**
	 * Whether touch controls are on and play Joystick &amp; tap to interact (tl118): no crosshair, the game's pick
	 * following the finger ({@link TouchTap#pick}), and no aim assist.
	 */
	public static boolean tapMode() {
		return active && mode() != TouchMode.CROSSHAIR;
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
				refused = true;
				return;
			}
			refused = false;
			active = true;
			TouchInput.setInWorld(TouchPad::inWorld);
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
		TouchControls controls = controls();
		boolean touched = TouchInput.takeTouchscreen();
		boolean mouse = TouchInput.takeMouse();
		boolean key = TouchInput.takeKey();
		boolean pad = active && controls == TouchControls.AUTOMATIC && otherControllerUsed();
		Boolean change = turn(controls, active, touched, mouse, key, pad);
		if (Boolean.TRUE.equals(change) && (touched || !refused)) {
			setActive(true);
			if (active && touched) {
				// The touch that brought them on does only that: its finger is nobody's until it lifts.
				heldOver(TouchInput.fingers());
			}
		} else if (Boolean.FALSE.equals(change)) {
			setActive(false);
		}
		if (!active) {
			// Off, and to come on at a touch: in the world that touch must not be a click as well.
			hint(controls != TouchControls.OFF && MinecraftUtil.getScreen() == null ? "0" : "1");
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
			TouchInteract.clear();
			heldOver(TouchInput.fingers());
			return;
		}
		Window window = minecraft.getWindow();
		TouchInteract.frame(minecraft, mode(), window.getGuiScaledWidth(), window.getGuiScaledHeight());
		TouchTap.breakProgress(minecraft.gameMode != null && minecraft.gameMode.isDestroying()
				? (minecraft.gameMode.getDestroyStage() + 1) / 10f : 0f);
		TouchPick.setEnabled(pickBlock());
		Turn turn = readFingers(TouchInput.fingers(), view(minecraft));
		TouchPick.pick(minecraft);
		int slot = TouchButtons.takeSlot();
		if (slot >= 0) {
			minecraft.player.getInventory().setSelectedSlot(slot);
		}
		if (turn.any()) {
			float units = lookSpeed(cameraSensitivity()) * TURN_UNITS_PER_DEGREE;
			// Through a spyglass, slowed as the game slows the mouse there (first person and scoping), by the player's damping.
			if (minecraft.options.getCameraType().isFirstPerson() && minecraft.player.isScoping()) {
				units *= spyglassSpeed(spyglassDamping());
			}
			minecraft.player.turn(turn.yawDegrees() * units, turn.pitchDegrees() * units);
		}
	}

	/** The window as it is now; see {@link View}. */
	private static View view(Minecraft minecraft) {
		return view(minecraft, layout());
	}

	/** The window as it is now, with this layout. */
	private static View view(Minecraft minecraft, Layout layout) {
		return view(minecraft, layout, mode());
	}

	/** The window as it is now, with this layout, in this scheme (tl129: the glyph editor previews any of the three). */
	private static View view(Minecraft minecraft, Layout layout, TouchMode scheme) {
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
				hotbar, TouchButtons.inventoryX(guiWidth, rightSideTaken), flying, System.nanoTime(), layout, scheme, TouchInteract.shown());
	}

	/** The layout the player saved for the scheme they play (tl129), or the default before there is a config to read it from. */
	static Layout layout() {
		ConfigManager config = Controlify.instance().config();
		if (config == null) {
			return Layout.DEFAULT;
		}
		TouchSettings settings = config.getSettings().touchSettings();
		return Layout.of(settings.layout(settings.mode));
	}

	/**
	 * What the touch controls do this frame (tl120), as {@link TouchControls} says: {@code true} to come on, {@code false}
	 * to go off, {@code null} to stay as they are. On by themselves, a finger on a touchscreen brings them on; a click
	 * or scroll of the mouse, a key pressed in the world or another controller's input - and no touch with it - takes
	 * them off.
	 *
	 * @param touched whether a finger landed on a touchscreen since the last frame
	 * @param mouse   whether the mouse was clicked or scrolled since the last frame
	 * @param key     whether a key was pressed in the world since the last frame
	 * @param pad     whether a controller other than the pad is giving input
	 */
	static @Nullable Boolean turn(TouchControls controls, boolean active, boolean touched, boolean mouse, boolean key, boolean pad) {
		return switch (controls) {
			case ON -> active ? null : Boolean.TRUE;
			case OFF -> active ? Boolean.FALSE : null;
			case AUTOMATIC -> {
				if (!active) {
					yield touched ? Boolean.TRUE : null;
				}
				yield !touched && (mouse || key || pad) ? Boolean.FALSE : null;
			}
		};
	}

	/** Whether a controller other than the pad is giving input: the player has picked one up. */
	private static boolean otherControllerUsed() {
		ControllerEntity mine = entity();
		return Controlify.instance().getControllerManager()
				.map(manager -> manager.getConnectedControllers().stream()
						.anyMatch(controller -> controller != mine
								&& controller.input().map(input -> input.stateNow().isGivingInput()).orElse(false)))
				.orElse(false);
	}

	/** Whether the player is in the world with no screen up, where a key pressed is play (tl120). */
	private static boolean inWorld() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft != null && minecraft.player != null && MinecraftUtil.getScreen() == null;
	}

	/** When the touch controls are on (tl120), or by themselves before there is a config to read it from. */
	static TouchControls controls() {
		ConfigManager config = Controlify.instance().config();
		return config == null ? TouchControls.AUTOMATIC : config.getSettings().touchSettings().controls;
	}

	/**
	 * Whether walking into a single block jumps up it while the touch controls are on (tl124): the touch settings' own
	 * Auto Jump, as Bedrock keeps one for touch, or on before there is a config to read it from. The game asks it in
	 * {@code LocalPlayer.sendPosition}, through the accessibility {@code LocalPlayerMixin}.
	 */
	public static boolean autoJump() {
		ConfigManager config = Controlify.instance().config();
		return config == null ? TouchConfig.DEFAULT.autoJump() : config.getSettings().touchSettings().autoJump;
	}

	/** Whether the Pick Block button is shown (tl128), or not before there is a config to read it from, as by default. */
	static boolean pickBlock() {
		ConfigManager config = Controlify.instance().config();
		return config != null && config.getSettings().touchSettings().pickBlock;
	}

	/** The player's spyglass damping (tl127), or the middle before there is a config to read it from. */
	static int spyglassDamping() {
		ConfigManager config = Controlify.instance().config();
		return config == null ? TouchConfig.DEFAULT_DAMPING : config.getSettings().touchSettings().spyglassDamping;
	}

	/**
	 * How fast a swipe turns the camera through a spyglass at a damping (tl127), as a multiple of its speed without:
	 * eight to the minus damping over 50 - 0 no slower, the middle, 50, an eighth, as the game slows the mouse there,
	 * and 100 a sixty-fourth.
	 */
	static float spyglassSpeed(int damping) {
		return (float) Math.pow(8.0, -damping / 50.0);
	}

	/** The player's camera sensitivity (tl123), or the middle before there is a config to read it from. */
	static int cameraSensitivity() {
		ConfigManager config = Controlify.instance().config();
		return config == null ? TouchConfig.DEFAULT_SENSITIVITY : config.getSettings().touchSettings().cameraSensitivity;
	}

	/**
	 * How fast a swipe turns the camera at a camera sensitivity (tl123), as a multiple of {@link #LOOK_DEGREES_PER_WIDTH}: the
	 * curve the game's own mouse sensitivity follows, so the middle, 50, is that speed, 100 about four times it and 0 about a
	 * sixteenth.
	 */
	static float lookSpeed(int sensitivity) {
		float f = 0.2f + 0.6f * sensitivity / 100f;
		return f * f * f * 8f;
	}

	/** The mode the player chose (tl118), or aim crosshair before there is a config to read it from. */
	static TouchMode mode() {
		ConfigManager config = Controlify.instance().config();
		return config == null ? TouchMode.CROSSHAIR : config.getSettings().touchSettings().mode;
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
		return readFingers(fingers, view, TouchTap.GAME);
	}

	/**
	 * {@link #readFingers(List, View)}, with what a finger on the world touches in tap mode given (tl118) - for tests.
	 * In tap mode the stick takes only a finger landing on its ring, so the rest of the left side is world as well;
	 * a finger that lands on the world is offered to {@link TouchTap} first, to tap or hold - which hands it back as
	 * the look if it moves, or once its hold is done - and only one landing while that one is down is the look at
	 * once; a finger holding to use turns the camera while no finger is the look and none is dragged from a button;
	 * and the triggers are pulled by the taps and holds as well. In either mode the interact button, while the view
	 * has one, is offered each new finger before the buttons, and pulls use while it is held (tl119).
	 */
	static Turn readFingers(List<TouchInput.Finger> fingers, View view, TouchTap.Target target) {
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
			if (key.equals(stickFinger) || key.equals(lookFinger) || HELD_OVER.contains(key) || TouchButtons.owns(key)
					|| TouchTap.owns(key) || TouchInteract.owns(key) || TouchPick.owns(key) || TouchDpad.owns(key)) {
				continue;
			}
			boolean landed = !seen.contains(key);
			if (landed && (TouchInteract.claim(finger, view) || TouchPick.claim(finger, view) || TouchDpad.claim(finger, view)
					|| TouchButtons.claim(finger, view))) {
				continue;
			}
			if (stick == null && view.mode() != TouchMode.DPAD && (view.tap() ? onRest(finger, view) : (finger.x() < STICK_ZONE || onRest(finger, view)))) {
				stickFinger = key;
				anchorX = finger.x();
				anchorY = finger.y();
				stick = finger;
			} else if (view.tap() && landed && TouchTap.free()) {
				TouchTap.land(finger, view.nanos());
			} else if (look == null) {
				lookFinger = key;
				lookX = finger.x();
				lookY = finger.y();
				look = finger;
			}
		}

		if (view.mode() == TouchMode.DPAD) {
			// The D-pad in place of the stick (tl121): full tilt its way, the diagonals at a unit's length.
			TouchDpad.update(fingers, view);
			float[] move = TouchDpad.direction();
			stickFinger = null;
			writeStick(move[0], move[1], TouchDpad.sprinting());
		} else if (stick == null) {
			stickFinger = null;
			writeStick(0f, 0f, false);
		} else {
			// In units of the window's height either way, so the stick is round on screen.
			float dx = (stick.x() - anchorX) * aspect / view.stickRadius();
			float dy = (stick.y() - anchorY) / view.stickRadius();
			float reach = (float) Math.sqrt(dx * dx + dy * dy);
			if (reach > 1f) {
				dx /= reach;
				dy /= reach;
			}
			writeStick(dx, dy, reach > SPRINT_PAST);
		}

		float[] drag = TouchButtons.update(fingers, view);
		long now = view.nanos();
		boolean useHeld = TouchButtons.trigger(SdlGamepad.SDL_GAMEPAD_AXIS_LEFT_TRIGGER, now);
		useHeld |= TouchInteract.update(fingers, view);
		TouchPick.update(fingers);
		boolean attackHeld = TouchButtons.trigger(SdlGamepad.SDL_GAMEPAD_AXIS_RIGHT_TRIGGER, now);
		if (view.tap()) {
			TouchTap.update(fingers, view, target);
			FingerKey handed = TouchTap.takeLook();
			if (handed != null) {
				TouchInput.Finger finger = null;
				for (TouchInput.Finger candidate : fingers) {
					if (handed.equals(FingerKey.of(candidate))) {
						finger = candidate;
					}
				}
				if (look == null && finger != null) {
					lookFinger = handed;
					lookX = TouchTap.lookFromX();
					lookY = TouchTap.lookFromY();
					look = finger;
				} else {
					HELD_OVER.add(handed);
				}
			}
			if (drag == null) {
				drag = TouchTap.drag();
			}
			useHeld |= TouchTap.trigger(SdlGamepad.SDL_GAMEPAD_AXIS_LEFT_TRIGGER);
			attackHeld |= TouchTap.trigger(SdlGamepad.SDL_GAMEPAD_AXIS_RIGHT_TRIGGER);
		}
		writeButtons(TouchButtons.buttonMask(now) | (sprinting ? 1 << SdlGamepad.SDL_GAMEPAD_BUTTON_LEFT_STICK : 0)
				| (TouchDpad.sneakHeld(now) ? 1 << SdlGamepad.SDL_GAMEPAD_BUTTON_RIGHT_STICK : 0));
		writeTriggers(useHeld, attackHeld);

		if (look == null) {
			lookFinger = null;
			return drag == null ? Turn.NONE : turn(drag[0], drag[1], aspect);
		}
		Turn turn = turn(look.x() - lookX, look.y() - lookY, aspect);
		lookX = look.x();
		lookY = look.y();
		return turn;
	}

	/**
	 * Whether a finger landed on the stick's ring where it rests: that is the stick too, wherever the player put
	 * it, past its zone as well (tl117).
	 */
	static boolean onRest(TouchInput.Finger finger, View view) {
		int radius = Math.round(view.stickRadius() * view.guiHeight());
		int[] rest = rest(view, view.guiWidth(), view.guiHeight(), radius);
		float dx = finger.x() * view.guiWidth() - rest[0];
		float dy = finger.y() * view.guiHeight() - rest[1];
		return dx * dx + dy * dy <= (float) radius * radius;
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
		TouchTap.letGo();
		TouchInteract.letGo();
		TouchPick.letGo();
		TouchDpad.letGo();
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

	/**
	 * After any screen draws: the close button in its top-right corner, while touch controls are on and the
	 * screen is one that Esc would close (tl116). A screen that will not close - the title screen, the death
	 * screen - shows none.
	 */
	public static void renderScreen(Screen screen, GuiGraphicsExtractor graphics) {
		if (!active || !screen.shouldCloseOnEsc()) {
			return;
		}
		Window window = Minecraft.getInstance().getWindow();
		TouchButtons.renderClose(graphics, window.getWidth(), window.getHeight(), window.getGuiScale());
	}

	/**
	 * A click on a screen, before the screen has it: a left click - a finger's tap, which SDL makes a click on a
	 * screen - on the close button closes the screen as Esc does, with the sound a controller's back makes, and
	 * the screen never sees it. True if it did (tl116).
	 */
	public static boolean closeTapped(Screen screen, double guiX, double guiY, int button) {
		if (!active || button != InputConstants.MOUSE_BUTTON_LEFT || !screen.shouldCloseOnEsc()) {
			return false;
		}
		Window window = Minecraft.getInstance().getWindow();
		if (!TouchButtons.closeContains(window.getWidth(), window.getHeight(), window.getGuiScale(), guiX, guiY)) {
			return false;
		}
		ScreenProcessor.playClackSound();
		screen.onClose();
		return true;
	}

	/**
	 * The touch controls this layout places, for the glyph editor's Touch tab (tl117): the stick and the five
	 * buttons exactly where the game would draw them in the world now - kept clear of the hotbar as there, though
	 * the editor does not show it - at rest, as on a screen the pad has let go of every finger. Chat and pause are
	 * left out, as the tab does not move them and they would sit under its tab bar, and so is the slot of three dots.
	 */
	public static void drawPreview(GuiGraphicsExtractor graphics, Layout layout) {
		drawPreview(graphics, view(Minecraft.getInstance(), layout));
	}

	/** {@link #drawPreview(GuiGraphicsExtractor, Layout)} in a given scheme (tl129): the D-pad in D-pad mode, tap mode's buttons in tap mode. */
	public static void drawPreview(GuiGraphicsExtractor graphics, Layout layout, TouchMode scheme) {
		drawPreview(graphics, view(Minecraft.getInstance(), layout, scheme));
	}

	/**
	 * This layout with its offsets kept to what holds the stick's ring and the five buttons inside this window, for
	 * the glyph editor (tl117): its arrows and boxes stop at the edges rather than count on past them. The sizes are
	 * left as they are, and the hotbar is not measured; drawn, the controls are kept inside the window whatever the
	 * offsets ({@link #rest}, {@link TouchButtons#shift}).
	 */
	public static Layout keptInside(Layout layout) {
		return keptInside(layout, mode());
	}

	/** {@link #keptInside(Layout)} for a given scheme (tl129). */
	public static Layout keptInside(Layout layout, TouchMode scheme) {
		Window window = Minecraft.getInstance().getWindow();
		return keptInside(layout, window.getWidth(), window.getHeight(), window.getGuiScaledWidth(), window.getGuiScaledHeight(), scheme);
	}

	/** {@link #keptInside(Layout)}, against a given window - for tests. */
	static Layout keptInside(Layout layout, int width, int height, int guiWidth, int guiHeight) {
		return keptInside(layout, width, height, guiWidth, guiHeight, TouchMode.CROSSHAIR);
	}

	/** {@link #keptInside(Layout)}, against a given window, for the buttons there are in this mode (tl118) - for tests. */
	static Layout keptInside(Layout layout, int width, int height, int guiWidth, int guiHeight, TouchMode mode) {
		float gui = guiHeight;
		int radius = Math.round(STICK_RADIUS * layout.stickSize() * guiHeight);
		int fromCorner = Math.round(REST_FROM_CORNER * layout.stickSize() * guiHeight);
		float stickX = within(layout.stickX(), (radius - fromCorner) / gui, (guiWidth - radius - 1 - fromCorner) / gui);
		float stickY = within(layout.stickY(), (radius - guiHeight + fromCorner) / gui, (fromCorner - radius - 1) / gui);
		if (mode == TouchMode.DPAD) {
			// The D-pad in the stick's place (tl121), as far as its whole cross stays inside the window, in its pixels.
			int unit = Math.max(1, Math.round(TouchDpad.SIZE * layout.stickSize() * height / TouchButtons.GRID));
			int half = Math.round((TouchDpad.PITCH + TouchButtons.GRID / 2f) * unit);
			int middle = Math.round(TouchDpad.FROM_CORNER * unit);
			stickX = within(layout.stickX(), (half - middle) / (float) height, (width - half - middle) / (float) height);
			stickY = within(layout.stickY(), (half - height + middle) / (float) height, (middle - half) / (float) height);
		}
		int[] unmoved = TouchButtons.bounds(width, height, new Layout(0f, 0f, 1f, 0f, 0f, layout.buttonSize()), mode);
		float buttonsX = within(layout.buttonsX(), -unmoved[0] / (float) height, (width - unmoved[2]) / (float) height);
		float buttonsY = within(layout.buttonsY(), -unmoved[1] / (float) height, (height - unmoved[3]) / (float) height);
		return new Layout(stickX, stickY, layout.stickSize(), buttonsX, buttonsY, layout.buttonSize());
	}

	/** A value no lower than one end and no higher than the other; the low end wins were they ever crossed. */
	private static float within(float value, float low, float high) {
		return Math.max(low, Math.min(value, high));
	}

	/** {@link #drawPreview(GuiGraphicsExtractor, Layout)}, against a given window - for tests. */
	static void drawPreview(GuiGraphicsExtractor graphics, View view) {
		if (view.mode() == TouchMode.DPAD) {
			TouchDpad.render(graphics, view);
		} else {
			drawStick(graphics, view);
		}
		TouchButtons.render(graphics, view, true);
		TouchPick.render(graphics, view);
	}

	/** {@link #render}, against a given window - for tests. */
	static void draw(GuiGraphicsExtractor graphics, View view) {
		if (view.mode() == TouchMode.DPAD) {
			TouchDpad.render(graphics, view);
		} else {
			drawStick(graphics, view);
		}
		TouchButtons.render(graphics, view, false);
		TouchPick.render(graphics, view);
		if (view.interact() != null) {
			Minecraft minecraft = Minecraft.getInstance();
			TouchInteract.draw(graphics, minecraft == null ? null : minecraft.font, view.interact(), TouchInteract.pressed());
		}
		float[] ring = view.tap() ? TouchTap.ring() : null;
		if (ring != null) {
			int width = graphics.guiWidth();
			int height = graphics.guiHeight();
			holdRing(graphics, Math.round(ring[0] * width), Math.round(ring[1] * height), Math.round(HOLD_RING_RADIUS * height), ring[2]);
		}
	}

	/** The stick's ring, where it rests or where the thumb landed, and its knob. */
	private static void drawStick(GuiGraphicsExtractor graphics, View view) {
		int width = graphics.guiWidth();
		int height = graphics.guiHeight();
		int radius = Math.round(view.stickRadius() * height);
		boolean held = stickFinger != null;
		int[] rest = held ? null : rest(view, width, height, radius);
		int centreX = held ? Math.round(anchorX * width) : rest[0];
		int centreY = held ? Math.round(anchorY * height) : rest[1];
		ring(graphics, centreX, centreY, radius, held ? RING_HELD : RING);
		int knobX = centreX + Math.round(stickX * radius);
		int knobY = centreY + Math.round(stickY * radius);
		disc(graphics, knobX, knobY, Math.max(2, Math.round(radius * 0.35f)), sprinting ? KNOB_SPRINT : KNOB);
	}

	/**
	 * Where the stick's ring rests, in GUI pixels: {@link #REST_FROM_CORNER} in from the bottom-left corner, times
	 * the stick's size so it grows from that corner as the buttons grow from theirs, then moved by the player's
	 * offsets (tl117) - and moved left, or if it cannot go far enough or is right of the middle, up, just clear of
	 * the hotbar and what the game stacks over it wherever the ring would reach over them (a narrow window at a large
	 * GUI scale); last, kept inside the window, however the player moved and sized it (tl117), the edges of its own
	 * corner winning were it ever too big. At its default place and size it is inside already.
	 */
	static int[] rest(View view, int width, int height, int radius) {
		int fromCorner = Math.round(REST_FROM_CORNER * view.layout().stickSize() * height);
		int x = fromCorner + Math.round(view.layout().stickX() * height);
		int y = height - fromCorner + Math.round(view.layout().stickY() * height);
		int hotbarLeft = width / 2 - TouchButtons.HOTBAR_HALF_WIDTH;
		int hotbarRight = width / 2 + TouchButtons.HOTBAR_HALF_WIDTH;
		int stackTop = height - TouchButtons.HUD_STACK_HEIGHT;
		if (view.hotbar() && x + radius + 2 > hotbarLeft && x - radius - 2 < hotbarRight && y + radius + 2 > stackTop) {
			int left = hotbarLeft - radius - 2;
			if (x <= width / 2 && left - radius >= 1) {
				x = left;
			} else {
				y = stackTop - radius - 2;
			}
		}
		// The ring is drawn from radius left of its centre to radius right of it, and as far up and down.
		x = Math.max(radius, Math.min(x, width - radius - 1));
		y = Math.min(height - radius - 1, Math.max(y, radius));
		return new int[] {x, y};
	}

	/** A filled circle, a row of pixels at a time. */
	private static void disc(GuiGraphicsExtractor graphics, int cx, int cy, int r, int color) {
		for (int dy = -r; dy <= r; dy++) {
			int half = (int) Math.floor(Math.sqrt((double) r * r - (double) dy * dy));
			graphics.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
		}
	}

	/**
	 * The hold ring (tl118): a circle two pixels thick round a finger held on the world - every pixel whose centre is
	 * at least the radius less a pixel and a half from the ring's centre and less than the radius and a half, so its
	 * top, foot and sides are flat runs rather than single pixels - with as much of it as the block under the finger
	 * has broken, from the top, clockwise, drawn bright. Each row's runs are cut where the bright part ends, so nothing
	 * is drawn twice.
	 */
	static void holdRing(GuiGraphicsExtractor graphics, int cx, int cy, int r, float progress) {
		double reach = Math.max(0f, Math.min(progress, 1f)) * 2 * Math.PI;
		double outerSq = (r + 0.5) * (r + 0.5);
		double innerSq = (r - 1.5) * (r - 1.5);
		for (int dy = -r; dy <= r; dy++) {
			int outer = (int) Math.ceil(Math.sqrt(outerSq - (double) dy * dy)) - 1;
			double inside = innerSq - (double) dy * dy;
			int inner = inside <= 0 ? 0 : (int) Math.ceil(Math.sqrt(inside));
			if (inner == 0) {
				arcRow(graphics, cx, cy, dy, -outer, outer, reach);
			} else if (inner <= outer) {
				arcRow(graphics, cx, cy, dy, -outer, -inner, reach);
				arcRow(graphics, cx, cy, dy, inner, outer, reach);
			}
		}
	}

	/** One run of the hold ring's row, from {@code from} to {@code to} across the centre, cut where the bright part ends. */
	private static void arcRow(GuiGraphicsExtractor graphics, int cx, int cy, int dy, int from, int to, double reach) {
		int start = from;
		boolean bright = brightAt(from, dy, reach);
		for (int dx = from + 1; dx <= to + 1; dx++) {
			boolean next = dx <= to && brightAt(dx, dy, reach);
			if (dx > to || next != bright) {
				graphics.fill(cx + start, cy + dy, cx + dx, cy + dy + 1, bright ? HOLD_DONE : HOLD_RING);
				start = dx;
				bright = next;
			}
		}
	}

	/** Whether a pixel of the hold ring, this far across and down from its centre, is within the bright part: clockwise from the top. */
	private static boolean brightAt(int dx, int dy, double reach) {
		double angle = Math.atan2(dx, -dy);
		if (angle < 0) {
			angle += 2 * Math.PI;
		}
		return angle < reach;
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
		TouchTap.letGo();
		TouchInteract.letGo();
		TouchInteract.clear();
		TouchPick.letGo();
		TouchDpad.letGo();
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
