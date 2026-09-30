/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.utils;

import dev.isxander.controlify.mixins.feature.bind.GuiAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
//? if >=26.3 {
import org.lwjgl.sdl.SDLKeyboard;
//?}

public final class MinecraftUtil {
	private MinecraftUtil() {
	}

	private static final Minecraft minecraft = Minecraft.getInstance();


	public static void setScreen(@Nullable Screen screen) {
		//? if >=26.2 {
		minecraft.gui.setScreen(screen);
		//?} else {
		/*minecraft.setScreen(screen);
		*///?}
	}

	public static void forceSetScreen(@Nullable Screen screen) {
		//? if >=26.2 {
		((GuiAccessor) minecraft.gui).controlify$setScreenField(screen);
		//?} else {
		/*minecraft.screen = screen;
		*///?}
	}

	@Contract(pure = true)
	public static @Nullable Screen getScreen() {
		//? if >=26.2 {
		return minecraft.gui.screen();
		//?} else {
		/*return minecraft.screen;
		*///?}
	}

	@Contract(pure = true)
	public static @Nullable Overlay getOverlay() {
		//? if >=26.2 {
		return minecraft.gui.overlay();
		//?} else {
		/*return minecraft.getOverlay();
		*///?}
	}

	/**
	 * A press of the key {@code key} names, for handing to a widget's {@code keyPressed}, built the
	 * way the game builds one. The {@link com.mojang.blaze3d.platform.InputConstants} constants are
	 * what an event's first field holds on every version, and what buttons, sliders and screens
	 * read. Text editing reads the second field: on 26.3 that is the key's SDL keycode - the
	 * constants are SDL scancodes there - and an EditBox switches on it for backspace, delete, the
	 * arrows, home and end, so an event with nothing in that field is a press of no key at all;
	 * that is what the on-screen keyboard's backspace was on 26.3 (tl106). SDL's own keymap says
	 * which keycode a scancode is, as for a real press. 26.1 and 26.2 name keys by GLFW keycode and
	 * carry the scancode beside it, so there the event is built as before.
	 *
	 * @param scancode what the caller was handed beside the key; kept where an event carries one,
	 *                 and not needed on 26.3, where the key is the scancode
	 */
	public static KeyEvent keyEvent(int key, int scancode, int modifiers) {
		//? if >=26.3 {
		return new KeyEvent(key, SDLKeyboard.SDL_GetKeyFromScancode(key, (short) 0, true), modifiers);
		//?} else {
		/*return new KeyEvent(key, scancode, modifiers);
		*///?}
	}

	public static void sendToast(Component title, Component message, boolean longer) {
		var toastId = longer ? SystemToast.SystemToastId.UNSECURE_SERVER_WARNING : SystemToast.SystemToastId.PERIODIC_NOTIFICATION;

		//? if >=26.2 {
		SystemToast.add(minecraft.gui.toastManager(), toastId, title, message);
		//?} else {
		/*SystemToast toast = SystemToast.multiline(minecraft, toastId, title, message);
		minecraft.getToastManager().addToast(toast);
		*///?}
	}
}
