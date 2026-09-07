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
    
    // 3D position with Y (height) for multi-dimensional support
    public record ChunkPos3D(int x, int y, int z) {}

    public record StructureConfig(long salt, int regionSize, int spacing) {}

    public static final StructureConfig TEMPLE_CONFIG  = new StructureConfig(14357617L, 32, 8);
    public static final StructureConfig VILLAGE_CONFIG = new StructureConfig(10387312L, 32, 8);
    public static final StructureConfig OUTPOST_CONFIG = new StructureConfig(165745296L, 32, 8);

    // LCG constants matching java.util.Random
    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long ADDEND     = 0xBL;
    private static final long MASK       = (1L << 48) - 1;

    // -----------------------------------------------------------------------
    // Structure data with bit calculation
    // -----------------------------------------------------------------------

    public static class StructureData {
        public final ChunkPos pos;
        public final ChunkPos3D pos3D; // 3D position with Y
        public final StructureConfig config;
        public final int regionX;
        public final int regionZ;
        public final int regionY; // Y region (for 3D structures like dungeons)
        public final int offsetX;
        public final int offsetZ;
        public final int offsetY; // Y offset
        public final long offsetSquared;
        public final long offsetSquared3D; // 3D offset squared

        public StructureData(ChunkPos pos, StructureConfig config) {
            this.pos = pos;
            this.pos3D = new ChunkPos3D(pos.x(), 0, pos.z()); // Default Y=0 for 2D structures
            this.config = config;
            this.regionX = Math.floorDiv(pos.x(), config.regionSize());
            this.regionZ = Math.floorDiv(pos.z(), config.regionSize());
            this.regionY = 0;
            this.offsetX = pos.x() - regionX * config.regionSize();
            this.offsetZ = pos.z() - regionZ * config.regionSize();
            this.offsetY = 0;
            this.offsetSquared = (long) offsetX * offsetX + (long) offsetZ * offsetZ;
            this.offsetSquared3D = this.offsetSquared;
        }

        // Constructor for 3D positions (dungeons, underground structures)
        public StructureData(ChunkPos3D pos3D, StructureConfig config) {
            this.pos3D = pos3D;
            this.pos = new ChunkPos(pos3D.x(), pos3D.z());
            this.config = config;
            this.regionX = Math.floorDiv(pos3D.x(), config.regionSize());
            this.regionZ = Math.floorDiv(pos3D.z(), config.regionSize());
            this.regionY = Math.floorDiv(pos3D.y(), config.regionSize());
            this.offsetX = pos3D.x() - regionX * config.regionSize();
            this.offsetZ = pos3D.z() - regionZ * config.regionSize();
            this.offsetY = pos3D.y() - regionY * config.regionSize();
            this.offsetSquared = (long) offsetX * offsetX + (long) offsetZ * offsetZ;
            this.offsetSquared3D = (long) offsetX * offsetX + (long) offsetY * offsetY + (long) offsetZ * offsetZ;
        }

        // Bits of information this structure provides (like SeedcrackerX)
        public double getBits() {
            return Math.log(offsetSquared3D) / Math.log(2);
        }

        // 3D bits (includes Y dimension)
        public double getBits3D() {
            return Math.log(offsetSquared3D) / Math.log(2);
        }
    }

    // -----------------------------------------------------------------------
    // Phase 1 — Lattice-based structure seed solver
    //
    // Key insight: For each structure, we have:
    //   regionSeed_r = (worldSeed + rX*A + rZ*B + salt) & MASK
    //   state_0 = (regionSeed_r * MULT + ADDEND) & MASK  
    //   offset = (int)(state_0 >>> 17) % spread
    //
    // This constrains: regionSeed_r mod spread
    //
    // With multiple structures in different regions, we get constraints on
    // different regionSeeds. The relationship between regionSeeds is:
    //   regionSeed_r1 - regionSeed_r2 = (r1X-r2X)*A + (r1Z-r2Z)*B (mod MASK)
    //
    // We can use lattice reduction to find candidate worldSeeds efficiently.
    // -----------------------------------------------------------------------

    public static List<Long> findStructureSeeds(List<StructureData> structures) {
        // Use GPU if available, fall back to CPU
        return findStructureSeedsWithGpu(structures);
    }

    public static List<Long> findStructureSeedsWithGpu(List<StructureData> structures) {
        if (structures.size() < 2) {
            // Fall back to direct solving with single structure
            return solveSingleStructure(structures.get(0));
        }

        List<Long> results = new ArrayList<>();
        StructureData anchor = structures.get(0);
        int spread = anchor.config.regionSize() - anchor.config.spacing();

        final long A = 341873128712L;
        final long B = 132897987541L;
        final long salt = anchor.config.salt();

        // Strategy: enumerate possible regionSeeds for anchor that give correct offset
        // Then for each, compute worldSeed and verify against all other structures
        //
        // We enumerate regionSeed values where:
        //   ((regionSeed * MULT + ADDEND) >>> 17) % spread == anchor.offsetX
        //
        // regionSeed = ((state - ADDEND) * modInverse(MULT, 2^48)) & MASK
        // where state = (x * spread + offsetX) << 17 for x in [0, 2^31/spread)

        long modInverseMult = modInverse(MULTIPLIER, MASK + 1);
        long numStates = (1L << 31) / spread; // Number of valid states for offsetX

        System.out.println("[SeedReverser] Enumerating " + numStates + " candidate states for anchor...");

        // Parallel enumeration
        int numCores = Math.max(1, Runtime.getRuntime().availableProcessors());
        ExecutorService executor = Executors.newFixedThreadPool(numCores);
        ConcurrentHashMap<Long, Boolean> resultsMap = new ConcurrentHashMap<>();

        long chunkSize = numStates / numCores;

        for (int thread = 0; thread < numCores; thread++) {
            final long startX = thread * chunkSize;
            final long endX = (thread == numCores - 1) ? numStates : startX + chunkSize;

            executor.submit(() -> {
                for (long x = startX; x < endX; x++) {
                    // Reconstruct state from offset
                    long state = (x * spread + anchor.offsetX) << 17;

                    // Reverse LCG to get regionSeed
                    long regionSeed = ((state - ADDEND) * modInverseMult) & MASK;

                    // Now compute worldSeed from regionSeed
                    // regionSeed = (worldSeed + rX*A + rZ*B + salt) & MASK
                    long worldSeed = (regionSeed - (long) anchor.regionX * A - (long) anchor.regionZ * B - salt) & MASK;

                    // Verify Z offset
                    long state2 = (regionSeed * MULTIPLIER + ADDEND) & MASK;
                    state2 = (state2 * MULTIPLIER + ADDEND) & MASK;
                    int offsetZ = (int) (state2 >>> 17) % spread;
                    if (offsetZ < 0) offsetZ += spread;
                    if (offsetZ != anchor.offsetZ) continue;

                    // Verify against all other structures
                    boolean valid = true;
                    for (int i = 1; i < structures.size(); i++) {
                        StructureData s = structures.get(i);
                        long sRegionSeed = (worldSeed + (long) s.regionX * A + (long) s.regionZ * B + s.config.salt()) & MASK;

                        long sState = (sRegionSeed * MULTIPLIER + ADDEND) & MASK;
                        int sOffsetX = (int) (sState >>> 17) % spread;
                        if (sOffsetX < 0) sOffsetX += spread;
                        if (s.regionX * s.config.regionSize() + sOffsetX != s.pos.x()) {
                            valid = false;
                            break;
                        }

                        sState = (sState * MULTIPLIER + ADDEND) & MASK;
                        int sOffsetZ = (int) (sState >>> 17) % spread;
                        if (sOffsetZ < 0) sOffsetZ += spread;
                        if (s.regionZ * s.config.regionSize() + sOffsetZ != s.pos.z()) {
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
            executor.awaitTermination(1, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        results.addAll(resultsMap.keySet());
        System.out.println("[SeedReverser] Found " + results.size() + " structure seed candidates");
        return results;
    }

    // GPU-accelerated variant
    public static List<Long> findStructureSeedsGpu(List<StructureData> structures) {
        return GpuSeedSolver.findStructureSeeds(new GpuSeedSolver.GpuConfig(true, 0), structures);
    }

    // Fallback for single structure: solve via direct enumeration
    private static List<Long> solveSingleStructure(StructureData anchor) {
        List<Long> results = new ArrayList<>();
        int spread = anchor.config.regionSize() - anchor.config.spacing();
        final long A = 341873128712L;
        final long B = 132897987541L;
        final long salt = anchor.config.salt();

        // For single structure, we can only narrow down to candidates
        // that match the offset. This is the full enumeration.
        long modInverseMult = modInverse(MULTIPLIER, MASK + 1);
        long numStates = (1L << 31) / spread;

        System.out.println("[SeedReverser] Single structure mode: enumerating " + numStates + " candidates...");

        // This will take a while but should complete
        for (long x = 0; x < numStates; x++) {
            long state = (x * spread + anchor.offsetX) << 17;
            long regionSeed = ((state - ADDEND) * modInverseMult) & MASK;
            long worldSeed = (regionSeed - (long) anchor.regionX * A - (long) anchor.regionZ * B - salt) & MASK;

            // Verify Z offset
            long state2 = (regionSeed * MULTIPLIER + ADDEND) & MASK;
            state2 = (state2 * MULTIPLIER + ADDEND) & MASK;
            int offsetZ = (int) (state2 >>> 17) % spread;
            if (offsetZ < 0) offsetZ += spread;
            if (offsetZ != anchor.offsetZ) continue;

            results.add(worldSeed);
        }

        System.out.println("[SeedReverser] Found " + results.size() + " candidates from single structure");
        return results;
    }

    // Extended Euclidean algorithm for modular inverse
    private static long modInverse(long a, long m) {
        long m0 = m;
        long y = 0, x = 1;

        if (m == 1) return 0;

        while (a > 1) {
            long q = a / m;
            long t = m;
            m = a % m;
            a = t;
            t = y;
            y = x - q * y;
            x = t;
        }

        if (x < 0) x += m0;
        return x;
    }

    // -----------------------------------------------------------------------
    // Phase 2 — lift 48-bit structure seed → 64-bit world seed via slime chunks
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
                    long worldSeed = (upper << 48) | (structureSeed & MASK);
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
