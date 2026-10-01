/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.mixins.core;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.Window;
import dev.isxander.controlify.Controlify;
import dev.isxander.controlify.InputMode;
import dev.isxander.controlify.api.ControlifyApi;
import dev.isxander.controlify.touch.TouchPad;
import dev.isxander.controlify.utils.MinecraftUtil;
import dev.isxander.controlify.utils.MouseMinecraftCallNotifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//? if <26.3 {
/*import org.lwjgl.glfw.GLFWCursorPosCallbackI;
import org.lwjgl.glfw.GLFWDropCallbackI;
import org.lwjgl.glfw.GLFWMouseButtonCallbackI;
import org.lwjgl.glfw.GLFWScrollCallbackI;
*///?}

@Mixin(MouseHandler.class)
public class MouseHandlerMixin implements MouseMinecraftCallNotifier {
	@Shadow @Final private Minecraft minecraft;

	@Unique private boolean controlify$calledFromMinecraftSetScreen = false;

	//? if <26.3 {
	/*@WrapOperation(
			method = "setup",
			at = @At(
					value = "INVOKE",
					target = "Lcom/mojang/blaze3d/platform/InputConstants;setupMouseCallbacks(Lcom/mojang/blaze3d/platform/Window;Lorg/lwjgl/glfw/GLFWCursorPosCallbackI;Lorg/lwjgl/glfw/GLFWMouseButtonCallbackI;Lorg/lwjgl/glfw/GLFWScrollCallbackI;Lorg/lwjgl/glfw/GLFWDropCallbackI;)V"
			)
	)
	private void wrapMouseEvents(
			Window window,
			GLFWCursorPosCallbackI onMoveCallback,
			GLFWMouseButtonCallbackI onPressCallback,
			GLFWScrollCallbackI onScrollCallback,
			GLFWDropCallbackI onDropCallback,
			Operation<Void> operation
	) {
		operation.call(
				window,
				(GLFWCursorPosCallbackI) (w, x, y) -> {
					onMouse(w);
					onMoveCallback.invoke(w, x, y);
				},
				(GLFWMouseButtonCallbackI) (w, b, a, m) -> {
					onMouse(w);
					onPressCallback.invoke(w, b, a, m);
				},
				(GLFWScrollCallbackI) (w, dx, dy) -> {
					onMouse(w);
					onScrollCallback.invoke(w, dx, dy);
				},
				onDropCallback
		);
	}

	@Unique private void onMouse(long window) {
		if (window == minecraft.getWindow().handle()) {
			minecraft.execute(() -> {
				if (Controlify.instance().currentInputMode() != InputMode.MIXED) {
					Controlify.instance().setInputMode(InputMode.KEYBOARD_MOUSE);
				} else {
					Controlify.instance().showCursorTemporarily();
				}
			});
		}
	}
	*///?}

	/// Without this, mouse is left in the center of the screen that conflicts with controller focus.
	@Inject(
			method = "releaseMouse",
			at = @At(
					value = "INVOKE",
					//? if >=26.3 {
					target = "Lcom/mojang/blaze3d/platform/InputConstants;releaseMouse(Lcom/mojang/blaze3d/platform/Window;DD)V"
					//?} else {
					/*target = "Lcom/mojang/blaze3d/platform/InputConstants;grabOrReleaseMouse(Lcom/mojang/blaze3d/platform/Window;IDD)V"
					*///?}
			)
	)
	private void moveMouseIfNecessary(CallbackInfo ci) {
		if (!controlify$calledFromMinecraftSetScreen && ControlifyApi.get().currentInputMode().isController()) {
			Controlify.instance().hideMouse(true, true);
		}
	}

	// shift after RETURN to escape the if statement scope
	@Inject(method = "releaseMouse", at = @At(value = "RETURN"))
	private void resetCalledFromMinecraftSetScreen(CallbackInfo ci) {
		controlify$calledFromMinecraftSetScreen = false;
	}

	/** While the mouse stands in for a finger under touch controls, the cursor stays free: the game's grabs are refused (tl111, TouchPad). */
	@Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
	private void keepCursorFreeForTouch(CallbackInfo ci) {
		if (TouchPad.cursorFree()) {
			ci.cancel();
		}
	}

	/**
	 * While the mouse stands in for a finger, its own clicks do nothing in the world (tl112): SDL has made a
	 * finger of each already, which the touch controls read, and the click would attack or use as well. On
	 * a screen they click as ever.
	 */
	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void swallowClickForTouch(CallbackInfo ci) {
		if (TouchPad.cursorFree() && MinecraftUtil.getScreen() == null) {
			ci.cancel();
		}
	}

	/**
	 * Under touch controls a tap on the close button drawn over a screen closes the screen, as Esc does, and the
	 * screen never sees the click (tl116, TouchPad.closeTapped). Any other click goes to the screen as ever.
	 */
	@WrapOperation(
			method = "onButton",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/gui/screens/Screen;mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z"
			)
	)
	private boolean closeScreenForTouch(Screen screen, MouseButtonEvent event, boolean doubleClick, Operation<Boolean> original) {
		if (TouchPad.closeTapped(screen, event.x(), event.y(), event.button())) {
			return true;
		}
		return original.call(screen, event, doubleClick);
	}

	@ModifyExpressionValue(method = "grabMouse", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;isWindowActive()Z"))
	private boolean passWindowActiveCheckIfOOFInputIsOn(boolean isWindowActive) {
		return isWindowActive || (ControlifyApi.get().currentInputMode().isController() && Controlify.instance().config().getSettings().globalSettings().outOfFocusInput);
	}

	@Override
	public void controlify$imFromMinecraftSetScreen() {
		controlify$calledFromMinecraftSetScreen = true;
	}
}
