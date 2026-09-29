/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.aimassist;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * How far a mob moves each tick, averaged over its last {@value #TICKS} ticks (tl93).
 * <p>
 * Trajectory Aim leads a moving mob by its movement for as long as the shot flies plus Lag
 * Compensation's allowance - a dozen ticks and more - so whatever is read as its movement over one
 * tick is multiplied by that much before it becomes the point the crosshair is held on. Read over a
 * single tick that movement is rough: a mob on a path turns towards each node in turn, the client
 * shows another entity's movement by replaying the server's own steps from a queue that can run
 * dry for a tick and then catch up (26.3's SteppedInterpolationHandler), and the mob itself speeds
 * up, slows down and turns back. Every one of those became a jump of a few degrees in the point,
 * and Donny saw the crosshair "struggling to stay on the spot" (28 Sep). Averaged over the last
 * half second the movement is what the mob is actually doing, which is also the better guess at
 * where it will be when the shot lands. A mob turning round shows up in the average over that half
 * second rather than at once; a shot let go while it turns misses either way.
 * <p>
 * One sample a tick per entity, keyed on the entity's own tick count so several readings in a tick
 * share the sample and a gap in the readings starts the average over. Entities not read for a tick
 * are forgotten on the next {@link #tick}.
 */
final class MotionAverage {
	/**
	 * Ticks averaged over: half a second. A stalled tick of the replay is a tenth of the reading; a
	 * mob turning is followed within the half second; and a mob that strafes back and forth - a
	 * skeleton keeping its distance - is led by less than its speed says, which is nearer where it
	 * will be, since it turns back before a shot could get there. Modelled against the shorter and
	 * longer windows in TrackTest93: shorter, the point still leaps at every reversal; longer, a
	 * walker turning a corner is led the old way for too long.
	 */
	static final int TICKS = 10;

	/** By identity: an entity's own equals goes by its id, which it has only once it is in a world. */
	private static final Map<Entity, Track> TRACKS = new IdentityHashMap<>();
	private static int now;

	private MotionAverage() {
	}

	/**
	 * The entity's movement a tick: the average over the last {@link #TICKS} ticks it has been read
	 * on, and its movement over the last tick alone until there are two readings to average.
	 */
	static Vec3 motion(Entity entity) {
		Track track = TRACKS.computeIfAbsent(entity, e -> new Track());
		track.sample(entity, now);
		return track.average(entity);
	}

	/** Once a tick, before any reading: forgets every entity not read last tick. */
	static void tick() {
		now++;
		for (Iterator<Track> it = TRACKS.values().iterator(); it.hasNext(); ) {
			if (it.next().lastRead < now - 1) {
				it.remove();
			}
		}
	}

	/** Every reading, for a test or a world change. */
	static void reset() {
		TRACKS.clear();
	}

	/** How many ticks of movement the reading for {@code entity} averages over, 0 when it has none. */
	static int span(Entity entity) {
		Track track = TRACKS.get(entity);
		return track == null ? 0 : track.span();
	}

	private static final class Track {
		/** The last TICKS + 1 positions, newest at {@code head}. */
		private final Vec3[] positions = new Vec3[TICKS + 1];
		private int head = -1;
		private int count;
		private int lastTickCount = Integer.MIN_VALUE;
		private int lastRead = Integer.MIN_VALUE;

		void sample(Entity entity, int readTick) {
			lastRead = readTick;
			if (entity.tickCount == lastTickCount) {
				return;
			}
			if (entity.tickCount != lastTickCount + 1) {
				count = 0;
			}
			lastTickCount = entity.tickCount;
			head = (head + 1) % positions.length;
			positions[head] = entity.position();
			count = Math.min(count + 1, positions.length);
		}

		int span() {
			return Math.max(0, count - 1);
		}

		Vec3 average(Entity entity) {
			int span = span();
			if (span == 0) {
				return AimAssist.tickMotion(entity);
			}
			Vec3 oldest = positions[Math.floorMod(head - span, positions.length)];
			return positions[head].subtract(oldest).scale(1.0 / span);
		}
	}
}
