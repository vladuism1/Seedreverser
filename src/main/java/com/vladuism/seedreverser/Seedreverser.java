package com.vladuism.seedreverser;

import com.mojang.brigadier.CommandDispatcher;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.structure.DesertPyramid;
import com.seedfinding.mcfeature.structure.Igloo;
import com.seedfinding.mcfeature.structure.JunglePyramid;
import com.seedfinding.mcfeature.structure.OceanRuin;
import com.seedfinding.mcfeature.structure.RegionStructure;
import com.seedfinding.mcfeature.structure.Shipwreck;
import com.seedfinding.mcfeature.structure.SwampHut;
import com.seedfinding.mcfeature.structure.UniformStructure;
import com.seedfinding.mcfeature.structure.Village;
import net.minecraft.world.level.ChunkPos;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@SuppressWarnings({"unused", "SpellCheckingInspection"})
public class Seedreverser implements ModInitializer {
    public static final String MOD_ID = "seedreverser";

    private static final MCVersion SF_VERSION = MCVersion.latest();

    // Structure instances using only classes that exist in mc_feature
    private static final DesertPyramid DESERT_PYRAMID = new DesertPyramid(SF_VERSION);
    private static final JunglePyramid JUNGLE_PYRAMID = new JunglePyramid(SF_VERSION);
    private static final SwampHut SWAMP_HUT = new SwampHut(SF_VERSION);
    private static final Igloo IGLOO = new Igloo(SF_VERSION);
    private static final OceanRuin OCEAN_RUIN = new OceanRuin(SF_VERSION);
    private static final Shipwreck SHIPWRECK = new Shipwreck(SF_VERSION);
    private static final Village VILLAGE = new Village(SF_VERSION);

    // Thread-safe capture storage using CopyOnWriteArrayList
    private static final List<RegionStructure.Data<?>> capturedStructures = new CopyOnWriteArrayList<>();
    private static final List<ChunkPos> capturedSlimes = new CopyOnWriteArrayList<>();
    private static final Set<String> seenStructures = ConcurrentHashMap.newKeySet();

    // World seed received from SeedcrackerX (via API)
    private static volatile Long externalWorldSeed = null;

