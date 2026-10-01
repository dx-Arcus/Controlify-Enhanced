/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.controllermanager;

import dev.isxander.controlify.Controlify;
import dev.isxander.controlify.config.settings.profile.ProfileSettings;
import dev.isxander.controlify.controller.info.ControllerInfo;
import dev.isxander.controlify.controller.id.ControllerType;
import dev.isxander.controlify.controller.ControllerEntity;
import dev.isxander.controlify.debug.DebugProperties;
import dev.isxander.controlify.driver.CompoundDriver;
import dev.isxander.controlify.driver.Driver;
import dev.isxander.controlify.driver.sdl.SDL3GamepadDriver;
import dev.isxander.controlify.driver.sdl.SDL3JoystickDriver;
import dev.isxander.controlify.driver.sdl.SDLUtil;
import dev.isxander.controlify.driver.steamdeck.SteamDeckDriver;
import dev.isxander.controlify.driver.steamdeck.SteamDeckUtil;
import dev.isxander.controlify.hid.ControllerHIDInfo;
import dev.isxander.controlify.hid.HIDDevice;
import dev.isxander.controlify.hid.HIDID;
import dev.isxander.controlify.utils.CUtil;
import dev.isxander.controlify.utils.ControllerUtils;
import dev.isxander.controlify.utils.log.ControlifyLogger;
import dev.isxander.sdl.*;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.stream.Collectors;

import static dev.isxander.sdl.SdlEvents.*;

public class SDLControllerManager extends AbstractControllerManager {

	private final Sdl sdl;

	private SdlEvent event = new SdlEvent();

	// must keep a reference to prevent GC from collecting it and the callback failing
	@SuppressWarnings({"FieldCanBeLocal", "unused"})
	private final EventFilter eventFilter;

	private boolean steamDeckConsumed = false;

	/**
	 * The live manager, so the dev panel can ask what SDL currently sees without Controlify having
	 * to hand it out. Exactly one of these exists for the life of the game.
	 */
	private static SDLControllerManager instance;

	/**
	 * When a joystick of any kind last arrived or left - ignored duplicates included - as
	 * {@link System#nanoTime()}. The dev panel's Learn buttons wait for this to settle.
	 */
	private static volatile long lastHotplugNanos = System.nanoTime();

	/**
	 * Joysticks we deliberately did not register because they are a second view of a controller we
	 * already have. Kept so that their removal is recognised rather than warned about.
	 */
	private final Set<UniqueControllerID> ignoredDuplicates = new HashSet<>();

	/** How each registered controller is attached, so an arriving duplicate can be compared to it. */
	private final Map<UniqueControllerID, Integer> transportByUcid = new HashMap<>();

	public SDLControllerManager(Sdl sdl, ControlifyLogger logger) {
		super(logger);
		this.sdl = sdl;
		instance = this;
		logger.debugLog("Controller manager using SDL3");

		sdl.events().SDL_SetEventFilter(eventFilter = new EventFilter(), SdlPointer.NULL);

		this.loadGamepadMappings(minecraft.getResourceManager());
	}

