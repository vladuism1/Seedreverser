package com.vladuism.seedreverser;

import com.mojang.brigadier.CommandDispatcher;
import com.seedfinding.mccore.state.Dimension;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.Feature;
import com.seedfinding.mcfeature.structure.*;
import com.seedfinding.mcfeature.structure.AncientCity;
import com.seedfinding.mcfeature.structure.BastionRemnant;
import com.seedfinding.mcfeature.structure.EndCity;
import com.seedfinding.mcfeature.structure.NetherFortress;
import com.seedfinding.mcfeature.structure.PillagerOutpost;
import com.seedfinding.mcfeature.structure.TrailRuins;
import com.seedfinding.mcfeature.structure.WoodlandMansion;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Seedreverser — passive structure/slime collector + seed solver.
 * Complements SeedcrackerX: this mod detects structures/slimes automatically,
 * solves for the seed, and receives cracked seeds from SeedcrackerX via API.
 */
@SuppressWarnings({"unused", "SpellCheckingInspection"})
public class Seedreverser implements ModInitializer {
    public static final String MOD_ID = "seedreverser";

    // Seedfinding version (26.2 = MC 1.21.4)
    private static final MCVersion SF_VERSION = MCVersion.latest();

    // Structure instances — lazily initialized for dimension-specific ones
    private static final DesertPyramid DESERT_PYRAMID = new DesertPyramid(SF_VERSION);
    private static final JunglePyramid JUNGLE_PYRAMID = new JunglePyramid(SF_VERSION);
    private static final SwampHut SWAMP_HUT = new SwampHut(SF_VERSION);
    private static final Igloo IGLOO = new Igloo(SF_VERSION);
    private static final OceanRuin OCEAN_RUIN = new OceanRuin(SF_VERSION);
    private static final Shipwreck SHIPWRECK = new Shipwreck(SF_VERSION);
    private static final Village VILLAGE = new Village(SF_VERSION);
    private static final PillagerOutpost PILLAGER_OUTPOST = new PillagerOutpost(SF_VERSION);
    private static final AncientCity ANCIENT_CITY = new AncientCity(SF_VERSION);
    private static final WoodlandMansion WOODLAND_MANSION = new WoodlandMansion(SF_VERSION);
    private static final TrailRuins TRAIL_RUINS = new TrailRuins(SF_VERSION);
    // TrialChambers not in seedfinding yet — add when available

    // Thread-safe capture storage
    // Using CopyOnWriteArrayList for snapshot isolation during solve
    private static final List<RegionStructure.Data<?>> capturedStructures = new CopyOnWriteArrayList<>();
    private static final List<ChunkPos> capturedSlimes = new CopyOnWriteArrayList<>();
    private static final Set<String> seenStructures = ConcurrentHashMap.newKeySet();

    // World seed received from SeedcrackerX (via API)
    private static volatile Long externalWorldSeed = null;

    // Cached structure registry mapping for O(1) identification
    private static volatile Map<String, Function<ServerLevel, UniformStructure<?>>> structureMapper = Map.of();

    public static void receiveExternalSeed(long seed) {
        externalWorldSeed = seed;
    }

