/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.gui.screen;


import dev.isxander.controlify.Controlify;
import dev.isxander.controlify.api.bind.InputBinding;
import dev.isxander.controlify.api.bind.InputBindingSupplier;
import dev.isxander.controlify.bindings.ControlifyBindings;
import dev.isxander.controlify.config.settings.profile.GenericControllerSettings;
import dev.isxander.controlify.controller.ControllerEntity;
import dev.isxander.controlify.gui.guide.GuideRenderer;
import dev.isxander.controlify.gui.guide.PrecomputedLines;
import dev.isxander.controlify.utils.MinecraftUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Lets the player nudge the left/right ingame button guide columns independently, with a
 * live preview rendered using the player's actual bound inputs (real glyph icons and names
 * for a representative set of bindings), at the exact position the real HUD overlay would use.
 * The controls are {@link OffsetEditorScreen}'s; this lays them out, two clusters side by side.
 */
public class GuideOffsetEditScreen extends OffsetEditorScreen {
	// The real overlay only ever shows a couple of contextually-relevant lines at once, so its
	// tight betweenLines gap never gets stressed. Here every sample binding is shown together,
	// and glyph icons commonly render taller than plain text, so give lines extra breathing
	// room purely for this preview - the real in-game renderer/gap is untouched.
	private static final int PREVIEW_LINE_SPACING = 14;

	/** Gap between the two blocks when they have to go under the boxes instead of beside them. */
	private static final int STEP_BLOCK_STACKED_GAP = 6;

	/** Height reserved above each cluster for its "Left Guides" / "Right Guides" label. */
	private static final int LABEL_HEIGHT = 14;
	/** Horizontal gap between the two clusters, centred on the screen. */
	private static final int CLUSTER_GAP = 40;

	// Mirrors the private layout constants in GuideRenderer#extractLines, so the corner-snap
	// math below lands on exactly the same pixel the real HUD overlay would use.
	private static final int SAFE_AREA_X = 2;
	private static final int SAFE_AREA_Y = 5;
	private static final int BETWEEN_LINES = 2;

	// mirrors the default out-of-the-box guide layout: jump/sneak/inventory/radial on the
	// left, attack/use on the right - see the reference screenshot for the intended look.
	private static final List<InputBindingSupplier> LEFT_BINDINGS = List.of(
			ControlifyBindings.JUMP,
			ControlifyBindings.SNEAK,
			ControlifyBindings.INVENTORY,
			ControlifyBindings.RADIAL_MENU
	);
	private static final List<InputBindingSupplier> RIGHT_BINDINGS = List.of(
			ControlifyBindings.ATTACK,
			ControlifyBindings.USE
	);

	private final Screen parent;
	private final GenericControllerSettings.GuideSettings guideSettings;
	private final ControllerEntity controller;
	private final boolean bottomAligned;

	private int leftOffsetX;
	private int leftOffsetY;
	private int rightOffsetX;
	private int rightOffsetY;

	private EditBox leftXBox;
	private EditBox leftYBox;
	private EditBox rightXBox;
	private EditBox rightYBox;

	public GuideOffsetEditScreen(Screen parent, GenericControllerSettings.GuideSettings guideSettings, ControllerEntity controller) {
		super(Component.translatable("controlify.gui.glyph_editor.title"), "controlify.gui.glyph_editor");
		this.parent = parent;
		this.guideSettings = guideSettings;
		this.controller = controller;
		this.bottomAligned = guideSettings.ingameGuideBottom;
		this.leftOffsetX = guideSettings.ingameGuideOffsetLeftX;
		this.leftOffsetY = guideSettings.ingameGuideOffsetLeftY;
		this.rightOffsetX = guideSettings.ingameGuideOffsetRightX;
		this.rightOffsetY = guideSettings.ingameGuideOffsetRightY;
	}

	/** Where one side's controls sit, left to right across its cluster. */
	private record Cluster(int gridX, int rowX, int cornerX, int xStepX, int yStepX) {
	}

	/**
	 * Geometry for the two control clusters. Both clusters sit side by side in the middle of the
	 * screen, rather than against the left and right edges, so they don't cover the guide preview
	 * in the places the guides normally sit.
	 */
	private record ClusterLayout(Cluster left, Cluster right, int gridY, int rowY, int stepY,
								int cornerY, int gridSize) {
	}

