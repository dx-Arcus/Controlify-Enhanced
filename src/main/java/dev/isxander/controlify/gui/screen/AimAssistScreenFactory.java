/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.gui.screen;

import dev.isxander.controlify.Controlify;
import dev.isxander.controlify.aimassist.AimAssistMode;
import dev.isxander.controlify.aimassist.AimAssistTargets;
import dev.isxander.controlify.aimassist.LagCompensationMode;
import dev.isxander.controlify.aimassist.LockBindMode;
import dev.isxander.controlify.aimassist.PushAwayMode;
import dev.isxander.controlify.aimassist.TargetLockMode;
import dev.isxander.controlify.aimassist.TrajectoryAimMode;
import dev.isxander.controlify.config.dto.AimAssistConfig;
import dev.isxander.controlify.config.dto.CompassConfig;
import dev.isxander.controlify.config.dto.LockBindConfig;
import dev.isxander.controlify.config.dto.SnapConfig;
import dev.isxander.controlify.config.dto.TargetLockConfig;
import dev.isxander.controlify.config.settings.AimAssistSettings;
import dev.isxander.controlify.config.settings.SnapSettings;
import dev.isxander.controlify.config.settings.TargetLockSettings;
import dev.isxander.controlify.gui.controllers.TabExplainerController;
import dev.isxander.controlify.utils.MinecraftUtil;
import dev.isxander.controlify.utils.render.RainbowText;
import dev.isxander.yacl3.api.*;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.StringRepresentable;
import net.minecraft.util.Util;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * The Aim Assist screen, opened from Global Settings. Strength and range are split into melee
 * and bow groups, because a bow wants a gentler pull in a much tighter cone than a sword does,
 * and every setting is a slider in its own units rather than a three-step scale: the difference
 * between two levels was always a number, and hiding it only made tuning by feel harder.
 * <p>
 * The settings are spread over five tabs, LB and RB between them, each opening with one line on
 * how its settings work with the rest - Donny's layout, 27 Sep. Nothing about a setting changes
 * with the tab it is on: the same bindings, the same saved values, the same listeners.
 */
