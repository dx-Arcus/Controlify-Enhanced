/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.gui.screen;


import dev.isxander.controlify.Controlify;
import dev.isxander.controlify.aimassist.CompassBarRenderer;
import dev.isxander.controlify.config.dto.CompassConfig;
import dev.isxander.controlify.config.settings.TargetLockSettings;
import dev.isxander.controlify.utils.MinecraftUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jspecify.annotations.NonNull;

/**
 * Moves and resizes the compass bar, against a live preview of the real thing.
 * <p>
 * Built on the same vocabulary as {@link GuideOffsetEditScreen} — a directional pad, typed offsets
 * and corner snaps, {@link OffsetEditorScreen}'s — because they do the same job and there is no
 * reason to learn two of them. What it adds is a width slider, since a compass has a length worth
 * choosing and the button guides do not.
 */
public class CompassLayoutScreen extends OffsetEditorScreen {
	private static final int SLIDER_WIDTH = 220;
	private static final int SLIDER_HEIGHT = 20;

	/** How far the footer buttons sit above the bottom of the screen. */
	private static final int FOOTER_GAP = 28;
	/** Room the title and subtitle need above those buttons. */
	private static final int HEADING_HEIGHT = 30;

	/** Gap left between the bar and the screen edge when snapped, matching its default top gap. */
	private static final int SNAP_MARGIN = CompassBarRenderer.TOP;

	/** How far the preview marker swings either side of centre, and how long one sweep takes. */
	private static final double PREVIEW_SWING = 74;
	private static final double PREVIEW_PERIOD_MS = 7000;

	private final Screen parent;
	private final TargetLockSettings settings;

	private int offsetX;
	private int offsetY;
	private int barWidth;

	private EditBox xBox;
	private EditBox yBox;

	public CompassLayoutScreen(Screen parent, TargetLockSettings settings) {
		super(Component.translatable("controlify.gui.compass_editor.title"), "controlify.gui.compass_editor");
		this.parent = parent;
		this.settings = settings;
		this.offsetX = settings.compassOffsetX;
		this.offsetY = settings.compassOffsetY;
		this.barWidth = settings.compassWidth;
	}

	/** One centred column of controls, kept clear of wherever the bar itself has been put. */
	private record Layout(int gridX, int gridY, int rowX, int rowY,
						int xStepX, int yStepX, int stepY,
						int sliderX, int sliderY, int cornerX, int cornerY, int gridSize) {
	}

	private Layout layout() {
		int gridSize = BUTTON_SIZE * 3;
		int cornerGridHeight = CORNER_BUTTON_HEIGHT * 2 + CORNER_BUTTON_GAP;
		int blockHeight = gridSize + 12 + STEP_BLOCK_HEIGHT + 14 + SLIDER_HEIGHT + 14 + cornerGridHeight;

		// Centred, except on a short screen, where it is pushed up until it clears the heading
		// above the footer buttons rather than being drawn over it.
		int lowest = height - FOOTER_GAP - HEADING_HEIGHT - blockHeight;
		int top = Math.max(8, Math.min(height / 2 - blockHeight / 2 + 12, lowest));
		int gridY = top;
		int stepY = gridY + gridSize + 12;
		// The boxes sit centred against the taller blocks either side of them.
		int rowY = stepY + (STEP_BLOCK_HEIGHT - OFFSET_BOX_HEIGHT) / 2;
		int sliderY = stepY + STEP_BLOCK_HEIGHT + 14;
		int cornerY = sliderY + SLIDER_HEIGHT + 14;

		int rowWidth = STEP_BLOCK_WIDTH * 2 + STEP_BLOCK_MARGIN * 2 + OFFSET_ROW_WIDTH;
		int rowLeft = width / 2 - rowWidth / 2;
		int boxX = rowLeft + STEP_BLOCK_WIDTH + STEP_BLOCK_MARGIN;

		return new Layout(
				width / 2 - gridSize / 2, gridY,
				boxX, rowY,
				rowLeft, boxX + OFFSET_ROW_WIDTH + STEP_BLOCK_MARGIN, stepY,
				width / 2 - SLIDER_WIDTH / 2, sliderY,
				width / 2 - CORNER_GRID_WIDTH / 2, cornerY,
				gridSize);
	}

