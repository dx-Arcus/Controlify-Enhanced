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
import dev.isxander.controlify.config.dto.TouchConfig;
import dev.isxander.controlify.config.settings.TouchSettings;
import dev.isxander.controlify.config.settings.profile.GenericControllerSettings;
import dev.isxander.controlify.controller.ControllerEntity;
import dev.isxander.controlify.gui.guide.GuideRenderer;
import dev.isxander.controlify.gui.guide.PrecomputedLines;
import dev.isxander.controlify.touch.TouchInput;
import dev.isxander.controlify.touch.TouchPad;
import dev.isxander.controlify.utils.MinecraftUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
//? if >=26.2 {
import net.minecraft.client.gui.components.tabs.MenuTabBar;
//?}
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Lets the player nudge the left/right ingame button guide columns independently, with a
 * live preview rendered using the player's actual bound inputs (real glyph icons and names
 * for a representative set of bindings), at the exact position the real HUD overlay would use.
 * The controls are {@link OffsetEditorScreen}'s; this lays them out, two clusters side by side.
 * <p>
 * Where touch controls exist (26.3), a second tab does the same for them (tl117): the stick's resting
 * place and the five action buttons, moved with the same controls, a slider each for their size where the
 * guides have their corner snaps, and the touch controls drawn where they would be. The touch layout is
 * saved for every controller ({@link TouchSettings}); the guides, as before, for the one being edited.
 */
public class GuideOffsetEditScreen extends OffsetEditorScreen {
	private static final String KEY = "controlify.gui.glyph_editor";

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

	/** The tab bar's height (MenuTabBar's), and where the subtitle and the clusters go under it. */
	private static final int TAB_BAR_HEIGHT = 24;
	private static final int TABBED_SUBTITLE_Y = TAB_BAR_HEIGHT + 4;
	private static final int TABBED_CLUSTER_TOP = TABBED_SUBTITLE_Y + 12;

	/** A size slider is as wide as its cluster, up to this. */
	private static final int SIZE_SLIDER_MAX_WIDTH = 150;
	private static final int SIZE_SLIDER_HEIGHT = 20;

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

	/** The tabs, by index in the bar. */
	private static final int GUIDES = 0;
	private static final int TOUCH = 1;

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

	/** Whether there are touch controls to place, and so a Touch tab: 26.3 only. */
	private final boolean tabs = TouchInput.SUPPORTED;
	private final @Nullable TouchSettings touchSettings;

	// The touch layout being edited: offsets as fractions of the window's height, right and down
	// positive, as saved; sizes as fractions of the default.
	private float stickX;
	private float stickY;
	private float stickSize;
	private float buttonsX;
	private float buttonsY;
	private float buttonSize;

	private @Nullable EditBox stickXBox;
	private @Nullable EditBox stickYBox;
	private @Nullable EditBox buttonsXBox;
	private @Nullable EditBox buttonsYBox;
	private @Nullable SizeSlider stickSlider;
	private @Nullable SizeSlider buttonSlider;

	/** The tab showing; kept when the screen is resized, which builds everything again. */
	private int page;
	/** The tab whose controls are being built, or null while they go straight onto the screen. */
	private @Nullable Page building;

	public GuideOffsetEditScreen(Screen parent, GenericControllerSettings.GuideSettings guideSettings, ControllerEntity controller) {
		super(Component.translatable(KEY + ".title"), KEY);
		this.parent = parent;
		this.guideSettings = guideSettings;
		this.controller = controller;
		this.bottomAligned = guideSettings.ingameGuideBottom;
		this.leftOffsetX = guideSettings.ingameGuideOffsetLeftX;
		this.leftOffsetY = guideSettings.ingameGuideOffsetLeftY;
		this.rightOffsetX = guideSettings.ingameGuideOffsetRightX;
		this.rightOffsetY = guideSettings.ingameGuideOffsetRightY;

		this.touchSettings = tabs ? Controlify.instance().config().getSettings().touchSettings() : null;
		if (touchSettings != null) {
			this.stickX = touchSettings.stickOffsetX;
			this.stickY = touchSettings.stickOffsetY;
			this.stickSize = TouchSettings.size(touchSettings.stickSize);
			this.buttonsX = touchSettings.buttonsOffsetX;
			this.buttonsY = touchSettings.buttonsOffsetY;
			this.buttonSize = TouchSettings.size(touchSettings.buttonSize);
		}
		// Playing by touch, the player has come for the touch controls.
		this.page = tabs && TouchPad.active() ? TOUCH : GUIDES;
	}