public class AimAssistScreenFactory {
	public static Screen createAimAssistScreen(Screen parent) {
		AimAssistSettings settings = Controlify.instance().config().getSettings().aimAssistSettings();
		AimAssistSettings defaults = AimAssistSettings.defaults();
		TargetLockSettings lock = settings.targetLock;
		TargetLockSettings lockDefaults = defaults.targetLock;
		AtomicReference<ButtonOption> customListOptRef = new AtomicReference<>();

		// Held as locals so the toggle below can grey them out. A slider that is still live while
		// the thing it configures is switched off is exactly what sent us hunting a phantom bug.
		Option<Integer> groundRange = slider("controlify.gui.target_lock.ground_range", 0, TargetLockConfig.MAX_RANGE, 1, BLOCKS,
				lockDefaults.groundRange, () -> lock.groundRange, v -> lock.groundRange = v);
		Option<Integer> flyingRange = slider("controlify.gui.target_lock.flying_range", 0, TargetLockConfig.MAX_RANGE, 1, BLOCKS,
				lockDefaults.flyingRange, () -> lock.flyingRange, v -> lock.flyingRange = v);
		Option<Integer> resetPercent = slider("controlify.gui.target_lock.reset_percent", 0, 100, 1, PERCENT,
				lockDefaults.resetPercent, () -> lock.resetPercent, v -> lock.resetPercent = v);
		Option<Integer> dropSeconds = slider("controlify.gui.target_lock.drop_seconds", 1, 300, 1, SECONDS,
				lockDefaults.dropSeconds, () -> lock.dropSeconds, v -> lock.dropSeconds = v);
		List<Option<Integer>> dropoutOptions = List.of(groundRange, flyingRange, resetPercent, dropSeconds);
		dropoutOptions.forEach(option -> option.setAvailable(lock.autoDrop));

		Option<Boolean> autoDrop = tickBox("controlify.gui.target_lock.auto_drop",
				lockDefaults.autoDrop, () -> lock.autoDrop, v -> lock.autoDrop = v);
		autoDrop.addListener((opt, event) -> dropoutOptions.forEach(option -> option.setAvailable(opt.pendingValue())));

		// The F.O.V sliders only mean anything in F.O.V Lock, so they are greyed out the rest of the
		// time, the same way the Letting Go sliders are while dropping is off.
		Option<Integer> fovAngle = slider("controlify.gui.target_lock.fov_angle",
				LockBindConfig.MIN_FOV_DEGREES, LockBindConfig.MAX_FOV_DEGREES, 1, WHOLE_DEGREES,
				lockDefaults.fovDegrees, () -> lock.fovDegrees, v -> lock.fovDegrees = v);
		Option<Integer> fovRange = slider("controlify.gui.target_lock.fov_range", 1, TargetLockConfig.MAX_LOCKED_RANGE, 1, BLOCKS,
				lockDefaults.fovRangeBlocks, () -> lock.fovRangeBlocks, v -> lock.fovRangeBlocks = v);
		Option<Integer> fovPriority = slider("controlify.gui.target_lock.fov_priority", 0, TargetLockConfig.MAX_LOCKED_RANGE, 1, BLOCKS_OR_OFF,
				lockDefaults.fovPriorityBlocks, () -> lock.fovPriorityBlocks, v -> lock.fovPriorityBlocks = v);
		List<Option<Integer>> fovOptions = List.of(fovAngle, fovRange, fovPriority);
		fovOptions.forEach(option -> option.setAvailable(lock.bindMode == LockBindMode.FOV));

		// Described one mode at a time, like Mode above it.
		Option<LockBindMode> bindMode = picker("controlify.gui.target_lock.bind_mode", LockBindMode.class,
				lockDefaults.bindMode, () -> lock.bindMode, v -> lock.bindMode = v);
		bindMode.addListener((opt, event) -> fovOptions.forEach(option -> option.setAvailable(opt.pendingValue() == LockBindMode.FOV)));

		// Trajectory Aim (tl91), under Distance in the Bow group at Donny's asking, its Lock-On Only
		// switch (tl95), Lag Compensation (tl92) and Live Start Angle (tl93): Lock-On Only and Lag
		// Compensation only mean anything with Trajectory Aim on, Lag Allowance only with Lag
		// Compensation on Manual, and Live Start Angle only with Trajectory Aim on Live, so each is
		// greyed out the rest of the time.
		Option<TrajectoryAimMode> trajectoryAim = picker("controlify.gui.aim_assist.trajectory_aim", TrajectoryAimMode.class,
				AimAssistScreenFactory::trajectoryDescription,
				defaults.trajectoryAim, () -> settings.trajectoryAim, v -> settings.trajectoryAim = v);
		Option<Boolean> trajectoryLockedOnly = tickBox("controlify.gui.aim_assist.trajectory_locked_only",
				defaults.trajectoryLockedOnly, () -> settings.trajectoryLockedOnly, v -> settings.trajectoryLockedOnly = v);
		Option<LagCompensationMode> lagCompensation = picker("controlify.gui.aim_assist.lag_compensation", LagCompensationMode.class,
				defaults.lagCompensation, () -> settings.lagCompensation, v -> settings.lagCompensation = v);
		Option<Integer> lagAllowance = slider("controlify.gui.aim_assist.lag_allowance", 0, AimAssistConfig.MAX_LAG_ALLOWANCE_MS, 10, MILLISECONDS,
				defaults.lagAllowanceMs, () -> settings.lagAllowanceMs, v -> settings.lagAllowanceMs = v);
		Option<Integer> liveStartAngle = slider("controlify.gui.aim_assist.live_start_angle",
				AimAssistConfig.MIN_LIVE_START_DEGREES, AimAssistConfig.MAX_LIVE_START_DEGREES, 1, WHOLE_DEGREES,
				defaults.liveStartDegrees, () -> settings.liveStartDegrees, v -> settings.liveStartDegrees = v);
		Runnable greyTrajectoryRows = () -> {
			trajectoryLockedOnly.setAvailable(trajectoryAim.pendingValue().isOn());
			lagCompensation.setAvailable(trajectoryAim.pendingValue().isOn());
			lagAllowance.setAvailable(trajectoryAim.pendingValue().isOn() && lagCompensation.pendingValue() == LagCompensationMode.MANUAL);
			liveStartAngle.setAvailable(trajectoryAim.pendingValue() == TrajectoryAimMode.LIVE);
		};
		greyTrajectoryRows.run();
		trajectoryAim.addEventListener((opt, event) -> greyTrajectoryRows.run());
		lagCompensation.addEventListener((opt, event) -> greyTrajectoryRows.run());

		return YetAnotherConfigLib.createBuilder()
				.title(Component.translatable("controlify.gui.aim_assist.title"))
				.save(() -> Controlify.instance().config().saveSafely())
				// Tab names are kept under 45 pixels of text so all five share the bar equally at any
				// GUI scale on a 16:9 or 16:10 screen. YACL gives a tab its own width once its name is
				// too wide for an equal share, and scrolls the bar once together they are too wide
				// for it. Their keys start with "controlify.": LiveOptionDescription keeps the
				// description pane live only on Controlify's own screens, and knows them by that.
				.category(ConfigCategory.createBuilder()
						.name(Component.translatable("controlify.gui.aim_assist.tab.general"))
						.option(explainer("controlify.gui.aim_assist.tab.general"))
						.option(picker("controlify.gui.aim_assist.mode", AimAssistMode.class,
								state -> OptionDescription.createBuilder()
										.text(Component.translatable("controlify.gui.aim_assist.mode.tooltip"))
										.text(state == AimAssistMode.EVERYWHERE
												? Component.translatable("controlify.gui.aim_assist.mode.tooltip.warning").withStyle(ChatFormatting.RED)
												: Component.empty())
										.build(),
								defaults.mode, () -> settings.mode, v -> settings.mode = v))
						.option(Util.make(() -> {
							OptionDescription about = OptionDescription.of(Component.translatable("controlify.gui.aim_assist.targets.tooltip"));
							Option<AimAssistTargets> targets = picker("controlify.gui.aim_assist.targets", AimAssistTargets.class,
									state -> about, defaults.targets, () -> settings.targets, v -> settings.targets = v);
							targets.addListener((opt, event) -> {
								ButtonOption customList = customListOptRef.get();
								if (customList != null) {
									customList.setAvailable(opt.pendingValue() == AimAssistTargets.CUSTOM);
								}
							});
							return targets;
						}))
						// Its name is red at Donny's asking: this is the switch that turns aim help
						// on other people.
						.option(tickBox(Component.translatable("controlify.gui.aim_assist.target_players").withStyle(ChatFormatting.RED),
								state -> warned("controlify.gui.aim_assist.target_players", state),
								defaults.targetPlayers, () -> settings.targetPlayers, v -> settings.targetPlayers = v))
						.option(Util.make(() -> {
							ButtonOption customList = ButtonOption.createBuilder()
									.name(Component.translatable("controlify.gui.aim_assist.custom_list"))
									.text(Component.translatable("controlify.gui.aim_assist.custom_list.button"))
									.description(OptionDescription.of(Component.translatable("controlify.gui.aim_assist.custom_list.tooltip")))
									.action((screen, button) ->
											MinecraftUtil.setScreen(new CustomTargetListScreen(screen, settings)))
									.available(settings.targets == AimAssistTargets.CUSTOM)
									.build();
							customListOptRef.set(customList);
							return customList;
						}))
						.build())
				.category(ConfigCategory.createBuilder()
						.name(Component.translatable("controlify.gui.aim_assist.tab.aim_help"))
						.option(explainer("controlify.gui.aim_assist.tab.aim_help"))
						.group(OptionGroup.createBuilder()
								.name(Component.translatable("controlify.gui.aim_assist.melee"))
								.description(OptionDescription.of(Component.translatable("controlify.gui.aim_assist.melee.tooltip")))
								.option(slider("controlify.gui.aim_assist.strength", 0, 100, 1, PERCENT,
										defaults.meleeStrengthPercent, () -> settings.meleeStrengthPercent, v -> settings.meleeStrengthPercent = v))
								.option(slider("controlify.gui.aim_assist.cone",
										AimAssistConfig.MIN_CONE_TENTHS, AimAssistConfig.MAX_MELEE_CONE_TENTHS, 5, DEGREES,
										defaults.meleeConeTenths, () -> settings.meleeConeTenths, v -> settings.meleeConeTenths = v))
								.option(slider("controlify.gui.aim_assist.distance", 1, AimAssistConfig.MAX_MELEE_DISTANCE, 1, BLOCKS,
										defaults.meleeDistanceBlocks, () -> settings.meleeDistanceBlocks, v -> settings.meleeDistanceBlocks = v))
								.build())
						.group(OptionGroup.createBuilder()
								.name(Component.translatable("controlify.gui.aim_assist.bow"))
								.description(OptionDescription.of(Component.translatable("controlify.gui.aim_assist.bow.tooltip")))
								.option(slider("controlify.gui.aim_assist.strength", 0, 100, 1, PERCENT,
										defaults.bowStrengthPercent, () -> settings.bowStrengthPercent, v -> settings.bowStrengthPercent = v))
								.option(slider("controlify.gui.aim_assist.cone",
										AimAssistConfig.MIN_CONE_TENTHS, AimAssistConfig.MAX_BOW_CONE_TENTHS, 5, DEGREES,
										defaults.bowConeTenths, () -> settings.bowConeTenths, v -> settings.bowConeTenths = v))
								.option(slider("controlify.gui.aim_assist.distance", 1, AimAssistConfig.MAX_BOW_DISTANCE, 1, BLOCKS,
										defaults.bowDistanceBlocks, () -> settings.bowDistanceBlocks, v -> settings.bowDistanceBlocks = v))
								// Under Distance in the Bow group, at Donny's asking (tl91), with Lock-On Only
								// (tl95), Lag Compensation (tl92) and Live Start Angle (tl93).
								.option(trajectoryAim)
								.option(trajectoryLockedOnly)
								.option(lagCompensation)
								.option(lagAllowance)
								.option(liveStartAngle)
								.build())
						.build())
				.category(ConfigCategory.createBuilder()
						.name(Component.translatable("controlify.gui.aim_assist.tab.snaps"))
						.option(explainer("controlify.gui.aim_assist.tab.snaps"))
						// Here rather than under Melee: its swings are what set off Snap on Swing.
						.option(warnedTickBox("controlify.gui.aim_assist.swing_timing",
								defaults.swingTiming, () -> settings.swingTiming, v -> settings.swingTiming = v))
						.group(snapGroup("controlify.gui.aim_assist.melee_snap", "controlify.gui.aim_assist.snap_on_swing",
								settings.meleeSnap, defaults.meleeSnap, AimAssistConfig.MAX_MELEE_DISTANCE))
						.group(snapGroup("controlify.gui.aim_assist.ranged_snap", "controlify.gui.aim_assist.snap_on_aim",
								settings.rangedSnap, defaults.rangedSnap, AimAssistConfig.MAX_BOW_DISTANCE))
						.build())
				.category(ConfigCategory.createBuilder()
						.name(Component.translatable("controlify.gui.aim_assist.tab.lock_on"))
						.option(explainer("controlify.gui.aim_assist.tab.lock_on"))
						.group(OptionGroup.createBuilder()
								.name(Component.translatable("controlify.gui.target_lock"))
								.description(OptionDescription.of(Component.translatable("controlify.gui.target_lock.tooltip")))
								.option(tickBox("controlify.gui.target_lock.enabled",
										lockDefaults.enabled, () -> lock.enabled, v -> lock.enabled = v))
								// Described one mode at a time. All three at once needed line breaks inside a
								// single translation string, which the lang loader hands over as literal
								// backslash-n.
								.option(picker("controlify.gui.target_lock.mode", TargetLockMode.class,
										lockDefaults.mode, () -> lock.mode, v -> lock.mode = v))
								.option(bindMode)
								.option(fovAngle)
								.option(fovRange)
								.option(fovPriority)
								.option(slider("controlify.gui.target_lock.strength", 0, 100, 1, PERCENT,
										lockDefaults.lockedStrengthPercent, () -> lock.lockedStrengthPercent, v -> lock.lockedStrengthPercent = v))
								.option(slider("controlify.gui.target_lock.range", 1, TargetLockConfig.MAX_LOCKED_RANGE, 1, BLOCKS,
										lockDefaults.lockedRangeBlocks, () -> lock.lockedRangeBlocks, v -> lock.lockedRangeBlocks = v))
								.option(slider("controlify.gui.target_lock.speed", 0, 100, 1, PERCENT,
										lockDefaults.lockedSpeedPercent, () -> lock.lockedSpeedPercent, v -> lock.lockedSpeedPercent = v))
								// Where the Locked settings - the three above and Ignore Crosshair Cone
								// below - take over: for melee, for the bow, one switch each at Donny's
								// asking (tl89).
								.option(tickBox("controlify.gui.target_lock.override_melee",
										defaults.lockOverridesMelee, () -> settings.lockOverridesMelee, v -> settings.lockOverridesMelee = v))
								.option(tickBox("controlify.gui.target_lock.override_bow",
										defaults.lockOverridesBow, () -> settings.lockOverridesBow, v -> settings.lockOverridesBow = v))
								.option(warnedTickBox("controlify.gui.target_lock.override_cone",
										lockDefaults.overrideCone, () -> lock.overrideCone, v -> lock.overrideCone = v))
								.build())
						.build())
				.category(ConfigCategory.createBuilder()
						.name(Component.translatable("controlify.gui.aim_assist.tab.extras"))
						.option(explainer("controlify.gui.aim_assist.tab.extras"))
						.group(OptionGroup.createBuilder()
								.name(Component.translatable("controlify.gui.target_lock.marker_compass"))
								.description(OptionDescription.of(Component.translatable("controlify.gui.target_lock.marker_compass.tooltip")))
								.option(tickBox("controlify.gui.target_lock.arrow",
										lockDefaults.arrowEnabled, () -> lock.arrowEnabled, v -> lock.arrowEnabled = v))
								.option(ButtonOption.createBuilder()
										.name(RainbowText.of(Component.translatable("controlify.gui.target_lock.colors")))
										.text(RainbowText.of(Component.translatable("controlify.gui.target_lock.colors.button"), 1))
										.description(OptionDescription.of(RainbowText.of(Component.translatable("controlify.gui.target_lock.colors.tooltip"), 2)))
										.action((screen, button) -> MinecraftUtil.setScreen(ColorWheelScreen.of(
												screen,
												RainbowText.of(Component.translatable("controlify.gui.target_lock.colors.title")),
												RainbowText.of(Component.translatable("controlify.gui.target_lock.colors.reset"), 3),
												List.of(
														new ColorWheelScreen.Entry(
																RainbowText.of(Component.translatable("controlify.gui.target_lock.colors.marker"), 1),
																TargetLockConfig.DEFAULT_ARROW_COLOR,
																() -> lock.arrowColor,
																v -> lock.arrowColor = v),
														new ColorWheelScreen.Entry(
																RainbowText.of(Component.translatable("controlify.gui.target_lock.colors.compass"), 2),
																CompassConfig.DEFAULT_COLOR,
																() -> lock.compassColor,
																v -> lock.compassColor = v)))))
										.build())
								.option(tickBox("controlify.gui.target_lock.compass",
										lockDefaults.compassEnabled, () -> lock.compassEnabled, v -> lock.compassEnabled = v))
								.option(ButtonOption.createBuilder()
										.name(Component.translatable("controlify.gui.target_lock.compass_layout"))
										.text(Component.translatable("controlify.gui.target_lock.compass_layout.button"))
										.description(OptionDescription.of(Component.translatable("controlify.gui.target_lock.compass_layout.tooltip")))
										.action((screen, button) -> MinecraftUtil.setScreen(new CompassLayoutScreen(screen, lock)))
										.build())
								.build())
						.group(OptionGroup.createBuilder()
								.name(Component.translatable("controlify.gui.target_lock.dropout"))
								.description(OptionDescription.of(Component.translatable("controlify.gui.target_lock.dropout.tooltip")))
								// What a hard push of the look stick away from the locked mob does (tl104):
								// described one mode at a time, like the pickers above.
								.option(picker("controlify.gui.target_lock.push_away", PushAwayMode.class,
										lockDefaults.pushAway, () -> lock.pushAway, v -> lock.pushAway = v))
								.option(autoDrop)
								.option(groundRange)
								.option(flyingRange)
								.option(resetPercent)
								.option(dropSeconds)
								.build())
						.build())
				.build().generateScreen(parent);
	}

