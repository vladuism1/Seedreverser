package com.vladuism.seedreverser;

import kaptainwutax.seedcrackerX.api.SeedCrackerAPI;

/**
 * SeedcrackerX API integration.
 * SeedcrackerX calls pushWorldSeed() when it cracks a world seed,
 * and this mod receives and displays it.
 */
public class SeedCrackerEP implements SeedCrackerAPI {

    @Override
    public void pushWorldSeed(long seed) {
        Seedreverser.receiveExternalSeed(seed);
    }
}