/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.touch;

import dev.isxander.controlify.touch.TouchButtons.Box;
import dev.isxander.controlify.touch.TouchButtons.Button;
import dev.isxander.controlify.touch.TouchButtons.Icon;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2fStack;

import java.util.List;

/**
 * Bedrock's camera perspective button (tl131), shown while the touch settings' Camera Perspective Button is on - off by
 * default, as Bedrock's. At the top beside chat, on the other side of it from pause, their size. A press switches the
 * camera on to the next perspective - first person, third behind, third in front - as the game's own key does
 * ({@link TouchPad#togglePerspective}). Drawn in the touch buttons' frame: an eye, the mod's own picture.
 */
final class TouchPerspective {
	private static final Button CHAT = button("chat");
	private static final Button PAUSE = button("pause");

	private static boolean enabled;
	private static TouchPad.@Nullable FingerKey finger;
	private static long pressedAt;
	private static boolean pending;

	private TouchPerspective() {
	}

	private static Button button(String name) {
		for (Button each : TouchButtons.BUTTONS) {
			if (each.name().equals(name)) {
				return each;
			}
		}
		throw new IllegalStateException(name);
	}

	/** Every frame, before the fingers are read: whether the setting shows the button. Hidden, it forgets everything. */
	static void setEnabled(boolean on) {
		enabled = on;
		if (!on) {
			letGo();
		}
	}

	static boolean shown() {
		return enabled;
	}

	/** Where the button sits in this frame's window: chat's size, as far left of chat as pause is right of it. */
	static Box box(TouchPad.View view) {
		Box chat = TouchButtons.box(CHAT, view);
		int pitch = Math.round((PAUSE.offsetX() - CHAT.offsetX()) * chat.unit());
		return new Box(Math.max(0, chat.x() - pitch), chat.y(), chat.unit());
	}

	/** A finger has just landed: if the button is shown and the finger is on it, it is pressed. True if so. */
	static boolean claim(TouchInput.Finger landed, TouchPad.View view) {
		if (!enabled || finger != null || !box(view).contains(landed.x() * view.width(), landed.y() * view.height())) {
			return false;
		}
		finger = TouchPad.FingerKey.of(landed);
		pressedAt = view.nanos();
		pending = true;
		return true;
	}

	static boolean owns(TouchPad.FingerKey key) {
		return key.equals(finger);
	}

	/** Every frame, with the fingers down: the button's finger, until it lifts. */
	static void update(List<TouchInput.Finger> fingers) {
		if (finger == null) {
			return;
		}
		for (TouchInput.Finger each : fingers) {
			if (finger.equals(TouchPad.FingerKey.of(each))) {
				return;
			}
		}
		finger = null;
	}

	/** Whether the button was pressed since the last frame, once. */
	static boolean takePress() {
		boolean pressed = pending;
		pending = false;
		return pressed;
	}

	static void letGo() {
		finger = null;
		pending = false;
	}

	/** Drawn pressed while a finger is on it, or touched too recently to show it went down. */
	static boolean held(long now) {
		return finger != null || (pressedAt != 0 && now - pressedAt < TouchButtons.MIN_PRESS_NANOS);
	}

	/** Draws the button into the window's own pixels, when shown. */
	static void render(GuiGraphicsExtractor graphics, TouchPad.View view) {
		if (!enabled) {
			return;
		}
		Matrix3x2fStack pose = graphics.pose().pushMatrix();
		pose.scale(1f / view.scale(), 1f / view.scale());
		TouchButtons.drawFramed(graphics, Icon.PERSPECTIVE, box(view), held(view.nanos()));
		pose.popMatrix();
	}
}