	private static final IntFunction<Component> PERCENT =
			v -> Component.translatable("controlify.gui.aim_assist.percent_format", v);
	private static final IntFunction<Component> BLOCKS =
			v -> Component.translatable("controlify.gui.aim_assist.blocks_format", v);
	private static final IntFunction<Component> SECONDS =
			v -> Component.translatable("controlify.gui.aim_assist.seconds_format", v);
	private static final IntFunction<Component> MILLISECONDS =
			v -> Component.translatable("controlify.gui.aim_assist.milliseconds_format", v);
	/** Cones are stored in tenths of a degree so half-degree steps survive as whole numbers. */
	private static final IntFunction<Component> DEGREES =
			v -> Component.translatable("controlify.gui.aim_assist.degrees_format", String.format("%.1f", v / 10.0));
	/** F.O.V Angle is stored in whole degrees: a wide cone has no use for half steps. */
	private static final IntFunction<Component> WHOLE_DEGREES =
			v -> Component.translatable("controlify.gui.aim_assist.degrees_format", v);
	/** F.O.V Priority Range reads OFF at 0, where it stops playing any part. */
	private static final IntFunction<Component> BLOCKS_OR_OFF =
			v -> v == 0 ? CommonComponents.OPTION_OFF : BLOCKS.apply(v);

