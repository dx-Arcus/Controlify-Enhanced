/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.compatibility.yacl.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.isxander.controlify.api.buttonguide.ButtonGuideApi;
import dev.isxander.controlify.api.buttonguide.ButtonGuidePredicate;
import dev.isxander.controlify.bindings.ControlifyBindings;
import dev.isxander.controlify.compatibility.yacl.LiveOptionDescription;
import dev.isxander.controlify.gui.devfunctions.DevFunctionsPanel;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.gui.DescriptionWithName;
import dev.isxander.yacl3.gui.OptionDescriptionWidget;
import dev.isxander.yacl3.gui.OptionListWidget;
import dev.isxander.yacl3.gui.YACLScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;
import java.util.function.Supplier;

@Mixin(YACLScreen.CategoryTab.class)
public class YACLScreenCategoryTabMixin {
	@Shadow @Final
	public Button saveFinishedButton;

	@Shadow @Final
	public Button undoButton;

	/** The Dev Functions panel; only present on Controlify's Global Settings tab. */
	@Unique @Nullable private DevFunctionsPanel controlify$devFunctionsPanel;

	/** The description pane at the top of the right-hand column. */
	@Unique @Nullable private OptionDescriptionWidget controlify$descriptionWidget;

	/**
	 * The last description YACL asked for, to put back when the cursor leaves the panel - or the
	 * same option's description brought up to date, once its value has changed under focus.
	 */
	@Unique @Nullable private DescriptionWithName controlify$optionDescription;

	/** The panel description currently being shown in the pane, or null when YACL's own is. */
	@Unique @Nullable private DevFunctionsPanel.Description controlify$shownDescription;

	/** Whether this tab is on one of Controlify's own screens, the only ones kept live. */
	@Unique private boolean controlify$liveDescriptions;

	/**
	 * What this tab's description pane opens with, or null for YACL's empty pane (tl87). Taken when
	 * the tab is built, from the category handed to the constructor, rather than from a shadow of
	 * YACL's private field: a renamed field would stop the mod loading, where a moved hook with
	 * require = 0 only stops this.
	 */
	@Unique @Nullable private DescriptionWithName controlify$opening;

	@Inject(method = "<init>", at = @At("RETURN"), require = 0)
	private void onConstructCategory(CallbackInfo ci) {
		ButtonGuideApi.addGuideToButton(saveFinishedButton, ControlifyBindings.GUI_ABSTRACT_ACTION_1, ButtonGuidePredicate.always());
	}

	@Inject(method = "<init>", at = @At("RETURN"), require = 0)
	private void controlify$addDevFunctionsPanel(YACLScreen screen, ConfigCategory category, ScreenRectangle tabArea, CallbackInfo ci) {
		if (DevFunctionsPanel.isHost(category)) {
			// YACL puts its search box 22px above the Cancel/Undo row.
			int searchFieldY = undoButton.getY() - 22;
			controlify$devFunctionsPanel = DevFunctionsPanel.create(screen.width, category, tabArea, searchFieldY);
		}
	}

	@Inject(method = "<init>", at = @At("RETURN"), require = 0)
	private void controlify$checkLiveDescriptions(YACLScreen screen, ConfigCategory category, ScreenRectangle tabArea, CallbackInfo ci) {
		controlify$liveDescriptions = LiveOptionDescription.isControlifyCategory(category);
		controlify$opening = LiveOptionDescription.opening(category);
	}

	/**
	 * While the Dev Functions panel is shown, keep the option description area above it, so
	 * long descriptions scroll instead of being drawn underneath the panel.
	 */
	@WrapOperation(method = "<init>", at = @At(value = "NEW", target = "dev/isxander/yacl3/gui/OptionDescriptionWidget"), require = 0)
	private OptionDescriptionWidget controlify$limitDescriptionArea(Supplier<ScreenRectangle> dimensions, DescriptionWithName description, Operation<OptionDescriptionWidget> original) {
		Supplier<ScreenRectangle> limited = () -> {
			ScreenRectangle rect = dimensions.get();
			DevFunctionsPanel panel = controlify$devFunctionsPanel;
			if (panel != null && panel.isShown()) {
				int maxHeight = Math.max(0, panel.top() - 3 - rect.top());
				if (maxHeight < rect.height()) {
					return new ScreenRectangle(rect.left(), rect.top(), rect.width(), maxHeight);
				}
			}
			return rect;
		};
		OptionDescriptionWidget widget = original.call(limited, description);
		controlify$descriptionWidget = widget;
		return widget;
	}

