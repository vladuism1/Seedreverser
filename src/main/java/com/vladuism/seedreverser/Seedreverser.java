package com.vladuism.seedreverser;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuppressWarnings({"unused", "SpellCheckingInspection"})
public class Seedreverser implements ModInitializer {
    public static final String MOD_ID = "seedreverser";

    private static final Set<StructureSeedSolver.ChunkPos> capturedTemples = new HashSet<>();
    private static final Set<StructureSeedSolver.ChunkPos> capturedSlimes = new HashSet<>();

    @Override
    public void onInitialize() {
        // 1. Passive Chunk Scanner: Detects temples as new chunks load
        ServerChunkEvents.CHUNK_LOAD.register((ServerLevel world, LevelChunk chunk, boolean isNewChunk) -> {
            if (world.isClientSide()) return;

            ChunkPos cPos = chunk.getPos();
            int chunkX = cPos.getMinBlockX() >> 4;
            int chunkZ = cPos.getMinBlockZ() >> 4;
            StructureSeedSolver.ChunkPos solverPos = new StructureSeedSolver.ChunkPos(chunkX, chunkZ);

            if (capturedTemples.contains(solverPos)) return;

            // Check if chunk contains a temple structure
            for (StructureStart start : chunk.getAllStarts().values()) {
                if (!start.isValid()) continue;
                
                // Find matching structure in registry
                for (HolderLookup.RegistryLookup<Structure> lookup : world.registryAccess().lookup(Registries.STRUCTURE).stream().toList()) {
                    for (Holder.Reference<Structure> ref : lookup.listElements().toList()) {
                        if (ref.value() == start.getStructure()) {
                            ResourceKey<Structure> key = ref.unwrapKey().orElse(null);
                            if (key != null) {
                                String structureKey = key.identifier().toString();
                                if (isTempleStructure(structureKey) && capturedTemples.add(solverPos)) {
                                    broadcastToOps(world, "Auto-detected temple at chunk (" + chunkX + ", " + chunkZ + ")!");
                                }
                            }
                        }
                    }
                }
            }
        });

        // 2. Passive Entity Scanner: Detects slime chunks near players
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerLevel world : server.getAllLevels()) {
                for (ServerPlayer player : world.players()) {
                    ChunkPos cPos = player.chunkPosition();
                    int chunkX = cPos.getMinBlockX() >> 4;
                    int chunkZ = cPos.getMinBlockZ() >> 4;
                    StructureSeedSolver.ChunkPos solverPos = new StructureSeedSolver.ChunkPos(chunkX, chunkZ);

                    boolean hasSlime = !world.getEntitiesOfClass(Slime.class, player.getBoundingBox().inflate(16)).isEmpty();

                    if (hasSlime && capturedSlimes.add(solverPos)) {
                        send(player.createCommandSourceStack(), "Auto-detected slime chunk at (" + chunkX + ", " + chunkZ + ")!");
                    }
                }
            }
        });

        // 3. Command Interface
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                registerCommands(dispatcher)
        );
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(MOD_ID)
                .then(Commands.literal("solve")
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            List<StructureSeedSolver.ChunkPos> templeList = new ArrayList<>(capturedTemples);
                            List<StructureSeedSolver.ChunkPos> slimeList = new ArrayList<>(capturedSlimes);

                            if (templeList.size() < 2) {
                                send(src, "Need at least 2 detected temples! Walk around desert/swamp/jungle biomes to scan loaded chunks.");
                                send(src, "Currently captured: " + templeList.size() + " temple(s).");
                                return 0;
                            }

                            send(src, "Solving seed using " + templeList.size() + " auto-detected temple(s)...");

                            new Thread(() -> {
                                try {
                                    List<Long> structureSeeds = StructureSeedSolver.findStructureSeeds(
                                            StructureSeedSolver.TEMPLE_CONFIG, templeList);

                                    if (structureSeeds.isEmpty()) {
                                        send(src, "No structure seeds found from captured positions.");
                                        return;
                                    }

                                    if (slimeList.isEmpty()) {
                                        send(src, "Found " + structureSeeds.size() + " candidate structure seed(s):");
                                        for (long s : structureSeeds) {
                                            send(src, "  Structure seed: " + s);
                                        }
                                        send(src, "Walk into slime caves to auto-detect slime chunks and pinpoint the exact world seed!");
                                    } else {
                                        int found = 0;
                                        for (long structSeed : structureSeeds) {
                                            List<Long> worldSeeds = StructureSeedSolver.liftTo64Bit(structSeed, slimeList);
                                            for (long worldSeed : worldSeeds) {
                                                send(src, ">>> WORLD SEED: " + worldSeed + " <<<");
                                                found++;
                                            }
                                        }
                                        if (found == 0) send(src, "No 64-bit seed matched. Keep walking to capture more slime chunks.");
                                    }
                                } catch (Exception e) {
                                    send(src, "Solver error: " + e.getMessage());
                                }
                            }).start();

                            return 1;
                        }))

                .then(Commands.literal("status")
                        .executes(ctx -> {
                            send(ctx.getSource(), "Captured Temples: " + capturedTemples.size()
                                    + " | Captured Slimes: " + capturedSlimes.size());
                            return 1;
                        }))

                .then(Commands.literal("clear")
                        .executes(ctx -> {
                            capturedTemples.clear();
                            capturedSlimes.clear();
                            send(ctx.getSource(), "Cleared all captured data.");
                            return 1;
                        }))
        );
    }

    private static boolean isTempleStructure(String key) {
        return key.contains("desert_pyramid") ||
                key.contains("jungle_pyramid") ||
                key.contains("swamp_hut") ||
                key.contains("igloo");
    }

    private static void broadcastToOps(ServerLevel level, String msg) {
        for (ServerPlayer player : level.players()) {
            if (player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
                send(player.createCommandSourceStack(), msg);
            }
        }
    }

    private static void send(CommandSourceStack src, String msg) {
        src.sendSystemMessage(Component.literal("[SeedReverser] " + msg));
    }
}
