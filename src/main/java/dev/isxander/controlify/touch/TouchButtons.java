/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.touch;

import dev.isxander.sdl.SdlGamepad;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Touch controls, the buttons (tl112, tl114): the buttons of Bedrock's "Joystick &amp; aim crosshair"
 * scheme, where Bedrock puts them, drawn in the mod's own style. Each presses a button of the virtual pad
 * ({@link TouchPad}) - the one its action is bound to on the default binds - so the mod's bindings, its
 * toggles and everything downstream take a tap as a press.
 *
 * <p>On the right, against the edge: jump, sneak and use; beside them: sprint and attack - Bedrock's
 * layout with its action buttons shown. At the top in the middle: chat, and pause to its right, where
 * Bedrock has them (its emote button, left of chat, has no counterpart here). The hotbar's slots select
 * on a tap, and a slot of three dots after its right end opens the inventory, as Bedrock's does.
 *
 * <p>A finger that lands on a button keeps it for as long as it is down, wherever it slides. A finger
 * dragged from one of the five on the right turns the camera as well, as Bedrock's do, while no other
 * finger is looking. A tapped button stays pressed for {@link #MIN_PRESS_NANOS} at least: the pad is read
 * twenty times a second, and a quicker tap could fall between two reads.
 *
 * <p>The arrangement is Bedrock's, measured off its screen, tucked into the corners. A button is a grid of
 * {@link #GRID} units a side, its size a fraction of the window's height rounded to a whole number of the
 * window's pixels a unit, drawn straight into the window's pixels so the edges stay sharp at any GUI scale.
 * Its centre is a fraction of the window across and down plus an offset in its own units, so the gaps
 * between buttons grow and shrink with the rounded size and neighbours never close up, at any window size.
 *
 * <p>The look is the mod's own and must stay so - nothing is traced from Mojang's art: a see-through dark
 * square with its corners cut and a grey outline, a light grey picture on it casting a shadow; held, the
 * square light grey and the picture dark. While the player flies, jump and sneak show up and down.
 *
 * <p>On a screen none of these is drawn or pressed. A close button is drawn instead, in the top-right corner
 * of any screen that Esc would close, and a tap on it closes the screen as Esc does ({@link #CLOSE}, tl116).
 *
 * <p>The player places the five on the right as one, in the glyph editor's Touch tab (tl117): moved together
 * and sized together, from half to twice their size, growing from their corner ({@link #box(Button, int, int,
 * TouchPad.Layout)}), and kept inside the window ({@link #shift}). Chat and pause stay where they are.
 *
 * <p>In tap mode (tl118) attack and use are not there - a tap on the world attacks and uses, as Bedrock's tap mode
 * has neither ({@link #shown}) - and jump, sprint and sneak each sit a row lower, in the place of the one below them,
 * so sneak is in the corner (Donny, 1 Oct 15:50: option B; {@link #lower}). Kept clear of the hotbar and inside the
 * window by those three alone.
 */
public final class TouchButtons {
	/** A tapped button stays pressed at least this long, so the controller's tick - twenty a second - sees it. */
	static final long MIN_PRESS_NANOS = 100_000_000L;

	/** A button is drawn on a grid of this many units a side. */
	static final int GRID = 20;

	/** What each unit of a button's frame is, row by row: O the outline, F the fill, a space nothing. */
	static final String[] FRAME = frame();

	/** The colours, greys all (Donny, 05:29): the outline, the fill, the picture and its shadow, at rest and held. */
	private static final int OUTLINE = 0xC0A4A4A4;
	private static final int FILL = 0x70000000;
	private static final int PICTURE = 0xF0C4C4C4;
	private static final int SHADOW = 0xE0101010;
	private static final int OUTLINE_HELD = 0xFFD8D8D8;
	private static final int FILL_HELD = 0xA0B4B4B4;
	private static final int PICTURE_HELD = 0xF0303030;
	private static final int SHADOW_HELD = 0x50000000;

	/** The hotbar's sprite, for the inventory slot after its end. */
	private static final Identifier HOTBAR_SPRITE = Identifier.withDefaultNamespace("hud/hotbar");

	/**
	 * The pictures, the mod's own: on the {@link #GRID}-unit grid, where the top-left of each sits, then its
	 * rows. Jump and sneak a caret up and down, flying up and down a double one; sprint an arrow with speed
	 * lines; attack an upright sword with a point; use an open hand; chat a bubble with lines of text; pause
	 * two bars; pick block an eyedropper (tl128); close sneak's caret over jump's, tip to tip. Each casts a shadow a unit down and right, worked
	 * out from its rows ({@link #rects}).
	 */
	enum Icon {
		JUMP(4, 7,
				".....##.....",
				"....####....",
				"...######...",
				"..###..###..",
				".###....###.",
				"###......###"),
		SNEAK(4, 7,
				"###......###",
				".###....###.",
				"..###..###..",
				"...######...",
				"....####....",
				".....##....."),
		FLY_UP(5, 5,
				"....##....",
				"...####...",
				"..##..##..",
				".##....##.",
				"##..##..##",
				"...####...",
				"..##..##..",
				".##....##.",
				"##......##"),
		FLY_DOWN(5, 6,
				"##......##",
				".##....##.",
				"..##..##..",
				"...####...",
				"##..##..##",
				".##....##.",
				"..##..##..",
				"...####...",
				"....##...."),
		SPRINT(3, 5,
				"........#....",
				"........##...",
				"...####.###..",
				"........####.",
				"######..#####",
				"........####.",
				"..#####.###..",
				"........##...",
				"........#...."),
		ATTACK(6, 2,
				"....#...",
				"...###..",
				"...###..",
				"...###..",
				"...###..",
				"...###..",
				"...###..",
				"...###..",
				"...###..",
				".#######",
				"....#...",
				"....#...",
				"...###..",
				"....#..."),
		USE(4, 4,
				"......#......",
				"...#..#..#...",
				"...#..#..#..#",
				"...#..#..#..#",
				"#..#..#..#..#",
				"##.########.#",
				".############",
				"..###########",
				"..##########.",
				"...#########.",
				"....#######..",
				".....#####..."),
		CHAT(4, 5,
				".##########.",
				"#..........#",
				"#.########.#",
				"#..........#",
				"#.#####....#",
				"#..........#",
				".######.###.",
				"......#.#...",
				"......##...."),
		PAUSE(6, 5,
				"###..###",
				"###..###",
				"###..###",
				"###..###",
				"###..###",
				"###..###",
				"###..###",
				"###..###",
				"###..###",
				"###..###"),
		DPAD_UP(4, 7,
				".....##.....",
				"....####....",
				"...######...",
				"..########..",
				".##########.",
				"############"),
		DPAD_DOWN(4, 7,
				"############",
				".##########.",
				"..########..",
				"...######...",
				"....####....",
				".....##....."),
		DPAD_LEFT(7, 4,
				".....#",
				"....##",
				"...###",
				"..####",
				".#####",
				"######",
				"######",
				".#####",
				"..####",
				"...###",
				"....##",
				".....#"),
		DPAD_RIGHT(7, 4,
				"#.....",
				"##....",
				"###...",
				"####..",
				"#####.",
				"######",
				"######",
				"#####.",
				"####..",
				"###...",
				"##....",
				"#....."),
		DPAD_UP_LEFT(5, 5,
				"########",
				"#######.",
				"######..",
				"#####...",
				"####....",
				"###.....",
				"##......",
				"#......."),
		DPAD_UP_RIGHT(6, 5,
				"########",
				".#######",
				"..######",
				"...#####",
				"....####",
				".....###",
				"......##",
				".......#"),
		PICK(4, 4,
				".........###",
				"........####",
				".......####.",
				"....#.####..",
				".....####...",
				"....###.#...",
				"...###......",
				"..###.......",
				".###........",
				".##.........",
				"#..........."),
		CLOSE(4, 4,
				"###......###",
				".###....###.",
				"..###..###..",
				"...######...",
				"....####....",
				".....##.....",
				".....##.....",
				"....####....",
				"...######...",
				"..###..###..",
				".###....###.",
				"###......###");

		final int left;
		final int top;
		final String[] rows;

		Icon(int left, int top, String... rows) {
			this.left = left;
			this.top = top;
			this.rows = rows;
		}
	}

	/**
	 * One button: its picture; its centre, {@code fromX} of the window's width across and {@code fromY} of its
	 * height down, each plus an offset in the button's own units; its side in window heights; what it
	 * presses - a pad button, or a trigger when {@code axis} is not -1; and whether a drag from it looks.
	 */
	record Button(String name, Icon icon, float fromX, float offsetX, float fromY, float offsetY, float size, int button, int axis, boolean looks) {
	}

	/**
	 * The five on the right in Bedrock's arrangement - its column pitch, 31.16 of a button's units, and its
	 * row pitch, 29.09, the inner column half a row higher - hung from the bottom-right corner 5 units clear
	 * of both edges (Donny, 05:29: tucked into the corner), 0.12 of the window's height a side. Chat and pause
	 * where Bedrock has them, at the top in the middle, 0.058 a side.
	 */
	static final List<Button> BUTTONS = List.of(
			new Button("jump", Icon.JUMP, 1f, -15f, 1f, -73.18f, 0.12f, SdlGamepad.SDL_GAMEPAD_BUTTON_SOUTH, -1, true),
			new Button("sprint", Icon.SPRINT, 1f, -46.16f, 1f, -58.64f, 0.12f, SdlGamepad.SDL_GAMEPAD_BUTTON_LEFT_STICK, -1, true),
			new Button("sneak", Icon.SNEAK, 1f, -15f, 1f, -44.09f, 0.12f, SdlGamepad.SDL_GAMEPAD_BUTTON_RIGHT_STICK, -1, true),
			new Button("attack", Icon.ATTACK, 1f, -46.16f, 1f, -29.55f, 0.12f, -1, SdlGamepad.SDL_GAMEPAD_AXIS_RIGHT_TRIGGER, true),
			new Button("use", Icon.USE, 1f, -15f, 1f, -15f, 0.12f, -1, SdlGamepad.SDL_GAMEPAD_AXIS_LEFT_TRIGGER, true),
			new Button("chat", Icon.CHAT, 0.5f, 0f, 0f, 11.26f, 0.058f, SdlGamepad.SDL_GAMEPAD_BUTTON_DPAD_UP, -1, false),
			new Button("pause", Icon.PAUSE, 0.5f, 21.26f, 0f, 11.26f, 0.058f, SdlGamepad.SDL_GAMEPAD_BUTTON_START, -1, false));

	/**
	 * The close button, on a screen only: in the top-right corner, the size of chat and pause and as far below
	 * the top, as far in from the right edge. It presses nothing on the pad; a tap on it closes the screen as Esc
	 * does ({@link TouchPad#closeTapped}).
	 */
	static final Button CLOSE = new Button("close", Icon.CLOSE, 1f, -11.26f, 0f, 11.26f, 0.058f, -1, -1, false);

	/** The inventory slot after the hotbar: an index past the buttons, pressing Y - inventory on the default binds. */
	static final int INVENTORY = BUTTONS.size();
	private static final int INVENTORY_BUTTON = SdlGamepad.SDL_GAMEPAD_BUTTON_NORTH;

	/** A finger on the hotbar: no button, a slot instead. */
	private static final int HOTBAR = -1;

	/** The hotbar, as the game draws it: 182 wide, 22 high, its nine slots 20 apart from one pixel in. */
	static final int HOTBAR_HALF_WIDTH = 91;
	static final int HOTBAR_HEIGHT = 22;
	static final int SLOT_PITCH = 20;
	static final int SLOT_SIZE = 22;

	/** How high the game stacks its HUD over the hotbar's width, in GUI pixels up from the bottom: the hotbar, the experience bar, health and food, armour and air. */
	static final int HUD_STACK_HEIGHT = 49;

	/** How many of their own units the five stand, from jump's top to use's foot (tl117). */
	static final float GROUP_HEIGHT = groupHeight();

	/** Each button's frame and picture as rectangles of colour, worked out once. */
	private static final Map<Icon, int[][]> REST_RECTS = new EnumMap<>(Icon.class);
	private static final Map<Icon, int[][]> HELD_RECTS = new EnumMap<>(Icon.class);

	static {
		for (Icon icon : Icon.values()) {
			REST_RECTS.put(icon, rects(icon, false));
			HELD_RECTS.put(icon, rects(icon, true));
		}
	}

	/** What a finger on the buttons has: which button (or the hotbar), and where it was last frame, for the look. */
	private static final class Claim {
		final int button;
		float lastX;
		float lastY;

		Claim(int button, float x, float y) {
			this.button = button;
			this.lastX = x;
			this.lastY = y;
		}
	}

	private static final Map<TouchPad.FingerKey, Claim> CLAIMS = new LinkedHashMap<>();

	/** When each button - and the inventory slot, last - was last pressed, for the shortest press; 0 for never. */
	private static final long[] PRESSED_AT = new long[BUTTONS.size() + 1];

	/** The hotbar slot a finger asked for since it was last taken, or -1. */
	private static int slotWanted = -1;

	private TouchButtons() {
	}

	/** A button's square in the window's pixels: its top left and the size of one of its units. */
	record Box(int x, int y, int unit) {
		int side() {
			return unit * GRID;
		}

		boolean contains(float px, float py) {
			return px >= x && px < x + side() && py >= y && py < y + side();
		}
	}

	/** Where a button sits in a window of this many pixels, nothing moved or resized. */
	static Box box(Button button, int width, int height) {
		return box(button, width, height, TouchPad.Layout.DEFAULT);
	}

	/**
	 * Where a button sits in a window of this many pixels with the player's layout (tl117): the five hung from
	 * the bottom-right corner are moved together, by whole pixels, and sized together - their gaps in their own
	 * units, so the group keeps its shape at any size, growing from that corner, though never taller than the
	 * window holds - and chat, pause and the close button stay where they are.
	 */
	static Box box(Button button, int width, int height, TouchPad.Layout layout) {
		return box(button, width, height, layout, TouchMode.CROSSHAIR);
	}

	/**
	 * Where a button sits in a window of this many pixels with the player's layout, in this mode: as above, and in
	 * tap mode, where attack and use are not there, each of the other three of the five sits a row lower, in the
	 * place of the one below it ({@link #lower}, Donny 1 Oct 15:50: option B) - sneak in the corner.
	 */
	static Box box(Button button, int width, int height, TouchPad.Layout layout, TouchMode mode) {
		boolean group = inGroup(button);
		Button place = group && mode != TouchMode.CROSSHAIR ? lower(button) : button;
		float size = group ? button.size() * layout.buttonSize() : button.size();
		int unit = Math.max(1, Math.round(size * height / GRID));
		if (group) {
			unit = Math.min(unit, Math.max(1, (int) ((height - 1) / GROUP_HEIGHT)));
		}
		int side = unit * GRID;
		int centreX = Math.round(place.fromX() * width + place.offsetX() * unit) + (group ? Math.round(layout.buttonsX() * height) : 0);
		int centreY = Math.round(place.fromY() * height + place.offsetY() * unit) + (group ? Math.round(layout.buttonsY() * height) : 0);
		return new Box(centreX - side / 2, centreY - side / 2, unit);
	}

	/**
	 * The one of the five next below a button in its column - jump's is sneak, sneak's is use, sprint's is attack -
	 * whose place it takes in tap mode (tl118); itself when none is below it.
	 */
	static Button lower(Button button) {
		Button below = button;
		for (Button other : BUTTONS) {
			if (inGroup(other) && other.offsetX() == button.offsetX() && other.offsetY() > button.offsetY()
					&& (below == button || other.offsetY() < below.offsetY())) {
				below = other;
			}
		}
		return below;
	}

	/** Whether a button is one of the five hung from the bottom-right corner, which the player moves and sizes together. */
	static boolean inGroup(Button button) {
		return button.fromY() == 1f;
	}

	/**
	 * Whether a button is there in this mode (tl118): all of them but in tap mode, where a tap on the world uses and
	 * attacks, so the two that pull the triggers - attack and use - are not, as Bedrock's tap mode has neither.
	 */
	static boolean shown(Button button, TouchMode mode) {
		return switch (mode) {
			case CROSSHAIR -> true;
			case TAP -> button.axis() < 0;
			// D-pad mode (tl121): jump alone on the right; sprint and sneak are the D-pad's.
			case DPAD -> !inGroup(button) || button.icon() == Icon.JUMP;
		};
	}

	/** See {@link #GROUP_HEIGHT}: from the highest top to the lowest foot of the five, in their units. */
	private static float groupHeight() {
		float top = Float.NEGATIVE_INFINITY;
		float foot = Float.POSITIVE_INFINITY;
		for (Button button : BUTTONS) {
			if (inGroup(button)) {
				top = Math.max(top, -button.offsetY() + GRID / 2f);
				foot = Math.min(foot, -button.offsetY() - GRID / 2f);
			}
		}
		return top - foot;
	}

	/**
	 * Where a button sits in this frame's window: as {@link #box(Button, int, int, TouchPad.Layout)} with the
	 * view's layout, the ones hung from the bottom moved together by {@link #shift}.
	 */
	static Box box(Button button, TouchPad.View view) {
		Box box = box(button, view.width(), view.height(), view.layout(), view.mode());
		if (!inGroup(button)) {
			return box;
		}
		int[] shift = shift(view);
		return shift[0] == 0 && shift[1] == 0 ? box : new Box(box.x() + shift[0], box.y() + shift[1], box.unit());
	}

	/**
	 * How far the five hung from the bottom move, together, from where the layout puts them, in window pixels,
	 * right and down positive: lifted just clear of the hotbar and what the game stacks over it, or of the slot of
	 * three dots, wherever one of them would reach over it ({@link #lift}) - only a narrow window at a large GUI
	 * scale does at their default place and size; at 16:9 they sit in the corner - then brought back inside the
	 * window from any edge they would cross (tl117), so however the player moved and sized them every one can be
	 * reached; were they ever too big for the window, the edges of their own corner would win. At their default
	 * place and size they cross none.
	 */
	static int[] shift(TouchPad.View view) {
		int up = lift(view);
		int[] bounds = bounds(view.width(), view.height(), view.layout(), view.mode());
		int left = bounds[0];
		int top = bounds[1] - up;
		int right = bounds[2];
		int bottom = bounds[3] - up;
		int dx = left < 0 ? -left : 0;
		if (right + dx > view.width()) {
			dx = view.width() - right;
		}
		int dy = top < 0 ? -top : 0;
		if (bottom + dy > view.height()) {
			dy = view.height() - bottom;
		}
		return new int[] {dx, dy - up};
	}

	/** Where the layout puts the five, in a window of this many pixels, together: their left, top, right and bottom edges. */
	static int[] bounds(int width, int height, TouchPad.Layout layout) {
		return bounds(width, height, layout, TouchMode.CROSSHAIR);
	}

	/** Where the layout puts those of the five there are in this mode (tl118), together: their left, top, right and bottom edges. */
	static int[] bounds(int width, int height, TouchPad.Layout layout, TouchMode mode) {
		int left = Integer.MAX_VALUE;
		int top = Integer.MAX_VALUE;
		int right = Integer.MIN_VALUE;
		int bottom = Integer.MIN_VALUE;
		for (Button button : BUTTONS) {
			if (!inGroup(button) || !shown(button, mode)) {
				continue;
			}
			Box box = box(button, width, height, layout, mode);
			left = Math.min(left, box.x());
			top = Math.min(top, box.y());
			right = Math.max(right, box.x() + box.side());
			bottom = Math.max(bottom, box.y() + box.side());
		}
		return new int[] {left, top, right, bottom};
	}

	/** How far the five hung from the bottom go up, a unit clear of the hotbar's stack and the three dots; 0 when none reaches over them, or there is no hotbar. */
	static int lift(TouchPad.View view) {
		if (!view.hotbar()) {
			return 0;
		}
		int scale = view.scale();
		int hotbarLeft = (view.guiWidth() / 2 - HOTBAR_HALF_WIDTH) * scale;
		int hotbarRight = (view.guiWidth() / 2 + HOTBAR_HALF_WIDTH) * scale;
		int stackTop = (view.guiHeight() - HUD_STACK_HEIGHT) * scale;
		int slotLeft = view.inventoryX() * scale;
		int slotRight = (view.inventoryX() + SLOT_SIZE) * scale;
		int slotTop = (view.guiHeight() - HOTBAR_HEIGHT - 1) * scale;
		int lift = 0;
		for (Button button : BUTTONS) {
			if (!inGroup(button) || !shown(button, view.mode())) {
				continue;
			}
			Box box = box(button, view.width(), view.height(), view.layout(), view.mode());
			int left = box.x();
			int right = box.x() + box.side();
			int bottom = box.y() + box.side() + box.unit();
			if (left < hotbarRight && right > hotbarLeft) {
				lift = Math.max(lift, bottom - stackTop);
			} else if (left < slotRight && right > slotLeft) {
				lift = Math.max(lift, bottom - slotTop);
			}
		}
		return lift;
	}

	/** Where the inventory slot's left edge is, in GUI pixels: past the hotbar, and past the offhand slot or the attack indicator when either is on that side. */
	static int inventoryX(int guiWidth, boolean rightSideTaken) {
		return guiWidth / 2 + HOTBAR_HALF_WIDTH + 1 + (rightSideTaken ? 29 : 0);
	}

	/**
	 * A finger has just landed: if it is on a button, the hotbar or the inventory slot, it is ours from now
	 * until it lifts. True if it is.
	 */
	static boolean claim(TouchInput.Finger finger, TouchPad.View view) {
		TouchPad.FingerKey key = TouchPad.FingerKey.of(finger);
		float px = finger.x() * view.width();
		float py = finger.y() * view.height();
		for (int i = 0; i < BUTTONS.size(); i++) {
			if (shown(BUTTONS.get(i), view.mode()) && box(BUTTONS.get(i), view).contains(px, py)) {
				CLAIMS.put(key, new Claim(i, finger.x(), finger.y()));
				PRESSED_AT[i] = view.nanos();
				return true;
			}
		}
		if (!view.hotbar()) {
			return false;
		}
		float gx = px / view.scale();
		float gy = py / view.scale();
		if (gy < view.guiHeight() - HOTBAR_HEIGHT) {
			return false;
		}
		if (gx >= view.inventoryX() && gx < view.inventoryX() + SLOT_SIZE) {
			CLAIMS.put(key, new Claim(INVENTORY, finger.x(), finger.y()));
			PRESSED_AT[INVENTORY] = view.nanos();
			return true;
		}
		int slot = slotAt(gx, view.guiWidth());
		if (slot < 0) {
			return false;
		}
		CLAIMS.put(key, new Claim(HOTBAR, finger.x(), finger.y()));
		slotWanted = slot;
		return true;
	}

	/** The hotbar slot under this many GUI pixels across, or -1 when it is off the hotbar's ends. */
	static int slotAt(float gx, int guiWidth) {
		float left = guiWidth / 2 - HOTBAR_HALF_WIDTH;
		if (gx < left || gx >= left + 2 * HOTBAR_HALF_WIDTH) {
			return -1;
		}
		return Math.min(8, (int) ((gx - left) / SLOT_PITCH));
	}

	/** Whether this finger is ours. */
	static boolean owns(TouchPad.FingerKey key) {
		return CLAIMS.containsKey(key);
	}

	/**
	 * Every frame, with the fingers that are down: the lifted ones are let go; a finger on the hotbar
	 * selects the slot it has slid to while it stays on the hotbar; and the first finger that landed on a
	 * button that looks, of those still down, says how far it moved since last frame - as fractions of the
	 * window, or null when there is none. Every finger's last position is brought up to date either way, so
	 * when the look passes to one there is no jump.
	 */
	static float[] update(List<TouchInput.Finger> fingers, TouchPad.View view) {
		Set<TouchPad.FingerKey> down = new HashSet<>();
		for (TouchInput.Finger finger : fingers) {
			down.add(TouchPad.FingerKey.of(finger));
		}
		CLAIMS.keySet().retainAll(down);

		float[] drag = null;
		for (TouchInput.Finger finger : fingers) {
			Claim claim = CLAIMS.get(TouchPad.FingerKey.of(finger));
			if (claim == null) {
				continue;
			}
			if (claim.button == HOTBAR && view.hotbar()) {
				float gy = finger.y() * view.height() / view.scale();
				int slot = slotAt(finger.x() * view.width() / view.scale(), view.guiWidth());
				if (slot >= 0 && gy >= view.guiHeight() - HOTBAR_HEIGHT) {
					slotWanted = slot;
				}
			}
			if (drag == null && claim.button >= 0 && claim.button < INVENTORY && BUTTONS.get(claim.button).looks()) {
				drag = new float[] {finger.x() - claim.lastX, finger.y() - claim.lastY};
			}
			claim.lastX = finger.x();
			claim.lastY = finger.y();
		}
		return drag;
	}

	/** Whether a button - or the inventory slot, {@link #INVENTORY} - is pressed: a finger on it, or tapped too recently to let go. */
	static boolean held(int index, long now) {
		for (Claim claim : CLAIMS.values()) {
			if (claim.button == index) {
				return true;
			}
		}
		return PRESSED_AT[index] != 0 && now - PRESSED_AT[index] < MIN_PRESS_NANOS;
	}

	/** The pad's buttons the buttons hold down, as a mask of SDL's gamepad buttons. */
	static int buttonMask(long now) {
		int mask = 0;
		for (int i = 0; i < BUTTONS.size(); i++) {
			Button button = BUTTONS.get(i);
			if (button.button() >= 0 && held(i, now)) {
				mask |= 1 << button.button();
			}
		}
		if (held(INVENTORY, now)) {
			mask |= 1 << INVENTORY_BUTTON;
		}
		return mask;
	}

	/** Whether the button pulling this trigger is held. */
	static boolean trigger(int axis, long now) {
		for (int i = 0; i < BUTTONS.size(); i++) {
			if (BUTTONS.get(i).axis() == axis && held(i, now)) {
				return true;
			}
		}
		return false;
	}

	/** The hotbar slot a finger asked for since the last call, or -1; asking again for the same slot asks again. */
	static int takeSlot() {
		int slot = slotWanted;
		slotWanted = -1;
		return slot;
	}

	/** Lets go of every finger and every press, a tap's shortest press too: a screen is up, or touch is off. */
	static void letGo() {
		CLAIMS.clear();
		Arrays.fill(PRESSED_AT, 0L);
		slotWanted = -1;
	}

	/**
	 * Draws the buttons into the window's own pixels (the GUI is {@code scale} pixels a unit, so the pose is
	 * scaled down by it), then the inventory slot in GUI pixels after the hotbar's end; for the glyph editor's
	 * preview ({@code placedOnly}, tl117), only the five the player places.
	 */
	static void render(GuiGraphicsExtractor graphics, TouchPad.View view, boolean placedOnly) {
		Matrix3x2fStack pose = graphics.pose().pushMatrix();
		pose.scale(1f / view.scale(), 1f / view.scale());
		for (int i = 0; i < BUTTONS.size(); i++) {
			Button button = BUTTONS.get(i);
			if ((placedOnly && !inGroup(button)) || !shown(button, view.mode())) {
				continue;
			}
			Box box = box(button, view);
			boolean down = held(i, view.nanos());
			int[][] rects = (down ? HELD_RECTS : REST_RECTS).get(icon(button, view.flying()));
			int u = box.unit();
			for (int[] r : rects) {
				graphics.fill(box.x() + r[0] * u, box.y() + r[1] * u, box.x() + r[2] * u, box.y() + r[3] * u, r[4]);
			}
		}
		pose.popMatrix();

		if (view.hotbar() && !placedOnly) {
			int x = view.inventoryX();
			int y = view.guiHeight() - HOTBAR_HEIGHT;
			// The hotbar's own last slot and right end, so it reads as a tenth slot.
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HOTBAR_SPRITE, 182, 22, 160, 0, x, y, SLOT_SIZE, HOTBAR_HEIGHT);
			graphics.fill(x + 3, y + 3, x + 19, y + 19, held(INVENTORY, view.nanos()) ? 0x80FFFFFF : 0x40FFFFFF);
			for (int dot = 0; dot < 3; dot++) {
				graphics.fill(x + 6 + dot * 5, y + 11, x + 8 + dot * 5, y + 13, SHADOW);
				graphics.fill(x + 5 + dot * 5, y + 10, x + 7 + dot * 5, y + 12, PICTURE | 0xFF000000);
			}
		}
	}

	/** Draws a button's frame with this picture into a box, at rest or held; the pose already in the window's pixels (tl121, the D-pad). */
	static void drawFramed(GuiGraphicsExtractor graphics, Icon icon, Box box, boolean held) {
		int u = box.unit();
		for (int[] r : (held ? HELD_RECTS : REST_RECTS).get(icon)) {
			graphics.fill(box.x() + r[0] * u, box.y() + r[1] * u, box.x() + r[2] * u, box.y() + r[3] * u, r[4]);
		}
	}

	/**
	 * Draws the close button over a screen, into the window's own pixels as the buttons are drawn in the world
	 * (the GUI is {@code scale} pixels a unit, so the pose is scaled down by it). It is never drawn held: a tap
	 * closes the screen at once.
	 */
	static void renderClose(GuiGraphicsExtractor graphics, int width, int height, int scale) {
		Matrix3x2fStack pose = graphics.pose().pushMatrix();
		pose.scale(1f / scale, 1f / scale);
		Box box = box(CLOSE, width, height);
		int u = box.unit();
		for (int[] r : REST_RECTS.get(CLOSE.icon())) {
			graphics.fill(box.x() + r[0] * u, box.y() + r[1] * u, box.x() + r[2] * u, box.y() + r[3] * u, r[4]);
		}
		pose.popMatrix();
	}

	/** Whether a point on a screen, in GUI pixels, is on the close button in a window of this many pixels at this GUI scale. */
	static boolean closeContains(int width, int height, int scale, double guiX, double guiY) {
		return box(CLOSE, width, height).contains((float) (guiX * scale), (float) (guiY * scale));
	}

	/** The picture a button shows: jump and sneak show flying up and down while the player flies. */
	static Icon icon(Button button, boolean flying) {
		if (flying && button.icon() == Icon.JUMP) {
			return Icon.FLY_UP;
		}
		if (flying && button.icon() == Icon.SNEAK) {
			return Icon.FLY_DOWN;
		}
		return button.icon();
	}

	/** The frame, unit by unit: a one-unit outline round a fill, its corners cut two units. */
	private static String[] frame() {
		String[] rows = new String[GRID];
		for (int y = 0; y < GRID; y++) {
			if (y == 0 || y == GRID - 1) {
				rows[y] = "  " + "O".repeat(GRID - 4) + "  ";
			} else if (y == 1 || y == GRID - 2) {
				rows[y] = " O" + "F".repeat(GRID - 4) + "O ";
			} else {
				rows[y] = "O" + "F".repeat(GRID - 2) + "O";
			}
		}
		return rows;
	}

	/** The colour of a unit of the frame, of the picture ('I') or of its shadow ('S'), at rest or held; 0 for nothing. */
	private static int colour(char code, boolean held) {
		return switch (code) {
			case 'O' -> held ? OUTLINE_HELD : OUTLINE;
			case 'F' -> held ? FILL_HELD : FILL;
			case 'I' -> held ? PICTURE_HELD : PICTURE;
			case 'S' -> held ? SHADOW_HELD : SHADOW;
			default -> 0;
		};
	}

	/**
	 * The frame with the picture and its shadow laid into it, as rectangles {x0, y0, x1, y1, colour} in units:
	 * each row cut into runs of one colour, and a run joined to the one above it when they match, so nothing
	 * is drawn twice and a see-through button blends with the world once.
	 */
	static int[][] rects(Icon icon, boolean held) {
		char[][] grid = new char[GRID][];
		for (int y = 0; y < GRID; y++) {
			grid[y] = FRAME[y].toCharArray();
		}
		for (int row = 0; row < icon.rows.length; row++) {
			String line = icon.rows[row];
			for (int col = 0; col < line.length(); col++) {
				if (line.charAt(col) == '#') {
					grid[icon.top + row][icon.left + col] = 'I';
				}
			}
		}
		// The shadow: the fill a unit down and right of the picture, where the picture does not cover it.
		for (int y = GRID - 2; y >= 0; y--) {
			for (int x = GRID - 2; x >= 0; x--) {
				if (grid[y][x] == 'I' && grid[y + 1][x + 1] == 'F') {
					grid[y + 1][x + 1] = 'S';
				}
			}
		}
		List<int[]> done = new ArrayList<>();
		List<int[]> open = new ArrayList<>();
		for (int y = 0; y < GRID; y++) {
			List<int[]> next = new ArrayList<>();
			int x = 0;
			while (x < GRID) {
				char code = grid[y][x];
				int end = x + 1;
				while (end < GRID && grid[y][end] == code) {
					end++;
				}
				int colour = colour(code, held);
				if (colour != 0) {
					int[] joined = null;
					for (Iterator<int[]> it = open.iterator(); it.hasNext(); ) {
						int[] r = it.next();
						if (r[0] == x && r[2] == end && r[4] == colour) {
							joined = r;
							it.remove();
							break;
						}
					}
					if (joined == null) {
						joined = new int[] {x, y, end, y + 1, colour};
					} else {
						joined[3] = y + 1;
					}
					next.add(joined);
				}
				x = end;
			}
			done.addAll(open);
			open = next;
		}
		done.addAll(open);
		return done.toArray(new int[0][]);
	}
}
