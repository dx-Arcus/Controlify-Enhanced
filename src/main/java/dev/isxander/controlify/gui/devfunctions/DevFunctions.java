/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.gui.devfunctions;

import dev.isxander.controlify.Controlify;
import dev.isxander.controlify.aimassist.AimAssist;
import dev.isxander.controlify.aimassist.TargetLock;
import dev.isxander.controlify.config.dto.DevConfig;
import dev.isxander.controlify.config.dto.TargetLockConfig;
import dev.isxander.controlify.config.settings.GlobalSettings;
import dev.isxander.controlify.config.settings.TargetLockSettings;
import dev.isxander.controlify.controllermanager.SDLControllerManager;
import dev.isxander.controlify.utils.CUtil;
import dev.isxander.controlify.utils.MinecraftUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Registry of what the "Dev Functions" panel on the right-hand side of Controlify's Global
 * Settings screen shows (see {@link DevFunctionsPanel}).
 * <p>
 * Two kinds of entry. A {@link DevFunction} is a button that does something when pressed; add one
 * with {@link #register} and give it a name, a tooltip, when it can be used, and what it does. A
 * {@link DevField} is a number to type into, for a value being tuned by feel rather than one that
 * has been settled on - add one with {@link #registerField} and give it the range it is allowed.
 * Both appear in the order they are registered, fields under the buttons.
 */
public final class DevFunctions {
	/**
	 * @param name        button label
	 * @param tooltip     shown when hovering the button
	 * @param available   whether the button can currently be pressed - asked every tick while the panel
	 *                    is up, so a button can grey out and light up again without the screen reopening
	 * @param action      what happens when the button is pressed
	 * @param waitName    the button's label while it is greyed out, or null to keep {@code name}
	 * @param waitTooltip shown in place of {@code tooltip} while the button is greyed out, or null to
	 *                    keep showing {@code tooltip}
	 */
	public record DevFunction(Component name, Component tooltip, BooleanSupplier available, Runnable action,
			@Nullable Component waitName, @Nullable Component waitTooltip) {
		/** A button with nothing different to say while it cannot be pressed. */
		public DevFunction(Component name, Component tooltip, BooleanSupplier available, Runnable action) {
			this(name, tooltip, available, action, null, null);
		}
	}

	/**
	 * A whole number typed into the panel, kept inside {@code min}..{@code max} however it is
	 * edited. Anything outside that, or half-typed, leaves the stored value alone.
	 *
	 * @param name    label shown to the left of the box
	 * @param tooltip shown when hovering either
	 * @param min     smallest value that may be stored, inclusive
	 * @param max     largest value that may be stored, inclusive
	 */
	public record DevField(Component name, Component tooltip, int min, int max,
			IntSupplier get, IntConsumer set) {
	}

	private static final List<DevFunction> FUNCTIONS = new ArrayList<>();
	private static final List<DevField> FIELDS = new ArrayList<>();

	/** How every XInput device path starts: {@code XInput#0}, {@code XInput#1} and so on. */
	private static final String XINPUT_PATH_PREFIX = "XInput#";

	/**
	 * How long the Learn buttons stay greyed out after any joystick arrives or leaves - the
	 * duplicates Controlify ignores included. One replug is several events in a row: on 26 Sep the
	 * XInput entry dropped and came back up to 2 seconds apart, and the GameInput entry followed it
	 * within a second. A press in the middle of that records whatever happens to be attached at that
	 * instant - and plugging in raises no disconnect at all, so without this the buttons never grey
	 * out there, while the receiver's path may still be attached beside the cable's. Recorded as
	 * wired, the receiver would then be on both sides and wireless could never be recognised.
	 */
	private static final long SETTLE_MILLIS = 3000;

	/** What the Learn buttons were last reported as, so each change is logged once. */
	private static boolean learnReady = true;

	static {
		register(new DevFunction(
				Component.translatable("controlify.gui.dev_functions.new_server_toast"),
				Component.translatable("controlify.gui.dev_functions.new_server_toast.tooltip"),
				() -> true,
				DevFunctions::showNewServerToast
		));

		register(new DevFunction(
				Component.translatable("controlify.gui.dev_functions.aim_assist_target"),
				Component.translatable("controlify.gui.dev_functions.aim_assist_target.tooltip"),
				() -> Minecraft.getInstance().player != null,
				DevFunctions::showAimAssistToast
		));

		register(new DevFunction(
				Component.translatable("controlify.gui.dev_functions.target_lock"),
				Component.translatable("controlify.gui.dev_functions.target_lock.tooltip"),
				() -> Minecraft.getInstance().player != null,
				DevFunctions::showTargetLockToast
		));

		register(new DevFunction(
				Component.translatable("controlify.gui.check_movement_type"),
				Component.translatable("controlify.gui.check_movement_type.tooltip"),
				() -> Minecraft.getInstance().player != null,
				DevFunctions::showMovementTypeToast
		));

		register(new DevFunction(
				Component.translatable("controlify.gui.dev_functions.learn_wired"),
				Component.translatable("controlify.gui.dev_functions.learn_wired.tooltip"),
				DevFunctions::canLearn,
				() -> learnConnection(true),
				Component.translatable("controlify.gui.dev_functions.learn.wait_name"),
				Component.translatable("controlify.gui.dev_functions.learn.wait")
		));

		register(new DevFunction(
				Component.translatable("controlify.gui.dev_functions.learn_wireless"),
				Component.translatable("controlify.gui.dev_functions.learn_wireless.tooltip"),
				DevFunctions::canLearn,
				() -> learnConnection(false),
				Component.translatable("controlify.gui.dev_functions.learn.wait_name"),
				Component.translatable("controlify.gui.dev_functions.learn.wait")
		));

		register(new DevFunction(
				Component.translatable("controlify.gui.dev_functions.controller_connection"),
				Component.translatable("controlify.gui.dev_functions.controller_connection.tooltip"),
				() -> true,
				DevFunctions::showConnectionToast
		));

		register(new DevFunction(
				Component.translatable("controlify.gui.dev_functions.forget_connections"),
				Component.translatable("controlify.gui.dev_functions.forget_connections.tooltip"),
				() -> true,
				DevFunctions::forgetConnections
		));

		registerField(new DevField(
				Component.translatable("controlify.gui.dev_functions.marker_floor"),
				Component.translatable("controlify.gui.dev_functions.marker_floor.tooltip"),
				TargetLockConfig.MIN_MARKER_FLOOR,
				TargetLockConfig.MAX_MARKER_FLOOR,
				() -> targetLock().markerFloorBlocks,
				value -> targetLock().markerFloorBlocks = value
		));

		registerField(new DevField(
				Component.translatable("controlify.gui.dev_functions.color_pointer_speed"),
				Component.translatable("controlify.gui.dev_functions.color_pointer_speed.tooltip"),
				DevConfig.MIN_COLOR_POINTER_SPEED,
				DevConfig.MAX_COLOR_POINTER_SPEED,
				() -> global().colorPointerSpeed,
				value -> global().colorPointerSpeed = value
		));
	}

	private DevFunctions() {
	}

	public static void register(DevFunction function) {
		FUNCTIONS.add(function);
	}

	public static List<DevFunction> all() {
		return Collections.unmodifiableList(FUNCTIONS);
	}

	public static void registerField(DevField field) {
		FIELDS.add(field);
	}

	public static List<DevField> fields() {
		return Collections.unmodifiableList(FIELDS);
	}

	private static GlobalSettings global() {
		return Controlify.instance().config().getSettings().globalSettings();
	}

	private static TargetLockSettings targetLock() {
		return Controlify.instance().config().getSettings().aimAssistSettings().targetLock;
	}

	/** Shows the exact "New server detected" toast players get on a server that isn't whitelisted. */
	private static void showNewServerToast() {
		ServerData server = Minecraft.getInstance().getCurrentServer();
		Controlify.instance().sendNewServerToast(server != null ? server.name : "Test Server");
	}

	/** Reports what aim assist is doing right now, for tuning the strength and range levels. */
	private static void showAimAssistToast() {
		AimAssist.Debug debug = AimAssist.debug();
		Component description;
		if (!debug.active()) {
			description = Component.translatable("controlify.toast.aim_assist.inactive");
		} else if (debug.target() == null) {
			description = Component.translatable(
					"controlify.toast.aim_assist.no_target",
					debug.targets().getDisplayName(),
					Component.translatable(debug.bowMode()
							? "controlify.gui.aim_assist.bow"
							: "controlify.gui.aim_assist.melee"),
					String.valueOf(debug.counts().nearby),
					String.format("%d eligible, %d far, %d outside cone, %d blocked, best %.1f°",
							debug.counts().eligible,
							debug.counts().tooFar,
							debug.counts().outsideCone,
							debug.counts().losBlocked,
							debug.counts().bestAngle)
			);
		} else {
			description = Component.translatable(
					"controlify.toast.aim_assist.target",
					debug.target().getDisplayName(),
					String.format("%.1f", debug.angle()),
					String.format("%.0f", debug.multiplier() * 100),
					String.format("%.2f", debug.pull()),
					Component.translatable(debug.bowMode()
							? "controlify.gui.aim_assist.bow"
							: "controlify.gui.aim_assist.melee")
			);
		}
		if (debug.locked()) {
			description = description.copy().append(Component.translatable("controlify.toast.aim_assist.locked"));
		}
		MinecraftUtil.sendToast(
				Component.translatable("controlify.toast.aim_assist.title"),
				description,
				false
		);
	}

	/** Reports what target lock is holding, and why it would let go. */
	private static void showTargetLockToast() {
		MinecraftUtil.sendToast(
				Component.translatable("controlify.toast.target_lock.title"),
				Component.literal(TargetLock.describe()),
				false
		);
	}

	/**
	 * Reports how the controller is attached, and everything SDL says about every joystick.
	 * <p>
	 * Wired or wireless is only claimed once both of the other buttons have been used. Until then
	 * it says so rather than guessing: SDL's own connection state is unknown on pads handled by
	 * XInput or GameInput, its battery reading has been seen to say "charging" on a pad running off
	 * a receiver, and a pad's GUID is identical either way. Only the device path differs. The full
	 * list goes to the log regardless, which is the part worth having when something is odd.
	 */
	private static void showConnectionToast() {
		List<SDLControllerManager.Connection> connections = SDLControllerManager.connections();

		CUtil.LOGGER.log("Controller connections ({}):", connections.size());
		for (SDLControllerManager.Connection c : connections) {
			CUtil.LOGGER.log("  {} {} {} guid={} path={} power={}",
					c.id(), c.registered() ? "[in use]" : "[ignored]", c.name(), c.guid(), c.path(), c.power());
		}

		if (connections.isEmpty()) {
			MinecraftUtil.sendToast(
					Component.translatable("controlify.toast.connection.none.title"),
					Component.translatable("controlify.toast.connection.none.description"),
					false);
			return;
		}

		GlobalSettings settings = global();
		Set<String> wired = paths(settings.wiredPaths);
		Set<String> wireless = paths(settings.wirelessPaths);

		Component title;
		String hint = null;
		if (wired.isEmpty() || wireless.isEmpty()) {
			// One side on its own cannot tell them apart: every path recorded would match it and
			// nothing would ever read as the other, so the answer would be the same either way.
			// Both have to be taught before there is anything to compare against.
			title = Component.translatable("controlify.toast.connection.unlearned.title");
			hint = "controlify.toast.connection.unlearned.hint";
		} else {
			// Only the paths unique to one side say anything. A path recorded both ways is the
			// same either way by definition, so it is left out rather than making both true.
			Set<String> wiredOnly = new LinkedHashSet<>(wired);
			wiredOnly.removeAll(wireless);
			Set<String> wirelessOnly = new LinkedHashSet<>(wireless);
			wirelessOnly.removeAll(wired);

			// Every path attached, not just the one Controlify drives, less XInput's (see
			// learnable). After a replug Controlify drives the XInput interface, whose path is the
			// same on a cable and on a receiver; the interface that does change is the GameInput
			// duplicate it set aside. Reading only the one in use would mean reading a path that
			// never moves, and the answer could never change.
			Set<String> now = learnablePaths();
			boolean looksWired = now.stream().anyMatch(wiredOnly::contains);
			boolean looksWireless = now.stream().anyMatch(wirelessOnly::contains);
			if (looksWired == looksWireless) {
				title = Component.translatable("controlify.toast.connection.unclear.title");
				hint = "controlify.toast.connection.unclear.hint";
			} else {
				title = Component.translatable(looksWired
						? "controlify.toast.connection.wired.title"
						: "controlify.toast.connection.wireless.title");
			}
		}

		StringBuilder detail = new StringBuilder();
		for (SDLControllerManager.Connection c : connections) {
			if (!detail.isEmpty()) detail.append('\n');
			detail.append(c.registered() ? "> " : "  ").append(c.id()).append(' ').append(c.name());
		}
		MinecraftUtil.sendToast(title, hint == null
				? Component.literal(detail.toString())
				: Component.translatable(hint).append("\n" + detail), true);
	}

	/**
	 * Records the device paths visible right now as meaning one connection or the other.
	 * <p>
	 * This is the one thing SDL will not say and cannot be worked out - which path is a cable is a
	 * fact about someone's desk. Press one button with the controller wired and the other with it
	 * wireless, in any order, and nothing is claimed until both are known.
	 */
	private static void learnConnection(boolean wired) {
		Set<String> now = learnablePaths();
		if (!canLearn()) {
			// The Learn buttons are greyed out while this is so, which should make it unreachable.
			// It stays for a press that lands in the moment before the panel notices: nothing is
			// recorded, and saying so beats a press that silently does nothing.
			boolean anything = !SDLControllerManager.attachedPaths().isEmpty();
			MinecraftUtil.sendToast(
					Component.translatable(anything
							? "controlify.toast.connection.not_ready.title"
							: "controlify.toast.connection.none.title"),
					Component.translatable(anything
							? "controlify.toast.connection.not_ready.description"
							: "controlify.toast.connection.none.description"),
					false);
			return;
		}

		GlobalSettings settings = global();
		// Added to rather than replacing what is there. One connection can present more than one
		// path, and not all of them every time - plugging in while the receiver is still live shows
		// both the cable and the receiver at once. Pressing this in each of those states builds the
		// full picture up.
		// Nothing is ever taken off the other side. A path that turns up both ways - the
		// receiver's, if it is still live when the cable goes in - has to end up recorded on both,
		// because that is what stops it counting as evidence for either. Moving it to whichever
		// button was pressed last instead makes it the deciding path every time, alternately wrong
		// in both directions, and pressing the buttons more never settles it. Clearing and starting
		// again is the way back from a press in the wrong state.
		Set<String> known = new LinkedHashSet<>(paths(wired ? settings.wiredPaths : settings.wirelessPaths));
		known.addAll(now);
		String joined = String.join(DevConfig.PATH_SEPARATOR, known);
		if (wired) {
			settings.wiredPaths = joined;
		} else {
			settings.wirelessPaths = joined;
		}
		Controlify.instance().config().markDirty();

		CUtil.LOGGER.log("Learned connection paths: wired=[{}] wireless=[{}]",
				settings.wiredPaths.replace('\n', ' '), settings.wirelessPaths.replace('\n', ' '));

		int wiredCount = paths(settings.wiredPaths).size();
		int wirelessCount = paths(settings.wirelessPaths).size();
		boolean bothKnown = wiredCount > 0 && wirelessCount > 0;
		MinecraftUtil.sendToast(
				Component.translatable(wired
						? "controlify.toast.connection.learned_wired.title"
						: "controlify.toast.connection.learned_wireless.title"),
				Component.translatable(bothKnown
						? "controlify.toast.connection.learned.complete"
						: "controlify.toast.connection.learned.more",
						String.valueOf(wiredCount), String.valueOf(wirelessCount)),
				true);
	}

	/**
	 * Throws away everything the Learn buttons recorded.
	 * <p>
	 * Needed because learning adds rather than replaces: a press in the wrong state is otherwise
	 * permanent, and a path recorded on both sides quietly stops being useful evidence. This is the
	 * way back to a clean slate.
	 */
	private static void forgetConnections() {
		GlobalSettings settings = global();
		int had = paths(settings.wiredPaths).size() + paths(settings.wirelessPaths).size();
		settings.wiredPaths = "";
		settings.wirelessPaths = "";
		Controlify.instance().config().markDirty();

		CUtil.LOGGER.log("Forgot {} learned connection path(s).", had);
		MinecraftUtil.sendToast(
				Component.translatable("controlify.toast.connection.forgotten.title"),
				Component.translatable("controlify.toast.connection.forgotten.description", String.valueOf(had)),
				false);
	}

	/**
	 * Whether a Learn press would record something trustworthy right now: some path that can tell a
	 * cable from a receiver is attached, and nothing has arrived or left for {@link #SETTLE_MILLIS}.
	 * The Learn buttons are greyed out, and say to wait, while this is false.
	 * <p>
	 * Every change is logged with the paths attached at that moment, so what the buttons did during
	 * a replug can be read back from {@code latest.log} rather than guessed.
	 */
	private static boolean canLearn() {
		boolean ready = !learnablePaths().isEmpty()
				&& SDLControllerManager.millisSinceHotplug() >= SETTLE_MILLIS;
		if (ready != learnReady) {
			learnReady = ready;
			CUtil.LOGGER.log("Learn buttons {} - attached: [{}]",
					ready ? "ready" : "greyed out", String.join(" ", SDLControllerManager.attachedPaths()));
		}
		return ready;
	}

	/**
	 * The device paths attached right now that can tell a cable from a receiver, in the order SDL
	 * lists them. Asked every tick while the panel is up, so it comes from a lookup that opens
	 * nothing.
	 */
	private static Set<String> learnablePaths() {
		return learnable(SDLControllerManager.attachedPaths());
	}

	/**
	 * Every path except XInput's, trimmed, blanks dropped, in the order given.
	 * <p>
	 * On this pad the XInput entry is {@code XInput#0} on a cable and on a receiver alike, and its
	 * number is not even fixed - {@code XInput#1} has turned up after a replug - so it never says
	 * which is which. It is also absent at launch: only the GameInput entry is there until the first
	 * unplug or plug-in. Kept, it was recorded only by whichever button was pressed after a replug,
	 * sat on that side alone, and made the report unclear until the other side was taught a second
	 * time. Left out everywhere - including from what was saved before this - one press per side is
	 * enough however the game was started.
	 */
	private static Set<String> learnable(Iterable<String> paths) {
		Set<String> out = new LinkedHashSet<>();
		for (String path : paths) {
			String trimmed = path.trim();
			if (!trimmed.isEmpty() && !trimmed.startsWith(XINPUT_PATH_PREFIX)) out.add(trimmed);
		}
		return out;
	}

	/** Splits a stored set of paths back out, through {@link #learnable}, so an empty setting is an empty set. */
	private static Set<String> paths(String stored) {
		return learnable(List.of(stored.split(DevConfig.PATH_SEPARATOR)));
	}

	/** Shows whether analog or keyboard-like movement is active right now. */
	private static void showMovementTypeToast() {
		GlobalSettings globalSettings = Controlify.instance().config().getSettings().globalSettings();
		boolean keyboardLike = globalSettings.shouldUseKeyboardMovement();
		MinecraftUtil.sendToast(
				Component.translatable(keyboardLike
						? "controlify.toast.movement_type.keyboard.title"
						: "controlify.toast.movement_type.analogue.title"),
				Component.translatable(keyboardLike
						? "controlify.toast.movement_type.keyboard.description"
						: "controlify.toast.movement_type.analogue.description"),
				false
		);
	}
}
