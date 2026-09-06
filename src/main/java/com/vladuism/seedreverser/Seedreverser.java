package com.vladuism.seedreverser;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.network.chat.Component;
import java.util.List;

public class Seedreverser implements ModInitializer {
	public static final String MOD_ID = "seedreverser";

	@Override
	public void onInitialize() {
		// Register /findseed command
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(CommandManager.literal("findseed")
					.executes(context -> {
						context.getSource().sendSystemMessage(Component.literal("Solving seed..."));

						// Example chunk coordinates of 3 structures
						List<StructureSeedSolver.ChunkPos> temples = List.of(
								new StructureSeedSolver.ChunkPos(12, -45),
								new StructureSeedSolver.ChunkPos(-30, 22),
								new StructureSeedSolver.ChunkPos(55, 60)
						);

						// 1. Solve 48-bit structure seed
						List<Long> structureSeeds = StructureSeedSolver.findStructureSeeds(
								StructureSeedSolver.TEMPLE_CONFIG,
								temples
						);

						// 2. Lift to 64-bit using known Slime Chunks
						List<StructureSeedSolver.ChunkPos> slimes = List.of(
								new StructureSeedSolver.ChunkPos(3, 5),
								new StructureSeedSolver.ChunkPos(-12, 8)
						);

						for (long structSeed : structureSeeds) {
							List<Long> worldSeeds = StructureSeedSolver.liftTo64Bit(structSeed, slimes);
							for (long worldSeed : worldSeeds) {
								context.getSource().sendSystemMessage(
										Component.literal("World seed: " + worldSeed)
								);
							}
						}

						return 1;
					}));
		});
	}
}