	/**
	 * One snap's group: its switch, then its sliders, which are greyed out while the switch is off
	 * the way the Letting Go sliders are while dropping is off.
	 */
	private static OptionGroup snapGroup(String key, String switchKey, SnapSettings snap, SnapSettings defaults, int maxRange) {
		List<Option<Integer>> sliders = List.of(
				slider("controlify.gui.aim_assist.snap_range", 1, maxRange, 1, BLOCKS,
						defaults.rangeBlocks, () -> snap.rangeBlocks, v -> snap.rangeBlocks = v),
				slider("controlify.gui.aim_assist.snap_angle", SnapConfig.MIN_ANGLE_DEGREES, SnapConfig.MAX_ANGLE_DEGREES, 1, WHOLE_DEGREES,
						defaults.angleDegrees, () -> snap.angleDegrees, v -> snap.angleDegrees = v),
				slider("controlify.gui.aim_assist.snap_strength", 1, 100, 1, PERCENT,
						defaults.strengthPercent, () -> snap.strengthPercent, v -> snap.strengthPercent = v),
				slider("controlify.gui.aim_assist.snap_ramp_up", 1, 100, 1, PERCENT,
						defaults.rampUpPercent, () -> snap.rampUpPercent, v -> snap.rampUpPercent = v),
				slider("controlify.gui.aim_assist.snap_ramp_down", 1, 100, 1, PERCENT,
						defaults.rampDownPercent, () -> snap.rampDownPercent, v -> snap.rampDownPercent = v));
		sliders.forEach(option -> option.setAvailable(snap.enabled));

		Option<Boolean> toggle = warnedTickBox(switchKey, defaults.enabled, () -> snap.enabled, v -> snap.enabled = v);
		toggle.addEventListener((opt, event) -> sliders.forEach(option -> option.setAvailable(opt.pendingValue())));

		return OptionGroup.createBuilder()
				.name(Component.translatable(key))
				.description(OptionDescription.of(Component.translatable(key + ".tooltip")))
				.option(toggle)
				.options(sliders)
				.build();
	}