	@Override
	public void tick(boolean outOfFocus) {
		if (event == null) {
			logger.warn("SDL_Event has somehow been set to null. Recreating...");
			event = new SdlEvent();
		}

		while (sdl.events().SDL_PollEvent(event)) {
			switch (event.type()) {
				// On added, `which` refers to the device index
				case SDL_EVENT_JOYSTICK_ADDED -> {
					var jdevice = (SdlEvent.JoyDevice) event.data();
					SdlJoystickId jid = jdevice.which();
					logger.validateIsTrue(jid != null, "event.jdevice.which was null during SDL_EVENT_JOYSTICK_ADDED event");

					logger.debugLog("SDL event: Joystick added: {}", jid.value());
					lastHotplugNanos = System.nanoTime();

					UniqueControllerID ucid = new SDLUniqueControllerID(jid);
					ControllerHIDInfo hidInfo = fetchTypeFromSDL(sdl, jid)
							.orElse(new ControllerHIDInfo(ControllerType.DEFAULT, Optional.empty()));

					accept(ucid, hidInfo, probe(jid), true);
				}

				// On removed, `which` refers to the device instance ID
				case SDL_EVENT_JOYSTICK_REMOVED -> {
					var jdevice = (SdlEvent.JoyDevice) event.data();
					SdlJoystickId jid = jdevice.which();
					logger.validateIsTrue(jid != null, "event.jdevice.which was null during SDL_EVENT_JOYSTICK_REMOVED event");

					logger.debugLog("SDL event: Joystick removed: {}", jid.value());
					lastHotplugNanos = System.nanoTime();

					UniqueControllerID removed = new SDLUniqueControllerID(jid);
					if (ignoredDuplicates.remove(removed)) {
						// Never registered, so there is nothing to disconnect and nothing is wrong. Logged
						// rather than debug-only: when a duplicate leaves is part of what a replug looks
						// like, and on 26 Sep it was the one step the log could not show.
						logger.log("Ignored duplicate {} went away.", removed);
					} else {
						transportByUcid.remove(removed);
						getController(removed)
								.ifPresentOrElse(
										this::onControllerRemoved,
										() -> CUtil.LOGGER.warn("Controller removed but not found: {}", jid.value())
								);
					}
				}
			}
		}

		super.tick(outOfFocus);
	}

	@Override
	public void discoverControllers() {
		logger.debugLog("Discovering controllers...");

		SdlJoystickId[] joysticks = sdl.joystick().SDL_GetJoysticks();
		for (SdlJoystickId jid : joysticks) {
			UniqueControllerID ucid = new SDLUniqueControllerID(jid);
			ControllerHIDInfo hidInfo = fetchTypeFromSDL(sdl, jid)
					.orElse(new ControllerHIDInfo(ControllerType.DEFAULT, Optional.empty()));

			accept(ucid, hidInfo, probe(jid), false);
		}
	}

	@Override
	protected Optional<ControllerEntity> createController(UniqueControllerID ucid, ControllerHIDInfo hidInfo, ControlifyLogger controllerLogger) {
		SdlJoystickId jid = ((SDLUniqueControllerID) ucid).jid();
		controllerLogger.debugLog("Creating controller: {}", jid.value());

		boolean isGamepad = isControllerGamepad(ucid) && !DebugProperties.FORCE_JOYSTICK;
		controllerLogger.debugLog("Controller is gamepad: {}", isGamepad);

		List<Driver> drivers = new ArrayList<>();
		if ((SteamDeckUtil.DECK_MODE.isGamingMode() || DebugProperties.STEAM_DECK_CUSTOM_CEF_URL != null)
			&& !steamDeckConsumed
			&& hidInfo.type().namespace().equals(SteamDeckUtil.STEAM_DECK_NAMESPACE)
		) {
			controllerLogger.debugLog("Controller is steam deck candidate");
			Optional<SteamDeckDriver> steamDeckDriver = SteamDeckDriver.create(controllerLogger);
			if (steamDeckDriver.isPresent()) {
				drivers.add(steamDeckDriver.get());
				steamDeckConsumed = true;
				controllerLogger.debugLog("Adding SteamDeckDriver - this controller has been reserved for Steam Deck");
			}
		}

		if (isGamepad) {
			SdlGamepadHandle ptrGamepad = SDLUtil.openGamepad(sdl, jid);
			drivers.add(new SDL3GamepadDriver(sdl, ptrGamepad, jid, hidInfo.type(), controllerLogger));
		} else {
			SdlJoystickHandle ptrJoystick = SDLUtil.openJoystick(sdl, jid);
			drivers.add(new SDL3JoystickDriver(sdl, ptrJoystick, jid, hidInfo.type(), controllerLogger));
		}

		controllerLogger.debugLog("Drivers: {}", drivers.stream().map(driver -> driver.getClass().getSimpleName()).collect(Collectors.joining(", ")));

		CompoundDriver compoundDriver = new CompoundDriver(drivers);

		ControllerInfo info = new ControllerInfo(ucid, hidInfo.type(), hidInfo.hidDevice());
		ControllerEntity controller = new ControllerEntity(
				info,
				compoundDriver,
				this.controlify.config().getActiveProfile(),
				ProfileSettings.createDefault(),
				controllerLogger
		);

		controllerLogger.debugLog("Unique Controller ID: {}", info.ucid());

		this.addController(ucid, controller);
		return Optional.of(controller);
	}

