package com.vladuism.seedreverser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class StructureSeedSolver {

    // LCG constants
    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long ADDEND     = 0xBL;
    private static final long MASK       = (1L << 48) - 1;

    // Accept StructureInfo from main mod
    public static List<Long> findStructureSeeds(List<Seedreverser.StructureInfo> structures) {
        if (structures.isEmpty()) return new ArrayList<>();
        
        List<Long> results = new ArrayList<>();
        Seedreverser.StructureInfo anchor = structures.get(0);
        int spread = anchor.regionSize - anchor.spacing;
        
        final long A = 341873128712L;
        final long B = 132897987541L;
        final long salt = anchor.salt;
        
        long anchorRegionOffset = (long) anchor.regionX * A + (long) anchor.regionZ * B + salt;
        
        int numCores = Math.max(1, Runtime.getRuntime().availableProcessors());
        ExecutorService executor = Executors.newFixedThreadPool(numCores);
        ConcurrentHashMap<Long, Boolean> resultsMap = new ConcurrentHashMap<>();
        
        long totalSeeds = 1L << 48;
        long chunkSize = totalSeeds / numCores;
        
        System.out.println("[SeedReverser] Brute forcing 2^48 seeds across " + numCores + " cores...");
        System.out.println("[SeedReverser] WARNING: This will take a LONG TIME. For fast solving, integrate mc_feature library.");
        
        for (int thread = 0; thread < numCores; thread++) {
            final long startSeed = thread * chunkSize;
            final long endSeed = (thread == numCores - 1) ? totalSeeds : startSeed + chunkSize;
            
            executor.submit(() -> {
                for (long worldSeed = startSeed; worldSeed < endSeed; worldSeed++) {
                    long regionSeed = (worldSeed + anchorRegionOffset) & MASK;
                    
                    long state = (regionSeed * MULTIPLIER + ADDEND) & MASK;
                    int offsetX = (int) (state >>> 17) % spread;
                    if (offsetX < 0) offsetX += spread;
                    if (offsetX != anchor.offsetX) continue;
                    
                    state = (state * MULTIPLIER + ADDEND) & MASK;
                    int offsetZ = (int) (state >>> 17) % spread;
                    if (offsetZ < 0) offsetZ += spread;
                    if (offsetZ != anchor.offsetZ) continue;
                    
                    boolean valid = true;
                    for (int i = 1; i < structures.size(); i++) {
                        Seedreverser.StructureInfo s = structures.get(i);
                        long sRegionSeed = (worldSeed + (long) s.regionX * A + (long) s.regionZ * B + s.salt) & MASK;
                        long sState = (sRegionSeed * MULTIPLIER + ADDEND) & MASK;
                        int sOffsetX = (int) (sState >>> 17) % spread;
                        if (sOffsetX < 0) sOffsetX += spread;
                        if (s.regionX * s.regionSize + sOffsetX != s.chunkX) {
                            valid = false;
                            break;
                        }
                        
                        sState = (sState * MULTIPLIER + ADDEND) & MASK;
                        int sOffsetZ = (int) (sState >>> 17) % spread;
                        if (sOffsetZ < 0) sOffsetZ += spread;
                        if (s.regionZ * s.regionSize + sOffsetZ != s.chunkZ) {
                            valid = false;
                            break;
                        }
                    }
                    
                    if (valid) {
                        resultsMap.put(worldSeed, Boolean.TRUE);
                    }
                }
            });
        }
        
        executor.shutdown();
        try {
            executor.awaitTermination(2, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        results.addAll(resultsMap.keySet());
        System.out.println("[SeedReverser] Found " + results.size() + " candidates");
        return results;
    }

    public static List<Long> liftTo64Bit(long structureSeed, List<net.minecraft.world.level.ChunkPos> slimeChunks) {
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
