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

1. **Phase 1 (Structure Seed)**: Brute forces the 48-bit structure seed using captured temple positions. Uses multi-threading for faster processing.

2. **Phase 2 (World Seed)**: If slime chunks are captured, lifts the 48-bit structure seed to a 64-bit world seed by testing the upper 16 bits.

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

Uses seedfinding libraries:
- latticg
- mc_math
- mc_seed
- mc_core
- mc_reversal

## License

CC0 1.0 Universal (Public Domain Dedication)
