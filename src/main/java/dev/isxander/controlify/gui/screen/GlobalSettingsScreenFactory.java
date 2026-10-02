/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.gui.screen;

import dev.isxander.controlify.Controlify;
import dev.isxander.controlify.api.ControlifyApi;
import dev.isxander.controlify.api.event.ControlifyEvents;
import dev.isxander.controlify.config.settings.GlobalSettings;
import dev.isxander.controlify.controller.ControllerEntity;
import dev.isxander.controlify.driver.steamdeck.SteamDeckUtil;
import dev.isxander.controlify.reacharound.ReachAroundMode;
import dev.isxander.controlify.server.ServerPolicies;
import dev.isxander.controlify.server.ServerPolicy;
import dev.isxander.controlify.touch.TouchInput;
import dev.isxander.controlify.utils.CUtil;
import dev.isxander.controlify.utils.DebugDump;
import dev.isxander.controlify.utils.MinecraftUtil;
import dev.isxander.yacl3.api.*;
import dev.isxander.yacl3.api.controller.*;
import net.minecraft.ChatFormatting;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

public class GlobalSettingsScreenFactory {
	/**
	 * The "Edit Glyph Positions" button of the Global Settings screen that is currently open, if
	 * any. Its availability depends on a controller being connected, which can change while the
	 * screen is open, so it is refreshed from the controller connect/disconnect events below.
	 */
	private static final AtomicReference<ButtonOption> editGlyphPositionsOpt = new AtomicReference<>();
	private static boolean controllerAvailabilityListenersRegistered = false;

	/**
	 * Controlify's events cannot be unregistered, so this registers one pair of listeners for the
	 * lifetime of the game, the first time the screen is opened. They only touch the option of the
	 * screen that is open at the time.
	 */
	private static void registerControllerAvailabilityListeners() {
		if (controllerAvailabilityListenersRegistered) return;
		controllerAvailabilityListenersRegistered = true;

		ControlifyEvents.CONTROLLER_CONNECTED.register(event -> refreshControllerAvailability());
		ControlifyEvents.CONTROLLER_DISCONNECTED.register(event -> refreshControllerAvailability());
	}

	private static void refreshControllerAvailability() {
		// Connection events can arrive off the render thread; YACL widgets must not be touched there.
		Minecraft.getInstance().execute(() -> {
			ButtonOption option = editGlyphPositionsOpt.get();
			if (option != null) {
				option.setAvailable(ControlifyApi.get().getCurrentController().isPresent());
			}
		});
	}

