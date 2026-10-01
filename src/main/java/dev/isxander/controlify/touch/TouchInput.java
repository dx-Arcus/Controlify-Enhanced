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
import java.util.concurrent.atomic.AtomicLong;
//? if >=26.3 {
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLHints;
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

	/** Forgets every finger. For a test; the game forgets them as they lift. */
	public static void clear() {
		synchronized (FINGERS) {
			FINGERS.clear();
		}
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
	 * four in a row from {@code SDL_EVENT_FINGER_DOWN}; everything else is left alone. The return value is
	 * ignored for a watch.
	 */
	private static boolean onEvent(long userdata, long address) {
		SDL_Event event = SDL_Event.create(address);
		int type = event.type();
		if (type < SDLEvents.SDL_EVENT_FINGER_DOWN || type > SDLEvents.SDL_EVENT_FINGER_CANCELED) {
			return true;
		}
		SDL_TouchFingerEvent finger = event.tfinger();
		switch (type) {
			case SDLEvents.SDL_EVENT_FINGER_DOWN -> fingerDown(finger.touchID(), finger.fingerID(), finger.x(), finger.y(),
					finger.pressure(), System.nanoTime());
			case SDLEvents.SDL_EVENT_FINGER_MOTION -> fingerMoved(finger.touchID(), finger.fingerID(), finger.x(), finger.y(),
					finger.pressure(), System.nanoTime());
			default -> fingerUp(finger.touchID(), finger.fingerID());
		}
		return true;
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
