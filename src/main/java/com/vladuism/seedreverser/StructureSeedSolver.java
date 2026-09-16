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
import java.util.stream.Collectors;

public class StructureSeedSolver {

    private static final MCVersion VERSION = MCVersion.latest();
    private static final long MASK = (1L << 48) - 1;

    // Use AtomicBoolean for thread-safe cancellation
    private static final AtomicBoolean cancelled = new AtomicBoolean(false);
    private static final AtomicBoolean lastRunTimedOut = new AtomicBoolean(false);

    // Reused executor pool
    private static final AtomicReference<ExecutorService> solverExecutor = new AtomicReference<>();

    public static void cancel() {
        cancelled.set(true);
        ExecutorService exec = solverExecutor.getAndSet(null);
        if (exec != null) {
            exec.shutdownNow();
        }
    }

    public static boolean lastRunTimedOut() {
        return lastRunTimedOut.get();
    }

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
     * Phase 1 — find 48-bit structure seeds from captured structure data.
     */
    public static List<Long> findStructureSeeds(List<RegionStructure.Data<?>> captures) {
        List<Long> results = new ArrayList<>();
        if (captures.isEmpty()) return results;

        cancelled.set(false);
        lastRunTimedOut.set(false);

        // Phase A — lower-bits (2^19) mod-4 filter
        List<Long> survivingLowerBits = new ArrayList<>();

        ChunkRand rand = new ChunkRand();
        for (long lowerBits = 0; lowerBits < (1L << 19); lowerBits++) {
            // Check cancellation every 1024 iterations
            if ((lowerBits & 0x3FF) == 0 && cancelled.get()) {
                return results;
            }

            boolean matches = true;
            for (RegionStructure.Data<?> data : captures) {
                rand.setRegionSeed(lowerBits, data.regionX, data.regionZ,
                        data.feature.getSalt(), VERSION);

                // Safely get offset — handle both UniformStructure and other types
                int offset;
                if (data.feature instanceof UniformStructure<?> us) {
                    offset = us.getOffset();
                } else {
                    offset = 16; // default safe offset
                }

                if (offset <= 0) {
                    matches = false;
                    break;
                }

                if (rand.nextInt(offset) % 4 != data.offsetX % 4
                        || rand.nextInt(offset) % 4 != data.offsetZ % 4) {
                    matches = false;
                    break;
                }
            }

            if (matches) survivingLowerBits.add(lowerBits);
        }

        if (survivingLowerBits.isEmpty()) return results;

        // Phase B — scan upper bits with proper cancellation and testStart verification
        Set<Long> structureSeeds = ConcurrentHashMap.newKeySet();
        ExecutorService executor = getSolverExecutor();
        int workerCount = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);

        // Partition surviving lowerBits across workers for balanced work distribution
        List<List<Long>> partitions = new ArrayList<>(workerCount);
        for (int i = 0; i < workerCount; i++) partitions.add(new ArrayList<>());
        for (int i = 0; i < survivingLowerBits.size(); i++) {
            partitions.get(i % workerCount).add(survivingLowerBits.get(i));
        }

        // Use CountDownLatch for proper task completion tracking
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
                        // Check cancellation every 1024 outer iterations
                        if (cancelled.get()) {
                            phaseBCancelled.set(true);
                            return;
                        }

                        // Phase B inner loop: scan all 2^29 upper bits combinations
                        // structureSeed = upperBits << 19 | lowerBits
                        for (long upperBits = 0; upperBits < (1L << 29); upperBits++) {
                            // Check cancellation every 65536 inner iterations
                            if ((upperBits & 0xFFFFL) == 0 && cancelled.get()) {
                                phaseBCancelled.set(true);
                                return;
                            }

                            long structureSeed = (upperBits << 19) | lowerBits;

                            // Verify every captured structure with testStart
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
                } catch (Exception e) {
                    phaseBCancelled.set(true);
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
            lastRunTimedOut.set(true);
            phaseBCancelled.set(true);
        }

        if (cancelled.get() || phaseBCancelled.get()) {
            results.addAll(structureSeeds);
            return results;
        }

        // Sort for deterministic output
        results.addAll(structureSeeds.stream().sorted().collect(Collectors.toList()));
        return results;
    }

    /**
     * Phase 2 — lift 48-bit structure seed to 64-bit world seed via slime chunks.
     */
    public static List<Long> liftTo64Bit(long structureSeed, List<ChunkPos> slimeChunks) {
        if (slimeChunks.isEmpty()) return List.of();

        ExecutorService executor = getSolverExecutor();
        Set<Long> results = ConcurrentHashMap.newKeySet();

        final long TOTAL_UPPER = 1L << 16; // 65536
        int numCores = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
        // Minimum chunk size of 256 to avoid task overhead
        final long CHUNK_SIZE = Math.max(256, TOTAL_UPPER / numCores);
        int numTasks = (int) ((TOTAL_UPPER + CHUNK_SIZE - 1) / CHUNK_SIZE);

        // Use CountDownLatch for proper completion tracking
        CountDownLatch latch = new CountDownLatch(numTasks);

        for (int task = 0; task < numTasks; task++) {
            final long startUpper = task * CHUNK_SIZE;
            final long endUpper = Math.min(startUpper + CHUNK_SIZE, TOTAL_UPPER);

            executor.submit(() -> {
                try {
                    for (long upper = startUpper; upper < endUpper; upper++) {
                        // Check cancellation every 4096 iterations
                        if ((upper & 0xFFF) == 0 && cancelled.get()) {
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
                lastRunTimedOut.set(true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastRunTimedOut.set(true);
        }

        return new ArrayList<>(results);
    }

    /**
     * Vanilla slime chunk formula — matches SeedcrackerX implementation.
     */
    public static boolean isSlimeChunk(long worldSeed, int chunkX, int chunkZ) {
        long seed = worldSeed
                + (long) chunkX * chunkX * 0x4c1906L
                + (long) chunkX * 0x5ac0dbL
                + (long) chunkZ * chunkZ * 0x4307a7L
                + (long) chunkZ * 0x5f24fL
                ^ 0x3ad8025fL;

        java.util.Random rand = new java.util.Random(seed);
        return rand.nextInt(10) == 0;
    }
}