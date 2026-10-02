/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.gui.screen;

import dev.isxander.controlify.Controlify;
import dev.isxander.controlify.api.ControlifyApi;
import dev.isxander.controlify.config.dto.TouchConfig;
import dev.isxander.controlify.config.settings.TouchSettings;
import dev.isxander.controlify.touch.JoystickVisibility;
import dev.isxander.controlify.touch.TouchControls;
import dev.isxander.controlify.touch.TouchMode;
import dev.isxander.controlify.touch.TouchPad;
import dev.isxander.controlify.utils.MinecraftUtil;
import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The touch controls' own settings screen (tl122), opened from Global Settings, after Bedrock's Touch settings page:
 * when they are on, the mode they play in, the way to the glyph editor's Touch tab to move and size them, how fast a
 * swipe turns the camera (tl123), whether up is down (tl130), how much it slows through a spyglass (tl127), the Pick
 * Block button (tl128), auto jump (tl124), when the joystick shows, the perspective button and easy sprint (tl131), and
 * which side of the hotbar the inventory button is on (tl133), in Bedrock's order.
 */
public final class TouchSettingsScreenFactory {
	private TouchSettingsScreenFactory() {
	}

	public static Screen createTouchSettingsScreen(Screen parent) {
		TouchSettings touch = Controlify.instance().config().getSettings().touchSettings();
		TouchSettings defaults = TouchSettings.defaults();
		return YetAnotherConfigLib.createBuilder()
				.title(Component.translatable("controlify.gui.touch_settings.title"))
				.save(() -> {
					Controlify.instance().config().saveSafely();
					// Acted on at once, as the Dev Functions button does: always brings them on, never takes them off.
					if (touch.controls == TouchControls.ON) {
						TouchPad.setActive(true);
					} else if (touch.controls == TouchControls.OFF) {
						TouchPad.setActive(false);
					}
				})
				.category(ConfigCategory.createBuilder()
						.name(Component.translatable("controlify.gui.touch_settings.title"))
						.option(Option.<TouchControls>createBuilder()
								.name(Component.translatable("controlify.touch.controls"))
								.description(OptionDescription.of(Component.translatable("controlify.gui.dev_functions.touch_controls.tooltip")))
								.binding(defaults.controls, () -> touch.controls, value -> touch.controls = value)
								.controller(option -> EnumControllerBuilder.create(option).enumClass(TouchControls.class))
								.build())
						.option(Option.<TouchMode>createBuilder()
								.name(Component.translatable("controlify.touch.mode"))
								.description(OptionDescription.of(Component.translatable("controlify.gui.dev_functions.touch_mode.tooltip")))
								.binding(defaults.mode, () -> touch.mode, value -> touch.mode = value)
								.controller(option -> EnumControllerBuilder.create(option).enumClass(TouchMode.class))
								.build())
						.option(ButtonOption.createBuilder()
								.name(Component.translatable("controlify.gui.touch_settings.customize"))
								.text(Component.translatable("controlify.gui.touch_settings.customize.button"))
								.description(OptionDescription.of(Component.translatable("controlify.gui.touch_settings.customize.tooltip")))
								.action((screen, button) -> ControlifyApi.get().getCurrentController().ifPresent(controller ->
										MinecraftUtil.setScreen(new GuideOffsetEditScreen(screen, controller.settings().generic.guide, controller))))
								.available(ControlifyApi.get().getCurrentController().isPresent())
								.build())
						.option(Option.<Boolean>createBuilder()
								.name(Component.translatable("controlify.touch.left_handed_inventory"))
								.description(OptionDescription.of(Component.translatable("controlify.touch.left_handed_inventory.tooltip")))
								.binding(defaults.leftHandedInventory, () -> touch.leftHandedInventory, value -> touch.leftHandedInventory = value)
								.controller(option -> BooleanControllerBuilder.create(option).onOffFormatter())
								.build())
						.option(Option.<JoystickVisibility>createBuilder()
								.name(Component.translatable("controlify.touch.joystick_visibility"))
								.description(OptionDescription.of(Component.translatable("controlify.touch.joystick_visibility.tooltip")))
								.binding(defaults.joystickVisibility, () -> touch.joystickVisibility, value -> touch.joystickVisibility = value)
								.controller(option -> EnumControllerBuilder.create(option).enumClass(JoystickVisibility.class))
								.build())
						.option(Option.<Boolean>createBuilder()
								.name(Component.translatable("controlify.touch.invert_y"))
								.description(OptionDescription.of(Component.translatable("controlify.touch.invert_y.tooltip")))
								.binding(defaults.invertY, () -> touch.invertY, value -> touch.invertY = value)
								.controller(option -> BooleanControllerBuilder.create(option).onOffFormatter())
								.build())
						.option(Option.<Integer>createBuilder()
								.name(Component.translatable("controlify.touch.camera_sensitivity"))
								.description(OptionDescription.of(Component.translatable("controlify.touch.camera_sensitivity.tooltip")))
								.binding(defaults.cameraSensitivity, () -> touch.cameraSensitivity, value -> touch.cameraSensitivity = value)
								.controller(option -> IntegerSliderControllerBuilder.create(option)
										.range(TouchConfig.MIN_SENSITIVITY, TouchConfig.MAX_SENSITIVITY)
										.step(1))
								.build())
						.option(Option.<Integer>createBuilder()
								.name(Component.translatable("controlify.touch.spyglass_damping"))
								.description(OptionDescription.of(Component.translatable("controlify.touch.spyglass_damping.tooltip")))
								.binding(defaults.spyglassDamping, () -> touch.spyglassDamping, value -> touch.spyglassDamping = value)
								.controller(option -> IntegerSliderControllerBuilder.create(option)
										.range(TouchConfig.MIN_DAMPING, TouchConfig.MAX_DAMPING)
										.step(1))
								.build())
						.option(Option.<Boolean>createBuilder()
								.name(Component.translatable("controlify.touch.pick_block"))
								.description(OptionDescription.of(Component.translatable("controlify.touch.pick_block.tooltip")))
								.binding(defaults.pickBlock, () -> touch.pickBlock, value -> touch.pickBlock = value)
								.controller(option -> BooleanControllerBuilder.create(option).onOffFormatter())
								.build())
						.option(Option.<Boolean>createBuilder()
								.name(Component.translatable("controlify.touch.perspective_button"))
								.description(OptionDescription.of(Component.translatable("controlify.touch.perspective_button.tooltip")))
								.binding(defaults.perspectiveButton, () -> touch.perspectiveButton, value -> touch.perspectiveButton = value)
								.controller(option -> BooleanControllerBuilder.create(option).onOffFormatter())
								.build())
						.option(Option.<Boolean>createBuilder()
								.name(Component.translatable("controlify.touch.auto_jump"))
								.description(OptionDescription.of(Component.translatable("controlify.touch.auto_jump.tooltip")))
								.binding(defaults.autoJump, () -> touch.autoJump, value -> touch.autoJump = value)
								.controller(option -> BooleanControllerBuilder.create(option).onOffFormatter())
								.build())
						.option(Option.<Boolean>createBuilder()
								.name(Component.translatable("controlify.touch.easy_sprint"))
								.description(OptionDescription.of(Component.translatable("controlify.touch.easy_sprint.tooltip")))
								.binding(defaults.easySprint, () -> touch.easySprint, value -> touch.easySprint = value)
								.controller(option -> BooleanControllerBuilder.create(option).onOffFormatter())
								.build())
						.build())
				.build().generateScreen(parent);
	}
}
