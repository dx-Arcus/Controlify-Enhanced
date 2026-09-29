/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.gui.screen;

import dev.isxander.controlify.Controlify;
import dev.isxander.controlify.api.bind.InputBindingSupplier;
import dev.isxander.controlify.bindings.ControlifyBindings;
import dev.isxander.controlify.controller.ControllerEntity;
import dev.isxander.controlify.controller.haptic.HapticEffects;
import dev.isxander.controlify.controller.input.ControllerStateView;
import dev.isxander.controlify.controller.input.GamepadInputs;
import dev.isxander.controlify.controller.input.InputComponent;
import dev.isxander.controlify.screenop.ComponentProcessor;
import dev.isxander.controlify.screenop.ScreenProcessor;
import dev.isxander.controlify.utils.HoldRepeatHelper;
import dev.isxander.controlify.utils.MinecraftUtil;
import dev.isxander.controlify.utils.render.RainbowText;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.jspecify.annotations.NonNull;

import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Picks one or more colors, each off its own hue/saturation wheel with a brightness column beside
 * it. The marker and the compass bar share this screen rather than having one each, so their
 * colors can be chosen against each other instead of by memory.
 * <p>
 * Each wheel is drawn as a grid of small squares rather than uploaded as a texture: it costs a few
 * thousand rectangles a frame on a settings screen, which is nothing, and it needs no more of the
 * renderer than drawing a rectangle - the same bet the marker itself makes.
 * <p>
 * The disc and the brightness column are real widgets rather than regions the screen hit-tests, so
 * a controller can reach them the same way it reaches any button: they turn up in the focus order,
 * and once focused the right stick moves the point around. Doing it with the secondary stick means
 * the left stick still moves focus off the wheel, so no mode has to be entered or left.
 */
public class ColorWheelScreen extends Screen {
	private static final int WHEEL_RADIUS = 56;
	/** Size of each square a wheel is built from. Smaller is smoother and costs more. */
	private static final int CELL = 2;

	private static final int BAR_WIDTH = 16;
	private static final int BAR_GAP = 20;
	private static final int SWATCH_HEIGHT = 20;
	private static final int COLUMN_GAP = 56;
	private static final int RESET_WIDTH = 96;
	private static final int DONE_WIDTH = 150;
	/** Wheel plus the gap and brightness column beside it. */
	private static final int COLUMN_WIDTH = WHEEL_RADIUS * 2 + BAR_GAP + BAR_WIDTH;

	/** How far one push of the stick moves the point on the disc, in pixels. */
	private static final int DISC_STEP = 2;
	/** How much one push changes brightness, as a fraction of the column. */
	private static final float BAR_STEP = 0.02f;
	/** Under this the stick counts as centred, so a thumb resting on it does not drift. */
	private static final float POINTER_DEADZONE = 0.1f;
	/** The longest a single frame may count for, so a hitch does not fling the pointer. */
	private static final double LONGEST_FRAME_SECONDS = 0.05;
	/** The outline round a wheel that has been stepped into, as opposed to merely focused. */
	private static final int COLOR_ENTERED = 0xFFFFC107;

	/**
	 * One color being edited: where it lives on screen, the setting behind it, and the hue,
	 * saturation and brightness currently showing. Everything a wheel needs is here, so the screen
	 * itself only has to decide which one the mouse is over.
	 */
	private static final class Wheel {
		private final Component label;
		private final int defaultColor;
		private final IntSupplier get;
		private final IntConsumer set;
		private final int originalColor;

		private float hue;
		private float saturation;
		private float value;

		private int wheelX;
		private int wheelY;
		private int barX;
		private int barTop;
		private int barBottom;
		private int labelY;

		Wheel(Component label, int defaultColor, IntSupplier get, IntConsumer set) {
			this.label = label;
			this.defaultColor = defaultColor;
			this.get = get;
			this.set = set;
			this.originalColor = get.getAsInt();
			setFromRgb(get.getAsInt());
		}

		boolean changed() {
			return get.getAsInt() != originalColor;
		}

		/**
		 * Puts the point at a position relative to the middle of the disc, in pixels. Anything
		 * past the rim is pulled back onto it rather than refused, so dragging out of the disc
		 * pins the color at full saturation instead of dropping the drag.
		 */
		void pointAt(double dx, double dy) {
			double distance = Math.sqrt(dx * dx + dy * dy);
			if (distance > WHEEL_RADIUS) {
				dx = dx / distance * WHEEL_RADIUS;
				dy = dy / distance * WHEEL_RADIUS;
				distance = WHEEL_RADIUS;
			}
			hue = (float) ((Math.toDegrees(Math.atan2(dy, dx)) + 360) % 360 / 360.0);
			saturation = (float) (distance / WHEEL_RADIUS);
			apply();
		}

