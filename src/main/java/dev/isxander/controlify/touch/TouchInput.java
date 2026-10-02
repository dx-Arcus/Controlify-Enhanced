/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.touch;

import dev.isxander.controlify.utils.CUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
//? if >=26.3 {
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLTouch;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_EventFilter;
import org.lwjgl.sdl.SDL_TouchFingerEvent;
//?}

/**
 * The fingers on the screen, read out of the game's own SDL (tl110).
 *
 * <p>On 26.3 the game's window is SDL's - LWJGL's copy of SDL3, which is not the one Controlify loads for
 * controllers ({@code SDLNativesLoader} keeps its own) - and every finger that lands on the window arrives
 * there as a finger event: where it is, 0 to 1 across and down the window, an id for the finger and one for
 * the touch device it is on. The game handles none of them ({@code SDLEventHandler} switches on keyboard,
 * text, mouse and drop events and lets the rest fall through); a tap clicks today only because SDL makes a
 * mouse out of touches unless told not to ({@code SDL_TOUCH_MOUSE_EVENTS}). So this class asks that SDL to
 * call it with every event as it is queued - an event watch, which sees an event before anything polls it -
 * and keeps the fingers it sees. Everything else in {@code touch} reads them from here.
 *
 * <p>Two things are kept apart on purpose. The watch is the only part that needs LWJGL and it is a few
 * lines; what it learns goes through {@link #fingerDown}, {@link #fingerMoved} and {@link #fingerUp}, which
 * take plain numbers, so the tracking can be driven and checked without SDL at all. And the watch is called
 * on whichever thread hands SDL the event - the game's main thread, as it pumps - so the fingers are kept
 * under a lock and handed out as a copy.
 *
 * <p>The watch also keeps what the touch controls switch on and off by (tl120, {@link TouchControls}): a finger
 * landing on a touchscreen, a click or scroll of the mouse, a key pressed in the world - each kept until the touch
 * controls take it, once a frame.
 *
 * <p>26.1 and 26.2 have a GLFW window, which has no touch on a desktop. There {@link #SUPPORTED} is false,
 * {@link #install} does nothing, and there are never any fingers.
 */
public final class TouchInput {
	/** Whether this build can see fingers at all: 26.3, the first version whose window is SDL's. */
	public static final boolean SUPPORTED = /*? if >=26.3 {*/ true /*?} else {*/ /*false *//*?}*/;

	/**
	 * SDL's id for the touch device it makes out of the mouse while {@link #setMouseAsFinger} is on
	 * ({@code SDL_MOUSE_TOUCHID}). No real touch device has it.
	 */
	public static final long MOUSE_TOUCH_ID = -1L;

	/** SDL's id for the touch device it makes out of a pen ({@code SDL_PEN_TOUCHID}). No real touch device has it either. */
	public static final long PEN_TOUCH_ID = -2L;

	/** SDL's kind of touch device for a touchscreen, where a finger is where it touches ({@code SDL_TOUCH_DEVICE_DIRECT}). */
	public static final int DIRECT = 0;

	/** What SDL says of a touch device it does not know ({@code SDL_TOUCH_DEVICE_INVALID}). */
	public static final int INVALID = -1;

	/**
	 * SDL's ids for the mouse it makes out of touches ({@code SDL_TOUCH_MOUSEID}: a tap on a screen is a click) and out
	 * of a pen ({@code SDL_PEN_MOUSEID}). Every other mouse event is the mouse's.
	 */
	public static final int TOUCH_MOUSE_ID = -1;
	public static final int PEN_MOUSE_ID = -2;

	/** The modifier keys' scancodes, left Ctrl to right GUI ({@code SDL_SCANCODE_LCTRL} to {@code SDL_SCANCODE_RGUI}). */
	private static final int FIRST_MODIFIER = 224;
	private static final int LAST_MODIFIER = 231;

