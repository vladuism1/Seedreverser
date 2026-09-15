package com.vladuism.seedreverser;

import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.Feature;
import com.seedfinding.mcfeature.structure.RegionStructure;
import com.seedfinding.mcfeature.structure.UniformStructure;
import net.minecraft.world.level.ChunkPos;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

/**
 * Structure seed solver using the Seedfinding libraries (MIT).
 *
 * The lifting algorithm below is adapted from SeedcrackerX's TimeMachine.pokeLifting()
 * (https://github.com/19MisterX98/SeedcrackerX — MIT License, Copyright (c) 2020 KaptainWutax).
 * MIT requires this notice be preserved — do not remove the attribution above.
 *
 * Algorithm:
 *  Phase A: filter 2^19 "lower bits" candidates using the mod-4 constraint of
 *           region RNG offsets for each captured OldStructure (temple) position.
 *  Phase B: for each surviving lower-bits value, scan the 2^29 upper combos
 *           (structureSeed = upperBits << 19 | lowerBits) and verify every
 *           captured structure with Feature.Data.testStart().
 *
 * Compared to a blind 2^48 brute force, each captured temple cuts the Phase B
 * space by ~2^16, so a handful of structures makes this finish in seconds/minutes.
 */
public class StructureSeedSolver {

    private static final MCVersion VERSION = MCVersion.latest();

    // LCG constants (java.util.Random) — kept for the slime chunk formula
    private static final long MASK = (1L << 48) - 1;

    // Cooperative cancellation — checked in the hot loops (volatile for memory visibility)
    private static final AtomicBoolean cancelled = new AtomicBoolean(false);

    // True if the last run hit the 15-minute Phase B cap before scanning the
    // full candidate space (empty results then mean "not enough data", NOT
    // "no seed exists in the space").
    private static final AtomicBoolean lastRunTimedOut = new AtomicBoolean(false);

    // Reused executor pool (daemon, low priority) — created lazily
    private static final AtomicReference<ExecutorService> solverExecutor = new AtomicReference<>();

    public static void cancel() {
        cancelled.set(true);
        // Interrupt any running tasks
        ExecutorService exec = solverExecutor.getAndSet(null);
        if (exec != null) {
            exec.shutdownNow();
        }
    }

    public static boolean lastRunTimedOut() {
        return lastRunTimedOut.get();
    }

