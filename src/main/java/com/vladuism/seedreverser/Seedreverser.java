package com.vladuism.seedreverser;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuppressWarnings({"unused", "SpellCheckingInspection"})
public class Seedreverser implements ModInitializer {
    public static final String MOD_ID = "seedreverser";

    private static final Set<StructureInfo> capturedStructures = new HashSet<>();
    private static final Set<ChunkPos> capturedSlimes = new HashSet<>();

    public static class StructureInfo {
        public final int chunkX;
        public final int chunkZ;
        public final long salt;
        public final int regionSize;
        public final int spacing;
        public final int regionX;
        public final int regionZ;
        public final int offsetX;
        public final int offsetZ;

        public StructureInfo(int chunkX, int chunkZ, long salt, int regionSize, int spacing) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.salt = salt;
            this.regionSize = regionSize;
            this.spacing = spacing;
            this.regionX = Math.floorDiv(chunkX, regionSize);
            this.regionZ = Math.floorDiv(chunkZ, regionSize);
            this.offsetX = chunkX - regionX * regionSize;
            this.offsetZ = chunkZ - regionZ * regionSize;
        }

        public double getBits() {
            long offsetSq = (long) offsetX * offsetX + (long) offsetZ * offsetZ;
            return Math.log(offsetSq) / Math.log(2);
        }
    }

    public static final long TEMPLE_SALT = 14357617L;
    public static final int TEMPLE_REGION_SIZE = 32;
    public static final int TEMPLE_SPACING = 8;

    @Override
    public void onInitialize() {
        ServerChunkEvents.CHUNK_LOAD.register((ServerLevel world, LevelChunk chunk, boolean isNewChunk) -> {
            if (world.isClientSide()) return;

            ChunkPos cPos = chunk.getPos();

            for (StructureStart start : chunk.getAllStarts().values()) {
                if (!start.isValid()) continue;

                StructureInfo info = identifyStructure(start);
                if (info != null && capturedStructures.add(info)) {
                    broadcastToOps(world, "Auto-detected structure at chunk (" + cPos.x() + ", " + cPos.z() + ")!");
                }
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerLevel world : server.getAllLevels()) {
                for (ServerPlayer player : world.players()) {
                    ChunkPos cPos = player.chunkPosition();
                    
                    boolean hasSlime = false;
                    for (Slime slime : world.getEntitiesOfClass(Slime.class, player.getBoundingBox().inflate(16))) {
                        if (slime.getY() < 40) {
                            hasSlime = true;
                            break;
                        }
                    }

                    if (hasSlime && capturedSlimes.add(cPos)) {
                        send(player.createCommandSourceStack(), 
                            "Auto-detected slime chunk at (" + cPos.x() + ", " + cPos.z() + ")!");
                    }
                }
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            registerCommands(dispatcher)
        );
    }

    private static StructureInfo identifyStructure(StructureStart start) {
        // Identify structure by checking the registry - simplified approach
        // Uses chunk position directly since we know it's a temple structure
        int chunkX = start.getChunkPos().x();
        int chunkZ = start.getChunkPos().z();
        
        // All temple structures use the same salt for now
        // TODO: Differentiate by structure type for more accuracy
        return new StructureInfo(chunkX, chunkZ, TEMPLE_SALT, TEMPLE_REGION_SIZE, TEMPLE_SPACING);
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(MOD_ID)
            .then(Commands.literal("solve")
                .executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();

                    if (capturedStructures.isEmpty()) {
                        send(src, "Need at least 1 structure! Walk around.");
                        send(src, "Captured: " + capturedStructures.size());
                        return 0;
                    }

                    double totalBits = 0;
                    for (StructureInfo info : capturedStructures) {
                        totalBits += info.getBits();
                    }

                    send(src, "Solving with " + capturedStructures.size() + 
                        " structure(s) (" + String.format("%.1f", totalBits) + " bits)...");

                    new Thread(() -> {
                        try {
                            List<StructureInfo> structList = new ArrayList<>(capturedStructures);
                            List<Long> structureSeeds = StructureSeedSolver.findStructureSeeds(structList);

                            if (structureSeeds.isEmpty()) {
                                send(src, "No structure seeds found. Get more structures.");
                                return;
                            }

                            List<ChunkPos> slimeList = new ArrayList<>(capturedSlimes);

                            if (slimeList.isEmpty()) {
                                send(src, "Found " + structureSeeds.size() + " candidate structure seed(s):");
                                for (long s : structureSeeds) {
                                    send(src, "  Structure seed: " + s);
                                }
                                send(src, "Find slime chunks to narrow to world seed!");
                            } else {
                                int found = 0;
                                for (long structSeed : structureSeeds) {
                                    List<Long> worldSeeds = StructureSeedSolver.liftTo64Bit(structSeed, slimeList);
                                    for (long worldSeed : worldSeeds) {
                                        send(src, ">>> WORLD SEED: " + worldSeed + " <<<");
                                        found++;
                                    }
                                }
                                if (found == 0) {
                                    send(src, "No 64-bit seed matched. Capture more slimes.");
                                }
                            }
                        } catch (Exception e) {
                            send(src, "Solver error: " + e.getMessage());
                        }
                    }).start();

                    return 1;
                }))

            .then(Commands.literal("status")
                .executes(ctx -> {
                    send(ctx.getSource(), 
                        "Structures: " + capturedStructures.size() + 
                        " | Slimes: " + capturedSlimes.size());
                    return 1;
                }))

            .then(Commands.literal("clear")
                .executes(ctx -> {
                    capturedStructures.clear();
                    capturedSlimes.clear();
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