	/** Where one side's controls sit, left to right across its cluster, and the cluster itself. */
	private record Cluster(int x, int width, int gridX, int rowX, int cornerX, int xStepX, int yStepX) {
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
		if (tabs) {
			// Clear of the tab bar and the subtitle under it.
			clusterTop = Math.max(clusterTop, TABBED_CLUSTER_TOP);
		}
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
				x,
				clusterWidth,
				x + (clusterWidth - gridSize) / 2,
				rowX,
				x + (clusterWidth - CORNER_GRID_WIDTH) / 2,
				xStepX,
				yStepX
		);
	}

	/**
	 * A tab of the editor: its controls, placed by the screen rather than by a layout. The bar shows and
	 * hides them together; {@link #doLayout} leaves them where they are.
	 */
	private static final class Page extends GridLayoutTab {
		private final List<AbstractWidget> widgets = new ArrayList<>();

		Page(Component title) {
			super(title);
		}

		@Override
		public void visitChildren(Consumer<AbstractWidget> consumer) {
			widgets.forEach(consumer);
		}

		@Override
		public void doLayout(ScreenRectangle rectangle) {
		}
	}

	@Override
	protected <T extends AbstractWidget> T addControl(T widget) {
		if (building != null) {
			building.widgets.add(widget);
			return widget;
		}
		return super.addControl(widget);
	}

	@Override
	protected void init() {
		clearStepButtons();
		if (!tabs) {
			addGuideControls();
		} else {
			Page guides = new Page(Component.translatable(KEY + ".tab.guides"));
			Page touch = new Page(Component.translatable(KEY + ".tab.touch"));
			building = guides;
			addGuideControls();
			building = touch;
			addTouchControls();
			building = null;

			TabManager tabManager = new TabManager(this::addRenderableWidget, this::removeWidget,
					tab -> page = tab == touch ? TOUCH : GUIDES, tab -> {});
			TabNavigationBar bar = tabBar(tabManager, guides, touch);
			addRenderableWidget(bar);
			bar.selectTab(page, false);
		}

		int footerY = height - 28;
		addRenderableWidget(Button.builder(Component.translatable(KEY + ".reset_all"), b -> resetAll())
				.bounds(width / 2 - FOOTER_BUTTON_WIDTH - 4, footerY, FOOTER_BUTTON_WIDTH, 20)
				.build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> commitAndClose())
				.bounds(width / 2 + 4, footerY, FOOTER_BUTTON_WIDTH, 20)
				.build());
	}

	/** The bar across the top, Guides and Touch: the game's own menu tab bar, which a controller's bumpers step through. */
	private TabNavigationBar tabBar(TabManager tabManager, Page guides, Page touch) {
		//? if >=26.2 {
		TabNavigationBar bar = MenuTabBar.builder(tabManager, width).addTabs(guides, touch).build();
		bar.arrangeElements(width);
		//?} else {
		/*TabNavigationBar bar = TabNavigationBar.builder(tabManager, width).addTabs(guides, touch).build();
		bar.arrangeElements();
		*///?}
		return bar;
	}

	/** The guides' two clusters: a directional pad, the offset boxes with their jumps, and the corner snaps each. */
	private void addGuideControls() {
		ClusterLayout layout = clusterLayout();

		Cluster left = layout.left();
		Cluster right = layout.right();

		addDirectionalPad(
				left.gridX(), layout.gridY(),
				() -> leftOffsetY -= STEP, () -> leftOffsetY += STEP,
				() -> leftOffsetX -= STEP, () -> leftOffsetX += STEP,
				() -> { leftOffsetX = 0; leftOffsetY = 0; },
				KEY + ".reset_side"
		);
		addDirectionalPad(
				right.gridX(), layout.gridY(),
				() -> rightOffsetY -= STEP, () -> rightOffsetY += STEP,
				() -> rightOffsetX -= STEP, () -> rightOffsetX += STEP,
				() -> { rightOffsetX = 0; rightOffsetY = 0; },
				KEY + ".reset_side"
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
		addControl(leftXBox);
		addControl(leftYBox);
		addControl(rightXBox);
		addControl(rightYBox);

		int stepY = layout.stepY();
		addStepButtons(left.xStepX(), stepY, d -> leftOffsetX += d);
		addStepButtons(right.xStepX(), stepY, d -> rightOffsetX += d);
		// Negated: the Y box counts upwards, so +5 has to raise the guides, which is a smaller
		// offset from the top. Without this the button and the number it sits beside disagree.
		addStepButtons(left.yStepX(), stepY, d -> leftOffsetY -= d);
		addStepButtons(right.yStepX(), stepY, d -> rightOffsetY -= d);

		addCornerButtons(left.cornerX(), layout.cornerY(), (top, rightEdge) -> snapToCorner(false, top, rightEdge));
		addCornerButtons(right.cornerX(), layout.cornerY(), (top, rightEdge) -> snapToCorner(true, top, rightEdge));
	}

	/**
	 * The touch controls' two clusters (tl117), the stick's on the left and the buttons' on the right, laid
	 * out as the guides' are: a directional pad, the offset boxes with their jumps - in GUI pixels from
	 * where each sits by default, X right and Y up, and stopping at the window's edges - and in the corner
	 * snaps' place a size slider.
	 */
	private void addTouchControls() {
		ClusterLayout layout = clusterLayout();
		Cluster left = layout.left();
		Cluster right = layout.right();

		addDirectionalPad(
				left.gridX(), layout.gridY(),
				() -> moved(() -> stickY -= pixels(STEP)), () -> moved(() -> stickY += pixels(STEP)),
				() -> moved(() -> stickX -= pixels(STEP)), () -> moved(() -> stickX += pixels(STEP)),
				() -> { stickX = 0f; stickY = 0f; },
				KEY + ".reset_side"
		);
		addDirectionalPad(
				right.gridX(), layout.gridY(),
				() -> moved(() -> buttonsY -= pixels(STEP)), () -> moved(() -> buttonsY += pixels(STEP)),
				() -> moved(() -> buttonsX -= pixels(STEP)), () -> moved(() -> buttonsX += pixels(STEP)),
				() -> { buttonsX = 0f; buttonsY = 0f; },
				KEY + ".reset_side"
		);

		int rowY = layout.rowY();
		// Y as typed counts up, the layout down: negated as a whole number, so a 0 is never the float -0, which the
		// file would keep as if it were moved.
		stickXBox = createOffsetBox(left.rowX(), rowY, shown(stickX), v -> moved(() -> stickX = pixels(v)));
		stickYBox = createOffsetBox(left.rowX() + OFFSET_BOX_WIDTH + OFFSET_BOX_GAP, rowY, -shown(stickY), v -> moved(() -> stickY = pixels(-v)));
		buttonsXBox = createOffsetBox(right.rowX(), rowY, shown(buttonsX), v -> moved(() -> buttonsX = pixels(v)));
		buttonsYBox = createOffsetBox(right.rowX() + OFFSET_BOX_WIDTH + OFFSET_BOX_GAP, rowY, -shown(buttonsY), v -> moved(() -> buttonsY = pixels(-v)));
		addControl(stickXBox);
		addControl(stickYBox);
		addControl(buttonsXBox);
		addControl(buttonsYBox);

		int stepY = layout.stepY();
		addStepButtons(left.xStepX(), stepY, d -> moved(() -> stickX += pixels(d)));
		addStepButtons(right.xStepX(), stepY, d -> moved(() -> buttonsX += pixels(d)));
		// Negated as the guides' are: the Y box counts upwards.
		addStepButtons(left.yStepX(), stepY, d -> moved(() -> stickY -= pixels(d)));
		addStepButtons(right.yStepX(), stepY, d -> moved(() -> buttonsY -= pixels(d)));

		stickSlider = addControl(sizeSlider(left, layout.cornerY(), stickSize, size -> stickSize = size, KEY + ".touch.stick_size.tooltip"));
		buttonSlider = addControl(sizeSlider(right, layout.cornerY(), buttonSize, size -> buttonSize = size, KEY + ".touch.buttons_size.tooltip"));
	}

	/** A move of the touch controls: made, then held to what keeps them inside the window. */
	private void moved(Runnable move) {
		move.run();
		TouchPad.Layout kept = TouchPad.keptInside(touchLayout());
		stickX = kept.stickX();
		stickY = kept.stickY();
		buttonsX = kept.buttonsX();
		buttonsY = kept.buttonsY();
	}

	private SizeSlider sizeSlider(Cluster cluster, int y, float size, Consumer<Float> onChange, String tooltipKey) {
		int sliderWidth = Math.min(cluster.width(), SIZE_SLIDER_MAX_WIDTH);
		return new SizeSlider(cluster.x() + (cluster.width() - sliderWidth) / 2, y, sliderWidth, size, onChange,
				Component.translatable(tooltipKey));
	}

	/** So many GUI pixels as a fraction of the window's height, which is how the touch layout is kept. */
	private float pixels(int guiPixels) {
		return guiPixels / (float) height;
	}

	/** A fraction of the window's height in whole GUI pixels, for the boxes. */
	private int shown(float fraction) {
		return Math.round(fraction * height);
	}

	/** A size from half to twice the default, a twentieth at a time, its percentage on the slider. */
	private static final class SizeSlider extends AbstractSliderButton {
		private final Consumer<Float> onChange;

		SizeSlider(int x, int y, int width, float size, Consumer<Float> onChange, Component tooltip) {
			super(x, y, width, SIZE_SLIDER_HEIGHT, Component.empty(), toValue(size));
			this.onChange = onChange;
			setTooltip(Tooltip.create(tooltip));
			updateMessage();
		}

		private static double toValue(float size) {
			return (size - TouchConfig.MIN_SIZE) / (TouchConfig.MAX_SIZE - TouchConfig.MIN_SIZE);
		}

		float size() {
			float size = (float) (TouchConfig.MIN_SIZE + value * (TouchConfig.MAX_SIZE - TouchConfig.MIN_SIZE));
			return Math.round(size * 20f) / 20f;
		}

		/** Puts the slider at a size without reporting it, for Reset All. */
		void set(float size) {
			this.value = toValue(size);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.translatable(KEY + ".touch.size", Math.round(size() * 100f) + "%"));
		}

		@Override
		protected void applyValue() {
			onChange.accept(size());
		}
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
		if (stickXBox != null && stickYBox != null && buttonsXBox != null && buttonsYBox != null) {
			stickXBox.setValue(String.valueOf(shown(stickX)));
			stickYBox.setValue(String.valueOf(-shown(stickY)));
			buttonsXBox.setValue(String.valueOf(shown(buttonsX)));
			buttonsYBox.setValue(String.valueOf(-shown(buttonsY)));
		}
	}

	/** Puts back what the tab showing edits: the guides' offsets, or the touch controls' places and sizes. */
	private void resetAll() {
		if (page == TOUCH) {
			stickX = 0f;
			stickY = 0f;
			stickSize = 1f;
			buttonsX = 0f;
			buttonsY = 0f;
			buttonSize = 1f;
			if (stickSlider != null && buttonSlider != null) {
				stickSlider.set(stickSize);
				buttonSlider.set(buttonSize);
			}
		} else {
			leftOffsetX = 0;
			leftOffsetY = 0;
			rightOffsetX = 0;
			rightOffsetY = 0;
		}
		syncEditBoxes();
	}

	private void commitAndClose() {
		guideSettings.ingameGuideOffsetLeftX = leftOffsetX;
		guideSettings.ingameGuideOffsetLeftY = leftOffsetY;
		guideSettings.ingameGuideOffsetRightX = rightOffsetX;
		guideSettings.ingameGuideOffsetRightY = rightOffsetY;
		if (touchSettings != null) {
			touchSettings.stickOffsetX = stickX;
			touchSettings.stickOffsetY = stickY;
			touchSettings.stickSize = stickSize;
			touchSettings.buttonsOffsetX = buttonsX;
			touchSettings.buttonsOffsetY = buttonsY;
			touchSettings.buttonSize = buttonSize;
		}
		Controlify.instance().config().saveSafely();
		MinecraftUtil.setScreen(parent);
	}

	@Override
	public void onClose() {
		commitAndClose();
	}

	/** The touch layout as it stands in the editor. */
	private TouchPad.Layout touchLayout() {
		return new TouchPad.Layout(stickX, stickY, stickSize, buttonsX, buttonsY, buttonSize);
	}

	@Override
	public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		boolean touch = tabs && page == TOUCH;
		if (touch) {
			// Under the controls: the touch controls as the game would draw them, moved and sized as set here.
			TouchPad.drawPreview(graphics, touchLayout());
		}

		super.extractRenderState(graphics, mouseX, mouseY, a);

		int subtitleY = 24;
		if (tabs) {
			subtitleY = TABBED_SUBTITLE_Y;
		} else {
			graphics.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
		}
		graphics.centeredText(font, Component.translatable(touch ? KEY + ".touch.subtitle" : KEY + ".subtitle"), width / 2, subtitleY, 0xFFA0A0A0);

		ClusterLayout layout = clusterLayout();
		int gridSize = layout.gridSize();

		graphics.centeredText(font, Component.translatable(touch ? KEY + ".touch.stick" : KEY + ".left_side"), layout.left().gridX() + gridSize / 2, layout.gridY() - LABEL_HEIGHT, 0xFFFFFFFF);
		graphics.centeredText(font, Component.translatable(touch ? KEY + ".touch.buttons" : KEY + ".right_side"), layout.right().gridX() + gridSize / 2, layout.gridY() - LABEL_HEIGHT, 0xFFFFFFFF);

		int rowY = layout.rowY();
		int leftRowX = layout.left().rowX();
		int rightRowX = layout.right().rowX();

		graphics.centeredText(font, Component.literal("X"), leftRowX + OFFSET_BOX_WIDTH / 2, rowY - 10, 0xFFAAAAAA);
		graphics.centeredText(font, Component.literal("Y"), leftRowX + OFFSET_BOX_WIDTH + OFFSET_BOX_GAP + OFFSET_BOX_WIDTH / 2, rowY - 10, 0xFFAAAAAA);
		graphics.centeredText(font, Component.literal("X"), rightRowX + OFFSET_BOX_WIDTH / 2, rowY - 10, 0xFFAAAAAA);
		graphics.centeredText(font, Component.literal("Y"), rightRowX + OFFSET_BOX_WIDTH + OFFSET_BOX_GAP + OFFSET_BOX_WIDTH / 2, rowY - 10, 0xFFAAAAAA);

		if (touch) {
			return;
		}

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