	/**
	 * One finger on the screen.
	 *
	 * @param touchId   the touch device it is on; {@link #MOUSE_TOUCH_ID} for the mouse standing in for a finger
	 * @param fingerId  SDL's id for the finger, unique on its device while it is down
	 * @param x         where it is now, 0 to 1 across the window
	 * @param y         where it is now, 0 to 1 down the window
	 * @param startX    where it landed, across
	 * @param startY    where it landed, down
	 * @param pressure  0 to 1; 1 where the device reports none
	 * @param downNanos {@link System#nanoTime()} when it landed
	 */
	public record Finger(long touchId, long fingerId, float x, float y, float startX, float startY, float pressure,
			long downNanos) {
		/** Whether this is the mouse standing in for a finger rather than a finger on a touch device. */
		public boolean fromMouse() {
			return touchId == MOUSE_TOUCH_ID;
		}

		/** This finger somewhere else, where it landed and when kept. */
		Finger movedTo(float x, float y, float pressure) {
			return new Finger(touchId, fingerId, x, y, startX, startY, pressure, downNanos);
		}
	}

	/** A finger is one per device: the same finger id can be down on two touch devices at once. */
	private record Key(long touchId, long fingerId) {
	}

	/** Every finger down right now, in the order they landed. Written by the watch, read by anyone. */
	private static final Map<Key, Finger> FINGERS = new LinkedHashMap<>();

	/** How many finger events have been seen since the game started - the watch is wired even with no finger down. */
	private static final AtomicLong EVENTS = new AtomicLong();

	/** What the player did since the touch controls last looked (tl120): a finger on a touchscreen, the mouse, a key in the world. */
	private static final AtomicBoolean TOUCHSCREEN = new AtomicBoolean();
	private static final AtomicBoolean MOUSE = new AtomicBoolean();
	private static final AtomicBoolean KEY = new AtomicBoolean();

	/** Whether the player is in the world with no screen up, where a key is play - set by the touch controls; never, until then. */
	private static volatile BooleanSupplier inWorld = () -> false;

	private TouchInput() {
	}

	/** A finger landed. One already down with the same ids - an up that was missed - is replaced. */
	public static void fingerDown(long touchId, long fingerId, float x, float y, float pressure, long nowNanos) {
		EVENTS.incrementAndGet();
		synchronized (FINGERS) {
			FINGERS.put(new Key(touchId, fingerId), new Finger(touchId, fingerId, x, y, x, y, pressure, nowNanos));
		}
	}

	/**
	 * A finger landed on a touch device of this SDL kind ({@code SDL_GetTouchDeviceType}): as
	 * {@link #fingerDown(long, long, float, float, float, long)}, and kept if it is a touchscreen's ({@link #touchscreen}).
	 */
	public static void fingerDown(long touchId, long fingerId, float x, float y, float pressure, long nowNanos, int deviceType) {
		fingerDown(touchId, fingerId, x, y, pressure, nowNanos);
		if (touchscreen(touchId, deviceType)) {
			TOUCHSCREEN.set(true);
		}
	}

	/**
	 * Whether a finger on this touch device is one on a touchscreen: a device of SDL's direct kind, and not SDL's own
	 * stand-in for the mouse ({@link #setMouseAsFinger}) or a pen, which it counts as direct too. A Mac's trackpad is
	 * an indirect kind; Windows hands a laptop's touchpad to the game as the mouse.
	 */
	public static boolean touchscreen(long touchId, int deviceType) {
		return touchId != MOUSE_TOUCH_ID && touchId != PEN_TOUCH_ID && deviceType == DIRECT;
	}

	/** A button of a mouse went down, or its wheel turned: kept if it is the mouse's, not one SDL made of a touch or a pen. */
	public static void mouseUsed(int which) {
		if (realMouse(which)) {
			MOUSE.set(true);
		}
	}

	/** Whether a mouse event is the mouse's: not one SDL made out of a touch or a pen. */
	public static boolean realMouse(int which) {
		return which != TOUCH_MOUSE_ID && which != PEN_MOUSE_ID;
	}