		/** Where the point currently sits, in the same pixels {@link #pointAt} takes. */
		double pointX() {
			return Math.cos(Math.toRadians(hue * 360)) * saturation * WHEEL_RADIUS;
		}

		double pointY() {
			return Math.sin(Math.toRadians(hue * 360)) * saturation * WHEEL_RADIUS;
		}

		void setValue(double v) {
			value = (float) Mth.clamp(v, 0, 1);
			apply();
		}

		int currentRgb() {
			return hsvToRgb(hue, saturation, value);
		}

		void apply() {
			set.accept(currentRgb());
		}

		void setFromRgb(int rgb) {
			float r = ((rgb >> 16) & 0xFF) / 255f;
			float g = ((rgb >> 8) & 0xFF) / 255f;
			float b = (rgb & 0xFF) / 255f;
			float max = Math.max(r, Math.max(g, b));
			float min = Math.min(r, Math.min(g, b));
			float delta = max - min;

			if (delta <= 0.0001f) {
				hue = 0;
			} else if (max == r) {
				hue = ((g - b) / delta % 6) / 6f;
			} else if (max == g) {
				hue = ((b - r) / delta + 2) / 6f;
			} else {
				hue = ((r - g) / delta + 4) / 6f;
			}
			if (hue < 0) {
				hue += 1;
			}
			saturation = max <= 0 ? 0 : delta / max;
			value = max;
			set.accept(rgb);
		}
	}

	private final Screen parent;
	private final Component resetLabel;
	private final List<Wheel> wheels;

	private ColorWheelScreen(Screen parent, Component title, Component resetLabel, List<Wheel> wheels) {
		super(title);
		this.parent = parent;
		this.resetLabel = resetLabel;
		this.wheels = wheels;
	}

	/** The way in: callers describe the colors they want and never touch the wheels themselves. */
	public static ColorWheelScreen of(Screen parent, Component title, Component resetLabel,
			List<Entry> entries) {
		return new ColorWheelScreen(parent, title, resetLabel,
				entries.stream().map(e -> new Wheel(e.label(), e.defaultColor(), e.get(), e.set())).toList());
	}

	/** One color offered on this screen: what to call it, where it resets to, and how to read and write it. */
	public record Entry(Component label, int defaultColor, IntSupplier get, IntConsumer set) {
	}

	@Override
	protected void init() {
		int count = Math.max(1, wheels.size());
		int blockWidth = count * COLUMN_WIDTH + (count - 1) * COLUMN_GAP;
		int blockLeft = width / 2 - blockWidth / 2;
		// Anchored from the top rather than centred: the columns are tall, and a short window
		// should lose the space under the swatch rather than push the wheels off the top.
		int top = Math.max(40, height / 2 - 108);

		for (int i = 0; i < wheels.size(); i++) {
			Wheel wheel = wheels.get(i);
			int left = blockLeft + i * (COLUMN_WIDTH + COLUMN_GAP);
			wheel.labelY = top;
			wheel.wheelX = left + WHEEL_RADIUS;
			wheel.wheelY = top + 16 + WHEEL_RADIUS;
			wheel.barX = left + WHEEL_RADIUS * 2 + BAR_GAP;
			wheel.barTop = wheel.wheelY - WHEEL_RADIUS;
			wheel.barBottom = wheel.wheelY + WHEEL_RADIUS;

			addRenderableWidget(new DiscWidget(wheel));
			addRenderableWidget(new BarWidget(wheel));

			addRenderableWidget(Button.builder(resetLabel, b -> wheel.setFromRgb(wheel.defaultColor))
					.bounds(left + COLUMN_WIDTH / 2 - RESET_WIDTH / 2,
							wheel.barBottom + 14 + SWATCH_HEIGHT + 20, RESET_WIDTH, 20)
					.build());
		}

		addRenderableWidget(Button.builder(RainbowText.of(CommonComponents.GUI_DONE, 4), b -> onClose())
				.bounds(width / 2 - DONE_WIDTH / 2, height - 28, DONE_WIDTH, 20)
				.build());
	}