	/**
	 * Remembers which option description YACL last asked for, so it can be put back once the
	 * cursor leaves the Dev Functions panel, and kept current while its option has focus. Nothing
	 * else about the list changes.
	 */
	@WrapOperation(method = "<init>", at = @At(value = "NEW", target = "dev/isxander/yacl3/gui/OptionListWidget"), require = 0)
	private OptionListWidget controlify$rememberOptionDescription(
			YACLScreen screen, ConfigCategory category, Minecraft client, int x, int y, int width, int height,
			Consumer<DescriptionWithName> hoverEvent, Operation<OptionListWidget> original) {
		Consumer<DescriptionWithName> remembering = description -> {
			controlify$optionDescription = description;
			hoverEvent.accept(description);
		};
		return original.call(screen, category, client, x, y, width, height, remembering);
	}

	/**
	 * Shows a Dev Functions widget's description in the pane above the panel while it is hovered or
	 * focused, rather than in a tooltip floating over the cursor - which on this screen covers the
	 * pane it would otherwise be read in, and the panel it is describing.
	 * <p>
	 * The pane is only told to change when the description actually changes, because setting one
	 * puts its scroll back to the top: a long description would never scroll if this fired on every
	 * tick. The panel hands back the same instance while the cursor stays put, which is what makes
	 * that comparison work.
	 * <p>
	 * The panel also re-reads which of its buttons can be pressed here first, since some change while
	 * the screen is open.
	 */
	@Inject(method = "tick", at = @At("TAIL"), require = 0)
	private void controlify$showDevFunctionDescription(CallbackInfo ci) {
		DevFunctionsPanel panel = controlify$devFunctionsPanel;
		if (panel == null) {
			return;
		}
		panel.tick();

		OptionDescriptionWidget widget = controlify$descriptionWidget;
		if (widget == null) {
			return;
		}

		DevFunctionsPanel.Description hovered = panel.hovered();
		if (hovered != null) {
			if (hovered != controlify$shownDescription) {
				controlify$shownDescription = hovered;
				widget.setOptionDescription(DescriptionWithName.of(
						hovered.name(), OptionDescription.of(hovered.text())));
			}
		} else if (controlify$shownDescription != null) {
			controlify$shownDescription = null;
			if (controlify$optionDescription != null) {
				widget.setOptionDescription(controlify$optionDescription);
			}
		}
	}

	/**
	 * Keeps the description pane current while an option has focus, on Controlify's own screens.
	 * YACL hands the pane an option's description when focus arrives and every frame the mouse is
	 * over it, but not when the value of a focused option changes - so with a controller, a
	 * description written for the value (Target Lock's Mode, for one) kept describing the old value
	 * until focus left and came back. {@link LiveOptionDescription} decides when to step in: only
	 * while the pane is showing that same option, and never while the Dev Functions panel has it.
	 */
	@Inject(method = "tick", at = @At("TAIL"), require = 0)
	private void controlify$keepFocusedDescriptionCurrent(CallbackInfo ci) {
		OptionDescriptionWidget widget = controlify$descriptionWidget;
		if (!controlify$liveDescriptions || widget == null || controlify$shownDescription != null) {
			return;
		}
		OptionListWidget list = ((YACLScreenCategoryTabAccessor) (Object) this).getOptionList().getType();
		Option<?> focused = list.getFocused() instanceof OptionListWidget.OptionEntry entry ? entry.option : null;
		DescriptionWithName current = LiveOptionDescription.refreshed(controlify$optionDescription, focused);
		if (current != null) {
			controlify$optionDescription = current;
			widget.setOptionDescription(current);
		}
	}

	/**
	 * Opens a tab on its own line of text, where it has one - the explainer at the top of each Aim
	 * Assist tab (tl86). YACL starts a tab's description pane empty and fills it only from what is
	 * hovered or focused, so clicked with the mouse a tab showed nothing there until its line was
	 * pointed at (Donny, 27 Sep). The game lays a tab out each time it becomes the one shown - clicked,
	 * LB or RB, or the screen opening or resizing - so the pane is set here, every time. Anything
	 * hovered or focused afterwards takes the pane over as before.
	 */
	@Inject(method = "doLayout", at = @At("RETURN"), require = 0)
	private void controlify$openOnTabLine(ScreenRectangle tabArea, CallbackInfo ci) {
		OptionDescriptionWidget widget = controlify$descriptionWidget;
		DescriptionWithName opening = controlify$opening;
		if (widget == null || opening == null || controlify$shownDescription != null) {
			return;
		}
		controlify$optionDescription = opening;
		widget.setOptionDescription(opening);
	}

	@Inject(method = "visitChildren", at = @At("TAIL"), require = 0)
	private void controlify$visitDevFunctionsPanel(Consumer<AbstractWidget> consumer, CallbackInfo ci) {
		if (controlify$devFunctionsPanel != null) {
			controlify$devFunctionsPanel.visitWidgets(consumer);
		}
	}
}
