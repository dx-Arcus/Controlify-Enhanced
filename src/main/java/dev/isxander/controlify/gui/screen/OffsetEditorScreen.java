/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.gui.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * What the two screens that move something about the HUD have in common - {@link GuideOffsetEditScreen}
 * for the button guides, {@link CompassLayoutScreen} for the compass bar: a directional pad, typed
 * offsets with coarse jumps beside them, corner snaps, and the focus rule the jump buttons need. Each
 * screen lays these out and says what they do; this is the vocabulary, so there is one of it rather
 * than two copies to keep in step.
 */
abstract class OffsetEditorScreen extends Screen {
	protected static final int STEP = 1;
	protected static final int BUTTON_SIZE = 20;
	protected static final int FOOTER_BUTTON_WIDTH = 150;

	protected static final int OFFSET_BOX_WIDTH = 50;
	protected static final int OFFSET_BOX_HEIGHT = 16;
	protected static final int OFFSET_BOX_GAP = 6;
	protected static final int OFFSET_ROW_WIDTH = OFFSET_BOX_WIDTH * 2 + OFFSET_BOX_GAP;

	// A 2x2 of coarse jumps either side of each number box: +5 / -5 over +10 / -10. Wide enough for
	// "-10" with room to spare, since a button you have to aim at is worse than no button.
	protected static final int STEP_BUTTON_WIDTH = 22;
	protected static final int STEP_BUTTON_HEIGHT = 12;
	protected static final int STEP_BUTTON_GAP = 2;
	protected static final int STEP_BLOCK_WIDTH = STEP_BUTTON_WIDTH * 2 + STEP_BUTTON_GAP;
	protected static final int STEP_BLOCK_HEIGHT = STEP_BUTTON_HEIGHT * 2 + STEP_BUTTON_GAP;
	/** Gap between a block and the box it drives. */
	protected static final int STEP_BLOCK_MARGIN = 3;

	protected static final int CORNER_BUTTON_WIDTH = 22;
	protected static final int CORNER_BUTTON_HEIGHT = 13;
	protected static final int CORNER_BUTTON_GAP = 2;
	protected static final int CORNER_GRID_WIDTH = CORNER_BUTTON_WIDTH * 2 + CORNER_BUTTON_GAP;

	/** What a corner button does: put the thing flush against that corner. */
	protected interface CornerSnap {
		void snap(boolean top, boolean rightEdge);
	}

	/** The start of this screen's translation keys, for the texts the shared controls carry. */
	private final String keyPrefix;

	/**
	 * The coarse jump buttons, kept so focus can be told apart from the rest. See
	 * {@link #setFocused(GuiEventListener)}.
	 */
	private final Set<GuiEventListener> stepButtons = new HashSet<>();

	protected OffsetEditorScreen(Component title, String keyPrefix) {
		super(title);
		this.keyPrefix = keyPrefix;
	}

	/**
	 * Vanilla leaves focus on whatever was last clicked, and a focused button is drawn in its lit
	 * state - so on a block of four small buttons the last one pressed stays lit until something
	 * else is pressed, which reads as a selection rather than as where the keyboard is. Focus
	 * landing on one of them from a mouse click is dropped here; focus from the keyboard or a
	 * controller, which is the case the ring is actually for, is kept.
	 * <p>
	 * It has to be done here rather than in the button's own press handler: vanilla runs the press
	 * first and sets focus afterwards, so anything the handler cleared would be put straight back.
	 */
	@Override
	public void setFocused(@Nullable GuiEventListener focused) {
		if (focused != null && stepButtons.contains(focused)
				&& Minecraft.getInstance().getLastInputType().isMouse()) {
			super.setFocused(null);
			return;
		}
		super.setFocused(focused);
	}

	/** Forgets the jump buttons of the last layout; the first thing {@code init} does. */
	protected void clearStepButtons() {
		stepButtons.clear();
	}

	/** Keeps the number boxes showing the offsets after anything that changed them other than typing. */
	protected abstract void syncEditBoxes();