	@Override
	protected void init() {
		clearStepButtons();
		Layout layout = layout();

		addDirectionalPad(layout.gridX(), layout.gridY(),
				() -> offsetY -= STEP, () -> offsetY += STEP, () -> offsetX -= STEP, () -> offsetX += STEP,
				() -> { offsetX = 0; offsetY = 0; }, "controlify.gui.compass_editor.recentre");

		xBox = createOffsetBox(layout.rowX(), layout.rowY(), offsetX, v -> offsetX = v);
		yBox = createOffsetBox(layout.rowX() + OFFSET_BOX_WIDTH + OFFSET_BOX_GAP, layout.rowY(), offsetY, v -> offsetY = v);
		addRenderableWidget(xBox);
		addRenderableWidget(yBox);

		addStepButtons(layout.xStepX(), layout.stepY(), d -> offsetX += d);
		addStepButtons(layout.yStepX(), layout.stepY(), d -> offsetY += d);

		addRenderableWidget(new WidthSlider(layout.sliderX(), layout.sliderY()));
		addCornerButtons(layout.cornerX(), layout.cornerY(), this::snapToCorner);

		int footerY = height - FOOTER_GAP;
		addRenderableWidget(Button.builder(Component.translatable("controlify.gui.compass_editor.reset"), b -> resetAll())
				.bounds(width / 2 - FOOTER_BUTTON_WIDTH - 4, footerY, FOOTER_BUTTON_WIDTH, 20)
				.build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> commitAndClose())
				.bounds(width / 2 + 4, footerY, FOOTER_BUTTON_WIDTH, 20)
				.build());
	}

	/**
	 * Works out the offset that lands the bar against a corner. The bar is centred by nature, so
	 * both offsets are measured from that centred position rather than from a screen edge — which
	 * is also why a snap made at one window size stays roughly right at another.
	 */
	private void snapToCorner(boolean top, boolean rightEdge) {
		int drawn = CompassBarRenderer.barWidth(barWidth, width);
		int centred = CompassBarRenderer.leftEdge(width, drawn, 0);

		offsetX = (rightEdge ? width - SNAP_MARGIN - drawn : SNAP_MARGIN) - centred;
		offsetY = top ? 0 : height - CompassBarRenderer.TOTAL_HEIGHT - SNAP_MARGIN - CompassBarRenderer.TOP;
		syncEditBoxes();
	}

	/** Keeps the boxes showing the offsets after anything that changed them other than typing. */
	@Override
	protected void syncEditBoxes() {
		if (xBox == null) {
			return; // not yet initialised
		}
		xBox.setValue(String.valueOf(offsetX));
		yBox.setValue(String.valueOf(offsetY));
	}

	private void resetAll() {
		offsetX = CompassConfig.DEFAULT.offsetX();
		offsetY = CompassConfig.DEFAULT.offsetY();
		barWidth = CompassConfig.DEFAULT.width();
		syncEditBoxes();
		rebuildWidgets();
	}

	private void commitAndClose() {
		settings.compassOffsetX = offsetX;
		settings.compassOffsetY = offsetY;
		settings.compassWidth = barWidth;
		Controlify.instance().config().saveSafely();
		MinecraftUtil.setScreen(parent);
	}

	@Override
	public void onClose() {
		commitAndClose();
	}

	@Override
	public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);

		Layout layout = layout();
		graphics.centeredText(font, Component.translatable("controlify.gui.compass_editor.offset"),
				width / 2, layout.gridY() - 14, 0xFFFFFFFF);
		graphics.centeredText(font, Component.literal("X"),
				layout.rowX() + OFFSET_BOX_WIDTH / 2, layout.rowY() - 10, 0xFFAAAAAA);
		graphics.centeredText(font, Component.literal("Y"),
				layout.rowX() + OFFSET_BOX_WIDTH + OFFSET_BOX_GAP + OFFSET_BOX_WIDTH / 2, layout.rowY() - 10, 0xFFAAAAAA);

		// Down by the buttons rather than up at the top: the top of the screen is where the bar
		// itself lives by default, and a heading sitting on the thing being positioned is exactly
		// the view the player needs unobstructed.
		int subtitleY = height - FOOTER_GAP - 13;
		graphics.centeredText(font, title, width / 2, subtitleY - 11, 0xFFFFFFFF);
		graphics.centeredText(font, Component.translatable("controlify.gui.compass_editor.subtitle"),
				width / 2, subtitleY, 0xFFA0A0A0);

		// The real bar, at the position and width being set, over the screen it will be drawn on.
		// Drawn last, on top of everything: the whole point of the screen is seeing where the bar
		// lands, so it wins any overlap with the controls or the title rather than hiding behind
		// them - including when it is snapped into the corner one of them happens to occupy.
		double bearing = Math.sin(System.nanoTime() / 1_000_000L % (long) PREVIEW_PERIOD_MS
				/ PREVIEW_PERIOD_MS * Math.PI * 2) * PREVIEW_SWING;
		CompassBarRenderer.extractPreview(graphics, font, width, offsetX, offsetY, barWidth,
				settings.compassColor, settings.arrowColor, bearing,
				Component.translatable("controlify.gui.compass_editor.sample_name"),
				Component.translatable("controlify.gui.compass_editor.sample_readout").getString());
	}

	/** Width in whole pixels, shown on the handle, so the number is never a mystery. */
	private class WidthSlider extends AbstractSliderButton {
		WidthSlider(int x, int y) {
			super(x, y, SLIDER_WIDTH, SLIDER_HEIGHT, Component.empty(), toSliderValue(barWidth));
			updateMessage();
			setTooltip(Tooltip.create(Component.translatable("controlify.gui.compass_editor.width.tooltip")));
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.translatable("controlify.gui.compass_editor.width", fromSliderValue(value)));
		}

		@Override
		protected void applyValue() {
			barWidth = fromSliderValue(value);
		}
	}

	private static double toSliderValue(int width) {
		int span = CompassConfig.MAX_WIDTH - CompassConfig.MIN_WIDTH;
		return Mth.clamp((double) (width - CompassConfig.MIN_WIDTH) / span, 0, 1);
	}

	private static int fromSliderValue(double value) {
		int span = CompassConfig.MAX_WIDTH - CompassConfig.MIN_WIDTH;
		return CompassConfig.MIN_WIDTH + (int) Math.round(value * span);
	}
}
