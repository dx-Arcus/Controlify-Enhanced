/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.gui.screen;

import com.mojang.blaze3d.platform.InputConstants;
import dev.isxander.controlify.Controlify;
import dev.isxander.controlify.api.bind.InputBindingSupplier;
import dev.isxander.controlify.bindings.ControlifyBindings;
import dev.isxander.controlify.config.settings.AimAssistSettings;
import dev.isxander.controlify.controller.ControllerEntity;
import dev.isxander.controlify.controller.haptic.HapticEffects;
import dev.isxander.controlify.controller.input.ControllerStateView;
import dev.isxander.controlify.controller.input.GamepadInputs;
import dev.isxander.controlify.controller.input.InputComponent;
import dev.isxander.controlify.screenop.ComponentProcessor;
import dev.isxander.controlify.screenop.ScreenControllerEventListener;
import dev.isxander.controlify.screenop.ScreenProcessor;
import dev.isxander.controlify.utils.CUtil;
import dev.isxander.controlify.utils.HoldRepeatHelper;
import dev.isxander.controlify.utils.MinecraftUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.MobCategory;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;
import java.util.Set;

/**
 * Picks which entity types aim assist is allowed to help with, when Targets is set to Custom.
 * <p>
 * Every registered entity type is offered rather than a curated list, so mobs from any mod appear
 * without this screen knowing anything about them. That makes the list long — a heavy pack runs
 * past a thousand entries — which is why the search box and the tabs are the point of the screen
 * rather than decoration.
 */
public class CustomTargetListScreen extends Screen implements ScreenControllerEventListener {
	private static final int ROW_HEIGHT = 34;
	private static final int CHIP_SIZE = 14;
	/** Width of the icon column; the mob is drawn inside it. */
	private static final int ICON_BOX = 32;
	/** Roughly how tall a mob should be drawn, in GUI pixels, whatever its real size. */
	private static final float ICON_TARGET_HEIGHT = 25f;
	private static final int TICK_SIZE = 12;
	private static final int SCROLLBAR_WIDTH = 9;
	/** The list fills the window apart from a margin, so the models get as much room as possible. */
	private static final int SIDE_MARGIN = 40;
	private static final int MIN_LIST_WIDTH = 320;
	/** Names are drawn through a scaled matrix; the font itself has only one size. */
	private static final float NAME_SCALE = 2.0f;
	private static final int PILL_HEIGHT = 15;
	private static final int COLOR_PILL = 0xFFD5342E;
	private static final int COLOR_PILL_EDGE = 0xFFA82722;
	/** The pill's label. Measured once, in init, rather than for every ticked row on every frame. */
	private static final Component TARGETED = Component.translatable("controlify.gui.custom_list.targeted")
			.withStyle(ChatFormatting.ITALIC);
	private int pillTextWidth;

	/**
	 * How far the right stick scrolls the list at full tilt, in pixels a second. A little over
	 * fourteen rows a second - fast enough to be worth using on a long list, slow enough to stop
	 * on a row you wanted.
	 * <p>
	 * Per second rather than per update on purpose. See {@link #advanceStickScroll}.
	 */
	private static final double SCROLL_PER_SECOND = 480;
	/**
	 * The longest a single frame is allowed to count for. A hitch - a chunk loading, the window
	 * coming back from behind something else - would otherwise be paid off in one lurch.
	 */
	private static final double LONGEST_FRAME_SECONDS = 0.05;
	/** Under this the stick counts as centred, so a thumb resting on it does not creep. */
	private static final float SCROLL_DEADZONE = 0.12f;

	private static final int HEADER_BOTTOM = 72;
	/** Tall enough that the counter clears the footer buttons instead of printing over them. */
	private static final int FOOTER_HEIGHT = 46;
	private static final int PANEL_PAD = 8;
	private static final int PANEL_TOP = 4;
	private static final int SEARCH_Y = 28;
	private static final int SEARCH_HEIGHT = 18;
	private static final int TABS_Y = 50;
	private static final int TABS_HEIGHT = 16;
	/** Distance from the bottom of the screen to the top of the footer buttons. */
	private static final int FOOTER_BUTTON_TOP = 26;
	private static final int FOOTER_BUTTON_HEIGHT = 20;

	private static final int COLOR_SCRIM = 0xB4000000;
	private static final int COLOR_PANEL = 0xF0141414;
	private static final int COLOR_PANEL_BORDER = 0xFF4A4A4A;
	private static final int COLOR_LIST_BG = 0xFF0E0E0E;
	private static final int COLOR_ROW_ODD = 0x14FFFFFF;
	private static final int COLOR_ROW_HOVER = 0x33FFFFFF;
	private static final int COLOR_ACCENT = 0xFF4CAF50;
	private static final int COLOR_CHIP_HOSTILE = 0xFFD05B5B;
	private static final int COLOR_CHIP_PASSIVE = 0xFF5FA85F;
	private static final int COLOR_CHIP_MISC = 0xFF6E6E80;
	private static final int COLOR_TICK_BORDER = 0xFF6E6E6E;
	private static final int COLOR_TICK_BORDER_ON = 0xFF4CAF50;
	private static final int COLOR_NAME = 0xFFFFFFFF;
	private static final int COLOR_ID = 0xFF8A8A8A;
	private static final int COLOR_SCROLL_TRACK = 0x40000000;
	private static final int COLOR_SCROLL_THUMB = 0xFF9A9A9A;

	/** How many buttons sit in each of the two bulk groups. */
	private static final int BULK_COUNT = 3;
	private static final int BULK_GAP = 4;
	/** Keeps the bulk groups from touching Clear All and Done, so the three clusters read apart. */
	private static final int BULK_PAD = 6;
	private static final int BULK_LABEL_PAD = 10;

	/**
	 * The passive mobs that fight back when the player hurts them.
	 * <p>
	 * Taken from which mobs actually carry the game's retaliate-when-hurt behaviour rather than
	 * from memory: foxes and goats look like they belong here and do not, and the rabbit only
	 * fights as the Killer Bunny, which would drag every ordinary rabbit along with it.
	 * <p>
	 * Enderman and zombified piglin are provocable too, but the game files them as monsters, so
	 * the hostile set already covers them and repeating them here would double them up.
	 */
	private static final Set<String> PROVOCABLE = Set.of(
			"minecraft:bee",
			"minecraft:dolphin",
			"minecraft:iron_golem",
			"minecraft:llama",
			"minecraft:panda",
			"minecraft:polar_bear",
			"minecraft:trader_llama",
			"minecraft:wolf");

	/** The ways the list can be narrowed. Deliberately few: search does the fine-grained work. */
	private enum Tab {
		MAIN, HOSTILE, PASSIVE, OTHER, MODDED, SELECTED;

		Component label() {
			return Component.translatable("controlify.gui.custom_list.tab." + name().toLowerCase(Locale.ROOT));
		}
	}