	/**
	 * The description of a switch that carries a warning: what it does, and while it is on, in
	 * red, why a server may object - the way Ignore Crosshair Cone's has always read.
	 */
	private static OptionDescription warned(String key, boolean on) {
		return OptionDescription.createBuilder()
				.text(Component.translatable(key + ".tooltip"))
				.text(on
						? Component.translatable(key + ".tooltip.warning").withStyle(ChatFormatting.RED)
						: Component.empty())
				.build();
	}

	/**
	 * The description of an option that picks a mode: what the option is for, then the chosen
	 * mode's name as a heading over what that mode does. Built again whenever the value changes,
	 * and kept current while the option has focus by LiveOptionDescription.
	 */
	private static <E extends NameableEnum & StringRepresentable> OptionDescription modeDescription(String key, E mode) {
		return OptionDescription.createBuilder()
				.text(Component.translatable(key + ".tooltip"))
				.text(Component.empty())
				.text(mode.getDisplayName().copy().withStyle(ChatFormatting.BOLD))
				.text(Component.translatable(key + "." + mode.getSerializedName() + ".desc"))
				.build();
	}

	/**
	 * Trajectory Aim's description: what it is for, the chosen mode's name as a heading over what
	 * that mode does, the way the other mode pickers read - and while it is on, in red, the warning
	 * everything that turns the camera by itself carries.
	 */
	private static OptionDescription trajectoryDescription(TrajectoryAimMode mode) {
		String key = "controlify.gui.aim_assist.trajectory_aim";
		return OptionDescription.createBuilder()
				.text(Component.translatable(key + ".tooltip"))
				.text(Component.empty())
				.text(mode.getDisplayName().copy().withStyle(ChatFormatting.BOLD))
				.text(Component.translatable(key + "." + mode.getSerializedName() + ".desc"))
				.text(mode.isOn()
						? Component.translatable(key + ".tooltip.warning").withStyle(ChatFormatting.RED)
						: Component.empty())
				.build();
	}