	private ClusterLayout clusterLayout() {
		int gridSize = BUTTON_SIZE * 3;
		int cornerGridHeight = CORNER_BUTTON_HEIGHT * 2 + CORNER_BUTTON_GAP;

		// Blocks beside the boxes is the layout worth having, but two clusters of it need a wide
		// screen. Where there isn't one, they go under the boxes instead and the cluster stays the
		// width it has always been - a cramped screen is better than two clusters overlapping.
		int flankedWidth = STEP_BLOCK_WIDTH * 2 + STEP_BLOCK_MARGIN * 2 + OFFSET_ROW_WIDTH;
		boolean flanked = width >= flankedWidth * 2 + CLUSTER_GAP;

		int clusterWidth = flanked
				? flankedWidth
				: Math.max(gridSize, Math.max(OFFSET_ROW_WIDTH, CORNER_GRID_WIDTH));
		int rowHeight = flanked
				? STEP_BLOCK_HEIGHT
				: OFFSET_BOX_HEIGHT + 6 + STEP_BLOCK_HEIGHT;
		int clusterHeight = LABEL_HEIGHT + gridSize + 10 + rowHeight + 10 + cornerGridHeight;

		int clusterTop = height / 2 - clusterHeight / 2;
		int gridY = clusterTop + LABEL_HEIGHT;
		int leftClusterX = width / 2 - CLUSTER_GAP / 2 - clusterWidth;
		int rightClusterX = width / 2 + CLUSTER_GAP / 2;

		int rowTop = gridY + gridSize + 10;
		// Flanked, the boxes sit centred against the taller blocks; stacked, they lead the row.
		int rowY = flanked ? rowTop + (STEP_BLOCK_HEIGHT - OFFSET_BOX_HEIGHT) / 2 : rowTop;
		int stepY = flanked ? rowTop : rowTop + OFFSET_BOX_HEIGHT + 6;
		int cornerY = rowTop + rowHeight + 10;

		return new ClusterLayout(
				cluster(leftClusterX, clusterWidth, gridSize, flanked),
				cluster(rightClusterX, clusterWidth, gridSize, flanked),
				gridY, rowY, stepY, cornerY, gridSize
		);
	}

	private static Cluster cluster(int x, int clusterWidth, int gridSize, boolean flanked) {
		int rowX = flanked
				? x + STEP_BLOCK_WIDTH + STEP_BLOCK_MARGIN
				: x + (clusterWidth - OFFSET_ROW_WIDTH) / 2;
		int xStepX = flanked
				? x
				: x + (clusterWidth - (STEP_BLOCK_WIDTH * 2 + STEP_BLOCK_STACKED_GAP)) / 2;
		int yStepX = flanked
				? rowX + OFFSET_ROW_WIDTH + STEP_BLOCK_MARGIN
				: xStepX + STEP_BLOCK_WIDTH + STEP_BLOCK_STACKED_GAP;

		return new Cluster(
				x + (clusterWidth - gridSize) / 2,
				rowX,
				x + (clusterWidth - CORNER_GRID_WIDTH) / 2,
				xStepX,
				yStepX
		);
	}

