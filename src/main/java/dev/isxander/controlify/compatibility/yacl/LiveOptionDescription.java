/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.compatibility.yacl;

import dev.isxander.controlify.gui.controllers.TabExplainerController;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.gui.DescriptionWithName;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Keeps YACL's description pane in step with the option that has focus, on Controlify's own
 * screens.
 * <p>
 * YACL hands the pane an option's description when focus arrives on it, and every frame the mouse
 * is over it, but never when the value of a focused option changes. So a description written for
 * the value - Target Lock's Mode and Keybind Mode, the warnings under Aim Assist and Ignore
 * Crosshair Cone - went on describing the old value for anyone driving the screen with a
 * controller, until focus left the option and came back. YACLScreenCategoryTabMixin asks this
 * every tick whether the pane needs catching up.
 * <p>
 * It also says what a tab's pane opens with ({@link #opening}), which YACL leaves empty.
 * <p>
 * Kept apart from the mixin, with nothing of the screen in it, so the rule can be exercised on its
 * own.
 */
public final class LiveOptionDescription {
	private LiveOptionDescription() {
	}

	/** Whether a category belongs to one of Controlify's own screens. Other mods' are left alone. */
	public static boolean isControlifyCategory(ConfigCategory category) {
		return category.name().getContents() instanceof TranslatableContents contents
				&& contents.getKey().startsWith("controlify.");
	}

	/**
	 * What the pane should show in place of {@code shown}, or null to leave it as it is.
	 * <p>
	 * Only ever the focused option's own description, brought up to date. When the pane is showing
	 * something else - an entry under the mouse, a group heading - it is left alone, and so it is
	 * when the text has not actually changed, since setting the pane scrolls it back to the top.
	 */
	public static @Nullable DescriptionWithName refreshed(@Nullable DescriptionWithName shown, @Nullable Option<?> focused) {
		if (shown == null || focused == null) {
			return null;
		}
		OptionDescription now = focused.description();
		if (now == shown.description() || now.text().equals(shown.description().text())) {
			return null;
		}
		DescriptionWithName current = DescriptionWithName.of(focused.name(), now);
		return current.name().equals(shown.name()) ? current : null;
	}

	/**
	 * What a tab's description pane opens with, or null to leave it empty as YACL does: the tab's
	 * own line, when its first row is one - a {@link TabExplainerController} row named after the tab
	 * itself, the way each Aim Assist tab opens (tl86). Only on Controlify's own screens. YACL's own
	 * label rows on the other screens are a different controller, and are left as they are.
	 */
	public static @Nullable DescriptionWithName opening(ConfigCategory category) {
		if (!isControlifyCategory(category) || category.groups().isEmpty()) {
			return null;
		}
		List<? extends Option<?>> top = category.groups().get(0).options();
		if (top.isEmpty()) {
			return null;
		}
		Option<?> first = top.get(0);
		if (!(first.controller() instanceof TabExplainerController) || !first.name().equals(category.name())) {
			return null;
		}
		return DescriptionWithName.of(first.name(), first.description());
	}
}