	@Override
	public boolean probeConnectedControllers() {
		return sdl.joystick().SDL_HasJoystick() || sdl.gamepad().SDL_HasGamepad();
	}

	@Override
	public boolean isControllerGamepad(UniqueControllerID ucid) {
		SdlJoystickId jid = ((SDLUniqueControllerID) ucid).jid;
		return sdl.gamepad().SDL_IsGamepad(jid);
	}

	@Override
	protected String getControllerSystemName(UniqueControllerID ucid) {
		SdlJoystickId jid = ((SDLUniqueControllerID) ucid).jid;
		return isControllerGamepad(ucid)
			? sdl.gamepad().SDL_GetGamepadNameForID(jid)
			: sdl.joystick().SDL_GetJoystickNameForID(jid);
	}

	private Optional<ControllerEntity> getController(UniqueControllerID ucid) {
		return Optional.ofNullable(controllersByJid.getOrDefault(ucid, null));
	}

	@Override
	protected void loadGamepadMappings(ResourceProvider resourceProvider) {
		CUtil.LOGGER.debugLog("Loading gamepad mappings...");

		Optional<Resource> resourceOpt = resourceProvider
				.getResource(CUtil.rl("controllers/gamecontrollerdb-sdl3.txt"));
		if (resourceOpt.isEmpty()) {
			CUtil.LOGGER.error("Failed to find game controller database.");
			return;
		}

		try (InputStream is = resourceOpt.get().open()) {
			byte[] bytes = is.readAllBytes();
			ByteBuffer byteBuffer = ByteBuffer.allocateDirect(bytes.length);
			byteBuffer.put(bytes);
			byteBuffer.flip();

			SdlIoStreamHandle stream = sdl.ioStream().SDL_IOFromConstMem(byteBuffer);
			if (stream == null) throw new IllegalStateException("Failed to open stream");

			int count = sdl.gamepad().SDL_AddGamepadMappingsFromIO(stream, true);
			if (count < 0) {
				CUtil.LOGGER.error("Failed to load gamepad mappings: {}", sdl.error().SDL_GetError());
			} else if (count == 0) {
				CUtil.LOGGER.warn("Successfully applied gamepad mappings but none were found for this OS. Unsupported OS?");
			} else {
				CUtil.LOGGER.log("Successfully loaded {} gamepad mapping entries!", count);
			}
		} catch (Throwable e) {
			CUtil.LOGGER.error("Failed to load gamepad mappings", e);
		}
	}

