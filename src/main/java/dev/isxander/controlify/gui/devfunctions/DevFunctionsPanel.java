/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.gui.devfunctions;

import dev.isxander.controlify.Controlify;
import dev.isxander.controlify.config.settings.GlobalSettings;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The "Dev Functions" panel in the lower part of the right-hand pane of Controlify's Global
 * Settings screen, plus the small toggle that shows or hides it.
 * <p>
 * YACL generates that screen, so the panel is attached by
 * {@code YACLScreenCategoryTabMixin}, which calls {@link #create} when the tab is built
 * (every time the screen opens or is resized) and adds {@link #visitWidgets} to the screen.
 * <p>
 * The buttons themselves come from {@link DevFunctions}.
 */
public final class DevFunctionsPanel {
	/** Only the Controlify Global Settings tab gets the panel. */
	private static final String HOST_CATEGORY_KEY = "controlify.gui.global_settings.title";

	/**
	 * The panel starts right below this option's description, which is the tallest one on the
	 * screen (an image plus several lines of text), so no description is covered by the panel.
	 */
	private static final String REFERENCE_OPTION_KEY = "controlify.gui.reach_around";
	/** Height / width of the reference option's description image (reach-around-placement.webp is 320x180). */
	private static final float REFERENCE_IMAGE_ASPECT = 180f / 320f;

	private static final int GAP = 6;
	private static final int INNER_PADDING = 4;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_SPACING = 2;
	/** Space between the two columns. */
	private static final int COLUMN_GAP = 4;
	/** Room a label needs either side of it inside a button before it starts to look cramped. */
	private static final int LABEL_INSET = 6;
	private static final int TOGGLE_SIZE = 20;
	/** A field row: the box, with its label beside it. */
	private static final int FIELD_HEIGHT = 16;
	private static final int FIELD_BOX_WIDTH = 46;
	private static final int FIELD_LABEL_GAP = 4;

	private final int top;
	private final Frame frame;
	private final List<Button> buttons = new ArrayList<>();
	private final List<DevFunctions.DevFunction> buttonFunctions = new ArrayList<>();
	private final List<Boolean> buttonAvailable = new ArrayList<>();
	private final List<Description> buttonDescriptions = new ArrayList<>();
	/** What a button says while it is greyed out; null where it has nothing different to say. */
	private final List<Description> buttonWaitDescriptions = new ArrayList<>();
	private final List<AbstractWidget> fieldWidgets = new ArrayList<>();
	private final List<Description> fieldDescriptions = new ArrayList<>();
	private final Button toggle;
	private final Label toggleLabel;
	private final Description toggleDescription;

	public static boolean isHost(ConfigCategory category) {
		return category.name().getContents() instanceof TranslatableContents tc
				&& HOST_CATEGORY_KEY.equals(tc.getKey());
	}

	/**
	 * @param screenWidth  width of the YACL screen
	 * @param category     the tab's category, used to measure the reference description
	 * @param tabArea      the tab area YACL gave the tab
	 * @param searchFieldY y of YACL's search box in the right pane; the toggle sits just above it
	 */
	public static DevFunctionsPanel create(int screenWidth, ConfigCategory category, ScreenRectangle tabArea, int searchFieldY) {
		// Same maths YACL uses (YACLScreen.CategoryTab) for the right pane's description area.
		int columnWidth = screenWidth / 3;
		int padding = columnWidth / 20;
		columnWidth = Math.min(columnWidth, 400);
		int paddedWidth = columnWidth - padding * 2;
		int left = screenWidth / 3 * 2 + padding;
		int descriptionTop = tabArea.top() + padding;

		Font font = Minecraft.getInstance().font;
		int top = descriptionTop + referenceDescriptionHeight(category, font, paddedWidth) + GAP;

		int toggleY = searchFieldY - 2 - TOGGLE_SIZE;
		int maxBottom = Math.max(toggleY - GAP, top);

		// The frame is only as tall as its contents, so adding or removing a button resizes the
		// panel instead of leaving a half-empty box stretched down to the toggle. Buttons pair up
		// two to a row where both labels fit, so the count of rows is what decides the height -
		// not the count of buttons.
		int rows = planRows(font, paddedWidth - INNER_PADDING * 2).size();
		int fields = DevFunctions.fields().size();
		int contentHeight = INNER_PADDING + font.lineHeight + INNER_PADDING
				+ rows * BUTTON_HEIGHT + Math.max(0, rows - 1) * BUTTON_SPACING
				+ (fields > 0 ? BUTTON_SPACING + fields * (FIELD_HEIGHT + BUTTON_SPACING) : 0)
				+ INNER_PADDING;
		int bottom = Math.min(top + contentHeight, maxBottom);

		return new DevFunctionsPanel(left, top, paddedWidth, bottom, toggleY, font);
	}

	/**
	 * Groups the buttons into rows: two side by side wherever both labels fit in half the width,
	 * and one across the whole width otherwise.
	 * <p>
	 * Pairing is decided by measuring rather than declared per button, because the panel is a third
	 * of the screen and that is a different number of pixels on every window size and GUI scale. A
	 * button that would have to scroll its own label to fit gets the full width instead, so a long
	 * name costs a row rather than becoming unreadable. On a narrow window nothing pairs and the
	 * panel is exactly what it was before.
	 * <p>
	 * Greedy and in order, so the list registered in {@link DevFunctions} is the reading order:
	 * two that fit become a row, and anything too wide breaks the pair and takes a row of its own.
	 */
	private static List<List<DevFunctions.DevFunction>> planRows(Font font, int buttonWidth) {
		int halfWidth = (buttonWidth - COLUMN_GAP) / 2;
		List<DevFunctions.DevFunction> all = DevFunctions.all();
		List<List<DevFunctions.DevFunction>> rows = new ArrayList<>();
		int i = 0;
		while (i < all.size()) {
			DevFunctions.DevFunction first = all.get(i);
			DevFunctions.DevFunction second = i + 1 < all.size() ? all.get(i + 1) : null;
			if (second != null && fitsHalf(font, first, halfWidth) && fitsHalf(font, second, halfWidth)) {
				rows.add(List.of(first, second));
				i += 2;
			} else {
				rows.add(List.of(first));
				i++;
			}
		}
		return rows;
	}

	private static boolean fitsHalf(Font font, DevFunctions.DevFunction function, int halfWidth) {
		return font.width(function.name()) + LABEL_INSET * 2 <= halfWidth;
	}

	private DevFunctionsPanel(int left, int top, int width, int bottom, int toggleY, Font font) {
		this.top = top;
		this.frame = new Frame(left, top, width, bottom - top, font);

		int buttonX = left + INNER_PADDING;
		int buttonWidth = width - INNER_PADDING * 2;
		int halfWidth = (buttonWidth - COLUMN_GAP) / 2;
		int y = top + INNER_PADDING + font.lineHeight + INNER_PADDING;
		for (List<DevFunctions.DevFunction> row : planRows(font, buttonWidth)) {
			if (y + BUTTON_HEIGHT > bottom - INNER_PADDING) {
				// Not enough room in this window size for more buttons.
				break;
			}
			boolean paired = row.size() == 2;
			for (int column = 0; column < row.size(); column++) {
				DevFunctions.DevFunction function = row.get(column);
				Button button = Button.builder(function.name(), btn -> function.action().run())
						.pos(paired ? buttonX + column * (halfWidth + COLUMN_GAP) : buttonX, y)
						.size(paired ? halfWidth : buttonWidth, BUTTON_HEIGHT)
						.build();
				buttons.add(button);
				buttonFunctions.add(function);
				buttonAvailable.add(function.available().getAsBoolean());
				buttonDescriptions.add(new Description(function.name(), function.tooltip()));
				buttonWaitDescriptions.add(function.waitTooltip() == null
						? null
						: new Description(function.name(), function.waitTooltip()));
			}
			y += BUTTON_HEIGHT + BUTTON_SPACING;
		}

		for (DevFunctions.DevField field : DevFunctions.fields()) {
			if (y + FIELD_HEIGHT > bottom - INNER_PADDING) {
				break;
			}
			int boxX = buttonX + buttonWidth - FIELD_BOX_WIDTH;
			NumberField box = new NumberField(font, boxX, y, FIELD_BOX_WIDTH, FIELD_HEIGHT, field);
			Label label = new Label(buttonX, y, buttonWidth - FIELD_BOX_WIDTH - FIELD_LABEL_GAP, FIELD_HEIGHT,
					field.name(), font);
			Description description = new Description(field.name(), field.tooltip());
			fieldWidgets.add(label);
			fieldDescriptions.add(description);
			fieldWidgets.add(box);
			fieldDescriptions.add(description);
			y += FIELD_HEIGHT + BUTTON_SPACING;
		}

		this.toggle = Button.builder(Component.empty(), btn -> setShown(!isShown()))
				.pos(left, toggleY)
				.size(TOGGLE_SIZE, TOGGLE_SIZE)
				.build();
		this.toggleDescription = new Description(
				Component.translatable("controlify.gui.dev_functions.toggle"),
				Component.translatable("controlify.gui.dev_functions.toggle.tooltip"));
		this.toggleLabel = new Label(left + TOGGLE_SIZE + 4, toggleY, width - TOGGLE_SIZE - 4, TOGGLE_SIZE,
				Component.translatable("controlify.gui.dev_functions.toggle"), font);

		applyVisibility();
	}

	/**
	 * Re-reads which buttons can be pressed. Called every tick, because some of them change while
	 * the screen is open: the Learn buttons grey out for the few seconds after a replug when there is
	 * nothing yet to learn, and light up again by themselves.
	 */
	public void tick() {
		boolean changed = false;
		for (int i = 0; i < buttons.size(); i++) {
			boolean available = buttonFunctions.get(i).available().getAsBoolean();
			if (available != buttonAvailable.get(i)) {
				buttonAvailable.set(i, available);
				changed = true;
			}
		}
		if (changed) {
			applyVisibility();
		}
	}

	/** Adds the panel's widgets to the screen, in draw order (frame behind the buttons). */
	public void visitWidgets(Consumer<AbstractWidget> consumer) {
		consumer.accept(frame);
		buttons.forEach(consumer);
		fieldWidgets.forEach(consumer);
		consumer.accept(toggle);
		consumer.accept(toggleLabel);
	}

	/**
	 * What one of the panel's widgets has to say about itself, for the screen's own description
	 * pane rather than a tooltip. The pane is right above the panel and already empty most of the
	 * time, while a tooltip floats over the cursor and covers whatever it is describing.
	 */
	public record Description(Component name, Component text) {
	}

	/**
	 * The description of whichever of the panel's widgets is under the cursor or holds focus, or
	 * null when none is.
	 * <p>
	 * The instances are made once and handed back unchanged, so whoever is showing one can tell it
	 * is still the same one and leave it alone - re-setting a description restarts its scroll. A
	 * greyed-out button hands back its waiting description instead, a different instance, so the
	 * pane changes the moment the button does.
	 */
	public @Nullable Description hovered() {
		for (int i = 0; i < buttons.size(); i++) {
			Button button = buttons.get(i);
			if (button.visible && button.isHoveredOrFocused()) {
				Description waiting = buttonWaitDescriptions.get(i);
				return !buttonAvailable.get(i) && waiting != null ? waiting : buttonDescriptions.get(i);
			}
		}
		for (int i = 0; i < fieldWidgets.size(); i++) {
			AbstractWidget widget = fieldWidgets.get(i);
			if (widget.visible && widget.isHoveredOrFocused()) {
				return fieldDescriptions.get(i);
			}
		}
		if (toggle.isHoveredOrFocused() || toggleLabel.isHoveredOrFocused()) {
			return toggleDescription;
		}
		return null;
	}

	/** y of the panel's top edge; the description area above is kept above this while the panel is shown. */
	public int top() {
		return top;
	}

	public boolean isShown() {
		return settings().showDevFunctions;
	}

	private void setShown(boolean shown) {
		settings().showDevFunctions = shown;
		Controlify.instance().config().saveSafely();
		applyVisibility();
	}

	private void applyVisibility() {
		boolean shown = isShown();
		frame.visible = shown;
		for (int i = 0; i < buttons.size(); i++) {
			Button button = buttons.get(i);
			DevFunctions.DevFunction function = buttonFunctions.get(i);
			boolean available = buttonAvailable.get(i);
			// Invisible and inactive: not drawn, can't be clicked, can't be reached with a controller.
			button.visible = shown;
			button.active = shown && available;
			// A greyed-out button says so on its face. Its description only shows while it is hovered
			// or focused, and a controller cannot focus a greyed-out button at all.
			button.setMessage(!available && function.waitName() != null ? function.waitName() : function.name());
		}
		for (AbstractWidget widget : fieldWidgets) {
			widget.visible = shown;
			// A Label is never interactive; a box is only typed into while the panel is up.
			widget.active = shown && widget instanceof EditBox;
		}
		toggle.setMessage(Component.literal(shown ? "✔" : ""));
	}

	private static GlobalSettings settings() {
		return Controlify.instance().config().getSettings().globalSettings();
	}

	/** Height YACL uses to draw the reference option's description (name, image, wrapped text). */
	private static int referenceDescriptionHeight(ConfigCategory category, Font font, int width) {
		@Nullable Option<?> reference = null;
		for (OptionGroup group : category.groups()) {
			for (Option<?> option : group.options()) {
				if (option.name().getContents() instanceof TranslatableContents tc
						&& REFERENCE_OPTION_KEY.equals(tc.getKey())) {
					reference = option;
				}
			}
		}

		int nameHeight = font.lineHeight + 5;
		int imageHeight = (int) (width * REFERENCE_IMAGE_ASPECT) + 5;
		if (reference == null) {
			// Fall back to an image and four lines of text if the option ever goes away.
			return nameHeight + imageHeight + font.lineHeight * 4;
		}
		int textHeight = font.split(reference.description().text(), width).size() * font.lineHeight;
		return nameHeight + imageHeight + textHeight;
	}

	/**
	 * A box for typing a whole number into, which never lets a value outside its field's range
	 * reach the setting. Half-typed and out-of-range text simply does not commit, and the box is
	 * put back to what actually got stored the moment it loses focus - so what is on screen is
	 * always what is in effect, rather than a number that was quietly rejected.
	 */
	private static final class NumberField extends EditBox {
		private final DevFunctions.DevField field;

		NumberField(Font font, int x, int y, int width, int height, DevFunctions.DevField field) {
			super(font, x, y, width, height, field.name());
			this.field = field;
			setMaxLength(String.valueOf(field.max()).length());
			setValue(String.valueOf(field.get().getAsInt()));
			setResponder(text -> {
				try {
					int value = Integer.parseInt(text);
					if (value >= field.min() && value <= field.max()) {
						field.set().accept(value);
						Controlify.instance().config().saveSafely();
					}
				} catch (NumberFormatException ignored) {
					// empty or half-typed: leave the stored value where it is
				}
			});
		}

		@Override
		public void setFocused(boolean focused) {
			super.setFocused(focused);
			if (!focused) {
				String committed = String.valueOf(field.get().getAsInt());
				if (!committed.equals(getValue())) {
					setValue(committed);
				}
			}
		}
	}

	/** Background, border and title of the panel. Not interactive. */
	private static final class Frame extends AbstractWidget {
		private final Font font;

		Frame(int x, int y, int width, int height, Font font) {
			super(x, y, width, height, Component.translatable("controlify.gui.dev_functions.title"));
			this.font = font;
			this.active = false;
		}

		@Override
		public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
			graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0x60000000);
			graphics.outline(getX(), getY(), getWidth(), getHeight(), 0xFF5A5A5A);
			graphics.text(font, getMessage(), getX() + INNER_PADDING, getY() + INNER_PADDING, 0xFFAAAAAA);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			output.add(NarratedElementType.TITLE, getMessage());
		}
	}

	/** Text shown next to the toggle. Not interactive. */
	private static final class Label extends AbstractWidget {
		private final Font font;

		Label(int x, int y, int width, int height, Component text, Font font) {
			super(x, y, width, height, text);
			this.font = font;
			this.active = false;
		}

		@Override
		public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
			graphics.text(font, getMessage(), getX(), getY() + (getHeight() - font.lineHeight) / 2 + 1, 0xFFFFFFFF);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			output.add(NarratedElementType.TITLE, getMessage());
		}
	}
}