    @Override
    public void onInitialize() {
        // Build structure mapper once (after registries are available)
        // Deferred to first chunk load since registries aren't ready in onInitialize
        ServerChunkEvents.CHUNK_LOAD.register((ServerLevel world, LevelChunk chunk, boolean isNewChunk) -> {
            if (structureMapper.isEmpty()) {
                buildStructureMapper(world);
            }
            if (world.dimension() != Level.OVERWORLD) return;

            ChunkPos cPos = chunk.getPos();

            for (StructureStart start : chunk.getAllStarts().values()) {
                if (!start.isValid()) continue;

                ChunkPos origin = start.getChunkPos();
                UniformStructure<?> structure = identifyStructure(world, start);
                if (structure == null) continue;

                RegionStructure.Data<?> data = structure.at(origin.x(), origin.z());
                if (data == null) continue;

                String dedupeKey = data.feature.getRegistryName() + ":" + origin.x() + ":" + origin.z();
                if (seenStructures.add(dedupeKey)) {
                    capturedStructures.add(data);
                    broadcastToOps(world, "Auto-detected " + data.feature.getRegistryName()
                            + " at chunk (" + origin.x() + ", " + origin.z() + ")!");
                }
            }
        });

        // Passive slime chunk detection — runs every 20 ticks (1 second) to reduce overhead
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 != 0) return; // once per second
            for (ServerLevel world : server.getAllLevels()) {
                if (world.dimension() != Level.OVERWORLD) continue;
                for (ServerPlayer player : world.players()) {
                    detectSlimeChunksAroundPlayer(world, player);
                }
            }
        });

        // Commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                registerCommands(dispatcher)
        );
    }

    /**
     * Builds a mapping from structure registry ID to UniformStructure factory.
     * Called once on first chunk load when registries are available.
     */
    private static void buildStructureMapper(ServerLevel world) {
        Registry<Structure> registry = world.registryAccess().lookup(Registries.STRUCTURE).orElse(null);
        if (registry == null) return;

        Map<String, Function<ServerLevel, UniformStructure<?>>> mapper = new HashMap<>();

        // Overworld UniformStructures (safe for Phase A + Phase B)
        mapper.put("minecraft:desert_pyramid", w -> DESERT_PYRAMID);
        mapper.put("minecraft:jungle_pyramid", w -> JUNGLE_PYRAMID);
        mapper.put("minecraft:swamp_hut", w -> SWAMP_HUT);
        mapper.put("minecraft:igloo", w -> IGLOO);
        mapper.put("minecraft:ocean_ruin", w -> OCEAN_RUIN);
        mapper.put("minecraft:shipwreck", w -> SHIPWRECK);
        mapper.put("minecraft:village", w -> VILLAGE);
        mapper.put("minecraft:pillager_outpost", w -> PILLAGER_OUTPOST);
        mapper.put("minecraft:ancient_city", w -> ANCIENT_CITY);
        mapper.put("minecraft:woodland_mansion", w -> WOODLAND_MANSION);
        mapper.put("minecraft:trail_ruins", w -> TRAIL_RUINS);

        // Dimension-specific structures
        mapper.put("minecraft:ruined_portal", w -> {
            Dimension sfDim = Dimension.fromString(w.dimension().location().toString());
            if (sfDim == null) sfDim = Dimension.OVERWORLD;
            return new RuinedPortal(sfDim, SF_VERSION);
        });
        // Nether-only
        mapper.put("minecraft:bastion_remnant", w -> new BastionRemnant(SF_VERSION));
        mapper.put("minecraft:nether_fortress", w -> new NetherFortress(SF_VERSION));
        // End-only
        mapper.put("minecraft:end_city", w -> new EndCity(SF_VERSION));

        structureMapper = Map.copyOf(mapper);
    }

    /**
     * Returns the Seedfinding UniformStructure for a Minecraft structure start,
     * or null if the structure isn't one we support.
     */
    private static UniformStructure<?> identifyStructure(ServerLevel world, StructureStart start) {
        try {
            Registry<Structure> registry = world.registryAccess().lookup(Registries.STRUCTURE).orElse(null);
            if (registry == null) return null;

            ResourceKey<Structure> key = registry.getResourceKey(start.getStructure()).orElse(null);
            if (key == null) return null;

            Function<ServerLevel, UniformStructure<?>> factory = structureMapper.get(key.location().toString());
            return factory != null ? factory.apply(world) : null;
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Detects slime chunks around a player (once per second per player).
     * Records the slime's chunk position, not the player's.
     */
    private static void detectSlimeChunksAroundPlayer(ServerLevel world, ServerPlayer player) {
        ChunkPos playerChunk = player.chunkPosition();
        // Search radius: 2 chunks (32 blocks) around player
        for (Slime slime : world.getEntitiesOfClass(Slime.class,
                player.getBoundingBox().inflate(32))) {
            if (slime.getY() < 40) {
                ChunkPos slimeChunk = slime.chunkPosition();
                if (capturedSlimes.add(slimeChunk)) { // add returns true if new
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
                                send(src, "WARNING: with fewer than ~4 structures (34+ bits) Phase 1 can take HOURS.");
                                send(src, "Capture more structures for speed. /seedreverser cancel stops the solver.");
                            }

                            var server = src.getServer();

                            new Thread(() -> {
                                try {
                                    // Atomic snapshot — CopyOnWriteArrayList gives us this for free
                                    List<RegionStructure.Data<?>> captureList = new ArrayList<>(capturedStructures);
                                    List<Long> structureSeeds = StructureSeedSolver.findStructureSeeds(captureList);

                                    if (structureSeeds.isEmpty()) {
                                        boolean timedOut = StructureSeedSolver.lastRunTimedOut();
                                        server.execute(() -> {
                                            if (timedOut) {
                                                send(src, "Search timed out after 15 min before scanning the full space.");
                                                send(src, "Capture MORE structures (4+) in different regions — each one speeds this up massively.");
                                            } else {
                                                send(src, "No structure seeds found. Capture more structures in different regions.");
                                            }
                                        });
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
                                    e.printStackTrace();
                                }
                            }, "SeedReverser-Solver-" + System.currentTimeMillis()).start();

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

    /**
     * Calculates total entropy bits from captured structures.
     * Uses known entropy per structure type (log2 of region spacing^2).
     */
    private static double calculateEntropyBits() {
        double bits = 0;
        for (RegionStructure.Data<?> d : capturedStructures) {
            String name = d.feature.getRegistryName();
            // Entropy = 2 * log2(spacing) where spacing = region size in chunks
            // Known values from seedfinding library
            bits += switch (name) {
                case "minecraft:desert_pyramid", "minecraft:jungle_pyramid",
                     "minecraft:swamp_hut", "minecraft:igloo" -> 2 * Math.log(32) / Math.log(2); // spacing 32
                case "minecraft:village" -> 2 * Math.log(32) / Math.log(2); // spacing 32
                case "minecraft:pillager_outpost" -> 2 * Math.log(32) / Math.log(2);
                case "minecraft:ancient_city" -> 2 * Math.log(64) / Math.log(2); // spacing 64
                case "minecraft:woodland_mansion" -> 2 * Math.log(80) / Math.log(2); // spacing 80
                case "minecraft:trail_ruins" -> 2 * Math.log(32) / Math.log(2);
                case "minecraft:ocean_ruin" -> 2 * Math.log(16) / Math.log(2); // spacing 16
                case "minecraft:shipwreck" -> 2 * Math.log(16) / Math.log(2);
                case "minecraft:ruined_portal" -> 2 * Math.log(16) / Math.log(2); // varies by dimension
                case "minecraft:bastion_remnant" -> 2 * Math.log(32) / Math.log(2);
                case "minecraft:nether_fortress" -> 2 * Math.log(16) / Math.log(2); // approx
                case "minecraft:end_city" -> 2 * Math.log(20) / Math.log(2); // approx
                default -> 0;
            };
        }
        return bits;
    }

    private static void broadcastToOps(ServerLevel level, String msg) {
        for (ServerPlayer player : level.players()) {
            if (player != null && player.permissions() != null
                    && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
                send(player.createCommandSourceStack(), msg);
            }
        }
    }

    private static void send(CommandSourceStack src, String msg) {
        src.sendSystemMessage(Component.literal("[SeedReverser] " + msg));
    }
}