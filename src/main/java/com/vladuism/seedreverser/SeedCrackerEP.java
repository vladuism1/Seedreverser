package com.vladuism.seedreverser;

import kaptainwutax.seedcrackerX.api.SeedCrackerAPI;

/**
 * SeedcrackerX API integration.
 * SeedcrackerX calls pushWorldSeed() when it cracks a world seed,
 * and this mod receives and displays it.
 *
 * NOTE: The cracking itself is done by SeedcrackerX. This mod is a
 * receiver/companion - it does not crack seeds through this API.
 */
public class SeedCrackerEP implements SeedCrackerAPI {

    @Override
    public void pushWorldSeed(long seed) {
        // Store the seed received from SeedcrackerX
        Seedreverser.receiveExternalSeed(seed);
    }
}