	/**
	 * The directional pad: a step each way, and in the middle the reset, each followed by the boxes
	 * catching up.
	 *
	 * @param resetTooltipKey what the middle button's tooltip says
	 */
	protected void addDirectionalPad(int gridX, int gridY, Runnable onUp, Runnable onDown, Runnable onLeft, Runnable onRight,
			Runnable onReset, String resetTooltipKey) {
		int s = BUTTON_SIZE;
		addRenderableWidget(Button.builder(Component.literal("▲"), b -> { onUp.run(); syncEditBoxes(); })
				.bounds(gridX + s, gridY, s, s)
				.build());
		addRenderableWidget(Button.builder(Component.literal("◄"), b -> { onLeft.run(); syncEditBoxes(); })
				.bounds(gridX, gridY + s, s, s)
				.build());
		addRenderableWidget(Button.builder(Component.literal("⟲"), b -> { onReset.run(); syncEditBoxes(); })
				.bounds(gridX + s, gridY + s, s, s)
				.tooltip(Tooltip.create(Component.translatable(resetTooltipKey)))
				.build());
		addRenderableWidget(Button.builder(Component.literal("►"), b -> { onRight.run(); syncEditBoxes(); })
				.bounds(gridX + s * 2, gridY + s, s, s)
				.build());
		addRenderableWidget(Button.builder(Component.literal("▼"), b -> { onDown.run(); syncEditBoxes(); })
				.bounds(gridX + s, gridY + s * 2, s, s)
				.build());
	}

	/**
	 * The coarse jumps beside one number box, as a 2x2: +5 and -5 over +10 and -10. Nudging a
	 * pixel at a time is right for the last few, and hopeless for crossing the screen.
	 */
	protected void addStepButtons(int x, int y, IntConsumer onStep) {
		int w = STEP_BUTTON_WIDTH;
		int h = STEP_BUTTON_HEIGHT;
		int gap = STEP_BUTTON_GAP;
		int[][] cells = {{5, 0, 0}, {-5, 1, 0}, {10, 0, 1}, {-10, 1, 1}};
		for (int[] cell : cells) {
			int amount = cell[0];
			Button button = Button.builder(
							Component.literal(amount > 0 ? "+" + amount : String.valueOf(amount)),
							b -> { onStep.accept(amount); syncEditBoxes(); })
					.bounds(x + cell[1] * (w + gap), y + cell[2] * (h + gap), w, h)
					.build();
			stepButtons.add(button);
			addRenderableWidget(button);
		}
	}

	/**
	 * A numeric text box for typing an exact offset instead of clicking the pad a hundred times.
	 * Accepts an empty value or a lone "-" mid-typing without touching the offset, only committing
	 * once a whole number has been entered.
	 */
	protected EditBox createOffsetBox(int x, int y, int initialValue, IntConsumer onChange) {
		EditBox box = new EditBox(font, x, y, OFFSET_BOX_WIDTH, OFFSET_BOX_HEIGHT,
				Component.translatable(keyPrefix + ".offset_value"));
		box.setMaxLength(6);
		box.setValue(String.valueOf(initialValue));
		box.setResponder(text -> {
			if (text.isEmpty() || text.equals("-")) {
				return;
			}
			try {
				onChange.accept(Integer.parseInt(text));
			} catch (NumberFormatException ignored) {
				// leave the offset alone until a complete number is typed
			}
		});
		return box;
	}

	/** A 2x2 of small buttons that snap the thing flush into a screen corner. */
	protected void addCornerButtons(int x, int y, CornerSnap snap) {
		int w = CORNER_BUTTON_WIDTH;
		int h = CORNER_BUTTON_HEIGHT;
		int gap = CORNER_BUTTON_GAP;

		addRenderableWidget(Button.builder(Component.literal("⌜"), b -> snap.snap(true, false))
				.bounds(x, y, w, h)
				.tooltip(Tooltip.create(Component.translatable(keyPrefix + ".snap_top_left")))
				.build());
		addRenderableWidget(Button.builder(Component.literal("⌝"), b -> snap.snap(true, true))
				.bounds(x + w + gap, y, w, h)
				.tooltip(Tooltip.create(Component.translatable(keyPrefix + ".snap_top_right")))
				.build());
		addRenderableWidget(Button.builder(Component.literal("⌞"), b -> snap.snap(false, false))
				.bounds(x, y + h + gap, w, h)
				.tooltip(Tooltip.create(Component.translatable(keyPrefix + ".snap_bottom_left")))
				.build());
		addRenderableWidget(Button.builder(Component.literal("⌟"), b -> snap.snap(false, true))
				.bounds(x + w + gap, y + h + gap, w, h)
				.tooltip(Tooltip.create(Component.translatable(keyPrefix + ".snap_bottom_right")))
				.build());
	}
}