	public static Screen createGlobalSettingsScreen(Screen parent) {
		registerControllerAvailabilityListeners();
		var globalSettings = Controlify.instance().config().getSettings().globalSettings();
		AtomicReference<ListOption<String>> analogueMovementWhitelist = new AtomicReference<>();
		AtomicReference<Option<Boolean>> keyboardMovementOptRef = new AtomicReference<>();
		AtomicReference<Option<Boolean>> forceAnalogMovementOptRef = new AtomicReference<>();
		AtomicReference<ButtonOption> addToWhitelistOptRef = new AtomicReference<>();
		// Shadow copies of the two mutually-exclusive movement-mode booleans.
		// The checkboxes below are bound to these instead of globalSettings directly, so that
		// nothing writes to the real, live settings until Save is actually clicked - Cancel then
		// works exactly like it does for every other option on this screen. The shadow values are
		// copied into globalSettings in the .save(...) callback below, after YACL applies pending
		// values into these bindings.
		AtomicBoolean shadowAlwaysKeyboardMovement = new AtomicBoolean(globalSettings.alwaysKeyboardMovement);
		AtomicBoolean shadowAnalogueMovementDefaultEnabled = new AtomicBoolean(globalSettings.analogueMovementDefaultEnabled);

		return YetAnotherConfigLib.createBuilder()
				.title(Component.translatable("controlify.gui.global_settings.title"))
				.save(() -> {
					globalSettings.alwaysKeyboardMovement = shadowAlwaysKeyboardMovement.get();
					globalSettings.analogueMovementDefaultEnabled = shadowAnalogueMovementDefaultEnabled.get();
					Controlify.instance().config().saveSafely();
				})
				.category(ConfigCategory.createBuilder()
						.name(Component.translatable("controlify.gui.global_settings.title"))
						.option(ButtonOption.createBuilder()
								.name(Component.translatable("controlify.gui.open_issue_tracker"))
								.text(Component.translatable("controlify.gui.open_issue_tracker.button"))
								.action((screen, button) -> CUtil.openUri("https://github.com/dx-Arcus/Controlify-Enhanced/issues"))
								.build())
						.group(OptionGroup.createBuilder()
								.name(Component.translatable("controlify.gui.server_options"))
								.option(Option.<ReachAroundMode>createBuilder()
										.name(Component.translatable("controlify.gui.reach_around"))
										.description(state -> OptionDescription.createBuilder()
												.webpImage(screenshot("reach-around-placement.webp"))
												.text(Component.translatable("controlify.gui.reach_around.tooltip"))
												.text(Component.translatable("controlify.gui.reach_around.tooltip.parity").withStyle(ChatFormatting.GRAY))
												.text(state == ReachAroundMode.EVERYWHERE ? Component.translatable("controlify.gui.reach_around.tooltip.warning").withStyle(ChatFormatting.RED) : Component.empty())
												.text(ServerPolicies.REACH_AROUND.getPolicy() == ServerPolicy.DISALLOWED ? Component.translatable("controlify.gui.server_controlled").withStyle(ChatFormatting.GOLD) : Component.empty())
												.build())
										.binding(GlobalSettings.defaults().reachAround, () -> globalSettings.reachAround, v -> globalSettings.reachAround = v)
										.controller(opt -> EnumControllerBuilder.create(opt)
												.enumClass(ReachAroundMode.class)
												.formatValue(mode -> switch (ServerPolicies.REACH_AROUND.getPolicy()) {
													case UNSET, ALLOWED -> mode.getDisplayName();
													case DISALLOWED -> CommonComponents.OPTION_OFF;
												}))
										.available(ServerPolicies.REACH_AROUND.get())
										.build())
								.option(Option.<Boolean>createBuilder()
										.name(Component.translatable("controlify.gui.allow_server_rumble"))
										.description(OptionDescription.createBuilder()
												.text(Component.translatable("controlify.gui.allow_server_rumble.tooltip"))
												.build())
										.binding(GlobalSettings.defaults().allowServerRumble, () -> globalSettings.allowServerRumble, v -> globalSettings.allowServerRumble = v)
										.controller(TickBoxControllerBuilder::create)
										.addListener((opt, val) -> {
											ControlifyApi.get().getCurrentController()
													.flatMap(ControllerEntity::rumble)
													.ifPresent(rumble -> rumble.rumbleManager().clearEffects());
										})
										.build())
								.option(Util.make(() -> {
									var opt = Option.<Boolean>createBuilder()
											.name(Component.translatable("controlify.gui.keyboard_movement"))
											.description(OptionDescription.createBuilder()
													.text(Component.translatable("controlify.gui.keyboard_movement.tooltip"))
													.build())
											.binding(GlobalSettings.defaults().alwaysKeyboardMovement, shadowAlwaysKeyboardMovement::get, shadowAlwaysKeyboardMovement::set)
											.controller(TickBoxControllerBuilder::create)
											.available(!shadowAnalogueMovementDefaultEnabled.get())
											.addListener((o, event) -> {
												if (event == OptionEventListener.Event.INITIAL) return;
												boolean val = o.pendingValue();
												Option<Boolean> forceAnalog = forceAnalogMovementOptRef.get();
												if (val) {
													forceAnalog.requestSet(false);
													forceAnalog.applyValue();
												}
												forceAnalog.setAvailable(!val);
											})
											.build();
									keyboardMovementOptRef.set(opt);
									return opt;
								}))
								.option(Util.make(() -> {
									var opt = Option.<Boolean>createBuilder()
											.name(Component.translatable("controlify.gui.analogue_movement_default_enabled").copy().withStyle(ChatFormatting.RED, ChatFormatting.ITALIC))
											.description(OptionDescription.createBuilder()
													.text(Component.translatable("controlify.gui.analogue_movement_default_enabled.tooltip").withStyle(ChatFormatting.RED))
													.text(Component.translatable("controlify.gui.analogue_movement_default_enabled.tooltip.warning").withStyle(ChatFormatting.RED, ChatFormatting.ITALIC))
													.build())
											.binding(GlobalSettings.defaults().analogueMovementDefaultEnabled, shadowAnalogueMovementDefaultEnabled::get, shadowAnalogueMovementDefaultEnabled::set)
											.controller(TickBoxControllerBuilder::create)
											.available(!shadowAlwaysKeyboardMovement.get())
											.addListener((o, event) -> {
												if (event == OptionEventListener.Event.INITIAL) return;
												boolean val = o.pendingValue();
												Option<Boolean> keyboardMovement = keyboardMovementOptRef.get();
												if (val) {
													keyboardMovement.requestSet(false);
													keyboardMovement.applyValue();
												}
												keyboardMovement.setAvailable(!val);
												addToWhitelistOptRef.get().setAvailable(Minecraft.getInstance().getCurrentServer() != null && !val);
												analogueMovementWhitelist.get().setAvailable(!val);
											})
											.build();
									forceAnalogMovementOptRef.set(opt);
									return opt;
								}))
								.option(Util.make(() -> {
									var opt = ButtonOption.createBuilder()
											.name(Component.translatable("controlify.gui.add_server_to_analogue_move_whitelist"))
											.text(Component.translatable("controlify.gui.add_server_to_analogue_move_whitelist.button"))
											.description(OptionDescription.createBuilder()
													.text(Component.translatable("controlify.gui.add_server_to_analogue_move_whitelist.tooltip"))
													.build())
											.action((screen, button) -> {
												ServerData server = Minecraft.getInstance().getCurrentServer();
												if (server != null) {
													analogueMovementWhitelist.get().insertNewEntry().requestSet(server.ip);
												}
											})
											.available(Minecraft.getInstance().getCurrentServer() != null && !globalSettings.analogueMovementDefaultEnabled)
											.build();
									addToWhitelistOptRef.set(opt);
									return opt;
								}))
								.build())
						.group(Util.make(() -> {
							var list = ListOption.<String>createBuilder()
									.name(Component.translatable("controlify.gui.analogue_movement_whitelist"))
									.description(OptionDescription.createBuilder()
											.text(Component.translatable("controlify.gui.analogue_movement_whitelist.tooltip"))
											.build())
									.binding(GlobalSettings.defaults().analogueMovementWhitelist, () -> globalSettings.analogueMovementWhitelist, v -> globalSettings.analogueMovementWhitelist = v)
									.controller(StringControllerBuilder::create)
									.initial("Server IP here")
									.available(!globalSettings.analogueMovementDefaultEnabled)
									.build();
							analogueMovementWhitelist.set(list);
							return list;
						}))
						.group(OptionGroup.createBuilder()
								.name(Component.translatable("controlify.gui.miscellaneous"))
								.option(Option.<Integer>createBuilder()
										.name(Component.translatable("controlify.gui.preferred_profile"))
										.description(OptionDescription.of(Component.translatable("controlify.gui.preferred_profile.tooltip")))
										.binding(GlobalSettings.defaults().preferredProfile, () -> globalSettings.preferredProfile, value ->
												globalSettings.preferredProfile = Math.max(0, value))
										.controller(option -> IntegerFieldControllerBuilder.create(option).min(0))
										.build())
								.option(Option.<Float>createBuilder()
										.name(Component.translatable("controlify.gui.ingame_button_guide_scale"))
										.description(val  -> OptionDescription.createBuilder()
												.text(Component.literal("This setting is currently broken on 1.21.6+").withStyle(ChatFormatting.RED))
												.text(Component.translatable("controlify.gui.ingame_button_guide_scale.tooltip"))
												.text(val != 1f ? Component.translatable("controlify.gui.ingame_button_guide_scale.tooltip.warning").withStyle(ChatFormatting.RED) : Component.empty())
												.build())
										.binding(GlobalSettings.defaults().ingameButtonGuideScale, () -> 1f, v -> globalSettings.ingameButtonGuideScale = v)
										.controller(opt -> FloatSliderControllerBuilder.create(opt)
												.range(0.5f, 1.5f)
												.step(0.05f)
												.formatValue(v -> Component.literal(String.format("%.0f%%", v*100))))
										.available(false)
										.build())
								.option(Util.make(() -> {
									ButtonOption editGlyphPositions = ButtonOption.createBuilder()
											.name(Component.translatable("controlify.gui.edit_glyph_positions"))
											.text(Component.translatable("controlify.gui.edit_glyph_positions.button"))
											.description(OptionDescription.of(Component.translatable("controlify.gui.edit_glyph_positions.tooltip")))
											.action((screen, button) -> ControlifyApi.get().getCurrentController().ifPresent(controller ->
													MinecraftUtil.setScreen(new GuideOffsetEditScreen(screen, controller.settings().generic.guide, controller))))
											.available(ControlifyApi.get().getCurrentController().isPresent())
											.build();
									// Remember it so a controller plugged in (or unplugged) while this screen is
									// already open greys the button in or out, instead of leaving it stale.
									editGlyphPositionsOpt.set(editGlyphPositions);
									return editGlyphPositions;
								}))
								.option(ButtonOption.createBuilder()
										.name(Component.translatable("controlify.gui.aim_assist"))
										.text(Component.translatable("controlify.gui.aim_assist.button"))
										.description(OptionDescription.of(Component.translatable("controlify.gui.aim_assist.tooltip")))
										.action((screen, button) -> MinecraftUtil.setScreen(AimAssistScreenFactory.createAimAssistScreen(screen)))
										.build())
								.optionIf(TouchInput.SUPPORTED, ButtonOption.createBuilder()
										.name(Component.translatable("controlify.gui.touch_settings"))
										.text(Component.translatable("controlify.gui.touch_settings.button"))
										.description(OptionDescription.of(Component.translatable("controlify.gui.touch_settings.tooltip")))
										.action((screen, button) -> MinecraftUtil.setScreen(TouchSettingsScreenFactory.createTouchSettingsScreen(screen)))
										.build())
								.option(Option.<Boolean>createBuilder()
										.name(Component.translatable("controlify.gui.ui_sounds"))
										.description(OptionDescription.createBuilder()
												.text(Component.translatable("controlify.gui.ui_sounds.tooltip"))
												.build())
										.binding(GlobalSettings.defaults().extraUiSounds, () -> globalSettings.extraUiSounds, v -> globalSettings.extraUiSounds = v)
										.controller(TickBoxControllerBuilder::create)
										.build())
								.option(Option.<Boolean>createBuilder()
										.name(Component.translatable("controlify.gui.out_of_focus_input"))
										.description(OptionDescription.createBuilder()
												.text(Component.translatable("controlify.gui.out_of_focus_input.tooltip"))
												.build())
										.binding(GlobalSettings.defaults().outOfFocusInput, () -> globalSettings.outOfFocusInput, v -> globalSettings.outOfFocusInput = v)
										.controller(TickBoxControllerBuilder::create)
										.build())
								.option(Option.<Boolean>createBuilder()
										.name(Component.translatable("controlify.gui.notify_low_battery"))
										.description(OptionDescription.createBuilder()
												.text(Component.translatable("controlify.gui.notify_low_battery.tooltip"))
												.build())
										.binding(GlobalSettings.defaults().notifyLowBattery, () -> globalSettings.notifyLowBattery, v -> globalSettings.notifyLowBattery = v)
										.controller(TickBoxControllerBuilder::create)
										.build())
								.option(Option.<Boolean>createBuilder()
										.name(Component.translatable("controlify.gui.mixed_input"))
										.description(OptionDescription.of(Component.translatable("controlify.gui.mixed_input.tooltip")))
										.binding(GlobalSettings.defaults().mixedInput, () -> globalSettings.mixedInput, v -> globalSettings.mixedInput = v)
										.controller(TickBoxControllerBuilder::create)
										.build())
								.optionIf(SteamDeckUtil.IS_STEAM_DECK, Option.<Boolean>createBuilder()
										.name(Component.translatable("controlify.gui.use_enhanced_steam_deck_driver"))
										.description(OptionDescription.createBuilder()
												.text(Component.translatable("controlify.gui.use_enhanced_steam_deck_driver.tooltip"))
												.build())
										.binding(GlobalSettings.defaults().useEnhancedSteamDeckDriver, () -> globalSettings.useEnhancedSteamDeckDriver, v -> globalSettings.useEnhancedSteamDeckDriver = v)
										.controller(TickBoxControllerBuilder::create)
										.flag(OptionFlag.GAME_RESTART)
										.build())
								.option(ButtonOption.createBuilder()
										.name(Component.translatable("controlify.gui.copy_debug_dump"))
										.text(Component.translatable("controlify.gui.copy_debug_dump.button"))
										.description(OptionDescription.createBuilder()
												.text(Component.translatable("controlify.gui.copy_debug_dump.tooltip"))
												.build())
										.action((screen, btn) -> {
											String dump = DebugDump.dumpDebug();
											String formatted = """
													Here's my Controlify debug dump
													```
													%s
													```
													""".formatted(dump).stripIndent();

											Minecraft.getInstance().keyboardHandler.setClipboard(formatted);
										})
										.build())
								.build())
						.build())
				.build().generateScreen(parent);
	}

	private static Identifier screenshot(String filename) {
		return CUtil.rl("textures/screenshots/" + filename);
	}
}
