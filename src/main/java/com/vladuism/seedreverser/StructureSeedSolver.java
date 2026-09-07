package com.vladuism.seedreverser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class StructureSeedSolver {

    // -----------------------------------------------------------------------
    // Public config records
    // -----------------------------------------------------------------------

    public record ChunkPos(int x, int z) {}

    public record StructureConfig(long salt, int regionSize, int spacing) {}

    public static final StructureConfig TEMPLE_CONFIG  = new StructureConfig(14357617L, 32, 8);
    public static final StructureConfig VILLAGE_CONFIG = new StructureConfig(10387312L, 32, 8);
    public static final StructureConfig OUTPOST_CONFIG = new StructureConfig(165745296L, 32, 8);

    // LCG constants matching java.util.Random
    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long ADDEND     = 0xBL;
    private static final long MASK       = (1L << 48) - 1;

    // -----------------------------------------------------------------------
    // Phase 1 — 48-bit structure seed brute force (multi-threaded)
    // -----------------------------------------------------------------------

    public static List<Long> findStructureSeeds(StructureConfig config, List<ChunkPos> positions) {
        // Use parallel processing across available CPU cores
        int numCores = Math.max(1, Runtime.getRuntime().availableProcessors());
        ExecutorService executor = Executors.newFixedThreadPool(numCores);
        ConcurrentHashMap<Long, Boolean> results = new ConcurrentHashMap<>();

        ChunkPos anchor = positions.get(0);
        int regionX = Math.floorDiv(anchor.x(), config.regionSize());
        int regionZ = Math.floorDiv(anchor.z(), config.regionSize());

        int targetOffsetX = anchor.x() - regionX * config.regionSize();
        int targetOffsetZ = anchor.z() - regionZ * config.regionSize();
        int spread = config.regionSize() - config.spacing();

        final long A = 341873128712L;
        final long B = 132897987541L;
        final long TOTAL_LOWER = 1L << 24;
        final long CHUNK_SIZE = TOTAL_LOWER / numCores;

        // Pre-compute common values for speed
        final long regionX_A = (long) regionX * A;
        final long regionZ_B = (long) regionZ * B;
        final long baseRegionOffset = regionX_A + regionZ_B + config.salt();

        // Split work across threads
        for (int thread = 0; thread < numCores; thread++) {
            final long startLower = thread * CHUNK_SIZE;
            final long endLower = (thread == numCores - 1) ? TOTAL_LOWER : startLower + CHUNK_SIZE;

            executor.submit(() -> {
                for (long lower = startLower; lower < endLower; lower++) {
                    for (long upper = 0; upper < (1L << 24); upper++) {
                        long worldSeed = (upper << 24) | lower;
                        long regionSeed = (worldSeed + baseRegionOffset) & MASK;

                        long state = (regionSeed * MULTIPLIER + ADDEND) & MASK;
                        int offsetX = (int) (state >>> 17) % spread;
                        if (offsetX < 0) offsetX += spread;

                        if (offsetX != targetOffsetX) continue;

                        state = (state * MULTIPLIER + ADDEND) & MASK;
                        int offsetZ = (int) (state >>> 17) % spread;
                        if (offsetZ < 0) offsetZ += spread;

                        if (offsetZ != targetOffsetZ) continue;

                        // Verify against all other positions
                        boolean valid = true;
                        for (int i = 1; i < positions.size(); i++) {
                            ChunkPos pos = positions.get(i);
                            int rX = Math.floorDiv(pos.x(), config.regionSize());
                            int rZ = Math.floorDiv(pos.z(), config.regionSize());

                            long posRegionSeed = (worldSeed + (long) rX * A + (long) rZ * B + config.salt()) & MASK;
                            long posState = (posRegionSeed * MULTIPLIER + ADDEND) & MASK;
                            int offX = (int) (posState >>> 17) % spread;
                            if (offX < 0) offX += spread;
                            if (rX * config.regionSize() + offX != pos.x()) {
                                valid = false;
                                break;
                            }

                            posState = (posState * MULTIPLIER + ADDEND) & MASK;
                            int offZ = (int) (posState >>> 17) % spread;
                            if (offZ < 0) offZ += spread;
                            if (rZ * config.regionSize() + offZ != pos.z()) {
                                valid = false;
                                break;
                            }
                        }

                        if (valid) {
                            results.put(worldSeed, true);
                        }
                    }
                }
            });
        }

        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        List<Long> resultList = new ArrayList<>(results.keySet());
        return resultList;
    }

    // -----------------------------------------------------------------------
    // Phase 2 — lift 48-bit → 64-bit via slime chunks (multi-threaded)
    // -----------------------------------------------------------------------

    public static List<Long> liftTo64Bit(long structureSeed, List<ChunkPos> slimeChunks) {
        int numCores = Math.max(1, Runtime.getRuntime().availableProcessors());
        ExecutorService executor = Executors.newFixedThreadPool(numCores);
        ConcurrentHashMap<Long, Boolean> results = new ConcurrentHashMap<>();

        final long TOTAL_UPPER = 1L << 16;
        final long CHUNK_SIZE = TOTAL_UPPER / numCores;

        for (int thread = 0; thread < numCores; thread++) {
            final long startUpper = thread * CHUNK_SIZE;
            final long endUpper = (thread == numCores - 1) ? TOTAL_UPPER : startUpper + CHUNK_SIZE;

            executor.submit(() -> {
                for (long upper = startUpper; upper < endUpper; upper++) {
                    long worldSeed = (upper << 48) | structureSeed;
                    boolean allMatch = true;

                    for (ChunkPos slime : slimeChunks) {
                        if (!isSlimeChunk(worldSeed, slime.x(), slime.z())) {
                            allMatch = false;
                            break;
                        }
                    }

                    if (allMatch) {
                        results.put(worldSeed, true);
                    }
                }
            });
        }

        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        List<Long> resultList = new ArrayList<>(results.keySet());
        return resultList;
    }

    public static boolean isSlimeChunk(long worldSeed, int chunkX, int chunkZ) {
        long seed = worldSeed
                + (long)(chunkX * chunkX * 0x4c1906)
                + (long)(chunkX * 0x5ac0db)
                + (long)(chunkZ * chunkZ) * 0x4307a7L
                + (long)(chunkZ * 0x5f24f)
                ^ 0x3ad8025fL;

        java.util.Random rand = new java.util.Random(seed);
        return rand.nextInt(10) == 0;
    }
}