	/**
	 * The three sets the bulk buttons act on. Adding and removing share them, so the button that
	 * puts a set in and the button that takes it out can never drift apart.
	 */
	private enum BulkSet {
		BOTH, HOSTILE, PROVOCABLE;

		private String key(boolean add) {
			return "controlify.gui.custom_list.bulk." + (add ? "add" : "remove") + "."
					+ name().toLowerCase(Locale.ROOT);
		}

		Component label(boolean add) {
			return Component.translatable(key(add));
		}

		Component tooltip(boolean add) {
			return Component.translatable(key(add) + ".tooltip");
		}
	}

	/**
	 * The list, as something the focus can land on.
	 * <p>
	 * It draws nothing and owns nothing: the rows are still painted by the screen, after the
	 * widgets, because the panel backing is deliberately laid over the widget layer and a list
	 * drawn as a widget would end up underneath it. All this is for is to exist in the focus order
	 * and to carry the input once it is focused - which is the whole job, since the rows were only
	 * ever reachable by pointing at them.
	 * <p>
	 * Implementing {@link ComponentProcessor} here rather than registering one elsewhere is enough:
	 * the lookup asks the widget first and only falls back to its registry for classes it cannot
	 * change.
	 */
	private final class ListWidget extends AbstractWidget implements ComponentProcessor {
		private final HoldRepeatHelper repeat = new HoldRepeatHelper(10, 2);

		ListWidget() {
			super(listLeft, listTop, listRight - SCROLLBAR_WIDTH - listLeft, listBottom - listTop,
					Component.translatable("controlify.gui.custom_list.title"));
		}

		@Override
		public void extractWidgetRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
			// Deliberately empty - see the class note.
		}

		@Override
		public void onClick(@NonNull MouseButtonEvent event, boolean doubleClick) {
			int index = rowAt(event.y());
			if (index < 0) {
				return;
			}
			// Put the controller's row where the click landed, so picking up the stick after using
			// the mouse carries on from the row last touched instead of jumping back.
			cursor = index;
			// Qualified: a widget has a `visible` flag of its own, which would otherwise shadow
			// the screen's list of rows and quietly compile as something else entirely.
			toggle(CustomTargetListScreen.this.visible.get(index));
		}

		/**
		 * One control, one job. Sorting that out is the whole point of this method.
		 * <p>
		 * The d-pad moves the row. Pressed again at the first or last row it steps off the list -
		 * up to the tabs, down to the buttons - but only as a fresh press: a held one stops at the
		 * end rather than running past it. That distinction is the whole fix. Left as it was, the
		 * d-pad was never read here at all, so every press fell through to focus navigation and
		 * threw you out of the list from wherever you happened to be in it.
		 * <p>
		 * The left stick is the opposite: it is handed straight back whatever it is doing, so focus
		 * navigation carries it, and it is how you leave the list for the tabs above or the buttons
		 * below. It no longer touches the rows at all, which is what stops a firm push racing down
		 * them and out the bottom.
		 * <p>
		 * The right stick is not read here. It scrolls the list from
		 * {@link CustomTargetListScreen#onControllerInput}, so it works wherever the focus happens
		 * to be, and reading it in both places would scroll twice as fast as asked.
		 */
		@Override
		public boolean overrideControllerNavigation(ScreenProcessor<?> screen, ControllerEntity controller) {
			boolean repeatAvailable = repeat.canNavigate();
			if (pushed(controller, ControlifyBindings.GUI_NAVI_UP)
					|| pushed(controller, ControlifyBindings.GUI_NAVI_DOWN)
					|| pushed(controller, ControlifyBindings.GUI_NAVI_LEFT)
					|| pushed(controller, ControlifyBindings.GUI_NAVI_RIGHT)) {
				return false;
			}
			// An empty list has no row to move to, so the d-pad is left alone as well - otherwise
			// a tab that matches nothing would be a corner with no way out of it.
			if (CustomTargetListScreen.this.visible.isEmpty()) {
				return false;
			}

			InputComponent input = controller.input().orElseThrow();
			ControllerStateView now = input.stateNow();
			ControllerStateView then = input.stateThen();
			boolean up = now.isButtonDown(GamepadInputs.DPAD_UP_BUTTON);
			boolean down = now.isButtonDown(GamepadInputs.DPAD_DOWN_BUTTON);
			if (up == down) {
				// Neither, or both at once on a pad that allows it. Left and right go back too, so
				// they still move the focus sideways out of the list.
				return false;
			}

			boolean fresh = !then.isButtonDown(down ? GamepadInputs.DPAD_DOWN_BUTTON : GamepadInputs.DPAD_UP_BUTTON);
			if (!fresh && !repeatAvailable) {
				// Held down, but not due another row yet. Still claimed: handing it back here is
				// exactly what used to carry the focus off the list mid-hold.
				return true;
			}
			if (!moveCursor(down ? 1 : -1)) {
				// Already at that end of the list. A fresh press there is taken as asking to
				// leave, so it goes back to focus navigation and steps off to the buttons below
				// or the tabs above. A held one is not: it stops at the end rather than running
				// past it, which is what made holding the stick unusable before. So the way out
				// is to let go and press again, and a hold can never overshoot.
				return !fresh;
			}
			if (fresh) {
				repeat.reset();
			}
			controller.hdHaptics().ifPresent(haptics -> haptics.playHaptic(HapticEffects.NAVIGATE));
			repeat.onNavigate();
			return true;
		}

		/** Puts the row under the focus as soon as the list is stepped onto, rather than nowhere. */
		@Override
		public void onFocusGained(ScreenProcessor<?> screen, ControllerEntity controller) {
			keepCursorInView();
		}

		private boolean pushed(ControllerEntity controller, InputBindingSupplier binding) {
			return binding.on(controller).digitalNow();
		}

		@Override
		public boolean overrideControllerButtons(ScreenProcessor<?> screen, ControllerEntity controller) {
			if (cursor < 0 || cursor >= CustomTargetListScreen.this.visible.size()) {
				return false;
			}
			if (!ControlifyBindings.GUI_PRESS.on(controller).guiPressed().get()) {
				// Claimed only when actually used, so Back still closes the screen from here.
				return false;
			}
			controller.hdHaptics().ifPresent(haptics -> haptics.playHaptic(HapticEffects.NAVIGATE));
			playDownSound(Minecraft.getInstance().getSoundManager());
			toggle(CustomTargetListScreen.this.visible.get(cursor));
			return true;
		}

		/**
		 * Enter or space ticks the row, for anyone who tabbed here rather than pointing. The arrow
		 * keys are deliberately left alone: they are how a keyboard moves focus between widgets,
		 * and taking them would trap the focus in the list. A controller has no such problem - it
		 * moves the row with the secondary stick, which focus navigation never uses.
		 */
		@Override
		public boolean keyPressed(@NonNull KeyEvent event) {
			if (event.key() != InputConstants.KEY_RETURN && event.key() != InputConstants.KEY_SPACE) {
				return super.keyPressed(event);
			}
			List<Entry> rows = CustomTargetListScreen.this.visible;
			if (rows.isEmpty()) {
				return false;
			}
			if (cursor < 0 || cursor >= rows.size()) {
				cursor = Math.max(0, (int) (scroll / ROW_HEIGHT));
			}
			playDownSound(Minecraft.getInstance().getSoundManager());
			toggle(rows.get(cursor));
			return true;
		}