	/** A key went down: kept if it is play ({@link #playKey}) and the player is in the world ({@link #setInWorld}). */
	public static void keyDown(int scancode, boolean repeat) {
		if (playKey(scancode, repeat) && inWorld.getAsBoolean()) {
			KEY.set(true);
		}
	}

	/**
	 * Whether a key press is play: a first press, not a held key's repeats, of a key that is not a modifier on its own -
	 * Shift, Ctrl, Alt and the Windows key are held with other things, Alt to switch windows among them.
	 */
	public static boolean playKey(int scancode, boolean repeat) {
		return !repeat && (scancode < FIRST_MODIFIER || scancode > LAST_MODIFIER);
	}

	/** Where a key counts as play: in the world, with no screen up. The touch controls say where that is. */
	public static void setInWorld(BooleanSupplier where) {
		inWorld = where;
	}

	/** Whether a finger landed on a touchscreen since the last call. */
	public static boolean takeTouchscreen() {
		return TOUCHSCREEN.getAndSet(false);
	}

	/** Whether the mouse was clicked or scrolled since the last call. */
	public static boolean takeMouse() {
		return MOUSE.getAndSet(false);
	}

	/** Whether a key was pressed in the world since the last call. */
	public static boolean takeKey() {
		return KEY.getAndSet(false);
	}

	/**
	 * A finger moved. One not known to be down - it landed before the watch was installed - is taken as
	 * landing here now.
	 */
	public static void fingerMoved(long touchId, long fingerId, float x, float y, float pressure, long nowNanos) {
		EVENTS.incrementAndGet();
		synchronized (FINGERS) {
			Key key = new Key(touchId, fingerId);
			Finger known = FINGERS.get(key);
			FINGERS.put(key, known != null
					? known.movedTo(x, y, pressure)
					: new Finger(touchId, fingerId, x, y, x, y, pressure, nowNanos));
		}
	}

	/** A finger lifted, or the device cancelled it. One not known is nothing to forget. */
	public static void fingerUp(long touchId, long fingerId) {
		EVENTS.incrementAndGet();
		synchronized (FINGERS) {
			FINGERS.remove(new Key(touchId, fingerId));
		}
	}

	/** Every finger down right now, in the order they landed: a copy, safe to keep. */
	public static List<Finger> fingers() {
		synchronized (FINGERS) {
			return List.copyOf(FINGERS.values());
		}
	}

	/** How many fingers are down right now. */
	public static int count() {
		synchronized (FINGERS) {
			return FINGERS.size();
		}
	}

	/** How many finger events the watch has seen since the game started. */
	public static long eventsSeen() {
		return EVENTS.get();
	}

	/** Forgets every finger, and what the player did. For a test; the game forgets them as they lift. */
	public static void clear() {
		synchronized (FINGERS) {
			FINGERS.clear();
		}
		TOUCHSCREEN.set(false);
		MOUSE.set(false);
		KEY.set(false);
	}

	//? if >=26.3 {
	/** The watch, kept so the native callback behind it is never collected while SDL still calls it. */
	private static SDL_EventFilter watch;

	/** What {@link #setMouseAsFinger} last set: nothing else in the game touches that hint. */
	private static volatile boolean mouseAsFinger;

	/**
	 * Starts reading fingers from the game's SDL. Once; the window exists by the time Controlify
	 * initialises, and SDL's event watches outlive everything but SDL itself.
	 */
	public static void install() {
		if (watch != null) {
			return;
		}
		SDL_EventFilter filter = SDL_EventFilter.create(TouchInput::onEvent);
		if (SDLEvents.SDL_AddEventWatch(filter, 0L)) {
			watch = filter;
			CUtil.LOGGER.log("Touch: watching the game's SDL for fingers");
		} else {
			filter.free();
			CUtil.LOGGER.warn("Touch: the game's SDL refused an event watch, so there will be no fingers");
		}
	}

