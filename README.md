# SeedReverser

A Fabric mod for Minecraft 26.2 that reverse-engineers world seeds from in-game structure positions and slime chunk data.

## Features

- **Passive Temple Detection**: Automatically detects desert pyramids, jungle pyramids, swamp huts, and igloos as chunks load
- **Passive Slime Chunk Detection**: Automatically detects slime chunks near players
- **Multi-threaded Seed Solving**: Uses all available CPU cores for faster seed calculation
- **Command Interface**: `/seedreverser solve`, `/seedreverser status`, `/seedreverser clear`

## Commands

| Command | Description |
|---------|-------------|
| `/seedreverser solve` | Solve for the world seed using captured temples and slime chunks |
| `/seedreverser status` | Show how many temples and slime chunks have been captured |
| `/seedreverser clear` | Clear all captured data |

## How It Works

1. **Phase 1 (Structure Seed)**: Uses the Seedfinding libraries' structure placement math. A mod-4 filter narrows 2^19 lower-bits candidates, then verifies the remaining combos against every captured structure with `Feature.Data.testStart()`. Each captured structure cuts the search space by ~2^16, so a handful of structures solves in seconds to minutes — no blind 2^48 brute force.

2. **Phase 2 (World Seed)**: If slime chunks are captured, lifts the 48-bit structure seed to a 64-bit world seed by testing the upper 16 bits (65,536 candidates) with the vanilla slime chunk formula.

3. **SeedcrackerX API integration**: If [SeedcrackerX](https://github.com/19MisterX98/SeedcrackerX) is installed, this mod also receives its cracked world seed via the `seedcrackerx` entrypoint and displays it with `/seedreverser seed`.

## Requirements

- Minecraft 26.2
- Fabric Loader 0.19.5+
- Fabric API

## Building

```bash
./gradlew build
```

The built jar will be in `build/libs/`.

## Credits

Created by vladuism1.

- **SeedcrackerX** (https://github.com/19MisterX98/SeedcrackerX) by KaptainWutax & 19MisterX98 — MIT License, Copyright (c) 2020 KaptainWutax. The Phase 1 lifting algorithm in `StructureSeedSolver` is adapted from SeedcrackerX's `TimeMachine.pokeLifting()`. MIT license requires this attribution — please keep it.
- **Seedfinding libraries** (mc_core, mc_seed, mc_feature, mc_biome, mc_terrain, mc_noise, mc_math, mc_reversal, latticg) by the Seedfinding team — MIT License. Bundled jar-in-jar, pinned to SeedcrackerX's known-compatible commits.

## License

CC0 1.0 Universal (Public Domain Dedication)