	/**
	 * Registers an arriving joystick, unless it is a controller we already have.
	 * <p>
	 * On Windows one physical pad can be enumerated twice at once - for example by XInput and by
	 * GameInput - which registers it as two controllers that then trade the active slot between
	 * them on every event, rename themselves, and leave a moment with no controller at all in
	 * between. Only one of them is wanted.
	 * <p>
	 * A duplicate is normally ignored. The exception is transport: if the pad is already held over
	 * a wireless link and the same pad now arrives over a wired one, the wired connection is the
	 * one to keep, so the wireless one is dropped and the wired one registered in its place. That
	 * switch is a real change of connection, so it is announced like any other.
	 */
	private void accept(UniqueControllerID ucid, ControllerHIDInfo hidInfo, Probe probe, boolean hotplug) {
		int transport = probe.connection();
		logger.log("Joystick {} arrived over {}. {}", ucid, transportName(transport), probe.describe());

		Optional<ControllerEntity> duplicated = findDuplicated(hidInfo);
		if (duplicated.isPresent()) {
			ControllerEntity existing = duplicated.get();
			UniqueControllerID existingUcid = existing.info().ucid();
			int existingTransport = transportByUcid.getOrDefault(existingUcid, SdlJoystick.SDL_JOYSTICK_CONNECTION_UNKNOWN);

			if (!supersedes(transport, existingTransport)) {
				logger.log("Ignoring {}: same device as #{}, already held over {}. {} vs {}",
						ucid, existingUcid, transportName(existingTransport),
						hidInfo.hidDevice().map(HIDDevice::path).orElse("no guid"),
						existing.info().hid().map(HIDDevice::path).orElse("no guid"));
				ignoredDuplicates.add(ucid);
				return;
			}

			logger.log("{} is the same device as #{} but arrived over {} rather than {} - switching to it.",
					ucid, existingUcid, transportName(transport), transportName(existingTransport));
			onControllerRemoved(existing);
			transportByUcid.remove(existingUcid);
		}

		Optional<ControllerEntity> created = tryCreate(ucid, hidInfo);
		created.ifPresent(controller -> {
			transportByUcid.put(ucid, transport);
			ControllerUtils.wrapControllerError(() -> onControllerConnected(controller, hotplug), "Connecting controller", controller);
		});
	}

	/**
	 * Whether a connection arriving now should take over from the one already held.
	 * <p>
	 * Only wired taking over from wireless. Not the reverse, or unplugging a pad that is also
	 * paired would hand it straight back; not when either is unknown, because guessing is how a
	 * pad ends up flipping between two connections forever.
	 */
	private static boolean supersedes(int arriving, int existing) {
		if (!"1".equals(System.getProperty("controlify.sdl.preferwired", "1"))) return false;
		return arriving == SdlJoystick.SDL_JOYSTICK_CONNECTION_WIRED
				&& existing == SdlJoystick.SDL_JOYSTICK_CONNECTION_WIRELESS;
	}

	/**
	 * The controller this joystick is another view of, if any.
	 * <p>
	 * Vendor and product alone cannot decide this: two of the same pad plugged into one machine
	 * share them. The SDL GUID is what separates the cases. It encodes which backend produced the
	 * device, so the same pad seen twice has two different GUIDs, while two genuinely separate pads
	 * of the same model - both coming through the same backend - have identical ones. So a match on
	 * vendor and product with a DIFFERENT GUID is a second view of one device, and a match on both
	 * is a second device, which is left alone.
	 * <p>
	 * Pass -Dcontrolify.sdl.dedupe=0 to take every enumeration as its own controller, as it was
	 * before any of this.
	 */
	private Optional<ControllerEntity> findDuplicated(ControllerHIDInfo hidInfo) {
		if ("0".equals(System.getProperty("controlify.sdl.dedupe", "1"))) return Optional.empty();

		Optional<HIDDevice> arriving = hidInfo.hidDevice();
		if (arriving.isEmpty()) return Optional.empty();

		for (ControllerEntity existing : controllersByJid.values()) {
			Optional<HIDDevice> have = existing.info().hid();
			if (have.isEmpty()) continue;
			if (!have.get().hidid().equals(arriving.get().hidid())) continue;
			// Same vendor and product AND same GUID means a second pad of the same model. Keep it.
			if (have.get().path().equals(arriving.get().path())) continue;
			return Optional.of(existing);
		}
		return Optional.empty();
	}