		@Override
		protected void updateWidgetNarration(@NonNull NarrationElementOutput output) {
			output.add(NarratedElementType.TITLE, getMessage());
		}
	}

	/** One row. The icon and names are worked out once, not rebuilt every frame. */
	private record Entry(String id, EntityType<?> type, Component name, String lowerName,
						MobCategory category, boolean modded, boolean isMob) {
	}

	private final Screen parent;
	private final AimAssistSettings settings;
	private final List<Entry> allEntries = new ArrayList<>();
	private List<Entry> visible = List.of();

	private @Nullable EditBox search;
	private Tab tab = Tab.MAIN;
	private String query = "";
	private double scroll;
	/** How hard the right stick is pushed, -1 to 1, as of the last controller update. */
	private double stickScroll;
	/** When the scroll was last advanced, so the next frame can be paid its own share of time. */
	private long lastScrollNanos;
	/**
	 * One inert mob per entity type, built on demand and kept for the life of the screen. Empty
	 * means "asked and there isn't one": either the type isn't a LivingEntity (boats, arrows,
	 * displays) or building it threw. Either way it is never retried, so a mob that misbehaves
	 * costs one attempt rather than one per frame.
	 */
	private final Map<String, Optional<LivingEntity>> models = new HashMap<>();

	/**
	 * IDs handed to preview mobs. Negative so they can never collide with a real entity, and
	 * distinct so the renderer's per-entity caches don't share an entry between two previews.
	 */
	private int nextPreviewId = -10_000;

	private int[] tabX = new int[0];
	private int tabWidth;
	private int clearX;
	private int doneX;
	private int footerButtonWidth;
	private int[] bulkX = new int[0];
	private int bulkWidth;
	private int bulkY;
	/** Set when the bulk buttons could not fit beside Clear All and took a row of their own. */
	private boolean bulkStacked;
	/** {@link #FOOTER_HEIGHT}, plus another row when the bulk buttons are stacked. */
	private int footerHeight;

	private int listWidth;
	private boolean draggingScrollbar;
	/**
	 * The row a controller is sitting on, or -1 before one has been picked. Separate from the
	 * mouse's hover, because the two can be on different rows at once and only this one is moved
	 * by the stick.
	 */
	private int cursor = -1;
	/** The list as a focusable thing, so it takes its turn in the focus order like any button. */
	private @Nullable ListWidget list;
	/** Set when the screen is opened outside a world, where no mob can be built to preview. */
	private boolean showNoWorldNote;
	private static final int NOTE_HEIGHT = 13;

	private int listLeft;
	private int listRight;
	private int listTop;
	private int listBottom;

	public CustomTargetListScreen(Screen parent, AimAssistSettings settings) {
		super(Component.translatable("controlify.gui.custom_list.title"));
		this.parent = parent;
		this.settings = settings;
	}

	@Override
	protected void init() {
		if (allEntries.isEmpty()) {
			collectEntries();
		}
		pillTextWidth = font.width(TARGETED);

		listWidth = Math.max(MIN_LIST_WIDTH, width - SIDE_MARGIN * 2);
		listLeft = width / 2 - listWidth / 2;
		listRight = listLeft + listWidth;
		// Outside a world the rows have no models to show, so a line of explanation takes the place
		// the first row would have had. The list simply starts lower; nothing else moves.
		showNoWorldNote = minecraft.level == null;
		listTop = HEADER_BOTTOM + (showNoWorldNote ? NOTE_HEIGHT : 0);
		// The footer is measured first: how tall it turns out to be decides where the list ends.
		layOutFooter();
		listBottom = height - footerHeight;

		list = new ListWidget();
		addRenderableWidget(list);

		search = new EditBox(font, listLeft, SEARCH_Y, listWidth, SEARCH_HEIGHT,
				Component.translatable("controlify.gui.custom_list.search"));
		search.setHint(Component.translatable("controlify.gui.custom_list.search"));
		search.setMaxLength(64);
		search.setValue(query);
		search.setResponder(value -> {
			query = value.toLowerCase(Locale.ROOT).trim();
			scroll = 0;
			rebuildVisible();
		});
		addRenderableWidget(search);

		Tab[] tabs = Tab.values();
		int gap = 2;
		tabWidth = (listWidth - gap * (tabs.length - 1)) / tabs.length;
		tabX = new int[tabs.length];
		for (int i = 0; i < tabs.length; i++) {
			Tab which = tabs[i];
			tabX[i] = listLeft + i * (tabWidth + gap);
			addRenderableWidget(Button.builder(which.label(), b -> {
						tab = which;
						scroll = 0;
						rebuildVisible();
					})
					.bounds(tabX[i], TABS_Y, tabWidth, TABS_HEIGHT)
					.build());
		}

		addRenderableWidget(Button.builder(Component.translatable("controlify.gui.custom_list.clear"), b -> {
					settings.customTargets.clear();
					rebuildVisible();
				})
				.bounds(clearX, height - FOOTER_BUTTON_TOP, footerButtonWidth, FOOTER_BUTTON_HEIGHT)
				.build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
				.bounds(doneX, height - FOOTER_BUTTON_TOP, footerButtonWidth, FOOTER_BUTTON_HEIGHT)
				.build());

		// Adders on the left, removers on the right, on every tab: these act on the whole list,
		// so tying them to whichever tab is showing would only make them look narrower than they
		// are. The two groups are built together so neither can be forgotten.
		BulkSet[] sets = BulkSet.values();
		for (int i = 0; i < BULK_COUNT; i++) {
			addBulkButton(sets[i], true, bulkX[i]);
			addBulkButton(sets[i], false, bulkX[BULK_COUNT + i]);
		}

		rebuildVisible();
	}

	/**
	 * Works out the footer's geometry before anything is placed, because the bottom of the list
	 * depends on how tall the footer turns out to be.
	 * <p>
	 * The six bulk buttons prefer to sit either side of Clear All and Done, three adding on the
	 * left and three removing on the right. That only holds while the space beside those two is
	 * wide enough for the longest label; on a small window or a large GUI scale it isn't, and
	 * they drop to a row of their own above, keeping the same left and right split.
	 */
	private void layOutFooter() {
		footerButtonWidth = Math.min(200, (listWidth - 8) / 2);
		clearX = width / 2 - footerButtonWidth - 4;
		doneX = width / 2 + 4;

		// Measured rather than counted: the font is variable width, so character counts are
		// wrong for exactly the labels where the answer is close.
		int widest = 0;
		for (BulkSet set : BulkSet.values()) {
			widest = Math.max(widest, font.width(set.label(true)));
			widest = Math.max(widest, font.width(set.label(false)));
		}

		int sideGap = clearX - listLeft - BULK_PAD;
		int perButton = (sideGap - BULK_GAP * (BULK_COUNT - 1)) / BULK_COUNT;
		bulkStacked = perButton < widest + BULK_LABEL_PAD;
		bulkX = new int[BULK_COUNT * 2];

		if (bulkStacked) {
			bulkWidth = (listWidth - BULK_GAP * (bulkX.length - 1)) / bulkX.length;
			for (int i = 0; i < bulkX.length; i++) {
				bulkX[i] = listLeft + i * (bulkWidth + BULK_GAP);
			}
			bulkY = height - FOOTER_BUTTON_TOP - FOOTER_BUTTON_HEIGHT - BULK_GAP;
			footerHeight = FOOTER_HEIGHT + FOOTER_BUTTON_HEIGHT + BULK_GAP;
			return;
		}

		bulkWidth = perButton;
		int removeLeft = doneX + footerButtonWidth + BULK_PAD;
		for (int i = 0; i < BULK_COUNT; i++) {
			bulkX[i] = listLeft + i * (bulkWidth + BULK_GAP);
			bulkX[BULK_COUNT + i] = removeLeft + i * (bulkWidth + BULK_GAP);
		}
		bulkY = height - FOOTER_BUTTON_TOP;
		footerHeight = FOOTER_HEIGHT;
	}

	private void addBulkButton(BulkSet set, boolean add, int x) {
		addRenderableWidget(Button.builder(set.label(add), b -> applyBulk(set, add))
				.tooltip(Tooltip.create(set.tooltip(add)))
				.bounds(x, bulkY, bulkWidth, FOOTER_BUTTON_HEIGHT)
				.build());
	}

	/**
	 * Adds or removes a whole set in one press.
	 * <p>
	 * Walks every entry rather than the visible ones, so a search still in the box or whichever
	 * tab happens to be open cannot quietly narrow what the button did.
	 */
	private void applyBulk(BulkSet set, boolean add) {
		for (Entry entry : allEntries) {
			if (!inSet(entry, set)) {
				continue;
			}
			if (!add) {
				settings.customTargets.remove(entry.id);
			} else if (!settings.customTargets.contains(entry.id)) {
				// The targets are a list rather than a set, so an unguarded add would stack up
				// duplicates every time the button was pressed.
				settings.customTargets.add(entry.id);
			}
		}
		rebuildVisible();
	}

	private boolean inSet(Entry entry, BulkSet set) {
		boolean hostile = entry.category == MobCategory.MONSTER;
		boolean provocable = PROVOCABLE.contains(entry.id);
		return switch (set) {
			case BOTH -> hostile || provocable;
			case HOSTILE -> hostile;
			case PROVOCABLE -> provocable;
		};
	}

	/**
	 * Builds a row for every registered entity type, and a preview mob for each one that has a
	 * model, so mobs from any mod appear without this screen knowing anything about them.
	 * <p>
	 * The mobs are built here rather than on demand because the sort order depends on which types
	 * have one: everything with a model comes first, alphabetically, then everything without,
	 * also alphabetically. Working that out lazily would mean the list rearranging itself under
	 * the cursor as rows scrolled into view.
	 */
	private void collectEntries() {
		var level = minecraft.level;
		int built = 0;

		for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
			String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
			try {
				Component name = type.getDescription();
				// Every living entity type must register default attributes and nothing else has
				// any - Minecraft refuses to start otherwise - so this answers "is it a mob"
				// without needing a world to build one in.
				boolean isMob = DefaultAttributes.hasSupplier(type);
				LivingEntity model = null;

				if (level != null && isMob) {
					try {
						if (type.create(level, EntitySpawnReason.LOAD) instanceof LivingEntity living) {
							// The renderer asks the entity for its ID, and an ID is only assigned
							// when an entity joins a world. These never join one, so without this
							// the first model drawn takes the game down.
							living.setId(nextPreviewId--);
							model = living;
							built++;
						}
					} catch (Exception e) {
						CUtil.LOGGER.error("No preview model for {}", e, id);
					}
				}

				models.put(id, Optional.ofNullable(model));
				allEntries.add(new Entry(
						id,
						type,
						name,
						name.getString().toLowerCase(Locale.ROOT),
						type.getCategory(),
						!id.startsWith("minecraft:"),
						isMob));
			} catch (Exception e) {
				// This walks a registry any mod can put anything into, so one bad type costs one
				// row rather than the whole screen.
				CUtil.LOGGER.error("Skipping entity type {} in the custom target list", e, id);
			}
		}

		allEntries.sort(Comparator.comparing((Entry entry) -> entry.isMob ? 0 : 1)
				.thenComparing(entry -> entry.lowerName));
		CUtil.LOGGER.log("Custom target list opened: {} entity types, {} with a live model, level {}",
				allEntries.size(), built, level == null ? "ABSENT" : "present");
	}

	private void rebuildVisible() {
		List<Entry> filtered = new ArrayList<>();
		for (Entry entry : allEntries) {
			if (!matchesTab(entry)) {
				continue;
			}
			if (!query.isEmpty() && !entry.lowerName.contains(query) && !entry.id.contains(query)) {
				continue;
			}
			filtered.add(entry);
		}
		visible = List.copyOf(filtered);
		// The rows underneath just changed, so a row number kept from the old list would point at
		// a different mob. Dropped rather than guessed at.
		cursor = visible.isEmpty() ? -1 : Math.min(cursor, visible.size() - 1);
		clampScroll();
	}

	private boolean matchesTab(Entry entry) {
		return switch (tab) {
			// Main is the list proper: everything with a model to show. Everything else - boats,
			// arrows, displays, projectiles - is real and targetable but has no picture, so it
			// lives in Other rather than padding out the list you actually browse.
			//
			// Split on what the type IS rather than on whether a model got built, so the two tabs
			// hold the same things on the title screen as they do in a world. Only the pictures
			// depend on there being a world; the membership does not.
			case MAIN -> entry.isMob;
			case OTHER -> !entry.isMob;
			case HOSTILE -> entry.category == MobCategory.MONSTER;
			case PASSIVE -> isPassive(entry);
			case MODDED -> entry.modded;
			case SELECTED -> settings.customTargets.contains(entry.id);
		};
	}

	/**
	 * Everything alive that isn't a monster. Stated as an exclusion so a category added in a
	 * later version lands somewhere sensible without this needing an edit.
	 * <p>
	 * Miscellaneous is normally kept out, since it is where boats and arrows live - but it also
	 * holds the iron golem, which fights back exactly as the provocable creatures do. A mob filed
	 * that way that also fights back belongs here, or the provocable buttons would be acting on
	 * something this tab never lists. It is written as a rule rather than as a name so anything
	 * else that ends up in the same position is treated the same way.
	 */
	private boolean isPassive(Entry entry) {
		if (entry.category == MobCategory.MONSTER) {
			return false;
		}
		if (entry.category == MobCategory.MISC) {
			return entry.isMob && PROVOCABLE.contains(entry.id);
		}
		return true;
	}

	private int contentHeight() {
		return visible.size() * ROW_HEIGHT;
	}

	private int viewHeight() {
		return listBottom - listTop;
	}

	private void clampScroll() {
		scroll = Mth.clamp(scroll, 0, Math.max(0, contentHeight() - viewHeight()));
	}

	@Override
	public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
		advanceStickScroll();
		super.extractRenderState(graphics, mouseX, mouseY, partial);

		drawChrome(graphics);
		graphics.centeredText(font, title, width / 2, 12, COLOR_NAME);
		drawActiveTabMarker(graphics);
		if (showNoWorldNote) {
			graphics.centeredText(font, Component.translatable("controlify.gui.custom_list.no_world"),
					width / 2, HEADER_BOTTOM + 3, COLOR_ID);
		}

		graphics.fill(listLeft, listTop, listRight, listBottom, COLOR_LIST_BG);
		graphics.enableScissor(listLeft, listTop, listRight, listBottom);

		int first = Math.max(0, (int) (scroll / ROW_HEIGHT));
		int last = Math.min(visible.size(), first + viewHeight() / ROW_HEIGHT + 2);
		for (int i = first; i < last; i++) {
			int rowY = listTop + i * ROW_HEIGHT - (int) scroll;
			drawRow(graphics, visible.get(i), i, rowY, mouseX, mouseY);
		}

		graphics.disableScissor();
		graphics.outline(listLeft, listTop, listRight - listLeft, listBottom - listTop, COLOR_PANEL_BORDER);
		drawScrollbar(graphics);

		graphics.centeredText(font,
				Component.translatable("controlify.gui.custom_list.count",
						settings.customTargets.size(), visible.size()),
				width / 2, listBottom + 7, COLOR_ID);
	}

	/**
	 * The scrim and panel behind everything else.
	 * <p>
	 * Widgets are drawn by {@code super.extractRenderState}, which has already run by the time
	 * this is called, so anything filled here lands <em>on top of them</em>. Painting one big
	 * rectangle over the panel therefore greys out the search box, the tabs and the footer
	 * buttons. Instead the backing is filled around them: the side margins run the full height,
	 * because no widget is ever wider than the list, and the middle is filled only in the bands
	 * between one widget row and the next.
	 */
	private void drawChrome(GuiGraphicsExtractor graphics) {
		int panelLeft = listLeft - PANEL_PAD;
		int panelRight = listRight + PANEL_PAD;

		// Dim the world, but only outside the panel - inside it would fall over the widgets.
		graphics.fill(0, 0, panelLeft, height, COLOR_SCRIM);
		graphics.fill(panelRight, 0, width, height, COLOR_SCRIM);
		graphics.fill(panelLeft, 0, panelRight, PANEL_TOP, COLOR_SCRIM);
		graphics.fill(panelLeft, height - PANEL_TOP, panelRight, height, COLOR_SCRIM);

		// Margins either side, full height: widgets only ever span listLeft..listRight.
		graphics.fill(panelLeft, PANEL_TOP, listLeft, height - PANEL_TOP, COLOR_PANEL);
		graphics.fill(listRight, PANEL_TOP, panelRight, height - PANEL_TOP, COLOR_PANEL);

		// The gaps between widget rows, top to bottom.
		graphics.fill(listLeft, PANEL_TOP, listRight, SEARCH_Y, COLOR_PANEL);
		graphics.fill(listLeft, SEARCH_Y + SEARCH_HEIGHT, listRight, TABS_Y, COLOR_PANEL);
		graphics.fill(listLeft, TABS_Y + TABS_HEIGHT, listRight, listTop, COLOR_PANEL);
		int footerTop = height - FOOTER_BUTTON_TOP;
		graphics.fill(listLeft, listBottom, listRight, bulkStacked ? bulkY : footerTop, COLOR_PANEL);

		// The widget rows themselves are skipped above, but the widgets don't tile their row
		// edge to edge - there are gaps between the tabs, either side of the footer buttons and
		// between them, and a strip below them. Each of those showed the world through the panel.
		fillRowGaps(graphics, TABS_Y, TABS_HEIGHT, tabX, tabWidth);
		if (bulkStacked) {
			fillRowGaps(graphics, bulkY, FOOTER_BUTTON_HEIGHT, bulkX, bulkWidth);
			graphics.fill(listLeft, bulkY + FOOTER_BUTTON_HEIGHT, listRight, footerTop, COLOR_PANEL);
		}
		fillFooterGaps(graphics, footerTop);
		graphics.fill(listLeft, footerTop + FOOTER_BUTTON_HEIGHT, listRight,
				height - PANEL_TOP, COLOR_PANEL);

		graphics.outline(panelLeft, PANEL_TOP, panelRight - panelLeft, height - PANEL_TOP * 2,
				COLOR_PANEL_BORDER);
	}

	/**
	 * Fills the panel color into the gaps of one row of evenly spaced widgets: before the first,
	 * between each pair, and after the last. Filling the row wholesale would grey the widgets out,
	 * since they are drawn before any of this.
	 */
	private void fillRowGaps(GuiGraphicsExtractor graphics, int top, int rowHeight, int[] starts, int itemWidth) {
		if (starts.length == 0) {
			return;
		}
		int bottom = top + rowHeight;
		graphics.fill(listLeft, top, starts[0], bottom, COLOR_PANEL);
		for (int i = 0; i < starts.length - 1; i++) {
			graphics.fill(starts[i] + itemWidth, top, starts[i + 1], bottom, COLOR_PANEL);
		}
		graphics.fill(starts[starts.length - 1] + itemWidth, top, listRight, bottom, COLOR_PANEL);
	}

	/**
	 * The footer button row, which holds two different widths when the bulk buttons flank Clear
	 * All and Done, and only those two when they have taken a row of their own.
	 */
	private void fillFooterGaps(GuiGraphicsExtractor graphics, int top) {
		if (bulkStacked) {
			fillRowGaps(graphics, top, FOOTER_BUTTON_HEIGHT,
					new int[] { clearX, doneX }, footerButtonWidth);
			return;
		}
		int[] starts = new int[BULK_COUNT * 2 + 2];
		int[] widths = new int[starts.length];
		for (int i = 0; i < BULK_COUNT; i++) {
			starts[i] = bulkX[i];
			widths[i] = bulkWidth;
			starts[BULK_COUNT + 2 + i] = bulkX[BULK_COUNT + i];
			widths[BULK_COUNT + 2 + i] = bulkWidth;
		}
		starts[BULK_COUNT] = clearX;
		widths[BULK_COUNT] = footerButtonWidth;
		starts[BULK_COUNT + 1] = doneX;
		widths[BULK_COUNT + 1] = footerButtonWidth;
		fillRowGaps(graphics, top, FOOTER_BUTTON_HEIGHT, starts, widths);
	}

	/** As {@link #fillRowGaps(GuiGraphicsExtractor, int, int, int[], int)}, for a ragged row. */
	private void fillRowGaps(GuiGraphicsExtractor graphics, int top, int rowHeight, int[] starts, int[] widths) {
		if (starts.length == 0) {
			return;
		}
		int bottom = top + rowHeight;
		int last = starts.length - 1;
		graphics.fill(listLeft, top, starts[0], bottom, COLOR_PANEL);
		for (int i = 0; i < last; i++) {
			graphics.fill(starts[i] + widths[i], top, starts[i + 1], bottom, COLOR_PANEL);
		}
		graphics.fill(starts[last] + widths[last], top, listRight, bottom, COLOR_PANEL);
	}

	/** An accent bar under whichever tab is showing, so the choice survives the focus moving. */
	private void drawActiveTabMarker(GuiGraphicsExtractor graphics) {
		int index = tab.ordinal();
		if (index >= tabX.length) {
			return;
		}
		graphics.fill(tabX[index], TABS_Y + TABS_HEIGHT, tabX[index] + tabWidth, TABS_Y + TABS_HEIGHT + 2, COLOR_ACCENT);
	}

	private void drawRow(GuiGraphicsExtractor graphics, Entry entry, int index, int y, int mouseX, int mouseY) {
		boolean picked = settings.customTargets.contains(entry.id);
		boolean hovered = mouseX >= listLeft && mouseX < listRight && mouseY >= y && mouseY < y + ROW_HEIGHT
				&& mouseY >= listTop && mouseY < listBottom;
		// Only while the list itself holds focus: a leftover marker on a list nobody is on would
		// read as a selection rather than as where the stick is.
		boolean onCursor = index == cursor && list != null && list.isFocused();

		if (index % 2 == 1) {
			graphics.fill(listLeft, y, listRight, y + ROW_HEIGHT, COLOR_ROW_ODD);
		}
		if (hovered || onCursor) {
			graphics.fill(listLeft, y, listRight, y + ROW_HEIGHT, COLOR_ROW_HOVER);
		}
		if (onCursor) {
			graphics.outline(listLeft, y, listRight - listLeft, ROW_HEIGHT, COLOR_NAME);
		}

		int iconLeft = listLeft + (listRight - listLeft) / 2 - ICON_BOX / 2;
		LivingEntity model = modelFor(entry);
		boolean drewModel = false;
		if (model != null) {
			// A real model, lit and posed. Scaled per mob so a bee and an ender dragon both end up
			// row height.
			int scale = (int) Mth.clamp(ICON_TARGET_HEIGHT / Math.max(0.4f, model.getBbHeight()), 4, 48);
			try {
				drawModel(graphics, model, iconLeft, y, scale, mouseX, mouseY);
				drewModel = true;
			} catch (Exception e) {
				// A settings screen must never take the game down. Any renderer that throws - a
				// mod's model, a missing texture, an assumption about being in a world - costs one
				// picture, is logged, and is struck off so it isn't retried every frame.
				models.put(entry.id, Optional.empty());
				CUtil.LOGGER.error("Preview model for {} failed to draw, falling back to a chip", e, entry.id);
			}
		}
		if (!drewModel) {
			drawCategoryChip(graphics, entry, iconLeft + (ICON_BOX - CHIP_SIZE) / 2, y + (ROW_HEIGHT - CHIP_SIZE) / 2);
		}

		// Name on the left, model in the middle, state on the right.
		int textX = listLeft + 12;
		int nameHeight = (int) (font.lineHeight * NAME_SCALE);
		int nameY = entry.modded ? y + 3 : y + (ROW_HEIGHT - nameHeight) / 2;

		graphics.pose().pushMatrix();
		graphics.pose().translate((float) textX, (float) nameY);
		graphics.pose().scale(NAME_SCALE, NAME_SCALE);
		graphics.text(font, entry.name, 0, 0, COLOR_NAME);
		graphics.pose().popMatrix();

		if (entry.modded) {
			graphics.text(font, entry.id, textX, y + 4 + nameHeight, COLOR_ID);
		}

		int tickX = listRight - SCROLLBAR_WIDTH - TICK_SIZE - 8;
		if (picked) {
			drawTargetedPill(graphics, tickX - 12, y + (ROW_HEIGHT - PILL_HEIGHT) / 2);
		}
		int tickY = y + (ROW_HEIGHT - TICK_SIZE) / 2;
		graphics.fill(tickX, tickY, tickX + TICK_SIZE, tickY + TICK_SIZE, 0xFF202020);
		graphics.outline(tickX, tickY, TICK_SIZE, TICK_SIZE,
				picked ? COLOR_TICK_BORDER_ON : COLOR_TICK_BORDER);
		if (picked) {
			graphics.fill(tickX + 3, tickY + 3, tickX + TICK_SIZE - 3, tickY + TICK_SIZE - 3, COLOR_ACCENT);
		}
	}

	/**
	 * An inert mob of this type to draw, or null if there isn't one to draw.
	 * <p>
	 * The entity is created but never added to the world and never ticked, so it is just a bag of
	 * state for the renderer to read. Types that aren't living things - boats, arrows, block
	 * displays - have no model to show and fall back to the color chip, which is the honest
	 * answer for them anyway.
	 */
	private @Nullable LivingEntity modelFor(Entry entry) {
		Optional<LivingEntity> cached = models.get(entry.id);
		return cached == null ? null : cached.orElse(null);
	}

	/**
	 * The "Targeted" badge: a red pill with italic text, sitting to the left of the tick box and
	 * drawn only for rows that are on the list. It replaces the green row tint, which colored a
	 * whole row and left the reader to work out why.
	 *
	 * @param right the pill's right edge; it is laid out leftwards from there so it always clears
	 *              the tick box however wide the label ends up in another language
	 */
	private void drawTargetedPill(GuiGraphicsExtractor graphics, int right, int top) {
		Component label = TARGETED;
		int left = right - (pillTextWidth + 18);
		int bottom = top + PILL_HEIGHT;

		// Three inset bands make a capsule out of nothing but rectangles.
		graphics.fill(left + 4, top, right - 4, bottom, COLOR_PILL);
		graphics.fill(left + 2, top + 1, right - 2, bottom - 1, COLOR_PILL);
		graphics.fill(left, top + 3, right, bottom - 3, COLOR_PILL);
		graphics.fill(left + 4, top, right - 4, top + 1, COLOR_PILL_EDGE);
		graphics.fill(left + 4, bottom - 1, right - 4, bottom, COLOR_PILL_EDGE);

		graphics.text(font, label, left + 9, top + (PILL_HEIGHT - font.lineHeight) / 2 + 1, COLOR_NAME);
	}

	/**
	 * A color chip standing in for the mob. Spawn eggs were the obvious icon and turned out not
	 * to be usable: building an {@code ItemStack} for one throws "Components not bound yet" on the
	 * client, as does {@code SpawnEggItem.byId} - 88 of 88 eggs failed that way. The category is
	 * known without touching items at all, and hostile-or-not is arguably the more useful thing
	 * for this screen to be saying anyway.
	 */
	/**
	 * Draws one mob in its row, facing whichever way suits what is being held.
	 * <p>
	 * With a mouse in hand the mob follows the cursor, which is what vanilla's inventory preview
	 * does and is the nicer of the two to look at. With a controller there is no cursor to follow,
	 * so every mob in the list was left staring at wherever the pointer happened to have been
	 * parked - all of them the same wrong way, usually up and to the left. They turn slowly on the
	 * spot instead, which shows each one from every side and reads as meant.
	 * <p>
	 * Which of the two it is follows the input mode that is already being tracked, so picking up
	 * the mouse turns the following back on and touching the controller turns it off again, with
	 * no setting to find.
	 */
	private void drawModel(GuiGraphicsExtractor graphics, LivingEntity model, int x, int y, int scale,
			int mouseX, int mouseY) {
		int left = x;
		int top = y + 1;
		int right = x + ICON_BOX;
		int bottom = y + ROW_HEIGHT - 1;

		if (!Controlify.instance().currentInputMode().isController()) {
			InventoryScreen.extractEntityInInventoryFollowsMouse(graphics, left, top, right, bottom,
					scale, 0f, mouseX, mouseY, model);
			return;
		}
		turnInPlace(graphics, model, left, top, right, bottom, scale);
	}

	/** One full turn in this long. Slow on purpose: it is a list to read, not a thing to watch. */
	private static final double MODEL_SPIN_MS = 7000;

	/**
	 * The same draw vanilla's inventory preview makes, with the angle taken off the clock instead
	 * of off the cursor.
	 * <p>
	 * Every call in it was checked against the 26.1, 26.2 and 26.3 jars before the version guard
	 * around it came off, which is the only reason it is not guarded.
	 * <p>
	 * Written out rather than steered by handing the public helper a made up cursor position,
	 * because that one takes an arctangent of how far away the cursor is. However far out it is
	 * put, the turn is capped at about thirty degrees either side - a lean, not a rotation. Nothing
	 * below is private API: it is what the helper itself does, which is worth saying because it
	 * means this has to be kept in step with it by hand.
	 */
	private static void turnInPlace(GuiGraphicsExtractor graphics, LivingEntity model,
			int left, int top, int right, int bottom, int scale) {
		float spin = (float) (System.nanoTime() / 1_000_000L % (long) MODEL_SPIN_MS / MODEL_SPIN_MS * 360);

		EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
		EntityRenderState state = dispatcher.getRenderer(model).createRenderState(model, 1.0f);
		// No shadow and no outline: the row is not a floor, and nothing here is glowing.
		state.shadowPieces.clear();
		state.outlineColor = 0;
		if (state instanceof LivingEntityRenderState living) {
			living.bodyRot = 180 + spin;
			// Head in line with the body and level, so a mob is never caught looking at nothing.
			living.yRot = 0;
			living.xRot = 0;
			// The preview draws at a scale of one and carries the mob's own size in the box
			// measurements instead, which is what makes one number fit a bee and an ender dragon.
			living.boundingBoxWidth /= living.scale;
			living.boundingBoxHeight /= living.scale;
			living.scale = 1;
		}
		// Turned over, because the screen's y axis runs downwards and the model would otherwise
		// stand on its head; and no pitch at all, which is the whole difference from the version
		// that follows the cursor.
		Quaternionf upright = new Quaternionf().rotateZ((float) Math.PI);
		Quaternionf level = new Quaternionf();
		// Vector3f and Quaternionf rather than the interfaces they implement: 26.2 and 26.3 take
		// Vector3fc and Quaternionfc here, 26.1 takes the concrete classes, and passing concrete
		// instances satisfies all three from one source.
		graphics.entity(state, scale, new Vector3f(0, state.boundingBoxHeight / 2, 0), upright, level,
				left, top, right, bottom);
	}

	private void drawCategoryChip(GuiGraphicsExtractor graphics, Entry entry, int x, int y) {
		int color = switch (entry.category) {
			case MONSTER -> COLOR_CHIP_HOSTILE;
			case MISC -> COLOR_CHIP_MISC;
			default -> COLOR_CHIP_PASSIVE;
		};
		// Two overlapping rectangles make a square with the corners taken off, which reads as a
		// dot at this size and needs nothing but fill().
		graphics.fill(x + 1, y, x + CHIP_SIZE - 1, y + CHIP_SIZE, color);
		graphics.fill(x, y + 1, x + CHIP_SIZE, y + CHIP_SIZE - 1, color);
	}

	private void drawScrollbar(GuiGraphicsExtractor graphics) {
		int overflow = contentHeight() - viewHeight();
		if (overflow <= 0) {
			return;
		}
		int barLeft = listRight - SCROLLBAR_WIDTH;
		int thumbHeight = Math.max(20, viewHeight() * viewHeight() / contentHeight());
		int travel = viewHeight() - thumbHeight;
		int thumbTop = listTop + (int) (scroll / overflow * travel);
		graphics.fill(barLeft, listTop, listRight, listBottom, COLOR_SCROLL_TRACK);
		graphics.fill(barLeft + 1, thumbTop, listRight - 1, thumbTop + thumbHeight, COLOR_SCROLL_THUMB);
	}

	@Override
	public boolean mouseClicked(@NonNull MouseButtonEvent mouseButtonEvent, boolean doubleClick) {
		double mouseX = mouseButtonEvent.x();
		double mouseY = mouseButtonEvent.y();

		// Decided before anything else can return early. Grabbing the bar jumps the thumb to the
		// cursor and starts a drag; any other click ends one. That is why no mouse-release handler
		// is needed - a drag can only begin with a click, and every click settles the flag. Doing
		// this after the super call instead would leave the flag set when a widget swallows the
		// click, so dragging to select text in the search box would scroll the list.
		draggingScrollbar = mouseX >= listRight - SCROLLBAR_WIDTH && mouseX < listRight
				&& mouseY >= listTop && mouseY < listBottom;
		if (draggingScrollbar) {
			scrollToMouse(mouseY);
			return true;
		}

		return super.mouseClicked(mouseButtonEvent, doubleClick);
	}

	/** Which row a y coordinate falls on, or -1 for none. */
	private int rowAt(double mouseY) {
		int index = (int) ((mouseY - listTop + scroll) / ROW_HEIGHT);
		return index >= 0 && index < visible.size() ? index : -1;
	}

	/**
	 * Moves the controller's row by one, and says whether it could. It returns false at the two
	 * ends rather than stopping dead there, which is what lets a push past the last row carry the
	 * focus out of the list and on to the buttons underneath.
	 */
	private boolean moveCursor(int delta) {
		if (visible.isEmpty()) {
			return false;
		}
		int next = cursor < 0 ? (delta > 0 ? 0 : visible.size() - 1) : cursor + delta;
		if (next < 0 || next >= visible.size()) {
			return false;
		}
		cursor = next;
		scrollToRow(cursor);
		return true;
	}

	/** Brings a row fully inside the view, moving as little as will do it. */
	private void scrollToRow(int index) {
		int rowTop = index * ROW_HEIGHT;
		int rowBottom = rowTop + ROW_HEIGHT;
		if (rowTop < scroll) {
			scroll = rowTop;
		} else if (rowBottom > scroll + viewHeight()) {
			scroll = rowBottom - viewHeight();
		}
		clampScroll();
	}

	/**
	 * The two things a controller can do here without the focus being on anything in particular:
	 * change tab with the bumpers, and scroll the list with the right stick.
	 * <p>
	 * Both live at the screen rather than on a widget because neither is about what is focused.
	 * The bumpers work from the search box, from a footer button, from the list - anywhere - and
	 * the right stick scrolls the list even while the focus is somewhere else, which is what makes
	 * it worth having alongside the d-pad rather than a duplicate of it.
	 */
	@Override
	public void onControllerInput(ControllerEntity controller) {
		boolean next = ControlifyBindings.GUI_NEXT_TAB.on(controller).justPressed();
		boolean previous = ControlifyBindings.GUI_PREV_TAB.on(controller).justPressed();
		if (next != previous) {
			cycleTab(next ? 1 : -1, controller);
		}
		// Only recorded here, not acted on: this runs twenty times a second and the list is drawn
		// far more often than that. See advanceStickScroll.
		//
		// While the virtual mouse is up the right stick belongs to it; taking it here would fight
		// whatever the pointer is doing.
		stickScroll = Controlify.instance().virtualMouseHandler().isVirtualMouseEnabled()
				? 0
				: curve(ControlifyBindings.GUI_SECONDARY_NAVI_DOWN.on(controller).analogueNow())
						- curve(ControlifyBindings.GUI_SECONDARY_NAVI_UP.on(controller).analogueNow());
	}

	/**
	 * Steps to the next tab along, wrapping at either end so holding one bumper goes round and
	 * round rather than stopping at Selected.
	 */
	private void cycleTab(int delta, ControllerEntity controller) {
		Tab[] tabs = Tab.values();
		tab = tabs[Math.floorMod(tab.ordinal() + delta, tabs.length)];
		scroll = 0;
		// The rows underneath are about to be different ones, so the row number is dropped rather
		// than left pointing at whatever now happens to be in that position.
		cursor = -1;
		rebuildVisible();
		minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
		controller.hdHaptics().ifPresent(haptics -> haptics.playHaptic(HapticEffects.NAVIGATE));
	}

	/**
	 * Moves the view on by however much time has passed since the last frame, at whatever speed
	 * the right stick is asking for.
	 * <p>
	 * Done per frame rather than per controller update because controller updates arrive on the
	 * client tick - twenty a second - and the list is drawn at whatever the frame rate is. Adding
	 * the whole of a tick's worth of movement in one go leaves the rows still for two or three
	 * frames and then jumping, which is the judder. It shows up worst at slow speeds, where the
	 * jump is a single pixel but its timing beats against the fifty millisecond tick: the gaps
	 * between one pixel and the next come out 50ms, 100ms, 50ms, 100ms. Equal steps at uneven
	 * times is exactly what the eye reads as choppy. Paid out by elapsed time instead, the same
	 * pixels land evenly, and at speed the movement is spread across every frame rather than
	 * arriving in twenty lumps a second.
	 * <p>
	 * A whole pixel is still the smallest step there is - the rows are drawn on the pixel grid and
	 * the mob models are submitted with integer bounds that no transform applies to, so going
	 * finer would slide the models against their own rows. Even timing is the whole of what can be
	 * had here, and it is the part that was missing.
	 * <p>
	 * Squared rather than straight, so a small lean creeps a few pixels for picking a row out and
	 * a full push covers ground on a list that runs to a thousand entries. The row under the focus
	 * is carried along with it, so the d-pad picks up from what is on screen instead of snapping
	 * the view back to wherever it was left.
	 */
	private void advanceStickScroll() {
		long now = System.nanoTime();
		long since = lastScrollNanos == 0 ? 0 : now - lastScrollNanos;
		lastScrollNanos = now;

		// The push is only ever set while a controller is being used, so it would otherwise be
		// left pointing somewhere if the mouse were picked up mid-scroll.
		if (stickScroll == 0 || since <= 0 || !Controlify.instance().currentInputMode().isController()) {
			return;
		}
		scroll += stickScroll * SCROLL_PER_SECOND * Math.min(since / 1.0e9, LONGEST_FRAME_SECONDS);
		clampScroll();
		keepCursorInView();
	}

	private static double curve(float amount) {
		return amount < SCROLL_DEADZONE ? 0 : (double) amount * amount;
	}

	/**
	 * Brings the row under the focus back inside the view, moving it as little as will do - the
	 * mirror of {@link #scrollToRow}, which moves the view to the row instead.
	 */
	private void keepCursorInView() {
		if (visible.isEmpty()) {
			cursor = -1;
			return;
		}
		int first = Mth.clamp((int) Math.ceil(scroll / ROW_HEIGHT), 0, visible.size() - 1);
		int last = Mth.clamp((int) ((scroll + viewHeight()) / ROW_HEIGHT) - 1, first, visible.size() - 1);
		cursor = Mth.clamp(cursor < 0 ? first : cursor, first, last);
	}

	private void toggle(Entry entry) {
		if (!settings.customTargets.remove(entry.id)) {
			settings.customTargets.add(entry.id);
		}
		// The Selected tab is the one view where ticking something changes what belongs on screen.
		if (tab == Tab.SELECTED) {
			rebuildVisible();
		}
	}

	@Override
	public boolean mouseDragged(@NonNull MouseButtonEvent mouseButtonEvent, double dragX, double dragY) {
		if (draggingScrollbar) {
			scrollToMouse(mouseButtonEvent.y());
			return true;
		}
		return super.mouseDragged(mouseButtonEvent, dragX, dragY);
	}

	/** Puts the middle of the thumb under the cursor, so the list follows the hand exactly. */
	private void scrollToMouse(double mouseY) {
		int overflow = contentHeight() - viewHeight();
		if (overflow <= 0) {
			scroll = 0;
			return;
		}
		int thumbHeight = Math.max(20, viewHeight() * viewHeight() / contentHeight());
		int travel = viewHeight() - thumbHeight;
		if (travel <= 0) {
			scroll = 0;
			return;
		}
		double along = (mouseY - listTop - thumbHeight / 2.0) / travel;
		scroll = Mth.clamp(along, 0, 1) * overflow;
		clampScroll();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
			return true;
		}
		scroll -= scrollY * ROW_HEIGHT * 2;
		clampScroll();
		return true;
	}

	@Override
	public void onClose() {
		Controlify.instance().config().saveSafely();
		MinecraftUtil.setScreen(parent);
	}
}