	@Override
	public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);

		graphics.text(font, title, width / 2 - font.width(title) / 2, 18, 0xFFFFFFFF);

		for (Wheel wheel : wheels) {
			int left = wheel.wheelX - WHEEL_RADIUS;
			graphics.text(font, wheel.label,
					left + COLUMN_WIDTH / 2 - font.width(wheel.label) / 2, wheel.labelY, 0xFFFFFFFF);

			int color = wheel.currentRgb();
			int swatchY = wheel.barBottom + 14;
			graphics.fill(left - 1, swatchY - 1, left + COLUMN_WIDTH + 1, swatchY + SWATCH_HEIGHT + 1, 0xFF000000);
			graphics.fill(left, swatchY, left + COLUMN_WIDTH, swatchY + SWATCH_HEIGHT, 0xFF000000 | color);

			String hex = String.format("#%06X", color);
			graphics.text(font, hex, left + COLUMN_WIDTH / 2 - font.width(hex) / 2,
					swatchY + SWATCH_HEIGHT + 6, 0xFFFFFFFF);
		}
	}

	/**
	 * The disc's cells, worked out once: where each sits relative to the middle, and its hue and
	 * saturation, which never change - only the brightness they are drawn at does. Row by row from
	 * the top, left to right, cells outside the rim left out.
	 */
	private static final class Cells {
		static final int[] DX;
		static final int[] DY;
		static final float[] HUE;
		static final float[] SATURATION;

		static {
			int side = WHEEL_RADIUS * 2 / CELL + 1;
			int[] dxs = new int[side * side];
			int[] dys = new int[side * side];
			float[] hues = new float[side * side];
			float[] saturations = new float[side * side];
			int count = 0;
			for (int dy = -WHEEL_RADIUS; dy <= WHEEL_RADIUS; dy += CELL) {
				for (int dx = -WHEEL_RADIUS; dx <= WHEEL_RADIUS; dx += CELL) {
					double distance = Math.sqrt(dx * dx + dy * dy);
					if (distance > WHEEL_RADIUS) {
						continue;
					}
					dxs[count] = dx;
					dys[count] = dy;
					hues[count] = (float) ((Math.toDegrees(Math.atan2(dy, dx)) + 360) % 360 / 360.0);
					saturations[count] = (float) (distance / WHEEL_RADIUS);
					count++;
				}
			}
			DX = Arrays.copyOf(dxs, count);
			DY = Arrays.copyOf(dys, count);
			HUE = Arrays.copyOf(hues, count);
			SATURATION = Arrays.copyOf(saturations, count);
		}
	}

	private void drawWheel(GuiGraphicsExtractor graphics, Wheel wheel) {
		for (int i = 0; i < Cells.DX.length; i++) {
			int dx = Cells.DX[i];
			int dy = Cells.DY[i];
			graphics.fill(wheel.wheelX + dx, wheel.wheelY + dy, wheel.wheelX + dx + CELL, wheel.wheelY + dy + CELL,
					0xFF000000 | hsvToRgb(Cells.HUE[i], Cells.SATURATION[i], wheel.value));
		}

		double angle = Math.toRadians(wheel.hue * 360);
		int markerX = wheel.wheelX + (int) Math.round(Math.cos(angle) * wheel.saturation * WHEEL_RADIUS);
		int markerY = wheel.wheelY + (int) Math.round(Math.sin(angle) * wheel.saturation * WHEEL_RADIUS);
		drawRing(graphics, markerX, markerY, 4);
	}

	private void drawBrightnessBar(GuiGraphicsExtractor graphics, Wheel wheel) {
		graphics.fill(wheel.barX - 1, wheel.barTop - 1, wheel.barX + BAR_WIDTH + 1, wheel.barBottom + 1, 0xFF000000);
		int steps = wheel.barBottom - wheel.barTop;
		for (int i = 0; i < steps; i++) {
			float barValue = 1 - (float) i / steps;
			graphics.fill(wheel.barX, wheel.barTop + i, wheel.barX + BAR_WIDTH, wheel.barTop + i + 1,
					0xFF000000 | hsvToRgb(wheel.hue, wheel.saturation, barValue));
		}
		int handleY = wheel.barTop + Math.round((1 - wheel.value) * steps);
		graphics.fill(wheel.barX - 3, handleY - 1, wheel.barX + BAR_WIDTH + 3, handleY + 1, 0xFFFFFFFF);
	}

	/** A small hollow square, so the marker stays visible over any color underneath it. */
	private void drawRing(GuiGraphicsExtractor graphics, int x, int y, int radius) {
		graphics.fill(x - radius, y - radius, x + radius, y - radius + 1, 0xFF000000);
		graphics.fill(x - radius, y + radius - 1, x + radius, y + radius, 0xFF000000);
		graphics.fill(x - radius, y - radius, x - radius + 1, y + radius, 0xFF000000);
		graphics.fill(x + radius - 1, y - radius, x + radius, y + radius, 0xFF000000);
		graphics.fill(x - radius + 1, y - radius + 1, x + radius - 1, y - radius + 2, 0xFFFFFFFF);
		graphics.fill(x - radius + 1, y + radius - 2, x + radius - 1, y + radius - 1, 0xFFFFFFFF);
		graphics.fill(x - radius + 1, y - radius + 1, x - radius + 2, y + radius - 1, 0xFFFFFFFF);
		graphics.fill(x + radius - 2, y - radius + 1, x + radius - 1, y + radius - 1, 0xFFFFFFFF);
	}

	@Override
	public void onClose() {
		if (wheels.stream().anyMatch(Wheel::changed)) {
			Controlify.instance().config().saveSafely();
		}
		MinecraftUtil.setScreen(parent);
	}

	/**
	 * Shared behaviour for the two things on this screen that hold a value rather than perform an
	 * action: they take a press or a drag from the mouse, and the secondary stick from a
	 * controller.
	 * <p>
	 * Implementing {@link ComponentProcessor} on the widget itself rather than registering one is
	 * all that is needed for Controlify to find it - the lookup checks the widget first, and only
	 * falls back to its registry for classes it cannot change, which is to say vanilla's.
	 */
	private abstract static class PickerWidget extends AbstractWidget implements ComponentProcessor {
		/** Slower to start than the screen's own, so a nudge is a nudge and a hold is a sweep. */
		private final HoldRepeatHelper repeat = new HoldRepeatHelper(10, 1);
		protected final Wheel wheel;

		/**
		 * Whether A has been pressed on this one. Until it has, the wheel is only focused: the
		 * left stick still moves between wheels and nothing here touches the color. That is the
		 * whole point of the change - the stick used to alter whatever it happened to be over.
		 */
		private boolean entered;
		/** Where the pointer is, in screen pixels. Always exactly where the marker is. */
		private double pointerX;
		private double pointerY;
		/** How hard the left stick is pushed, as of the last controller update. */
		private double stickX;
		private double stickY;
		/** When the pointer was last moved, so the next frame gets its own share of time. */
		private long lastPointerNanos;

		PickerWidget(Wheel wheel, int x, int y, int width, int height) {
			super(x, y, width, height, wheel.label);
			this.wheel = wheel;
		}

		/** Move the value one step for each direction the d-pad is pushed. */
		protected abstract void nudge(int dx, int dy);

		/** Put the value where the pointer is. */
		protected abstract void grab(double mouseX, double mouseY);

		/** Where the marker sits now, in screen pixels - the inverse of {@link #grab}. */
		protected abstract double markerX();

		protected abstract double markerY();

		protected abstract void paint(GuiGraphicsExtractor graphics);

		@Override
		public void onClick(@NonNull MouseButtonEvent event, boolean doubleClick) {
			grab(event.x(), event.y());
		}

		@Override
		protected void onDrag(@NonNull MouseButtonEvent event, double dragX, double dragY) {
			grab(event.x(), event.y());
		}

		/**
		 * Moves the pointer on by however much time has passed, at whatever speed the left stick
		 * is asking for, and puts the color wherever it lands.
		 * <p>
		 * Done here, in the draw, rather than where the controller is read: controller updates
		 * arrive on the client tick, twenty a second, and this is drawn far more often than that.
		 * Moving a tick's worth in one go is what made the old version step - it moved one notch
		 * per push and had to be recentred for the next, which is what this replaces.
		 * <p>
		 * The pointer is read back off the marker every time rather than kept separately, so
		 * whatever clamping {@link #grab} does - the rim of the disc, the ends of the column -
		 * owns the pointer too, and the two can never drift apart.
		 */
		private void movePointer() {
			long now = System.nanoTime();
			long since = lastPointerNanos == 0 ? 0 : now - lastPointerNanos;
			lastPointerNanos = now;
			if (!entered || since <= 0 || (stickX == 0 && stickY == 0)) {
				return;
			}
			double speed = Controlify.instance().config().getSettings().globalSettings().colorPointerSpeed;
			double seconds = Math.min(since / 1.0e9, LONGEST_FRAME_SECONDS);
			pointerX += stickX * speed * seconds;
			pointerY += stickY * speed * seconds;
			grab(pointerX, pointerY);
			attachPointer();
		}

		/** Puts the pointer exactly on the marker, which is what stops entering from moving it. */
		private void attachPointer() {
			pointerX = markerX();
			pointerY = markerY();
		}

		@Override
		public void extractWidgetRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
			movePointer();
			paint(graphics);
			if (entered) {
				// Two rings rather than one, and in a different color: this one is being edited,
				// not merely pointed at, and that has to be obvious before the stick is touched.
				graphics.outline(getX() - 2, getY() - 2, getWidth() + 4, getHeight() + 4, COLOR_ENTERED);
				graphics.outline(getX() - 3, getY() - 3, getWidth() + 6, getHeight() + 6, COLOR_ENTERED);
			} else if (isFocused()) {
				// The same job vanilla's focus border does on a button: say which one A would
				// step into, without covering the colors being chosen.
				graphics.outline(getX() - 2, getY() - 2, getWidth() + 4, getHeight() + 4, 0xFFFFFFFF);
			}
		}

		/** Losing the focus by any route - a mouse click elsewhere, a rebuild - also lets go. */
		@Override
		public void setFocused(boolean focused) {
			super.setFocused(focused);
			if (!focused) {
				leave();
			}
		}

		private void enter() {
			entered = true;
			stickX = 0;
			stickY = 0;
			attachPointer();
		}

		private void leave() {
			entered = false;
			stickX = 0;
			stickY = 0;
		}

		/**
		 * Whether a sideways push means anything here. A widget that says no never reads the
		 * horizontal pair at all, so a column is not nudged sideways by a thumb that wandered.
		 */
		protected boolean usesHorizontal() {
			return true;
		}

		/**
		 * While this wheel has not been stepped into, nothing is taken: the left stick and the
		 * d-pad move the focus between wheels exactly as they would anywhere else.
		 * <p>
		 * Once it has, everything is taken, whether it moved anything or not. The left stick is
		 * only recorded here and spent in the draw, so it moves smoothly rather than in notches;
		 * the d-pad steps the value by a fixed amount, which is what makes the brightness columns
		 * adjustable without the stick at all. Handing anything back would move the focus off a
		 * wheel that is in the middle of being edited.
		 */
		@Override
		public boolean overrideControllerNavigation(ScreenProcessor<?> screen, ControllerEntity controller) {
			boolean repeatAvailable = repeat.canNavigate();
			if (!entered) {
				return false;
			}

			stickX = usesHorizontal()
					? push(controller, ControlifyBindings.GUI_NAVI_RIGHT, ControlifyBindings.GUI_NAVI_LEFT)
					: 0;
			stickY = push(controller, ControlifyBindings.GUI_NAVI_DOWN, ControlifyBindings.GUI_NAVI_UP);

			InputComponent input = controller.input().orElseThrow();
			ControllerStateView now = input.stateNow();
			ControllerStateView then = input.stateThen();
			int dx = usesHorizontal()
					? step(now, then, GamepadInputs.DPAD_RIGHT_BUTTON, GamepadInputs.DPAD_LEFT_BUTTON, repeatAvailable)
					: 0;
			int dy = step(now, then, GamepadInputs.DPAD_DOWN_BUTTON, GamepadInputs.DPAD_UP_BUTTON, repeatAvailable);
			if (dx != 0 || dy != 0) {
				if (freshPress) {
					repeat.reset();
				}
				repeat.onNavigate();
				nudge(dx, dy);
				attachPointer();
			}
			return true;
		}

		/**
		 * A steps into the wheel under the focus; B steps back out of it. A also steps out, for
		 * anyone who presses the button they came in with rather than the one they were told.
		 * <p>
		 * Everything is taken while inside, which is what stops B closing the whole screen at the
		 * moment it is being used to finish with one color.
		 */
		@Override
		public boolean overrideControllerButtons(ScreenProcessor<?> screen, ControllerEntity controller) {
			boolean press = ControlifyBindings.GUI_PRESS.on(controller).guiPressed().get();
			boolean back = ControlifyBindings.GUI_BACK.on(controller).guiPressed().get();
			if (!entered) {
				if (!press) {
					// Claimed only when actually used, so B still closes the screen from here.
					return false;
				}
				enter();
				feedback(controller);
				return true;
			}
			if (press || back) {
				leave();
				feedback(controller);
			}
			return true;
		}

		private void feedback(ControllerEntity controller) {
			controller.hdHaptics().ifPresent(haptics -> haptics.playHaptic(HapticEffects.NAVIGATE));
			playDownSound(Minecraft.getInstance().getSoundManager());
		}

		/** How far the stick is pushed one way less the other, eased so a lean is a crawl. */
		private double push(ControllerEntity controller, InputBindingSupplier positive,
				InputBindingSupplier negative) {
			return curve(positive.on(controller).analogueNow()) - curve(negative.on(controller).analogueNow());
		}

		private static double curve(float amount) {
			return amount < POINTER_DEADZONE ? 0 : (double) amount * amount;
		}

		/** Set while reading the d-pad if that direction was only just pushed, rather than held. */
		private boolean freshPress;

		private int step(ControllerStateView now, ControllerStateView then, Identifier positive, Identifier negative,
				boolean repeatAvailable) {
			freshPress = false;
			int result = 0;
			for (int sign : new int[]{1, -1}) {
				Identifier button = sign > 0 ? positive : negative;
				boolean down = now.isButtonDown(button);
				boolean was = then.isButtonDown(button);
				if (down && (repeatAvailable || !was)) {
					result += sign;
					if (!was) {
						freshPress = true;
					}
				}
			}
			return result;
		}

		@Override
		protected void updateWidgetNarration(@NonNull NarrationElementOutput output) {
			output.add(NarratedElementType.TITLE, getMessage());
		}
	}

	/** The hue and saturation disc. Hue is the angle round it, saturation the distance out. */
	private final class DiscWidget extends PickerWidget {
		DiscWidget(Wheel wheel) {
			super(wheel, wheel.wheelX - WHEEL_RADIUS, wheel.wheelY - WHEEL_RADIUS,
					WHEEL_RADIUS * 2, WHEEL_RADIUS * 2);
		}

		@Override
		protected void nudge(int dx, int dy) {
			wheel.pointAt(wheel.pointX() + dx * DISC_STEP, wheel.pointY() + dy * DISC_STEP);
		}

		@Override
		protected void grab(double mouseX, double mouseY) {
			wheel.pointAt(mouseX - wheel.wheelX, mouseY - wheel.wheelY);
		}

		@Override
		protected double markerX() {
			return wheel.wheelX + wheel.pointX();
		}

		@Override
		protected double markerY() {
			return wheel.wheelY + wheel.pointY();
		}

		@Override
		protected void paint(GuiGraphicsExtractor graphics) {
			drawWheel(graphics, wheel);
		}
	}

	/** The brightness column beside a disc. */
	private final class BarWidget extends PickerWidget {
		BarWidget(Wheel wheel) {
			super(wheel, wheel.barX - 3, wheel.barTop - 1, BAR_WIDTH + 6, wheel.barBottom - wheel.barTop + 2);
		}

		@Override
		protected boolean usesHorizontal() {
			return false;
		}

		@Override
		protected void nudge(int dx, int dy) {
			// Up is brighter, which is the way the column is drawn.
			wheel.setValue(wheel.value - dy * BAR_STEP);
		}

		@Override
		protected void grab(double mouseX, double mouseY) {
			wheel.setValue(1 - (mouseY - wheel.barTop) / (double) (wheel.barBottom - wheel.barTop));
		}

		@Override
		protected double markerX() {
			return wheel.barX + BAR_WIDTH / 2.0;
		}

		@Override
		protected double markerY() {
			return wheel.barTop + (1 - wheel.value) * (wheel.barBottom - wheel.barTop);
		}

		@Override
		protected void paint(GuiGraphicsExtractor graphics) {
			drawBrightnessBar(graphics, wheel);
		}
	}

	private static int hsvToRgb(float h, float s, float v) {
		int sector = (int) (h * 6) % 6;
		float f = h * 6 - (int) (h * 6);
		float p = v * (1 - s);
		float q = v * (1 - f * s);
		float t = v * (1 - (1 - f) * s);
		float r;
		float g;
		float b;
		switch (sector) {
			case 0 -> { r = v; g = t; b = p; }
			case 1 -> { r = q; g = v; b = p; }
			case 2 -> { r = p; g = v; b = t; }
			case 3 -> { r = p; g = q; b = v; }
			case 4 -> { r = t; g = p; b = v; }
			default -> { r = v; g = p; b = q; }
		}
		return Math.round(r * 255) << 16 | Math.round(g * 255) << 8 | Math.round(b * 255);
	}
}
