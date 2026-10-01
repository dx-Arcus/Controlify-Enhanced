/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.config.dto;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.isxander.controlify.config.dto.device.DeviceConfig;

import java.util.Map;

public record SharedConfig(
		GlobalConfig globalConfig,
		Map<String, DeviceConfig> deviceConfig,
		AimAssistConfig aimAssistConfig,
		TouchConfig touchConfig
) {
	public static final Codec<SharedConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			GlobalConfig.CODEC.fieldOf("global").forGetter(SharedConfig::globalConfig),
			Codec.unboundedMap(Codec.STRING, DeviceConfig.CODEC).fieldOf("devices").forGetter(SharedConfig::deviceConfig),
			// Optional so configs written before aim assist existed still load.
			AimAssistConfig.CODEC.optionalFieldOf("aim_assist", AimAssistConfig.DEFAULT).forGetter(SharedConfig::aimAssistConfig),
			// The touch controls' layout (tl117): optional too, and left out while it is all defaults.
			TouchConfig.CODEC.optionalFieldOf("touch", TouchConfig.DEFAULT).forGetter(SharedConfig::touchConfig)
	).apply(instance, SharedConfig::new));
}