	/**
	 * Called by SDL with every event as it is queued, on the thread that queued it. Finger events are the
	 * four in a row from {@code SDL_EVENT_FINGER_DOWN}; a mouse button going down, the wheel and a key going
	 * down are kept for the touch controls to switch by (tl120); everything else is left alone. The return
	 * value is ignored for a watch.
	 */
	private static boolean onEvent(long userdata, long address) {
		SDL_Event event = SDL_Event.create(address);
		int type = event.type();
		switch (type) {
			case SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN -> mouseUsed(event.button().which());
			case SDLEvents.SDL_EVENT_MOUSE_WHEEL -> mouseUsed(event.wheel().which());
			case SDLEvents.SDL_EVENT_KEY_DOWN -> keyDown(event.key().scancode(), event.key().repeat());
			default -> {
			}
		}
		if (type < SDLEvents.SDL_EVENT_FINGER_DOWN || type > SDLEvents.SDL_EVENT_FINGER_CANCELED) {
			return true;
		}
		SDL_TouchFingerEvent finger = event.tfinger();
		switch (type) {
			case SDLEvents.SDL_EVENT_FINGER_DOWN -> fingerDown(finger.touchID(), finger.fingerID(), finger.x(), finger.y(),
					finger.pressure(), System.nanoTime(), deviceType(finger.touchID()));
			case SDLEvents.SDL_EVENT_FINGER_MOTION -> fingerMoved(finger.touchID(), finger.fingerID(), finger.x(), finger.y(),
					finger.pressure(), System.nanoTime());
			default -> fingerUp(finger.touchID(), finger.fingerID());
		}
		return true;
	}

	/**
	 * SDL's kind of touch device for this id. Only asked while SDL's video is up - as it always is under the game - since
	 * SDL looks to the video driver for an id it does not know; the mouse's and a pen's stand-ins are never asked.
	 */
	private static int deviceType(long touchId) {
		if (touchId == MOUSE_TOUCH_ID || touchId == PEN_TOUCH_ID || (SDLInit.SDL_WasInit(SDLInit.SDL_INIT_VIDEO) & SDLInit.SDL_INIT_VIDEO) == 0) {
			return INVALID;
		}
		return SDLTouch.SDL_GetTouchDeviceType(touchId);
	}

	/**
	 * Whether the mouse stands in for a finger. On, SDL raises a finger event for the left button going
	 * down, for every move while it is held, and for its release, on a touch device of its own
	 * ({@link #MOUSE_TOUCH_ID}); the mouse goes on being a mouse as well. That is SDL's own hint
	 * {@code SDL_MOUSE_TOUCH_EVENTS}, which it reads live (SDL_mouse.c, SDL_MouseTouchEventsChanged), set on
	 * the game's SDL - the one with the mouse - not Controlify's. The moves count only while the cursor is
	 * free: grabbed, as it is in the world, the mouse's motion reaches SDL relative and makes no finger
	 * motion, so there the stand-in finger lands and lifts where the cursor sits and never moves. A screen,
	 * where the cursor is free, shows the whole thing.
	 */
	public static void setMouseAsFinger(boolean on) {
		if (SDLHints.SDL_SetHint(SDLHints.SDL_HINT_MOUSE_TOUCH_EVENTS, on ? "1" : "0")) {
			mouseAsFinger = on;
			if (!on) {
				// SDL drops its stand-in device without lifting the finger on it - the press that turned this off,
				// since a button acts as it goes down - so that finger would stay down for good (tl121).
				synchronized (FINGERS) {
					FINGERS.keySet().removeIf(key -> key.touchId() == MOUSE_TOUCH_ID);
				}
			}
		} else {
			CUtil.LOGGER.warn("Touch: the game's SDL refused the mouse-as-finger hint");
		}
	}

	/** Whether the mouse stands in for a finger right now - see {@link #setMouseAsFinger}. */
	public static boolean mouseAsFinger() {
		return mouseAsFinger;
	}
	//?} else {
	/*public static void install() {
	}

	public static void setMouseAsFinger(boolean on) {
	}

	public static boolean mouseAsFinger() {
		return false;
	}
	*///?}
}