    /**
     * Gets or creates the solver executor pool.
     * Threads: cores - 2 (leave headroom for game), daemon, low priority.
     */
    private static ExecutorService getSolverExecutor() {
        return solverExecutor.updateAndGet(exec -> {
            if (exec != null && !exec.isShutdown()) return exec;
            int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
            return Executors.newFixedThreadPool(threads, r -> {
                Thread t = new Thread(r, "SeedReverser-Worker");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY + 1);
                return t;
            });
        });
    }

    /**
     * Shuts down the solver executor (called on mod unload or explicit cancel).
     */
    static void shutdownExecutor() {
        ExecutorService exec = solverExecutor.getAndSet(null);
        if (exec != null) {
            exec.shutdownNow();
        }
    }

    /**
     * Phase 1 — find 48-bit structure seeds from captured structure data.
     *
     * @param captures RegionStructure.Data for every captured structure,
     *                 built with structure.at(chunkX, chunkZ)
     */
    public static List<Long> findStructureSeeds(List<RegionStructure.Data<?>> captures) {
        List<Long> results = new ArrayList<>();
        if (captures.isEmpty()) return results;

        cancelled.set(false);
        lastRunTimedOut.set(false);
        System.out.println("[SeedReverser] Lifting with " + captures.size() + " structure(s)...");

        // ------------------------------------------------------------------
        // Phase A — lower-bits (2^19) mod-4 filter
        // (adapted from SeedcrackerX TimeMachine.pokeLifting, MIT)
        // ------------------------------------------------------------------
        List<Long> survivingLowerBits = new ArrayList<>();

        ChunkRand rand = new ChunkRand();
        for (long lowerBits = 0; lowerBits < (1L << 19); lowerBits++) {
            // Check cancellation every 1024 iterations (cheap volatile read)
            if ((lowerBits & 0x3FF) == 0 && cancelled.get()) {
                System.out.println("[SeedReverser] Cancelled during Phase A");
                return results;
            }

            boolean matches = true;

            for (RegionStructure.Data<?> data : captures) {
                rand.setRegionSeed(lowerBits, data.regionX, data.regionZ,
                        data.feature.getSalt(), VERSION);

                // Get offset — handle both UniformStructure and others
                int offset = getOffset(data.feature);
                if (offset <= 0) {
                    matches = false;
                    break;
                }

                // Two sequential nextInt calls mirror vanilla: X offset first, then Z
                if (rand.nextInt(offset) % 4 != data.offsetX % 4
                        || rand.nextInt(offset) % 4 != data.offsetZ % 4) {
                    matches = false;
                    break;
                }
            }

            if (matches) survivingLowerBits.add(lowerBits);
        }

        System.out.println("[SeedReverser] Phase A: " + survivingLowerBits.size()
                + " surviving lower-bits value(s)");

        if (survivingLowerBits.isEmpty()) return results;

        // ------------------------------------------------------------------
        // Phase B — scan upper bits for each surviving lower value
        // (adapted from SeedcrackerX TimeMachine.pokeLifting, MIT)
        // ------------------------------------------------------------------
        Set<Long> structureSeeds = ConcurrentHashMap.newKeySet();

        ExecutorService executor = getSolverExecutor();
        int workerCount = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);

        // Partition surviving lowerBits across workers
        List<List<Long>> partitions = new ArrayList<>(workerCount);
        for (int i = 0; i < workerCount; i++) partitions.add(new ArrayList<>());
        for (int i = 0; i < survivingLowerBits.size(); i++) {
            partitions.get(i % workerCount).add(survivingLowerBits.get(i));
        }

        CountDownLatch latch = new CountDownLatch(partitions.size());
        AtomicBoolean phaseBCancelled = new AtomicBoolean(false);

        for (List<Long> partition : partitions) {
            if (partition.isEmpty()) {
                latch.countDown();
                continue;
            }
            executor.submit(() -> {
                try {
                    ChunkRand verifyRand = new ChunkRand();
                    for (long lowerBits : partition) {
                        // Check cancel every 65536 iterations
                        long cancelCheckMask = 0xFFFFL;
                        for (long upperBits = 0; upperBits < (1L << 29); upperBits++) {
                            if ((upperBits & cancelCheckMask) == 0) {
                                if (cancelled.get() || phaseBCancelled.get()) {
                                    return;
                                }
                            }

                            long structureSeed = (upperBits << 19) | lowerBits;

                            boolean matches = true;
                            for (RegionStructure.Data<?> data : captures) {
                                if (!data.testStart(structureSeed, verifyRand)) {
                                    matches = false;
                                    break;
                                }
                            }

                            if (matches) structureSeeds.add(structureSeed);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed;
        try {
            completed = latch.await(15, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            completed = false;
        }

        if (!completed) {
            // We did NOT finish scanning the candidate space — empty results
            // from a timed-out run are inconclusive, not a proof of absence.
            lastRunTimedOut.set(true);
            phaseBCancelled.set(true);
            // Don't shutdown executor here — it's reused
        }

        if (cancelled.get()) {
            System.out.println("[SeedReverser] Solver cancelled — returning partial results");
            results.addAll(structureSeeds);
            return results;
        }

        // Sort for deterministic output
        results.addAll(structureSeeds.stream().sorted().collect(Collectors.toList()));
        System.out.println("[SeedReverser] Found " + results.size() + " structure seed candidate(s)");
        return results;
    }

    /**
     * Gets the region spacing offset for a feature.
     * Handles both UniformStructure and other RegionStructure types.
     */
    private static int getOffset(Feature<?> feature) {
        if (feature instanceof UniformStructure<?> us) {
            return us.getOffset();
        }
        // Fallback for non-UniformStructure features (e.g., some RuinedPortal configs)
        try {
            return (int) feature.getClass().getMethod("getOffset").invoke(feature);
        } catch (Exception e) {
            return -1;
        }
    }

    // -----------------------------------------------------------------------
    // Phase 2 — lift 48-bit structure seed to 64-bit world seed via slime chunks
    // -----------------------------------------------------------------------

    public static List<Long> liftTo64Bit(long structureSeed, List<ChunkPos> slimeChunks) {
        if (slimeChunks.isEmpty()) return List.of();

        ExecutorService executor = getSolverExecutor();
        Set<Long> results = ConcurrentHashMap.newKeySet();

        final long TOTAL_UPPER = 1L << 16; // 65536 upper bits combinations
        int numCores = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
        // Minimum chunk size of 256 to avoid task overhead
        final long CHUNK_SIZE = Math.max(256, TOTAL_UPPER / numCores);
        int numTasks = (int) ((TOTAL_UPPER + CHUNK_SIZE - 1) / CHUNK_SIZE);

        CountDownLatch latch = new CountDownLatch(numTasks);
        AtomicBoolean phase2Cancelled = new AtomicBoolean(false);

        for (int task = 0; task < numTasks; task++) {
            final long startUpper = task * CHUNK_SIZE;
            final long endUpper = Math.min(startUpper + CHUNK_SIZE, TOTAL_UPPER);

            executor.submit(() -> {
                try {
                    for (long upper = startUpper; upper < endUpper; upper++) {
                        // Check cancel every 4096 iterations
                        if ((upper & 0xFFF) == 0 && (cancelled.get() || phase2Cancelled.get())) {
                            return;
                        }

                        long worldSeed = (upper << 48) | (structureSeed & MASK);
                        boolean allMatch = true;

                        for (ChunkPos slime : slimeChunks) {
                            if (!isSlimeChunk(worldSeed, slime.x(), slime.z())) {
                                allMatch = false;
                                break;
                            }
                        }

                        if (allMatch) {
                            results.add(worldSeed);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            boolean completed = latch.await(10, TimeUnit.MINUTES);
            if (!completed) {
                phase2Cancelled.set(true);
                System.out.println("[SeedReverser] Phase 2 (slime lift) timed out after 10 minutes");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            phase2Cancelled.set(true);
        }

        return new ArrayList<>(results);
    }

    /**
     * Vanilla slime chunk formula — matches SeedcrackerX's ChunkRand-based implementation.
     * Formula: seed = worldSeed + chunkX^2 * 0x4c1906 + chunkX * 0x5ac0db + chunkZ^2 * 0x4307a7 + chunkZ * 0x5f24f ^ 0x3ad8025f
     * Then: new Random(seed).nextInt(10) == 0
     */
    public static boolean isSlimeChunk(long worldSeed, int chunkX, int chunkZ) {
        long seed = worldSeed
                + (long) chunkX * chunkX * 0x4c1906L
                + (long) chunkX * 0x5ac0dbL
                + (long) chunkZ * chunkZ * 0x4307a7L
                + (long) chunkZ * 0x5f24fL
                ^ 0x3ad8025fL;

        // Use java.util.Random to match vanilla exactly (SeedcrackerX does the same)
        java.util.Random rand = new java.util.Random(seed);
        return rand.nextInt(10) == 0;
    }

    // For testing/debugging: expose slime chunk formula using ChunkRand (alternative)
    static boolean isSlimeChunkChunkRand(long worldSeed, int chunkX, int chunkZ) {
        ChunkRand rand = new ChunkRand();
        rand.setSeed(worldSeed);
        rand.consume(chunkX);
        rand.consume(chunkZ);
        return rand.nextInt(10) == 0;
    }
}