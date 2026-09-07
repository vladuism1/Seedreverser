package kaptainwutax.seedcrackerX.api;

// Vendored copy of the SeedcrackerX API interface (single method, matches upstream exactly):
// https://github.com/19MisterX98/SeedcrackerX/blob/master/src/main/java/kaptainwutax/seedcrackerX/api/SeedCrackerAPI.java
// At runtime, if SeedcrackerX is installed, its own copy of this interface is used.
public interface SeedCrackerAPI {

    void pushWorldSeed(long seed);

}
