/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.screenop.compat.vanilla;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * The quick move button on something people keep in the off hand, in your own inventory (tl91,
 * Donny 28 Sep, and asked for on Controlify's Discord): while the off hand is empty, it goes there -
 * the game's own swap with the off hand, the click F makes on a keyboard, and only when F would:
 * with nothing held on the cursor. On anything else, with the off hand already holding something,
 * or in any other screen - a chest, say - the button quick moves as it always has, which still
 * takes what is in the off hand back out.
 */
final class OffhandMove {
	/** Every lantern, the copper ones included: the game's own item tag, the same in 26.1 to 26.3. */
	private static final TagKey<Item> LANTERNS = TagKey.create(Registries.ITEM, Identifier.withDefaultNamespace("lanterns"));

	private OffhandMove() {
	}

	/** Whether the quick move button on this slot sends its item to the off hand instead. */
	static boolean goesToOffhand(AbstractContainerScreen<?> screen, @Nullable Slot slot, @Nullable Player player) {
		if (!(screen instanceof InventoryScreen) || slot == null || player == null) {
			return false;
		}
		// The main inventory and the hotbar: not the armour, the crafting grid, or the off hand itself.
		if (slot.container != player.getInventory() || slot.getContainerSlot() >= Inventory.INVENTORY_SIZE) {
			return false;
		}
		// F on a keyboard does nothing while something is held on the cursor, so then the button quick moves.
		if (!screen.getMenu().getCarried().isEmpty()) {
			return false;
		}
		return player.getOffhandItem().isEmpty() && isOffhandItem(slot.getItem());
	}

	/** Totems, shields, rockets, torches, lanterns, end rods, maps and arrows: the list Donny picked, 28 Sep. */
	static boolean isOffhandItem(ItemStack stack) {
		return stack.is(Items.TOTEM_OF_UNDYING)
				|| stack.is(Items.SHIELD)
				|| stack.is(Items.FIREWORK_ROCKET)
				|| stack.is(Items.TORCH)
				|| stack.is(Items.SOUL_TORCH)
				|| stack.is(Items.REDSTONE_TORCH)
				|| stack.is(Items.COPPER_TORCH)
				|| stack.is(LANTERNS)
				|| stack.is(Items.END_ROD)
				|| stack.is(Items.MAP)
				|| stack.is(Items.FILLED_MAP)
				|| stack.is(ItemTags.ARROWS);
	}
}
