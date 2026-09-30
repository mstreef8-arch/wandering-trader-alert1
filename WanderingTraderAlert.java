package com.example.traderalert;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Warns every player when a wandering trader spawns.
 *
 * ENTITY_LOAD also fires for traders that were merely saved in a chunk and
 * loaded again. A freshly spawned trader has a despawn delay of 48000 ticks,
 * which the spawner sets right AFTER the entity is added, so we check it one
 * tick later. Traders that already lived a while have a lower delay and are ignored.
 */
public class WanderingTraderAlert implements ModInitializer {
	private static final int FRESH_DELAY_THRESHOLD = 47_900;
	// Saved in the entity's NBT, so a trader is never announced twice,
	// even after a chunk reload or a server restart.
	private static final String ALERTED_TAG = "wandering_trader_alert.announced";

	private record Pending(ServerLevel level, Entity entity) {}

	private final List<Pending> pending = new ArrayList<>();
	private final Set<UUID> announced = new HashSet<>();

	@Override
	public void onInitialize() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity.getType() == EntityType.WANDERING_TRADER
					&& !entity.getTags().contains(ALERTED_TAG)
					&& !announced.contains(entity.getUUID())) {
				pending.add(new Pending(level, entity));
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (pending.isEmpty()) return;
			List<Pending> batch = new ArrayList<>(pending);
			pending.clear();

			for (Pending p : batch) {
				var trader = EntityType.WANDERING_TRADER.tryCast(p.entity());
				if (trader == null || trader.isRemoved()) continue;
				// Natural spawns start at ~48000. Traders added by /summon, spawn eggs or
				// other mods have a despawn delay of 0. Anything in between is an old trader
				// that was just loaded again, so it is ignored.
				int delay = trader.getDespawnDelay();
				if (delay != 0 && delay < FRESH_DELAY_THRESHOLD) continue;
				if (!announced.add(trader.getUUID())) continue;
				trader.addTag(ALERTED_TAG);

				BlockPos pos = trader.blockPosition();
				String dimension = p.level().dimension().identifier().toString();

				Component message = Component.literal("A Wandering Trader has appeared at ")
					.withStyle(ChatFormatting.AQUA)
					.append(Component.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ())
						.withStyle(ChatFormatting.YELLOW))
					.append(Component.literal(" (" + dimension + ")").withStyle(ChatFormatting.GRAY));

				for (ServerPlayer player : server.getPlayerList().getPlayers()) {
					player.sendSystemMessage(message);
					player.displayClientMessage(message, true); // action bar
					player.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.MASTER, 1.0f, 1.0f);
				}
			}

			if (announced.size() > 1000) announced.clear(); // avoid unbounded growth
		});
	}
}
