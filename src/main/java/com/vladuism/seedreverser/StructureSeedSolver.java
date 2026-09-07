package com.vladuism.seedreverser;

import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.Feature;
import com.seedfinding.mcfeature.structure.RegionStructure;
import com.seedfinding.mcfeature.structure.UniformStructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

    /**
     * Phase 1 — find 48-bit structure seeds from captured structure data.
     *
     * @param captures RegionStructure.Data for every captured structure,
     *                 built with structure.at(chunkX, chunkZ)
     */
    public static List<Long> findStructureSeeds(List<RegionStructure.Data<?>> captures) {
        List<Long> results = new ArrayList<>();
        if (captures.isEmpty()) return results;

        System.out.println("[SeedReverser] Lifting with " + captures.size() + " structure(s)...");

        // ------------------------------------------------------------------
        // Phase A — lower-bits (2^19) mod-4 filter
        // (adapted from SeedcrackerX TimeMachine.pokeLifting, MIT)
        // ------------------------------------------------------------------
        List<Long> survivingLowerBits = new ArrayList<>();

        for (long lowerBits = 0; lowerBits < (1L << 19); lowerBits++) {
            ChunkRand rand = new ChunkRand();
            boolean matches = true;

            for (RegionStructure.Data<?> data : captures) {
                rand.setRegionSeed(lowerBits, data.regionX, data.regionZ,
                        data.feature.getSalt(), VERSION);
                int offset = ((UniformStructure<?>) data.feature).getOffset();
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
        AtomicInteger done = new AtomicInteger();

        int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        // Split surviving lower values across threads
        List<List<Long>> partitions = new ArrayList<>();
        for (int i = 0; i < threads; i++) partitions.add(new ArrayList<>());
        for (int i = 0; i < survivingLowerBits.size(); i++) {
            partitions.get(i % threads).add(survivingLowerBits.get(i));
        }

        for (List<Long> partition : partitions) {
            if (partition.isEmpty()) continue;
            executor.submit(() -> {
                ChunkRand rand = new ChunkRand();
                for (long lowerBits : partition) {
                    for (long upperBits = 0; upperBits < (1L << 29); upperBits++) {
                        long structureSeed = (upperBits << 19) | lowerBits;

                        boolean matches = true;
                        for (Feature.Data<?> data : captures) {
                            if (!data.testStart(structureSeed, rand)) {
                                matches = false;
                                break;
                            }
                        }

                        if (matches) structureSeeds.add(structureSeed);
                    }
                }
                done.incrementAndGet();
            });
        }

        executor.shutdown();
        try {
            executor.awaitTermination(6, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        results.addAll(structureSeeds.stream().sorted().collect(Collectors.toList()));
        System.out.println("[SeedReverser] Found " + results.size() + " structure seed candidate(s)");
        return results;
    }

    // -----------------------------------------------------------------------
    // Phase 2 — lift 48-bit structure seed to 64-bit world seed via slime chunks
    // -----------------------------------------------------------------------

    public static List<Long> liftTo64Bit(long structureSeed, List<net.minecraft.world.level.ChunkPos> slimeChunks) {
        int numCores = Math.max(1, Runtime.getRuntime().availableProcessors());
        ExecutorService executor = Executors.newFixedThreadPool(numCores);
        ConcurrentHashMap<Long, Boolean> results = new ConcurrentHashMap<>();

        final long TOTAL_UPPER = 1L << 16;
        final long CHUNK_SIZE = Math.max(1, TOTAL_UPPER / numCores);

        for (int thread = 0; thread < numCores; thread++) {
            final long startUpper = thread * CHUNK_SIZE;
            final long endUpper = (thread == numCores - 1) ? TOTAL_UPPER : startUpper + CHUNK_SIZE;

            executor.submit(() -> {
                for (long upper = startUpper; upper < endUpper; upper++) {
                    long worldSeed = (upper << 48) | (structureSeed & MASK);
                    boolean allMatch = true;

                    for (net.minecraft.world.level.ChunkPos slime : slimeChunks) {
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

        return new ArrayList<>(results.keySet());
    }

    // Vanilla slime chunk formula (matches isSlimeChunk in Minecraft source)
    public static boolean isSlimeChunk(long worldSeed, int chunkX, int chunkZ) {
        long seed = worldSeed
                + (long) (chunkX * chunkX * 0x4c1906)
                + (long) (chunkX * 0x5ac0db)
                + (long) (chunkZ * chunkZ) * 0x4307a7L
                + (long) (chunkZ * 0x5f24f)
                ^ 0x3ad8025fL;

        java.util.Random rand = new java.util.Random(seed);
        return rand.nextInt(10) == 0;
    }
}
