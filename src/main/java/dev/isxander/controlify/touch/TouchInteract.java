/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.touch;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.animal.camel.CamelHusk;
import net.minecraft.world.entity.animal.cow.AbstractCow;
import net.minecraft.world.entity.animal.cow.MushroomCow;
import net.minecraft.world.entity.animal.dolphin.Dolphin;
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.SkeletonHorse;
import net.minecraft.world.entity.animal.equine.ZombieHorse;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.animal.fish.AbstractFish;
import net.minecraft.world.entity.animal.frog.Tadpole;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.SnowGolem;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.animal.nautilus.AbstractNautilus;
import net.minecraft.world.entity.animal.nautilus.ZombieNautilus;
import net.minecraft.world.entity.animal.panda.Panda;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.skeleton.Bogged;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.SuspiciousEffectHolder;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * The interact button (tl119): Bedrock's labelled button for what the player can do with what is in front of them -
 * Trade, Mount, Shear and the rest - centred over the hotbar while there is something to do, in both modes: for the
 * mob under the crosshair in aim crosshair mode, the one in the middle of the screen in tap mode, where a tap on a
 * mob attacks it and this is the way to do anything else with one (Donny, 18:13: Bedrock shows it in both). Pressing
 * it pulls use, the game's own, with the game's pick on that mob ({@link TouchTap#aim}): what happens is what a right
 * click there does.
 *
 * <p>The word comes from {@link #action}, the game's own order of checks for a player's interaction, for what the
 * server does with what the player holds, the main hand first: the name tag and the spawn egg
 * ({@code Mob.checkAndHandleImportantInteractions}); leads and shears ({@code Entity.interact}); the mob's own
 * ({@code mobInteract}); then the item on it ({@code Player.interactOn}: gear it equips, dye on a sheep, a name tag on
 * what is not a mob). Where the game does something the button has no word for, or the client cannot know whether
 * it will, there is no button. Our own box in the touch buttons' greys and frame, with the word in the game's font:
 * nothing of the game's art (Donny, 18:13: "the look is good").
 */
public final class TouchInteract {
	/** The button's height, in GUI pixels: a hotbar slot's. */
	static final int HEIGHT = 20;
	/** Room either side of the word, in GUI pixels (Bedrock's, measured off Donny's screenshots, 15:50). */
	static final int PAD = 11;
	/** The narrowest the button gets, so a short word still makes a button a thumb can find. */
	static final int MIN_WIDTH = 40;
	/** Between the button's foot and what is under it, in GUI pixels (Bedrock's, as {@link #PAD}; 18:13 too). */
	static final int GAP = 11;
	/** Where the word's top sits below the button's, in GUI pixels: its capitals, seven high, in the middle. */
	private static final int TEXT_TOP = 6;

	/** The touch buttons' colours ({@link TouchButtons}): the outline, the fill, the word and its shadow, at rest and held. */
	private static final int OUTLINE = 0xC0A4A4A4;
	private static final int FILL = 0x70000000;
	private static final int PICTURE = 0xF0C4C4C4;
	private static final int SHADOW = 0xE0101010;
	private static final int OUTLINE_HELD = 0xFFD8D8D8;
	private static final int FILL_HELD = 0xA0B4B4B4;
	private static final int PICTURE_HELD = 0xF0303030;
	private static final int SHADOW_HELD = 0x50000000;

	/** The middle of the window, 0 to 1 across and down: where the button's mob is in tap mode. */
	private static final float[] CENTRE = {0.5f, 0.5f};

	/** What the button says it does: plain words, as Bedrock's (Donny, 18:25). */
	public enum Action {
		NAME, SPAWN_BABY, UNLEASH, LEASH, SHEAR, TRADE, MILK, FEED, TAME, SIT, STAND, DYE, SADDLE, EQUIP, MOUNT, RIDE, OPEN,
		/** The game does something here, but nothing the button names, or not what the client can foresee: no button. */
		NONE;

		/** The word, from the lang file. */
		public Component label() {
			return Component.translatable("controlify.touch.interact." + name().toLowerCase(Locale.ROOT));
		}
	}

	/** What the target is, as far as the rules go: the kinds with interactions of their own, then the rest. */
	enum Kind {
		VILLAGER, TRADER, SHEEP, COW, MOOSHROOM, GOAT, HORSE, ZOMBIE_HORSE, CHESTED_HORSE, SKELETON_HORSE, CAMEL, PIG,
		HAPPY_GHAST, NAUTILUS, WOLF, CAT, PARROT, SNOW_GOLEM, BOGGED, COPPER_GOLEM, ARMADILLO, PANDA, TADPOLE, FISH,
		DOLPHIN, ANIMAL, MOB,
		BOAT, CHEST_BOAT, MINECART, CONTAINER_MINECART,
		LIVING, OTHER;

		/** A {@code Mob}: name tags and spawn eggs first, its own interaction after the leads. */
		boolean mob() {
			return ordinal() <= MOB.ordinal();
		}

		/** A boat or a minecart. */
		boolean vehicle() {
			return ordinal() >= BOAT.ordinal() && ordinal() <= CONTAINER_MINECART.ordinal();
		}
	}

	/** What the rules need to know of the target, of the player, and of the two together. */
	interface Target {
		Kind kind();

		boolean alive();

		boolean baby();

		/** A living thing a name tag names: one whose kind is saved with the world, not a player. */
		boolean nameable();

		/** Whether its own spawn egg makes a baby of it: one that can be a baby, and whose breeding makes one. */
		boolean babyFromEgg();

		boolean leashable();

		/** Leashed to this player. */
		boolean leashedToPlayer();

		/** Leashed to a player, this one or another. */
		boolean leashedToAPlayer();

		/** Would take a lead from this player: near enough, and one that takes leads ({@code canHaveALeashAttachedTo}). */
		boolean takesLead();

		/** Leashed, or holding mobs on leads: what shears cut ({@code Entity.dropAllLeashConnections}). */
		boolean leashConnections();

		/** Takes leads, and the player holds mobs on leads nearby that it would take ({@code Entity.interact}, sneaking). */
		boolean tiesLeashes();

		/** Wears gear shears take off, and lets this player take it ({@code Mob.canShearEquipment}, the wolf's owner). */
		boolean shearableGear();

		/** {@code Shearable.readyForShearing}. */
		boolean readyForShearing();

		/** A sheep with no wool. */
		boolean sheared();

		boolean sleeping();

		/** A villager with trades, as far as the client can tell: a profession, not none or nitwit (its offers are the server's). */
		boolean trades();

		/** Tame, or for a horse tamed. */
		boolean tame();

		/** Owned by this player. */
		boolean owned();

		/** Sitting, as its pose shows: what a toggle would turn it from. */
		boolean sitting();

		boolean hurt();

		boolean angry();

		boolean flying();

		/** Somebody rides it. */
		boolean ridden();

		int riders();

		/** A boat with room for the player and its eyes out of water. */
		boolean room();

		boolean saddled();

		/** Wears something in its body slot: horse armour, a carpet, wolf armour, a harness. */
		boolean armoured();

		/** A donkey, mule or llama carrying a chest. */
		boolean hasChest();

		/** A brown mooshroom. */
		boolean brown();

		/** An armadillo rolled up, or a panda scared. */
		boolean scared();

		/** A panda lying on its back. */
		boolean onBack();

		/** A copper golem holding an item. */
		boolean holding();

		/** Kept from growing up (the golden dandelion). */
		boolean ageLocked();

		/** Caught in a water bucket ({@code Bucketable}). */
		boolean bucketable();

		/** Its wool, or for a wolf or a cat its collar; null for anything else. */
		@Nullable DyeColor colour();

		/** The player holds a golden dandelion, in either hand. */
		boolean dandelion();
	}

	/** What the rules need to know of what one hand holds, against the target. */
	interface Held {
		boolean empty();

		/** A name tag with a name to give. */
		boolean namedTag();

		/** Any spawn egg; {@link #ownEgg} for the target's own kind. */
		boolean spawnEgg();

		boolean ownEgg();

		boolean villagerEgg();

		boolean shears();

		boolean bucket();

		boolean waterBucket();

		boolean bowl();

		boolean lead();

		boolean bone();

		boolean chest();

		boolean brush();

		boolean honeycomb();

		boolean axe();

		boolean goldenDandelion();

		/** A dye, and its colour; null for anything that is not one. */
		@Nullable DyeColor dye();

		/** A dye the target's collar takes (wolf or cat). */
		boolean collarDye();

		/** The target's food ({@code isFood}); for a dolphin a fish, for a tadpole a slime ball. */
		boolean food();

		boolean parrotFood();

		boolean parrotPoison();

		/** What a brown mooshroom puts in its stew. */
		boolean stewIngredient();

		/** The slot this goes into on the target when used on it ({@code Equippable.equipOnTarget}), or null. */
		@Nullable EquipmentSlot equips();

		/** Something the target could wear in its body slot. */
		boolean bodyArmour();

		/** Something the target could wear in its saddle slot. */
		boolean saddle();

		/** Mends the damaged armour the target wears. */
		boolean repairs();

		/** An item with a use of its own ({@link TouchTap#holdUses}): used before the other hand is tried. */
		boolean usesItself();
	}

	/** The button as shown: what it does, its word and how wide that is in the game's font, and where. */
	record Shown(Action action, Component label, int textWidth, Box box) {
	}

	/** Where the button is, in GUI pixels: its top-left corner and its size. */
	record Box(int x, int y, int width, int height) {
		boolean contains(double guiX, double guiY) {
			return guiX >= x && guiX < x + width && guiY >= y && guiY < y + height;
		}
	}

	/** The button this frame, or null; and how far the item name and the action bar move up for it. */
	private static @Nullable Shown shown;
	private static int lift;
	/** The finger on the button, and when it landed; whether use is pulled for it this frame. */
	private static TouchPad.@Nullable FingerKey finger;
	private static long pressedAt;
	private static boolean pressed;

	private TouchInteract() {
	}

	/** What the button does with the target, the main hand first, then the other; null for no button. */
	static @Nullable Action action(Target target, Held main, Held off, boolean sneaking) {
		Action action = forHand(target, main, sneaking);
		if (action != null) {
			return action == Action.NONE ? null : action;
		}
		// A main hand with a use of its own is used before the other hand is tried (Minecraft.startUseItem).
		if (main.usesItself()) {
			return null;
		}
		action = forHand(target, off, sneaking);
		return action == Action.NONE ? null : action;
	}

	/**
	 * What one hand does: null when nothing (the other hand is tried), {@link Action#NONE} when something the button has
	 * no word for.
	 */
	static @Nullable Action forHand(Target t, Held h, boolean sneaking) {
		Kind kind = t.kind();
		if (kind == Kind.OTHER || !t.alive()) {
			return null;
		}
		if (kind.mob()) {
			// Mob.checkAndHandleImportantInteractions: a name tag with a name; then any spawn egg, at which the client
			// stops - the other hand is never tried - while the server makes a baby from the mob's own egg, for a mob
			// that has babies, and otherwise goes on with the egg as with anything else.
			if (h.namedTag() && t.nameable()) {
				return Action.NAME;
			}
			if (h.spawnEgg()) {
				if (h.ownEgg() && t.babyFromEgg()) {
					return Action.SPAWN_BABY;
				}
				Action rest = afterEggs(t, h, sneaking);
				return rest != null ? rest : Action.NONE;
			}
		}
		return afterEggs(t, h, sneaking);
	}

	/** One hand, past the name tag and the spawn egg: the leads and shears, a vehicle's or the mob's own, then the item on it. */
	private static @Nullable Action afterEggs(Target t, Held h, boolean sneaking) {
		Kind kind = t.kind();
		// Entity.interact: the leads the player holds tied to it, sneaking; shears cutting its leads, then its gear;
		// its own lead let go of, or put on.
		if (sneaking && t.tiesLeashes()) {
			return Action.LEASH;
		}
		if (h.shears() && t.leashConnections()) {
			return Action.UNLEASH;
		}
		if (kind.mob() && h.shears() && t.shearableGear() && !sneaking) {
			return Action.SHEAR;
		}
		if (t.leashable()) {
			if (t.leashedToPlayer()) {
				return Action.UNLEASH;
			}
			if (h.lead() && !t.leashedToAPlayer()) {
				return t.takesLead() ? Action.LEASH : Action.NONE;
			}
		}
		if (kind.vehicle()) {
			return vehicle(t, sneaking);
		}
		if (kind.mob()) {
			Action own = mobInteract(t, h, sneaking);
			if (own != null) {
				return own;
			}
		}
		return itemOn(t, h);
	}

	/** A boat or a minecart's own interaction, after the leads. */
	private static @Nullable Action vehicle(Target t, boolean sneaking) {
		return switch (t.kind()) {
			case BOAT -> !sneaking && t.room() ? Action.RIDE : null;
			// AbstractChestBoat: aboard if there is room; full, or sneaking, its chest.
			case CHEST_BOAT -> !sneaking && t.room() ? Action.RIDE : Action.OPEN;
			case MINECART -> !sneaking && !t.ridden() ? Action.RIDE : null;
			case CONTAINER_MINECART -> Action.OPEN;
			default -> null;
		};
	}

	/** {@code Player.interactOn}'s last try, on a living target: the item itself - gear it equips, dye on a sheep, a name tag. */
	private static @Nullable Action itemOn(Target t, Held h) {
		if (t.kind() == Kind.OTHER || t.kind().vehicle() || h.empty()) {
			return null;
		}
		EquipmentSlot slot = h.equips();
		if (slot != null) {
			return gear(slot);
		}
		if (t.kind() == Kind.SHEEP && h.dye() != null && !t.sheared()) {
			return h.dye() != t.colour() ? Action.DYE : null;
		}
		if (h.namedTag() && t.nameable()) {
			return Action.NAME;
		}
		return null;
	}

	/** Gear going on: a saddle, or anything else. */
	private static Action gear(EquipmentSlot slot) {
		return slot == EquipmentSlot.SADDLE ? Action.SADDLE : Action.EQUIP;
	}

	/** The mob's own {@code mobInteract}: null when it passes, so the item gets its try. */
	private static @Nullable Action mobInteract(Target t, Held h, boolean sneaking) {
		return switch (t.kind()) {
			case VILLAGER -> {
				// A sleeper passes, and so does a villager offered a villager's egg; a baby shakes its head, and so
				// does a villager with no trades.
				if (t.sleeping() || h.villagerEgg()) {
					yield animal(t, h);
				}
				yield t.baby() || !t.trades() ? Action.NONE : Action.TRADE;
			}
			case TRADER -> !t.baby() && !h.villagerEgg() ? Action.TRADE : animal(t, h);
			case SHEEP -> h.shears() ? (t.readyForShearing() ? Action.SHEAR : Action.NONE) : animal(t, h);
			case COW, GOAT -> h.bucket() && !t.baby() ? Action.MILK : animal(t, h);
			case MOOSHROOM -> {
				if (h.bowl() && !t.baby()) {
					yield Action.MILK;
				}
				if (h.shears() && t.readyForShearing()) {
					yield Action.SHEAR;
				}
				if (t.brown() && !t.baby() && h.stewIngredient()) {
					yield Action.NONE;
				}
				yield h.bucket() && !t.baby() ? Action.MILK : animal(t, h);
			}
			case HORSE, ZOMBIE_HORSE, CHESTED_HORSE -> horse(t, h, sneaking);
			case SKELETON_HORSE -> t.tame() ? abstractHorse(t, h, sneaking) : null;
			case CAMEL -> {
				if (sneaking && !t.baby()) {
					yield Action.OPEN;
				}
				EquipmentSlot slot = h.equips();
				if (slot != null) {
					yield gear(slot);
				}
				if (h.food()) {
					yield Action.FEED;
				}
				yield t.riders() < 2 && !t.baby() ? Action.MOUNT : Action.NONE;
			}
			case PIG -> {
				// Pig and Strider: saddled and free, a ride; then the animal's; then a saddle.
				if (!h.food() && t.saddled() && !t.ridden() && !sneaking) {
					yield Action.MOUNT;
				}
				Action own = animal(t, h);
				if (own != null) {
					yield own;
				}
				yield h.saddle() && h.equips() == EquipmentSlot.SADDLE ? Action.SADDLE : null;
			}
			case HAPPY_GHAST -> {
				if (t.baby()) {
					yield animal(t, h);
				}
				EquipmentSlot slot = h.equips();
				if (slot != null) {
					yield gear(slot);
				}
				yield t.armoured() && !sneaking ? Action.MOUNT : animal(t, h);
			}
			case NAUTILUS -> nautilus(t, h, sneaking);
			case WOLF -> wolf(t, h);
			case CAT -> cat(t, h);
			case PARROT -> {
				if (!t.tame() && h.parrotFood()) {
					yield Action.TAME;
				}
				if (h.parrotPoison()) {
					yield Action.NONE;
				}
				yield !t.flying() && t.tame() && t.owned() ? (t.sitting() ? Action.STAND : Action.SIT) : animal(t, h);
			}
			case SNOW_GOLEM, BOGGED -> h.shears() && t.readyForShearing() ? Action.SHEAR : null;
			case COPPER_GOLEM -> {
				if (h.empty() && t.holding()) {
					yield Action.NONE;
				}
				if (h.shears() && t.readyForShearing()) {
					yield Action.SHEAR;
				}
				yield h.honeycomb() || h.axe() ? Action.NONE : null;
			}
			case ARMADILLO -> h.brush() || t.scared() ? Action.NONE : animal(t, h);
			case PANDA -> {
				if (t.scared()) {
					yield null;
				}
				if (t.onBack()) {
					yield Action.NONE;
				}
				yield h.food() ? Action.FEED : animal(t, h);
			}
			case TADPOLE -> {
				if (h.food() && !t.ageLocked()) {
					yield Action.FEED;
				}
				yield h.goldenDandelion() || h.waterBucket() ? Action.NONE : null;
			}
			case FISH -> h.waterBucket() ? Action.NONE : null;
			case DOLPHIN -> h.food() ? Action.FEED : null;
			case ANIMAL -> h.waterBucket() && t.bucketable() ? Action.NONE : animal(t, h);
			default -> null;
		};
	}

	/**
	 * {@code Animal.mobInteract}, then {@code AgeableMob}'s: its food breeds an adult or grows a baby - one kept a
	 * baby takes it and nothing comes of it; then a golden dandelion on a baby.
	 */
	private static @Nullable Action animal(Target t, Held h) {
		if (h.food()) {
			return t.baby() && t.ageLocked() ? Action.NONE : Action.FEED;
		}
		return t.baby() && h.goldenDandelion() ? Action.NONE : null;
	}

	/** Horse, ZombieHorse and AbstractChestedHorse's {@code mobInteract}: food, a temper, a chest; then the horse's. */
	private static @Nullable Action horse(Target t, Held h, boolean sneaking) {
		boolean openInventory = !t.baby() && t.tame() && sneaking;
		boolean dandelionHeld = t.kind() != Kind.ZOMBIE_HORSE && t.baby() && t.dandelion();
		if (!t.ridden() && !openInventory && !dandelionHeld && !h.empty()) {
			if (h.food()) {
				return Action.FEED;
			}
			if (!t.tame()) {
				return Action.NONE;
			}
			if (t.kind() == Kind.CHESTED_HORSE && !t.hasChest() && h.chest()) {
				return Action.EQUIP;
			}
		}
		return abstractHorse(t, h, sneaking);
	}

	/** {@code AbstractHorse.mobInteract}: a rider or a foal goes to the animal's; its pack; gear; else a ride. */
	private static @Nullable Action abstractHorse(Target t, Held h, boolean sneaking) {
		if (t.ridden() || t.baby()) {
			return animal(t, h);
		}
		if (t.tame() && sneaking) {
			return Action.OPEN;
		}
		if (!h.empty()) {
			EquipmentSlot slot = h.equips();
			if (slot != null) {
				return gear(slot);
			}
			if (h.bodyArmour() && !t.armoured()) {
				return Action.EQUIP;
			}
		}
		return Action.MOUNT;
	}

	/** {@code AbstractNautilus.mobInteract}. */
	private static @Nullable Action nautilus(Target t, Held h, boolean sneaking) {
		if (t.baby()) {
			return animal(t, h);
		}
		if (t.tame() && sneaking) {
			return Action.OPEN;
		}
		if (!h.empty()) {
			// The server tames with food; the client, which cannot, feeds - the server's is what happens.
			if (!t.tame() && h.food()) {
				return Action.TAME;
			}
			if (h.food() && t.hurt()) {
				return Action.FEED;
			}
			EquipmentSlot slot = h.equips();
			if (slot != null) {
				return gear(slot);
			}
		}
		if (t.tame() && !sneaking && !h.food()) {
			return Action.MOUNT;
		}
		return animal(t, h);
	}

	/** {@code Wolf.mobInteract}. */
	private static @Nullable Action wolf(Target t, Held h) {
		if (t.tame()) {
			if (h.food() && t.hurt()) {
				return Action.FEED;
			}
			if (!h.collarDye() || !t.owned()) {
				if (h.bodyArmour() && !t.armoured() && t.owned() && !t.baby()) {
					return Action.EQUIP;
				}
				if (t.sitting() && t.armoured() && t.owned() && h.repairs()) {
					return Action.NONE;
				}
				Action own = animal(t, h);
				if (own == null && t.owned()) {
					return t.sitting() ? Action.STAND : Action.SIT;
				}
				return own;
			}
			if (h.dye() != null && h.dye() != t.colour()) {
				return Action.DYE;
			}
		} else if (h.bone() && !t.angry()) {
			return Action.TAME;
		}
		return animal(t, h);
	}

	/** {@code Cat.mobInteract}. */
	private static @Nullable Action cat(Target t, Held h) {
		if (t.tame()) {
			if (t.owned()) {
				if (h.collarDye()) {
					if (h.dye() != null && h.dye() != t.colour()) {
						return Action.DYE;
					}
				} else if (h.food() && t.hurt()) {
					return Action.FEED;
				}
				Action own = animal(t, h);
				return own != null ? own : (t.sitting() ? Action.STAND : Action.SIT);
			}
		} else if (h.food()) {
			return Action.TAME;
		}
		return animal(t, h);
	}

	/** The rules' kind for a game entity. */
	static Kind kindOf(Entity entity) {
		if (entity instanceof Villager) {
			return Kind.VILLAGER;
		} else if (entity instanceof WanderingTrader) {
			return Kind.TRADER;
		} else if (entity instanceof Sheep) {
			return Kind.SHEEP;
		} else if (entity instanceof MushroomCow) {
			return Kind.MOOSHROOM;
		} else if (entity instanceof AbstractCow) {
			return Kind.COW;
		} else if (entity instanceof Goat) {
			return Kind.GOAT;
		} else if (entity instanceof Camel) {
			// before AbstractHorse: a camel is one
			return Kind.CAMEL;
		} else if (entity instanceof SkeletonHorse) {
			return Kind.SKELETON_HORSE;
		} else if (entity instanceof ZombieHorse) {
			return Kind.ZOMBIE_HORSE;
		} else if (entity instanceof AbstractChestedHorse) {
			return Kind.CHESTED_HORSE;
		} else if (entity instanceof AbstractHorse) {
			return Kind.HORSE;
		} else if (entity instanceof Pig || entity instanceof Strider) {
			return Kind.PIG;
		} else if (entity instanceof HappyGhast) {
			return Kind.HAPPY_GHAST;
		} else if (entity instanceof AbstractNautilus) {
			return Kind.NAUTILUS;
		} else if (entity instanceof Wolf) {
			return Kind.WOLF;
		} else if (entity instanceof Cat) {
			return Kind.CAT;
		} else if (entity instanceof Parrot) {
			return Kind.PARROT;
		} else if (entity instanceof SnowGolem) {
			return Kind.SNOW_GOLEM;
		} else if (entity instanceof Bogged) {
			return Kind.BOGGED;
		} else if (entity instanceof CopperGolem) {
			return Kind.COPPER_GOLEM;
		} else if (entity instanceof Armadillo) {
			return Kind.ARMADILLO;
		} else if (entity instanceof Panda) {
			return Kind.PANDA;
		} else if (entity instanceof Tadpole) {
			// before AbstractFish: a tadpole is one
			return Kind.TADPOLE;
		} else if (entity instanceof AbstractFish) {
			return Kind.FISH;
		} else if (entity instanceof Dolphin) {
			return Kind.DOLPHIN;
		} else if (entity instanceof Animal) {
			return Kind.ANIMAL;
		} else if (entity instanceof Mob) {
			return Kind.MOB;
		} else if (entity instanceof AbstractChestBoat) {
			return Kind.CHEST_BOAT;
		} else if (entity instanceof AbstractBoat) {
			return Kind.BOAT;
		} else if (entity instanceof Minecart) {
			return Kind.MINECART;
		} else if (entity instanceof AbstractMinecartContainer) {
			return Kind.CONTAINER_MINECART;
		} else if (entity instanceof LivingEntity) {
			return Kind.LIVING;
		}
		return Kind.OTHER;
	}

	/** The game's answers, for this entity in front of this player. */
	record GameTarget(Entity entity, LocalPlayer player) implements Target {
		@Override
		public Kind kind() {
			return kindOf(entity);
		}

		@Override
		public boolean alive() {
			return entity.isAlive();
		}

		@Override
		public boolean baby() {
			return entity instanceof LivingEntity living && living.isBaby();
		}

		@Override
		public boolean nameable() {
			return entity instanceof LivingEntity && !(entity instanceof Player) && entity.getType().canSerialize();
		}

		@Override
		public boolean babyFromEgg() {
			if (entity instanceof Zombie || entity instanceof Piglin || entity instanceof Zoglin) {
				return true;
			}
			// These breed nothing (getBreedOffspring gives null), so their eggs make no baby either.
			if (entity instanceof WanderingTrader || entity instanceof CamelHusk || entity instanceof Parrot
					|| entity instanceof ZombieNautilus) {
				return false;
			}
			//? if >=26.2 {
			if (entity instanceof net.minecraft.world.entity.monster.cubemob.AbstractCubeMob) {
				return false;
			}
			//?}
			return entity instanceof AgeableMob;
		}

		@Override
		public boolean leashable() {
			return entity instanceof Leashable;
		}

		@Override
		public boolean leashedToPlayer() {
			return entity instanceof Leashable leashable && leashable.getLeashHolder() == player;
		}

		@Override
		public boolean leashedToAPlayer() {
			return entity instanceof Leashable leashable && leashable.getLeashHolder() instanceof Player;
		}

		@Override
		public boolean takesLead() {
			return entity instanceof Leashable leashable && leashable.canHaveALeashAttachedTo(player);
		}

		@Override
		public boolean leashConnections() {
			return entity instanceof Leashable leashable && leashable.isLeashed() || !Leashable.leashableLeashedTo(entity).isEmpty();
		}

		@Override
		public boolean tiesLeashes() {
			if (!(entity instanceof Leashable leashable) || !leashable.canBeLeashed() || baby()) {
				return false;
			}
			List<Leashable> held = Leashable.leashableInArea(entity, mob -> mob.getLeashHolder() == player);
			for (Leashable mob : held) {
				if (mob.canHaveALeashAttachedTo(entity)) {
					return true;
				}
			}
			return false;
		}

		@Override
		public boolean shearableGear() {
			if (!(entity instanceof Mob mob)) {
				return false;
			}
			boolean allowed = mob instanceof Wolf wolf ? wolf.isOwnedBy(player) : !mob.isVehicle();
			if (!allowed) {
				return false;
			}
			for (EquipmentSlot slot : EquipmentSlot.VALUES) {
				ItemStack gear = mob.getItemBySlot(slot);
				Equippable equippable = gear.get(DataComponents.EQUIPPABLE);
				if (equippable != null && equippable.canBeSheared()
						&& (!EnchantmentHelper.has(gear, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE) || player.isCreative())) {
					return true;
				}
			}
			return false;
		}

		@Override
		public boolean readyForShearing() {
			return entity instanceof Shearable shearable && shearable.readyForShearing();
		}

		@Override
		public boolean sheared() {
			return entity instanceof Sheep sheep && sheep.isSheared();
		}

		@Override
		public boolean sleeping() {
			return entity instanceof LivingEntity living && living.isSleeping();
		}

		@Override
		public boolean trades() {
			return entity instanceof Villager villager
					&& !villager.getVillagerData().profession().is(VillagerProfession.NONE)
					&& !villager.getVillagerData().profession().is(VillagerProfession.NITWIT);
		}

		@Override
		public boolean tame() {
			if (entity instanceof TamableAnimal tamable) {
				return tamable.isTame();
			}
			return entity instanceof AbstractHorse horse && horse.isTamed();
		}

		@Override
		public boolean owned() {
			return entity instanceof TamableAnimal tamable && tamable.isOwnedBy(player);
		}

		@Override
		public boolean sitting() {
			return entity instanceof TamableAnimal tamable && tamable.isInSittingPose();
		}

		@Override
		public boolean hurt() {
			return entity instanceof LivingEntity living && living.getHealth() < living.getMaxHealth();
		}

		@Override
		public boolean angry() {
			return entity instanceof Wolf wolf && wolf.isAngry();
		}

		@Override
		public boolean flying() {
			return entity instanceof Parrot parrot && parrot.isFlying();
		}

		@Override
		public boolean ridden() {
			return entity.isVehicle();
		}

		@Override
		public int riders() {
			return entity.getPassengers().size();
		}

		@Override
		public boolean room() {
			int seats = entity instanceof AbstractChestBoat ? 1 : 2;
			return entity.getPassengers().size() < seats && !entity.isEyeInFluid(FluidTags.WATER);
		}

		@Override
		public boolean saddled() {
			return entity instanceof Mob mob && mob.isSaddled();
		}

		@Override
		public boolean armoured() {
			return entity instanceof Mob mob && mob.isWearingBodyArmor();
		}

		@Override
		public boolean hasChest() {
			return entity instanceof AbstractChestedHorse horse && horse.hasChest();
		}

		@Override
		public boolean brown() {
			return entity instanceof MushroomCow cow && cow.getVariant() == MushroomCow.Variant.BROWN;
		}

		@Override
		public boolean scared() {
			if (entity instanceof Armadillo armadillo) {
				return armadillo.isScared();
			}
			return entity instanceof Panda panda && panda.isScared();
		}

		@Override
		public boolean onBack() {
			return entity instanceof Panda panda && panda.isOnBack();
		}

		@Override
		public boolean holding() {
			return entity instanceof CopperGolem golem && !golem.getMainHandItem().isEmpty();
		}

		@Override
		public boolean ageLocked() {
			if (entity instanceof Tadpole tadpole) {
				return tadpole.isAgeLocked();
			}
			return entity instanceof AgeableMob ageable && ageable.isAgeLocked();
		}

		@Override
		public boolean bucketable() {
			//? if >=26.2 {
			return entity instanceof net.minecraft.world.entity.Bucketable;
			//?} else {
			/*return entity instanceof net.minecraft.world.entity.animal.Bucketable;
			*///?}
		}

		@Override
		public @Nullable DyeColor colour() {
			if (entity instanceof Sheep sheep) {
				return sheep.getColor();
			} else if (entity instanceof Wolf wolf) {
				return wolf.getCollarColor();
			} else if (entity instanceof Cat cat) {
				return cat.getCollarColor();
			}
			return null;
		}

		@Override
		public boolean dandelion() {
			return player.isHolding(Items.GOLDEN_DANDELION);
		}
	}

	/** The game's answers, for what one hand holds against this entity. */
	record GameHeld(ItemStack stack, Entity entity, LocalPlayer player) implements Held {
		@Override
		public boolean empty() {
			return stack.isEmpty();
		}

		@Override
		public boolean namedTag() {
			return stack.is(Items.NAME_TAG) && stack.get(DataComponents.CUSTOM_NAME) != null;
		}

		@Override
		public boolean spawnEgg() {
			return stack.getItem() instanceof SpawnEggItem;
		}

		@Override
		public boolean ownEgg() {
			return SpawnEggItem.spawnsEntity(stack, entity.getType());
		}

		@Override
		public boolean villagerEgg() {
			return stack.is(Items.VILLAGER_SPAWN_EGG);
		}

		@Override
		public boolean shears() {
			return stack.is(Items.SHEARS);
		}

		@Override
		public boolean bucket() {
			return stack.is(Items.BUCKET);
		}

		@Override
		public boolean waterBucket() {
			return stack.getItem() == Items.WATER_BUCKET;
		}

		@Override
		public boolean bowl() {
			return stack.is(Items.BOWL);
		}

		@Override
		public boolean lead() {
			return stack.is(Items.LEAD);
		}

		@Override
		public boolean bone() {
			return stack.is(Items.BONE);
		}

		@Override
		public boolean chest() {
			return stack.is(Items.CHEST);
		}

		@Override
		public boolean brush() {
			return stack.is(Items.BRUSH);
		}

		@Override
		public boolean honeycomb() {
			return stack.is(Items.HONEYCOMB);
		}

		@Override
		public boolean axe() {
			return stack.is(ItemTags.AXES);
		}

		@Override
		public boolean goldenDandelion() {
			return stack.getItem() == Items.GOLDEN_DANDELION;
		}

		@Override
		public @Nullable DyeColor dye() {
			return stack.getItem() instanceof DyeItem ? stack.get(DataComponents.DYE) : null;
		}

		@Override
		public boolean collarDye() {
			if (entity instanceof Wolf) {
				return stack.is(ItemTags.WOLF_COLLAR_DYES);
			}
			return entity instanceof Cat && stack.is(ItemTags.CAT_COLLAR_DYES);
		}

		@Override
		public boolean food() {
			if (stack.isEmpty()) {
				return false;
			} else if (entity instanceof Animal animal) {
				return animal.isFood(stack);
			} else if (entity instanceof Dolphin) {
				return stack.is(ItemTags.FISHES);
			}
			return entity instanceof Tadpole && stack.is(ItemTags.FROG_FOOD);
		}

		@Override
		public boolean parrotFood() {
			return stack.is(ItemTags.PARROT_FOOD);
		}

		@Override
		public boolean parrotPoison() {
			return stack.is(ItemTags.PARROT_POISONOUS_FOOD);
		}

		@Override
		public boolean stewIngredient() {
			return SuspiciousEffectHolder.tryGet(stack.getItem()) != null;
		}

		@Override
		public @Nullable EquipmentSlot equips() {
			Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
			if (equippable == null || !equippable.equipOnInteract() || !(entity instanceof LivingEntity living)) {
				return null;
			}
			EquipmentSlot slot = equippable.slot();
			return living.isEquippableInSlot(stack, slot) && !living.hasItemInSlot(slot) && living.isAlive() ? slot : null;
		}

		@Override
		public boolean bodyArmour() {
			return entity instanceof LivingEntity living && living.isEquippableInSlot(stack, EquipmentSlot.BODY);
		}

		@Override
		public boolean saddle() {
			return entity instanceof LivingEntity living && living.isEquippableInSlot(stack, EquipmentSlot.SADDLE);
		}

		@Override
		public boolean repairs() {
			if (!(entity instanceof Wolf wolf)) {
				return false;
			}
			ItemStack armour = wolf.getBodyArmorItem();
			return armour.isDamaged() && armour.isValidRepairItem(stack);
		}

		@Override
		public boolean usesItself() {
			return TouchTap.holdUses(stack, player);
		}
	}

	/**
	 * Every frame in the world, from {@link TouchPad#frame}, before the fingers are read: what is in front of the player
	 * - in the middle of the screen in tap mode, under the crosshair otherwise - what the button would do with it, and
	 * where the button goes; and how far the item name and the action bar move up for it.
	 */
	static void frame(Minecraft minecraft, TouchMode mode, int guiWidth, int guiHeight) {
		clear();
		LocalPlayer player = minecraft.player;
		if (player == null || player.isSpectator() || minecraft.gameMode == null) {
			return;
		}
		Entity target = target(minecraft, player, mode);
		if (target == null) {
			return;
		}
		Action action = action(new GameTarget(target, player), new GameHeld(player.getMainHandItem(), target, player),
				new GameHeld(player.getOffhandItem(), target, player), player.isSecondaryUseActive());
		if (action == null) {
			return;
		}
		Component label = action.label();
		show(action, label, minecraft.font.width(label), guiWidth, guiHeight, minecraft.gameMode.canHurtPlayer(), extraHearts(player), mountRows(player));
	}

	/**
	 * The button for this action, its word this many GUI pixels wide, placed in this window over what the game stacks
	 * there ({@link #bottom}); and the lift for the item name, which the game draws 59 up with the hearts showing and
	 * 45 without.
	 */
	static void show(Action action, Component label, int textWidth, int guiWidth, int guiHeight, boolean statusBars, int extraHearts, int mountRows) {
		Box box = box(guiWidth, bottom(guiHeight, statusBars, extraHearts, mountRows), textWidth);
		shown = new Shown(action, label, textWidth, box);
		lift = lift(guiHeight - (statusBars ? 59 : 45), box);
	}

	/** What the button acts on: the game's pick through the middle of the screen in tap mode, its own pick otherwise. */
	private static @Nullable Entity target(Minecraft minecraft, LocalPlayer player, TouchMode mode) {
		HitResult hit;
		if (mode != TouchMode.CROSSHAIR) {
			Entity camera = minecraft.getCameraEntity();
			if (camera == null) {
				return null;
			}
			hit = TouchTap.pickAt(player, camera, minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true), CENTRE[0], CENTRE[1]);
		} else {
			hit = minecraft.hitResult;
		}
		return hit instanceof EntityHitResult entityHit ? entityHit.getEntity() : null;
	}

	/**
	 * How much higher than one row the player's hearts stack, in GUI pixels, as {@code Hud.extractPlayerHealth} lays
	 * them out: a row of ten for each twenty points of health and absorption, rows packed closer the more there are.
	 */
	static int extraHearts(Player player) {
		return extraHearts(Math.max((float) player.getAttributeValue(Attributes.MAX_HEALTH), (float) Mth.ceil(player.getHealth())),
				Mth.ceil(player.getAbsorptionAmount()));
	}

	/** {@link #extraHearts(Player)} for this much health at most and this much absorption, both in half hearts. */
	static int extraHearts(float maxHealth, int absorption) {
		int rows = Mth.ceil((maxHealth + absorption) / 2.0F / 10.0F);
		int rowHeight = Math.max(10 - (rows - 2), 3);
		return (rows - 1) * rowHeight;
	}

	/** How many rows of hearts the ridden mount shows, as {@code Hud.extractVehicleHealth} draws them; 0 for none. */
	static int mountRows(Player player) {
		if (player.getVehicle() instanceof LivingEntity vehicle && vehicle.showVehicleHealth()) {
			return mountRows(vehicle.getMaxHealth());
		}
		return 0;
	}

	/** {@link #mountRows(Player)} for a mount with this much health at most: a heart for two, thirty at most, ten a row. */
	static int mountRows(float maxHealth) {
		int hearts = Math.min((int) (maxHealth + 0.5F) / 2, 30);
		return (hearts + 9) / 10;
	}

	/** Forgets the button: none shows until the next frame in the world finds one. */
	static void clear() {
		shown = null;
		lift = 0;
	}

	/** The button this frame, for {@link TouchPad.View}; null while there is nothing to do or touch controls are off. */
	static @Nullable Shown shown() {
		return TouchPad.active() ? shown : null;
	}

	/** How far the held item's name and the action bar's message move up this frame, in GUI pixels (HudMixin); 0 while no button shows. */
	public static int lift() {
		return TouchPad.active() && shown != null ? lift : 0;
	}

	/**
	 * Where the button's foot goes, in GUI pixels down the window: {@link #GAP} over what the game stacks over the hotbar.
	 * With no hearts to show - creative, spectator - that is the hotbar alone; with them, the hotbar, the experience bar,
	 * the hearts and food and the armour and air over them ({@link TouchButtons#HUD_STACK_HEIGHT}), whether or not the
	 * armour and air are showing, so the button stays put as they come and go - and higher by the rows of hearts past the
	 * first, and of a mount's hearts, which show while the player rides, in creative too.
	 *
	 * @param statusBars  whether the game draws the player's hearts and food ({@code canHurtPlayer})
	 * @param extraHearts how much higher the player's hearts stack than one row, in GUI pixels ({@link #extraHearts})
	 * @param mountRows   how many rows of hearts the ridden mount shows, 0 for none ({@link #mountRows})
	 */
	static int bottom(int guiHeight, boolean statusBars, int extraHearts, int mountRows) {
		if (!statusBars && mountRows == 0) {
			return guiHeight - TouchButtons.HOTBAR_HEIGHT - GAP;
		}
		int extra = Math.max(statusBars ? extraHearts : 0, Math.max(mountRows - 1, 0) * 10);
		return guiHeight - TouchButtons.HUD_STACK_HEIGHT - extra - GAP;
	}

	/**
	 * How far up the held item's name and the action bar's message move while the button shows, in GUI pixels: enough
	 * for the name's backdrop to clear the button's top by a pixel. The game draws the name at {@code nameY} - 59 up
	 * with the hearts showing, 45 without - on a backdrop two pixels round its nine-pixel line; the message over it
	 * moves as far, so the two stay as they were to each other.
	 */
	static int lift(int nameY, Box box) {
		int nameFoot = nameY + 9 + 2;
		return Math.max(0, nameFoot - (box.y() - 1));
	}

	/** The button for a word this many GUI pixels wide ({@code Font.width}), centred across the window, its foot at {@code bottom}. */
	static Box box(int guiWidth, int bottom, int textWidth) {
		int width = Math.max(MIN_WIDTH, textWidth + 2 * PAD);
		return new Box((guiWidth - width) / 2, bottom - HEIGHT, width, HEIGHT);
	}

	/** A finger landing on the button takes it, unless another has it: true if it did. */
	static boolean claim(TouchInput.Finger landing, TouchPad.View view) {
		Shown button = view.interact();
		if (button == null || finger != null) {
			return false;
		}
		double guiX = landing.x() * view.width() / view.scale();
		double guiY = landing.y() * view.height() / view.scale();
		if (!button.box().contains(guiX, guiY)) {
			return false;
		}
		finger = TouchPad.FingerKey.of(landing);
		pressedAt = view.nanos();
		return true;
	}

	/** Whether this finger is the one on the button. */
	static boolean owns(TouchPad.FingerKey key) {
		return key.equals(finger);
	}

	/**
	 * After the fingers are read: the button's finger lifted lets it go. Use is pulled while the finger is down and
	 * for {@link TouchButtons#MIN_PRESS_NANOS} at least, so a tap reaches the game's tick - and only while the button
	 * shows: gone, the mob walked off or ridden, it pulls nothing more, its finger still its own till it lifts.
	 */
	static boolean update(List<TouchInput.Finger> fingers, TouchPad.View view) {
		if (finger != null) {
			boolean down = false;
			for (TouchInput.Finger candidate : fingers) {
				if (finger.equals(TouchPad.FingerKey.of(candidate))) {
					down = true;
				}
			}
			if (!down) {
				finger = null;
			}
		}
		boolean tapping = pressedAt != 0 && view.nanos() - pressedAt < TouchButtons.MIN_PRESS_NANOS;
		if (finger == null && !tapping) {
			pressedAt = 0;
		}
		pressed = view.interact() != null && (finger != null || tapping);
		return pressed;
	}

	/** Whether the button pulls use this frame: the game's pick in tap mode then goes through the middle of the screen. */
	static boolean pressed() {
		return pressed;
	}

	/** Lets go of the button: a screen is up, or touch controls went off. */
	static void letGo() {
		finger = null;
		pressedAt = 0;
		pressed = false;
	}

	/** Where the game acts while the button is pressed, 0 to 1 across and down: the middle of the screen. */
	static float[] centre() {
		return CENTRE.clone();
	}

	/**
	 * Draws the button: the touch buttons' frame - a one-pixel outline round the fill, its corners cut two pixels - and
	 * the word in the middle with its shadow a pixel down and right, light on the dark fill at rest, dark on the light
	 * fill while held.
	 */
	static void draw(GuiGraphicsExtractor graphics, @Nullable Font font, Shown button, boolean held) {
		Box box = button.box();
		int x0 = box.x();
		int y0 = box.y();
		int x1 = x0 + box.width();
		int y1 = y0 + box.height();
		int outline = held ? OUTLINE_HELD : OUTLINE;
		int fill = held ? FILL_HELD : FILL;
		// The outline: top and foot cut two pixels in, a step a pixel in on the rows next to them, then the sides.
		graphics.fill(x0 + 2, y0, x1 - 2, y0 + 1, outline);
		graphics.fill(x0 + 1, y0 + 1, x0 + 2, y0 + 2, outline);
		graphics.fill(x1 - 2, y0 + 1, x1 - 1, y0 + 2, outline);
		graphics.fill(x0, y0 + 2, x0 + 1, y1 - 2, outline);
		graphics.fill(x1 - 1, y0 + 2, x1, y1 - 2, outline);
		graphics.fill(x0 + 1, y1 - 2, x0 + 2, y1 - 1, outline);
		graphics.fill(x1 - 2, y1 - 2, x1 - 1, y1 - 1, outline);
		graphics.fill(x0 + 2, y1 - 1, x1 - 2, y1, outline);
		// The fill inside it, each pixel once.
		graphics.fill(x0 + 2, y0 + 1, x1 - 2, y0 + 2, fill);
		graphics.fill(x0 + 1, y0 + 2, x1 - 1, y1 - 2, fill);
		graphics.fill(x0 + 2, y1 - 2, x1 - 2, y1 - 1, fill);
		// The word: its last pixel column is the font's spacing, so it is left out of the centring.
		int textX = x0 + (box.width() - (button.textWidth() - 1)) / 2;
		int textY = y0 + TEXT_TOP;
		graphics.text(font, button.label(), textX + 1, textY + 1, held ? SHADOW_HELD : SHADOW, false);
		graphics.text(font, button.label(), textX, textY, held ? PICTURE_HELD : PICTURE, false);
	}
}