	@Override
	protected void init() {
		clearStepButtons();
		ClusterLayout layout = clusterLayout();

		Cluster left = layout.left();
		Cluster right = layout.right();

		addDirectionalPad(
				left.gridX(), layout.gridY(),
				() -> leftOffsetY -= STEP, () -> leftOffsetY += STEP,
				() -> leftOffsetX -= STEP, () -> leftOffsetX += STEP,
				() -> { leftOffsetX = 0; leftOffsetY = 0; },
				"controlify.gui.glyph_editor.reset_side"
		);
		addDirectionalPad(
				right.gridX(), layout.gridY(),
				() -> rightOffsetY -= STEP, () -> rightOffsetY += STEP,
				() -> rightOffsetX -= STEP, () -> rightOffsetX += STEP,
				() -> { rightOffsetX = 0; rightOffsetY = 0; },
				"controlify.gui.glyph_editor.reset_side"
		);

		int rowY = layout.rowY();
		int leftRowX = left.rowX();
		int rightRowX = right.rowX();

		leftXBox = createOffsetBox(leftRowX, rowY, leftOffsetX, v -> leftOffsetX = v);
		leftYBox = createOffsetBox(leftRowX + OFFSET_BOX_WIDTH + OFFSET_BOX_GAP, rowY,
				shownY(false, leftOffsetY), v -> leftOffsetY = offsetFromShownY(false, v));
		rightXBox = createOffsetBox(rightRowX, rowY, rightOffsetX, v -> rightOffsetX = v);
		rightYBox = createOffsetBox(rightRowX + OFFSET_BOX_WIDTH + OFFSET_BOX_GAP, rowY,
				shownY(true, rightOffsetY), v -> rightOffsetY = offsetFromShownY(true, v));
		addRenderableWidget(leftXBox);
		addRenderableWidget(leftYBox);
		addRenderableWidget(rightXBox);
		addRenderableWidget(rightYBox);

		int stepY = layout.stepY();
		addStepButtons(left.xStepX(), stepY, d -> leftOffsetX += d);
		addStepButtons(right.xStepX(), stepY, d -> rightOffsetX += d);
		// Negated: the Y box counts upwards, so +5 has to raise the guides, which is a smaller
		// offset from the top. Without this the button and the number it sits beside disagree.
		addStepButtons(left.yStepX(), stepY, d -> leftOffsetY -= d);
		addStepButtons(right.yStepX(), stepY, d -> rightOffsetY -= d);

		addCornerButtons(left.cornerX(), layout.cornerY(), (top, rightEdge) -> snapToCorner(false, top, rightEdge));
		addCornerButtons(right.cornerX(), layout.cornerY(), (top, rightEdge) -> snapToCorner(true, top, rightEdge));

		int footerY = height - 28;
		addRenderableWidget(Button.builder(Component.translatable("controlify.gui.glyph_editor.reset_all"), b -> resetAll())
				.bounds(width / 2 - FOOTER_BUTTON_WIDTH - 4, footerY, FOOTER_BUTTON_WIDTH, 20)
				.build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> commitAndClose())
				.bounds(width / 2 + 4, footerY, FOOTER_BUTTON_WIDTH, 20)
				.build());
	}

	/**
	 * Computes and applies the offset needed to move a side's preview flush against a screen
	 * corner, using the exact same anchor math as {@link GuideRenderer}'s real extractLines:
	 * the left column's x anchors its block's left edge, the right column's x anchors its
	 * block's right edge, and the vertical anchor flips with {@link #bottomAligned} - so we
	 * measure from whichever edge is "natural" for this profile and offset from there.
	 */
	private void snapToCorner(boolean rightColumn, boolean top, boolean rightEdge) {
		PrecomputedLines preview = buildPreviewLines(rightColumn ? RIGHT_BINDINGS : LEFT_BINDINGS, !rightColumn);
		if (preview.lines().isEmpty()) {
			return;
		}

		int lineWidth = preview.width();
		int allLinesHeight = preview.height() + (preview.lines().size() - 1) * BETWEEN_LINES;

		int offsetX;
		if (rightColumn) {
			offsetX = rightEdge ? 0 : (2 * SAFE_AREA_X + lineWidth - width);
		} else {
			offsetX = rightEdge ? (width - 2 * SAFE_AREA_X - lineWidth) : 0;
		}

		int naturalY = bottomAligned ? (height - allLinesHeight - SAFE_AREA_Y) : SAFE_AREA_Y;
		int targetY = top ? SAFE_AREA_Y : (height - allLinesHeight - SAFE_AREA_Y);
		int offsetY = targetY - naturalY;

		if (rightColumn) {
			rightOffsetX = offsetX;
			rightOffsetY = offsetY;
		} else {
			leftOffsetX = offsetX;
			leftOffsetY = offsetY;
		}
		syncEditBoxes();
	}

	/**
	 * Where this side's guides sit before its offset is applied, as the y of the top of the block.
	 * Depends on how many lines the side has and on whether the profile hangs its guides from the
	 * bottom, which is why it is measured rather than assumed.
	 */
	private int naturalTop(boolean rightColumn) {
		PrecomputedLines preview = buildPreviewLines(rightColumn ? RIGHT_BINDINGS : LEFT_BINDINGS, !rightColumn);
		int allLinesHeight = preview.height() + Math.max(0, preview.lines().size() - 1) * BETWEEN_LINES;
		return bottomAligned ? (height - allLinesHeight - SAFE_AREA_Y) : SAFE_AREA_Y;
	}

	/**
	 * The Y that goes in the box: measured from the middle of the screen with up positive, rather
	 * than the stored offset, which counts downwards from wherever the guides would have sat
	 * anyway. Nobody thinks in offsets-from-natural; everybody can see the middle of the screen.
	 */
	private int shownY(boolean rightColumn, int offsetY) {
		return height / 2 - (naturalTop(rightColumn) + offsetY);
	}

	private int offsetFromShownY(boolean rightColumn, int shown) {
		return height / 2 - shown - naturalTop(rightColumn);
	}

	/**
	 * Keeps the text boxes showing the current offsets after any change made outside of typing
	 * into them directly (directional pad, per-side reset, reset all, corner snap).
	 */
	@Override
	protected void syncEditBoxes() {
		if (leftXBox == null) {
			return; // not yet initialised
		}
		leftXBox.setValue(String.valueOf(leftOffsetX));
		leftYBox.setValue(String.valueOf(shownY(false, leftOffsetY)));
		rightXBox.setValue(String.valueOf(rightOffsetX));
		rightYBox.setValue(String.valueOf(shownY(true, rightOffsetY)));
	}

	private void resetAll() {
		leftOffsetX = 0;
		leftOffsetY = 0;
		rightOffsetX = 0;
		rightOffsetY = 0;
		syncEditBoxes();
	}

	private void commitAndClose() {
		guideSettings.ingameGuideOffsetLeftX = leftOffsetX;
		guideSettings.ingameGuideOffsetLeftY = leftOffsetY;
		guideSettings.ingameGuideOffsetRightX = rightOffsetX;
		guideSettings.ingameGuideOffsetRightY = rightOffsetY;
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

		graphics.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
		graphics.centeredText(font, Component.translatable("controlify.gui.glyph_editor.subtitle"), width / 2, 24, 0xFFA0A0A0);

		ClusterLayout layout = clusterLayout();
		int gridSize = layout.gridSize();

		graphics.centeredText(font, Component.translatable("controlify.gui.glyph_editor.left_side"), layout.left().gridX() + gridSize / 2, layout.gridY() - LABEL_HEIGHT, 0xFFFFFFFF);
		graphics.centeredText(font, Component.translatable("controlify.gui.glyph_editor.right_side"), layout.right().gridX() + gridSize / 2, layout.gridY() - LABEL_HEIGHT, 0xFFFFFFFF);

		int rowY = layout.rowY();
		int leftRowX = layout.left().rowX();
		int rightRowX = layout.right().rowX();

		graphics.centeredText(font, Component.literal("X"), leftRowX + OFFSET_BOX_WIDTH / 2, rowY - 10, 0xFFAAAAAA);
		graphics.centeredText(font, Component.literal("Y"), leftRowX + OFFSET_BOX_WIDTH + OFFSET_BOX_GAP + OFFSET_BOX_WIDTH / 2, rowY - 10, 0xFFAAAAAA);
		graphics.centeredText(font, Component.literal("X"), rightRowX + OFFSET_BOX_WIDTH / 2, rowY - 10, 0xFFAAAAAA);
		graphics.centeredText(font, Component.literal("Y"), rightRowX + OFFSET_BOX_WIDTH + OFFSET_BOX_GAP + OFFSET_BOX_WIDTH / 2, rowY - 10, 0xFFAAAAAA);

		// live preview using the player's real bound inputs, rendered with the exact same
		// positioning math the real HUD overlay uses
		PrecomputedLines leftPreview = buildPreviewLines(LEFT_BINDINGS, true);
		PrecomputedLines rightPreview = buildPreviewLines(RIGHT_BINDINGS, false);
		GuideRenderer.extractPreviewLines(graphics, leftPreview, font, width, height, bottomAligned, false, true, leftOffsetX, leftOffsetY);
		GuideRenderer.extractPreviewLines(graphics, rightPreview, font, width, height, bottomAligned, true, true, rightOffsetX, rightOffsetY);
	}

	/**
	 * @param glyphFirst whether the glyph icon is shown before the name (left column style) or
	 *                   after it (right column style), matching how the real guide composes lines.
	 */
	private PrecomputedLines buildPreviewLines(List<InputBindingSupplier> bindings, boolean glyphFirst) {
		PrecomputedLines.Builder builder = new PrecomputedLines.Builder();
		for (InputBindingSupplier supplier : bindings) {
			InputBinding binding = supplier.onOrNull(controller);
			if (binding == null) {
				continue;
			}

			Component glyph = binding.inputGlyph();
			Component name = binding.name();
			Component text = glyphFirst
					? Component.empty().append(glyph).append(" ").append(name)
					: Component.empty().append(name).append(" ").append(glyph);

			int lineWidth = font.width(text);
			builder.addLine(text, lineWidth, PREVIEW_LINE_SPACING, 0, lineWidth);
		}
		return builder.build();
	}
}
