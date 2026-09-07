package com.vladuism.seedreverser;

import com.mojang.brigadier.CommandDispatcher;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.Feature;
import com.seedfinding.mcfeature.structure.DesertPyramid;
import com.seedfinding.mcfeature.structure.Igloo;
import com.seedfinding.mcfeature.structure.JunglePyramid;
import com.seedfinding.mcfeature.structure.OldStructure;
import com.seedfinding.mcfeature.structure.RegionStructure;
import com.seedfinding.mcfeature.structure.SwampHut;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings({"unused", "SpellCheckingInspection"})
public class Seedreverser implements ModInitializer {
    public static final String MOD_ID = "seedreverser";

    // mc_feature structure instances (MIT seedfinding libs).
    // MCVersion.latest() is what SeedcrackerX uses for current versions —
    // the region structure math is unchanged in 26.2.
    private static final MCVersion SF_VERSION = MCVersion.latest();
    private static final DesertPyramid DESERT_PYRAMID = new DesertPyramid(SF_VERSION);
    private static final JunglePyramid JUNGLE_PYRAMID = new JunglePyramid(SF_VERSION);
    private static final SwampHut SWAMP_HUT = new SwampHut(SF_VERSION);
    private static final Igloo IGLOO = new Igloo(SF_VERSION);

    // Concurrent sets: mutated on the server thread, read on solver threads
    private static final Set<RegionStructure.Data<?>> capturedStructures = ConcurrentHashMap.newKeySet();
    private static final Set<ChunkPos> capturedSlimes = ConcurrentHashMap.newKeySet();

    // Explicit dedupe keys (feature:originChunk) — Data may use identity equals,
    // and the same structure start is reported by every chunk it covers.
    private static final Set<String> seenStructures = ConcurrentHashMap.newKeySet();

    // World seed received from SeedcrackerX (via API)
    private static volatile Long externalWorldSeed = null;

    public static void receiveExternalSeed(long seed) {
        externalWorldSeed = seed;
    }

    @Override
    public void onInitialize() {
        // 1. Passive structure detection on chunk load
        ServerChunkEvents.CHUNK_LOAD.register((ServerLevel world, LevelChunk chunk, boolean isNewChunk) -> {
            if (world.isClientSide()) return;

            ChunkPos cPos = chunk.getPos();

            for (StructureStart start : chunk.getAllStarts().values()) {
                if (!start.isValid()) continue;

                // IMPORTANT: use the StructureStart's ORIGIN chunk, not the loading
                // chunk — big structures are referenced by many chunks, and using the
                // wrong chunk would compute wrong region/offset data.
                ChunkPos origin = start.getChunkPos();

                RegionStructure.Data<?> data = identifyStructure(world, start, origin);
                if (data == null) continue;

                String dedupeKey = data.feature.getName() + ":" + origin.x() + ":" + origin.z();
                if (seenStructures.add(dedupeKey)) {
                    capturedStructures.add(data);
                    broadcastToOps(world, "Auto-detected " + data.feature.getName()
                            + " at chunk (" + origin.x() + ", " + origin.z() + ")!");
                }
            }
        });

        // 2. Passive slime chunk detection (slimes spawn below y=40 in slime chunks)
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerLevel world : server.getAllLevels()) {
                for (ServerPlayer player : world.players()) {
                    ChunkPos cPos = player.chunkPosition();

                boolean hasSlime = false;
                ChunkPos slimeChunk = null;
                for (Slime slime : world.getEntitiesOfClass(Slime.class,
                        player.getBoundingBox().inflate(16))) {
                    if (slime.getY() < 40) {
                        hasSlime = true;
                        // Record the SLIME's chunk, not the player's — inflate(16)
                        // can reach slimes in neighbouring chunks.
                        slimeChunk = slime.chunkPosition();
                        break;
                    }
                }

                if (hasSlime && slimeChunk != null && capturedSlimes.add(slimeChunk)) {
                    send(player.createCommandSourceStack(),
                            "Auto-detected slime chunk at (" + slimeChunk.x() + ", " + slimeChunk.z() + ")!");
                }
                }
            }
        });

        // 3. Commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                registerCommands(dispatcher)
        );
    }

    @SuppressWarnings("unchecked")
    private static RegionStructure.Data<?> identifyStructure(ServerLevel world, StructureStart start, ChunkPos origin) {
        try {
            // O(1) reverse lookup (Registry.getResourceKey) instead of iterating
            // the whole structure registry on every chunk load
            net.minecraft.core.Registry<net.minecraft.world.level.levelgen.structure.Structure> registry =
                    world.registryAccess().lookup(Registries.STRUCTURE).orElse(null);
            if (registry == null) return null;

            ResourceKey<net.minecraft.world.level.levelgen.structure.Structure> key =
                    registry.getResourceKey(start.getStructure()).orElse(null);
            if (key == null) return null;

            OldStructure<?> structure = mapToMcFeatureStructure(key.identifier().toString());
            if (structure == null) return null;

            // RegionStructure.at(chunkX, chunkZ) computes region coords + offsets
            return (RegionStructure.Data<?>) structure.at(origin.x(), origin.z());
        } catch (Exception ignored) {
        }
        return null;
    }

    private static OldStructure<?> mapToMcFeatureStructure(String structureId) {
        if (structureId.contains("desert_pyramid")) return DESERT_PYRAMID;
        if (structureId.contains("jungle_pyramid")) return JUNGLE_PYRAMID;
        if (structureId.contains("swamp_hut")) return SWAMP_HUT;
        if (structureId.contains("igloo")) return IGLOO;
        return null;
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

                            double bits = 0;
                            for (RegionStructure.Data<?> d : capturedStructures) {
                                int offset = ((com.seedfinding.mcfeature.structure.UniformStructure<?>) d.feature).getOffset();
                                bits += Math.log((double) offset * offset) / Math.log(2);
                            }

                            send(src, "Solving with " + capturedStructures.size() + " structure(s) ("
                                    + String.format("%.1f", bits) + " bits)...");

                            if (bits < 34) {
                                send(src, "WARNING: with fewer than ~4 structures (34+ bits) Phase 1 can take HOURS.");
                                send(src, "Capture more structures for speed. /seedreverser cancel stops the solver.");
                            }

                            // All messages from the solver thread must hop onto the
                            // server thread — touching chat/server state off-thread is unsafe.
                            var server = src.getServer();

                            new Thread(() -> {
                                try {
                                    List<RegionStructure.Data<?>> captureList = new ArrayList<>(capturedStructures);
                                    List<Long> structureSeeds = StructureSeedSolver.findStructureSeeds(captureList);

                                    if (structureSeeds.isEmpty()) {
                                        server.execute(() -> send(src,
                                                "No structure seeds found. Capture more structures in different regions."));
                                        return;
                                    }

                                    List<ChunkPos> slimeList = new ArrayList<>(capturedSlimes);

                                    if (slimeList.isEmpty()) {
                                        List<Long> finalSeeds = structureSeeds;
                                        server.execute(() -> {
                                            send(src, "Found " + finalSeeds.size() + " structure seed candidate(s):");
                                            for (long s : finalSeeds) {
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
                            send(ctx.getSource(), "Cancellation requested — solver will stop within a few seconds.");
                            return 1;
                        }))

                .then(Commands.literal("seed")
                        .executes(ctx -> {
                            Long seed = externalWorldSeed;
                            if (seed == null) {
                                send(ctx.getSource(), "No world seed received from SeedcrackerX yet.");
                                send(ctx.getSource(), "Install SeedcrackerX and let it crack the seed.");
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