	/**
	 * The line at the top of a tab, saying how its settings work with the rest, drawn as the tab's
	 * header - bold and larger, on a dark band (TabExplainerController, tl88). A controller lands on
	 * it first when the tab opens, and the description pane opens on it (tl87), headed with the
	 * tab's name - where YACL's own label option would be headed "Label Option". It holds no setting
	 * and is never saved.
	 */
	private static Option<Component> explainer(String tabKey) {
		Component text = Component.translatable(tabKey + ".explainer");
		return Option.<Component>createBuilder()
				.name(Component.translatable(tabKey))
				.description(OptionDescription.of(text))
				.stateManager(StateManager.createImmutable(text))
				.customController(TabExplainerController::new)
				.build();
	}

	/** A switch: a tick box with its name, its description - fixed, or built from its state - and its binding. */
	private static Option<Boolean> tickBox(Component name, Function<Boolean, OptionDescription> description,
			boolean defaultValue, Supplier<Boolean> getter, Consumer<Boolean> setter) {
		return Option.<Boolean>createBuilder()
				.name(name)
				.description(description)
				.binding(defaultValue, getter, setter)
				.controller(TickBoxControllerBuilder::create)
				.build();
	}

	/** A switch named by {@code key} and described by {@code key.tooltip}. */
	private static Option<Boolean> tickBox(String key, boolean defaultValue, Supplier<Boolean> getter, Consumer<Boolean> setter) {
		OptionDescription description = OptionDescription.of(Component.translatable(key + ".tooltip"));
		return tickBox(Component.translatable(key), state -> description, defaultValue, getter, setter);
	}

