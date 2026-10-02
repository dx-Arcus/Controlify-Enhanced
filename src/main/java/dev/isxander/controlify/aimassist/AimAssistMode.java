/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.aimassist;

import com.mojang.serialization.Codec;
import dev.isxander.yacl3.api.NameableEnum;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * Where aim assist is allowed to run. Modelled on {@code ReachAroundMode}: aim assist is a
 * single-player convenience by default, because plenty of servers class any aim assist as an
 * unfair advantage regardless of how it's implemented. Unlike Reach-Around's, Singleplayer & LAN
 * here also takes a LAN world you joined, not only one you host - see {@link #ownWorldOrLan}.
 */
public enum AimAssistMode implements NameableEnum, StringRepresentable {
	OFF(minecraft -> false),
	SINGLEPLAYER_AND_LAN(minecraft -> ownWorldOrLan(minecraft.isLocalServer(), minecraft.getCurrentServer())),
	EVERYWHERE(minecraft -> true);

	public static final Codec<AimAssistMode> CODEC = StringRepresentable.fromEnum(AimAssistMode::values);

	private final Predicate<Minecraft> canAimAssist;
	private final Component displayName;

	AimAssistMode(Predicate<Minecraft> canAimAssist) {
		this.canAimAssist = canAimAssist;
		this.displayName = Component.translatable("controlify.aim_assist.mode." + this.name().toLowerCase(Locale.ROOT));
	}

	public boolean canAimAssist() {
		return canAimAssist.test(Minecraft.getInstance());
	}

	/**
	 * Singleplayer & LAN's rule. A world this game is running itself counts - singleplayer, or one
	 * opened to LAN - and, since Donny asked on 27 Sep, a LAN world joined from the multiplayer
	 * screen's list of LAN games, which the game marks as LAN ({@link ServerData#isLan}). That list
	 * only ever holds worlds announced on your own network, each at the address it was announced
	 * from. Joining by address - Direct Connection or a saved server - counts as a server even when
	 * the address is on your own network: a tool relaying a public server to your own machine
	 * would otherwise pass for LAN.
	 * <p>
	 * Kept free of the running game so the rule can be exercised on its own.
	 */
	static boolean ownWorldOrLan(boolean localServer, @Nullable ServerData server) {
		return localServer || (server != null && server.isLan());
	}

	@Override
	public Component getDisplayName() {
		return displayName;
	}

	@Override
	public @NonNull String getSerializedName() {
		return this.name().toLowerCase(Locale.ROOT);
	}
}