	/**
	 * Everything SDL will say about how a joystick is attached.
	 * <p>
	 * The connection state is the direct answer, but SDL only fills it in for devices that come
	 * through its HIDAPI drivers - on a pad handled by XInput or GameInput it is simply unknown,
	 * which is what happens here. So the power state is collected alongside it: a pad running off
	 * the dongle should report that it is on battery, while one on a cable should report charging,
	 * charged, or no battery at all. The device path and serial are recorded too, because a direct
	 * USB interface and a receiver are different pieces of hardware and need not name themselves
	 * the same way.
	 * <p>
	 * None of this is acted on yet. It is logged so that which of these actually tells the two
	 * connections apart on real hardware can be read off rather than assumed - assuming the
	 * connection state would work, purely because the function existed, is what made the previous
	 * attempt at this useless.
	 */
	private record Probe(int connection, int power, int percent, String path, String serial) {
		static final Probe NOTHING = new Probe(SdlJoystick.SDL_JOYSTICK_CONNECTION_UNKNOWN,
				SdlGamepad.SDL_POWERSTATE_UNKNOWN, -1, null, null);

		String describe() {
			return "[power=" + powerName(power) + (percent >= 0 ? " " + percent + "%" : "")
					+ ", path=" + (path == null || path.isEmpty() ? "none" : path)
					+ ", serial=" + (serial == null || serial.isEmpty() ? "none" : serial) + "]";
		}

		private static String powerName(int state) {
			return switch (state) {
				case SdlGamepad.SDL_POWERSTATE_ON_BATTERY -> "on battery";
				case SdlGamepad.SDL_POWERSTATE_NO_BATTERY -> "no battery";
				case SdlGamepad.SDL_POWERSTATE_CHARGING -> "charging";
				case SdlGamepad.SDL_POWERSTATE_CHARGED -> "charged";
				case SdlGamepad.SDL_POWERSTATE_ERROR -> "error";
				default -> "unknown";
			};
		}
	}

	/**
	 * Opens the joystick briefly to ask about it, then closes it again. SDL has no variant of most
	 * of these that takes an id, and this runs once as each joystick appears, before anything else
	 * is done with it, so it never overlaps the handle a controller really runs on. Anything that
	 * goes wrong is reported as nothing known rather than thrown - failing to describe a connection
	 * is not a reason to refuse the controller.
	 */
	private Probe probe(SdlJoystickId jid) {
		String path = null;
		try {
			path = sdl.joystick().SDL_GetJoystickPathForID(jid);
		} catch (Throwable ignored) {
			// Path is the one thing askable without opening anything; losing it is not worth a warning.
		}

		try {
			SdlJoystickHandle handle = sdl.joystick().SDL_OpenJoystick(jid);
			if (handle == null) return new Probe(SdlJoystick.SDL_JOYSTICK_CONNECTION_UNKNOWN,
					SdlGamepad.SDL_POWERSTATE_UNKNOWN, -1, path, null);
			try {
				SdlRefs.IntRef percent = new SdlRefs.IntRef(-1);
				int power = sdl.joystick().SDL_GetJoystickPowerInfo(handle, percent);
				return new Probe(
						sdl.joystick().SDL_GetJoystickConnectionState(handle),
						power,
						percent.value,
						path,
						sdl.joystick().SDL_GetJoystickSerial(handle));
			} finally {
				sdl.joystick().SDL_CloseJoystick(handle);
			}
		} catch (Throwable e) {
			logger.warn("Could not read the connection details of joystick {}", e, jid.value());
			return Probe.NOTHING;
		}
	}

	private static String transportName(int state) {
		if (state == SdlJoystick.SDL_JOYSTICK_CONNECTION_WIRED) return "a wired connection";
		if (state == SdlJoystick.SDL_JOYSTICK_CONNECTION_WIRELESS) return "a wireless connection";
		return "an unknown connection";
	}

	/**
	 * One joystick as SDL currently describes it, for the dev panel to show.
	 *
	 * @param registered whether this is the one Controlify is actually reading, as opposed to a
	 *                   duplicate view of it that was ignored
	 */
	public record Connection(String id, String name, String guid, String path, String power, boolean registered) {
	}

	/**
	 * Every joystick attached right now, however it is attached.
	 * <p>
	 * The path is the useful part: the connection state is unknown on pads handled by XInput or
	 * GameInput, and a pad's GUID is the same whether it arrives over a cable or a receiver, but
	 * those are different pieces of hardware and carry different paths. Which path means which is
	 * not something SDL will say, so it is not guessed at here - it is reported, and the dev panel
	 * compares it against what it has been told.
	 */
	public static List<Connection> connections() {
		SDLControllerManager manager = instance;
		return manager == null ? List.of() : manager.describeConnections();
	}

