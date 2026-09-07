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

    private static final Set<StructureSeedSolver.StructureData> capturedStructures = new HashSet<>();
    private static final Set<StructureSeedSolver.ChunkPos> capturedSlimes = new HashSet<>();

    @Override
    public void onInitialize() {
        // 1. Passive Chunk Scanner: Detects temples as new chunks load
        ServerChunkEvents.CHUNK_LOAD.register((ServerLevel world, LevelChunk chunk, boolean isNewChunk) -> {
            if (world.isClientSide()) return;

            ChunkPos cPos = chunk.getPos();
            // Use chunk coordinates directly - cPos.x() and cPos.z() give chunk coordinates
            int chunkX = cPos.x();
            int chunkZ = cPos.z();
            StructureSeedSolver.ChunkPos solverPos = new StructureSeedSolver.ChunkPos(chunkX, chunkZ);

            // Check if we already have this structure
            boolean alreadyHas = false;
            for (StructureSeedSolver.StructureData existing : capturedStructures) {
                if (existing.pos.x() == solverPos.x() && existing.pos.z() == solverPos.z() && existing.config == StructureSeedSolver.TEMPLE_CONFIG) {
                    alreadyHas = true;
                    break;
                }
            }
            if (alreadyHas) return;

            // Check if chunk contains a temple structure
            for (StructureStart start : chunk.getAllStarts().values()) {
                if (!start.isValid()) continue;
                
                // Find matching structure in registry
                for (HolderLookup.RegistryLookup<net.minecraft.world.level.levelgen.structure.Structure> lookup : world.registryAccess().lookup(Registries.STRUCTURE).stream().toList()) {
                    for (Holder.Reference<net.minecraft.world.level.levelgen.structure.Structure> ref : lookup.listElements().toList()) {
                        if (ref.value() == start.getStructure()) {
                            ResourceKey<net.minecraft.world.level.levelgen.structure.Structure> key = ref.unwrapKey().orElse(null);
                            if (key != null) {
                                String structureKey = key.identifier().toString();
                                if (isTempleStructure(structureKey)) {
                                    StructureSeedSolver.StructureData data = new StructureSeedSolver.StructureData(
                                            new StructureSeedSolver.ChunkPos(chunkX, chunkZ), StructureSeedSolver.TEMPLE_CONFIG);
                                    if (capturedStructures.add(data)) {
                                        broadcastToOps(world, "Auto-detected temple at chunk (" + chunkX + ", " + chunkZ + ")!");
                                    }
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

                    // Verify slime is actually in a slime spawn position (underground)
                    // Check for slimes below y=40 which is where slime chunks spawn them
                    boolean hasSlime = world.getEntitiesOfClass(Slime.class, 
                            player.getBoundingBox().inflate(16)).stream()
                            .anyMatch(s -> s.getY() < 40);

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
                            List<StructureSeedSolver.StructureData> structureList = new ArrayList<>(capturedStructures);
                            List<StructureSeedSolver.ChunkPos> slimeList = new ArrayList<>(capturedSlimes);

                            if (structureList.size() < 1) {
                                send(src, "Need at least 1 detected structure! Walk around to scan loaded chunks.");
                                send(src, "Currently captured: " + structureList.size() + " structure(s).");
                                return 0;
                            }

                            double totalBits = 0;
                            for (StructureSeedSolver.StructureData sd : structureList) {
                                totalBits += sd.getBits();
                            }
                            send(src, "Solving seed using " + structureList.size() + " structure(s) (" + totalBits + " bits)...");

                            new Thread(() -> {
                                try {
                                    List<Long> structureSeeds = StructureSeedSolver.findStructureSeedsGpu(structureList);

                                    if (structureSeeds.isEmpty()) {
                                        send(src, "No structure seeds found. Try capturing more structures in different regions.");
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
                            send(ctx.getSource(), "Captured Structures: " + capturedStructures.size()
                                    + " | Captured Slimes: " + capturedSlimes.size());
                            return 1;
                        }))

                .then(Commands.literal("clear")
                        .executes(ctx -> {
                            capturedStructures.clear();
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
