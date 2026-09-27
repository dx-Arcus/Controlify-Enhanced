/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.gui.controllers;

import dev.isxander.yacl3.api.Controller;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.utils.Dimension;
import dev.isxander.yacl3.gui.AbstractWidget;
import dev.isxander.yacl3.gui.YACLScreen;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The line at the top of each Aim Assist tab (tl86), drawn as the tab's header: larger than the rows,
 * on a dark band across the list with room above and below - the look Donny sketched on 27 Sep. He
 * then had it made 10% smaller and not bold (tl89). It holds no setting and is never saved. A
 * controller can land on it, as on YACL's own label rows, and the description pane shows its words.
 * <p>
 * The larger size is kept only while the line fits in {@link #MAX_LARGE_LINES} lines at it; on a
 * narrower screen it drops to the rows' size, so the band never crowds out the settings under it.
 * Everything drawn here - a fill, an outline, text under a scaled pose - is drawn the same way in
 * 26.1, 26.2 and 26.3.
 */
public record TabExplainerController(Option<Component> option) implements Controller<Component> {
	/**
	 * How much larger than the rows the line is drawn, while that fits in {@link #MAX_LARGE_LINES}:
	 * 1.5 in tl88, 10% less since tl89 at Donny's asking.
	 */
	public static final float LARGE_SCALE = 1.35f;
	public static final int MAX_LARGE_LINES = 2;
	/** Black at about two thirds, so the world shows faintly through, as in the sketch. */
	public static final int BAND_COLOR = 0xB0000000;
	public static final int TEXT_COLOR = 0xFFFFFFFF;
	/** From the band's left edge to the text; the rows' names sit 5 in, so the line stands apart. */
	public static final int PAD_X = 10;
	/** Above the text and below it, inside the band. */
	public static final int PAD_Y = 9;
	/** Left clear under the band before the first setting, on top of the list's own 2. */
	public static final int GAP_BELOW = 4;

	@Override
	public Component formatValue() {
		return option.pendingValue();
	}

	@Override
	public AbstractWidget provideWidget(YACLScreen screen, Dimension<Integer> widgetDimension) {
		return new Element(this, widgetDimension);
	}

	/**
	 * How the line sits in a band: the size it is drawn at, its lines, how far apart they are, and
	 * the band's height.
	 */
	public record Layout(float scale, List<FormattedCharSequence> lines, int lineStep, int bandHeight) {
		/** The whole row: the band and the gap left under it. */
		public int height() {
			return bandHeight + GAP_BELOW;
		}
	}

	/**
	 * Lays the line out in a band {@code width} wide: at {@link #LARGE_SCALE} if that keeps it to
	 * {@link #MAX_LARGE_LINES} lines, otherwise at the rows' size. Lines are a whole number of pixels
	 * apart, and the band is {@link #PAD_Y} taller than the text above and below - the text measured
	 * without the empty pixel row the font keeps under every line.
	 */
	public static Layout layout(Font font, Component text, int width) {
		float scale = LARGE_SCALE;
		List<FormattedCharSequence> lines = font.split(text, wrapWidth(width, scale));
		if (lines.size() > MAX_LARGE_LINES) {
			scale = 1f;
			lines = font.split(text, wrapWidth(width, scale));
		}
		int lineStep = Math.round(font.lineHeight * scale);
		int textHeight = (lines.size() - 1) * lineStep + Math.round((font.lineHeight - 1) * scale);
		return new Layout(scale, lines, lineStep, textHeight + PAD_Y * 2);
	}

	/** How wide a line may be before the size it is drawn at. */
	private static int wrapWidth(int width, float scale) {
		return Math.max(1, (int) ((width - PAD_X * 2) / scale));
	}

	public static class Element extends AbstractWidget {
		private final TabExplainerController control;
		private Layout layout;
		private boolean focused;

		public Element(TabExplainerController control, Dimension<Integer> dim) {
			super(dim);
			this.control = control;
			relayout();
		}

		/** Lays the line out again for the row's width, and makes the row as tall as the band needs. */
		private void relayout() {
			layout = TabExplainerController.layout(textRenderer, control.formatValue(), getDimension().width());
			setDimension(getDimension().withHeight(layout.height()));
		}

		public Layout currentLayout() {
			return layout;
		}

		@Override
		public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
			relayout();
			Dimension<Integer> dim = getDimension();
			int left = dim.x();
			int top = dim.y();
			graphics.fill(left, top, dim.xLimit(), top + layout.bandHeight(), BAND_COLOR);

			for (int i = 0; i < layout.lines().size(); i++) {
				graphics.pose().pushMatrix();
				graphics.pose().translate(left + PAD_X, top + PAD_Y + i * layout.lineStep());
				graphics.pose().scale(layout.scale(), layout.scale());
				graphics.text(textRenderer, layout.lines().get(i), 0, 0, TEXT_COLOR, true);
				graphics.pose().popMatrix();
			}

			// Where a controller has landed, the way YACL marks its own focused label rows.
			if (isFocused()) {
				graphics.outline(left - 1, top - 1, dim.width() + 2, layout.bandHeight() + 2, 0xFFFFFFFF);
			}
		}

		@Override
		public boolean matchesSearch(String query) {
			return control.formatValue().getString().toLowerCase().contains(query.toLowerCase());
		}

		@Override
		public @Nullable ComponentPath nextFocusPath(FocusNavigationEvent event) {
			if (!control.option().available()) {
				return null;
			}
			return !isFocused() ? ComponentPath.leaf(this) : null;
		}

		@Override
		public boolean isFocused() {
			return focused;
		}

		@Override
		public void setFocused(boolean focused) {
			this.focused = focused;
		}

		@Override
		public void updateNarration(NarrationElementOutput builder) {
			builder.add(NarratedElementType.TITLE, control.formatValue());
		}

		@Override
		public NarrationPriority narrationPriority() {
			return NarrationPriority.FOCUSED;
		}
	}
}