	/** A switch named by {@code key} whose description carries a warning while it is on ({@link #warned}). */
	private static Option<Boolean> warnedTickBox(String key, boolean defaultValue, Supplier<Boolean> getter, Consumer<Boolean> setter) {
		return tickBox(Component.translatable(key), state -> warned(key, state), defaultValue, getter, setter);
	}

	/** A picker for an enum, named by {@code key}, described as given. */
	private static <E extends Enum<E> & NameableEnum & StringRepresentable> Option<E> picker(String key, Class<E> type,
			Function<E, OptionDescription> description, E defaultValue, Supplier<E> getter, Consumer<E> setter) {
		return Option.<E>createBuilder()
				.name(Component.translatable(key))
				.description(description)
				.binding(defaultValue, getter, setter)
				.controller(opt -> EnumControllerBuilder.create(opt).enumClass(type))
				.build();
	}

	/** A picker for a mode, described one mode at a time ({@link #modeDescription}). */
	private static <E extends Enum<E> & NameableEnum & StringRepresentable> Option<E> picker(String key, Class<E> type,
			E defaultValue, Supplier<E> getter, Consumer<E> setter) {
		return picker(key, type, state -> modeDescription(key, state), defaultValue, getter, setter);
	}

	private static Option<Integer> slider(
			String translationKey,
			int min,
			int max,
			int step,
			IntFunction<Component> format,
			int defaultValue,
			Supplier<Integer> getter,
			Consumer<Integer> setter
	) {
		return Option.<Integer>createBuilder()
				.name(Component.translatable(translationKey))
				.description(OptionDescription.of(Component.translatable(translationKey + ".tooltip")))
				.binding(defaultValue, getter, setter)
				.controller(opt -> IntegerSliderControllerBuilder.create(opt)
						.range(min, max)
						.step(step)
						.formatValue(format::apply))
				.build();
	}
}