	/**
	 * The device path of every joystick attached right now, and nothing else, in the order SDL
	 * lists them. Unlike {@link #connections()} this opens nothing, so it is cheap enough to ask
	 * every tick - the dev panel does, to know when its Learn buttons have something to record.
	 */
	/** The SDL the controllers come from - what the touch pad attaches its virtual gamepad to (tl111). Null before init. */
	public static @Nullable Sdl sdl() {
		SDLControllerManager manager = instance;
		return manager == null ? null : manager.sdl;
	}

	public static List<String> attachedPaths() {
		SDLControllerManager manager = instance;
		return manager == null ? List.of() : manager.listPaths();
	}

	/** Milliseconds since a joystick of any kind last arrived or left. */
	public static long millisSinceHotplug() {
		return (System.nanoTime() - lastHotplugNanos) / 1_000_000L;
	}

	private List<String> listPaths() {
		List<String> out = new ArrayList<>();
		for (SdlJoystickId jid : sdl.joystick().SDL_GetJoysticks()) {
			try {
				String path = sdl.joystick().SDL_GetJoystickPathForID(jid);
				if (path != null && !path.isEmpty()) out.add(path);
			} catch (Throwable ignored) {
				// As in probe: a path that cannot be read is simply not listed.
			}
		}
		return out;
	}

	private List<Connection> describeConnections() {
		List<Connection> out = new ArrayList<>();
		for (SdlJoystickId jid : sdl.joystick().SDL_GetJoysticks()) {
			UniqueControllerID ucid = new SDLUniqueControllerID(jid);
			Probe probe = probe(jid);
			SdlGuid guid = sdl.joystick().SDL_GetJoystickGUIDForID(jid);
			out.add(new Connection(
					ucid.toString(),
					String.valueOf(sdl.joystick().SDL_GetJoystickNameForID(jid)),
					guid == null ? "" : guid.toString(),
					probe.path() == null ? "" : probe.path(),
					Probe.powerName(probe.power()),
					controllersByJid.containsKey(ucid)));
		}
		return out;
	}

	private static Optional<ControllerHIDInfo> fetchTypeFromSDL(Sdl sdl, SdlJoystickId jid) {
		int vid = sdl.joystick().SDL_GetJoystickVendorForID(jid);
		int pid = sdl.joystick().SDL_GetJoystickProductForID(jid);
		SdlGuid guid = sdl.joystick().SDL_GetJoystickGUIDForID(jid);
		String guidStr = guid.toString();

		if (vid != 0 && pid != 0) {
			CUtil.LOGGER.log("Using SDL to identify controller type.");
			return Optional.of(new ControllerHIDInfo(
					Controlify.instance().controllerTypeManager().getControllerType(new HIDID(vid, pid)),
					Optional.of(new HIDDevice(new HIDID(vid, pid), guidStr))
			));
		}

		return Optional.empty();
	}

	public record SDLUniqueControllerID(@NotNull SdlJoystickId jid) implements UniqueControllerID {
		@Override
		public boolean equals(Object obj) {
			return obj instanceof SDLUniqueControllerID && ((SDLUniqueControllerID) obj).jid.equals(jid);
		}

		@Override
		public String toString() {
			return "SDL-" + jid.value();
		}

		@Override
		public int hashCode() {
			return Objects.hash(jid.value());
		}
	}

	private static class EventFilter implements SdlCallbacks.EventFilter {
		@Override
		public boolean filter(SdlPointer userdata, SdlEvent event) {
			return switch (event.type()) {
				case SDL_EVENT_JOYSTICK_ADDED,
					SDL_EVENT_JOYSTICK_REMOVED -> true;
				default -> false;
			};
		}
	}
}