    @Override
    public void onInitialize() {
        // Structure detection on chunk load
        ServerChunkEvents.CHUNK_LOAD.register((ServerLevel world, LevelChunk chunk, boolean isNewChunk) -> {
            if (world.dimension() != Level.OVERWORLD) return;

            for (StructureStart start : chunk.getAllStarts().values()) {
                if (!start.isValid()) continue;

                ChunkPos origin = start.getChunkPos();
                UniformStructure<?> structure = identifyStructure(world, start);
                if (structure == null) continue;

                RegionStructure.Data<?> data = structure.at(origin.x(), origin.z());
                if (data == null) continue;

                // Use feature.getName() for stable dedupe key
                String dedupeKey = data.feature.getName() + ":" + origin.x() + ":" + origin.z();
                if (seenStructures.add(dedupeKey)) {
                    capturedStructures.add(data);
                    broadcastToOps(world, "Auto-detected " + data.feature.getName()
                            + " at chunk (" + origin.x() + ", " + origin.z() + ")!");
                }
            }
        });

        // Passive slime chunk detection - once per second
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 != 0) return;
            for (ServerLevel world : server.getAllLevels()) {
                if (world.dimension() != Level.OVERWORLD) continue;
                for (ServerPlayer player : world.players()) {
                    detectSlimeChunksAroundPlayer(world, player);
                }
            }
        });

        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                registerCommands(dispatcher)
        );
    }

    /**
     * Receives a world seed from SeedcrackerX API.
     */
    public static void receiveExternalSeed(long seed) {
        externalWorldSeed = seed;
    }

    /**
     * Returns the UniformStructure for a structure start, or null.
     */
    private static UniformStructure<?> identifyStructure(ServerLevel world, StructureStart start) {
        try {
            Registry<Structure> registry = world.registryAccess().lookup(Registries.STRUCTURE).orElse(null);
            if (registry == null) return null;

            ResourceKey<Structure> key = registry.getResourceKey(start.getStructure()).orElse(null);
            if (key == null) return null;

            String location = key.location().toString();

            return switch (location) {
                case "minecraft:desert_pyramid" -> DESERT_PYRAMID;
                case "minecraft:jungle_pyramid" -> JUNGLE_PYRAMID;
                case "minecraft:swamp_hut" -> SWAMP_HUT;
                case "minecraft:igloo" -> IGLOO;
                case "minecraft:ocean_ruin" -> OCEAN_RUIN;
                case "minecraft:shipwreck" -> SHIPWRECK;
                case "minecraft:village" -> VILLAGE;
                default -> null;
            };
        } catch (Exception e) {
            System.err.println("[SeedReverser] Error identifying structure: " + e.getMessage());
            return null;
        }
    }

    /**
     * Detects slime chunks around a player.
     */
    private static void detectSlimeChunksAroundPlayer(ServerLevel world, ServerPlayer player) {
        for (Slime slime : world.getEntitiesOfClass(Slime.class,
                player.getBoundingBox().inflate(32))) {
            if (slime.getY() < 40) {
                ChunkPos slimeChunk = slime.chunkPosition();
                if (capturedSlimes.add(slimeChunk)) {
                    send(player.createCommandSourceStack(),
                            "Auto-detected slime chunk at (" + slimeChunk.x() + ", " + slimeChunk.z() + ")!");
                }
            }
        }
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(MOD_ID)
                .then(Commands.literal("solve")
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();

                            if (capturedStructures.isEmpty()) {
                                send(src, "Need at least 1 detected structure! Walk around to load chunks.");
                                return 0;
                            }

                            double bits = calculateEntropyBits();
                            send(src, "Solving with " + capturedStructures.size() + " structure(s) ("
                                    + String.format("%.1f", bits) + " bits of entropy)...");

                            if (bits < 34) {
                                send(src, "WARNING: with fewer than ~4 structures Phase 1 can take HOURS.");
                                send(src, "Capture more structures for speed. /seedreverser cancel stops the solver.");
                            }

                            var server = src.getServer();

                            new Thread(() -> {
                                try {
                                    List<RegionStructure.Data<?>> captureList = new ArrayList<>(capturedStructures);
                                    List<Long> structureSeeds = StructureSeedSolver.findStructureSeeds(captureList);

                                    if (structureSeeds.isEmpty()) {
                                        boolean timedOut = StructureSeedSolver.lastRunTimedOut();
                                        server.execute(() -> {
                                            if (timedOut) {
                                                send(src, "Search timed out. Capture MORE structures (4+) in different regions.");
                                            } else {
                                                send(src, "No structure seeds found. Capture more structures.");
                                            }
                                        });
                                        return;
                                    }

                                    List<ChunkPos> slimeList = new ArrayList<>(capturedSlimes);

                                    if (slimeList.isEmpty()) {
                                        server.execute(() -> {
                                            send(src, "Found " + structureSeeds.size() + " structure seed candidate(s):");
                                            for (long s : structureSeeds) {
                                                send(src, "  Structure seed: " + s);
                                            }
                                            send(src, "Find underground slimes (y<40) to narrow to the exact world seed!");
                                        });
                                    } else {
                                        int found = 0;
                                        for (long structSeed : structureSeeds) {
                                            List<Long> worldSeeds = StructureSeedSolver.liftTo64Bit(structSeed, slimeList);
                                            for (long worldSeed : worldSeeds) {
                                                server.execute(() -> send(src, ">>> WORLD SEED: " + worldSeed + " <<<"));
                                                found++;
                                            }
                                        }
                                        if (found == 0) {
                                            server.execute(() -> send(src, "No 64-bit seed matched. Capture more slime chunks."));
                                        }
                                    }
                                } catch (Exception e) {
                                    server.execute(() -> send(src, "Solver error: " + e.getMessage()));
                                }
                            }, "SeedReverser-Solver").start();

                            return 1;
                        }))

                .then(Commands.literal("cancel")
                        .executes(ctx -> {
                            StructureSeedSolver.cancel();
                            send(ctx.getSource(), "Cancellation requested.");
                            return 1;
                        }))

                .then(Commands.literal("seed")
                        .executes(ctx -> {
                            Long seed = externalWorldSeed;
                            if (seed == null) {
                                send(ctx.getSource(), "No world seed received from SeedcrackerX yet.");
                            } else {
                                send(ctx.getSource(), ">>> WORLD SEED (from SeedcrackerX): " + seed + " <<<");
                            }
                            return 1;
                        }))

                .then(Commands.literal("status")
                        .executes(ctx -> {
                            Long ext = externalWorldSeed;
                            String extStr = (ext != null) ? " | External seed: YES" : "";
                            send(ctx.getSource(),
                                    "Structures: " + capturedStructures.size()
                                            + " | Slimes: " + capturedSlimes.size() + extStr);
                            return 1;
                        }))

                .then(Commands.literal("clear")
                        .executes(ctx -> {
                            capturedStructures.clear();
                            capturedSlimes.clear();
                            seenStructures.clear();
                            externalWorldSeed = null;
                            send(ctx.getSource(), "Cleared all data.");
                            return 1;
                        }))
        );
    }

    /**
     * Calculates total entropy bits from captured structures.
     */
    private static double calculateEntropyBits() {
        double bits = 0;
        for (RegionStructure.Data<?> d : capturedStructures) {
            String name = d.feature.getName();
            bits += switch (name) {
                case "minecraft:desert_pyramid", "minecraft:jungle_pyramid",
                     "minecraft:swamp_hut", "minecraft:igloo" -> 2 * Math.log(32) / Math.log(2);
                case "minecraft:village", "minecraft:pillager_outpost" -> 2 * Math.log(32) / Math.log(2);
                case "minecraft:ancient_city" -> 2 * Math.log(64) / Math.log(2);
                case "minecraft:woodland_mansion" -> 2 * Math.log(80) / Math.log(2);
                case "minecraft:trail_ruins" -> 2 * Math.log(32) / Math.log(2);
                case "minecraft:ocean_ruin", "minecraft:shipwreck" -> 2 * Math.log(16) / Math.log(2);
                case "minecraft:ruined_portal" -> 2 * Math.log(16) / Math.log(2);
                case "minecraft:bastion_remnant" -> 2 * Math.log(32) / Math.log(2);
                case "minecraft:nether_fortress" -> 2 * Math.log(16) / Math.log(2);
                case "minecraft:end_city" -> 2 * Math.log(20) / Math.log(2);
                default -> 0;
            };
        }
        return bits;
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