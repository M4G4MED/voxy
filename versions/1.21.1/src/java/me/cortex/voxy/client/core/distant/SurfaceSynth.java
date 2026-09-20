package me.cortex.voxy.client.core.distant;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.material.Fluids;

import java.util.List;
import java.util.function.Predicate;

/** Fills chunks that were never generated or saved by running only the
 *  biome/noise/surface steps of the world's own generator on a background
 *  thread (no structure placement, carvers, features, or vanilla lighting),
 *  then feeding the result through the normal ingest path with a column-wise
 *  sky light approximation. Never touches the server's chunk map or tickets. */
public class SurfaceSynth {

    /** Structure lookups are stubbed to "nothing here" so the noise stage's
     *  beardifier never asks the chunk system to generate real chunks. */
    private static class StructureFreeManager extends StructureManager {
        StructureFreeManager(ServerLevel level, WorldOptions options, StructureCheck check) {
            super(level, options, check);
        }

        @Override
        public List<StructureStart> startsForStructure(ChunkPos pos, Predicate<Structure> filter) {
            return List.of();
        }

        @Override
        public List<StructureStart> startsForStructure(SectionPos pos, Structure structure) {
            return List.of();
        }
    }

    public static boolean isSupported(NoiseBasedChunkGenerator generator) {
        return true;
    }

    /** Generates an approximation of one chunk and ingests it. Must be called
     *  from a background thread. Returns true if any sections were ingested. */
    public static boolean synthAndIngest(ServerLevel level, WorldEngine engine, int cx, int cz) {
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
            return false;
        }
        MinecraftServer server = level.getServer();
        try {
            var pos = new ChunkPos(cx, cz);
            var randomState = level.getChunkSource().randomState();

            var structureCheck = new StructureCheck(
                    level.getChunkSource().chunkScanner(),
                    level.registryAccess(),
                    server.getStructureManager(),
                    level.dimension(),
                    generator,
                    randomState,
                    level,
                    generator.getBiomeSource(),
                    level.getSeed(),
                    server.getFixerUpper());
            var structureManager = new StructureFreeManager(level, server.getWorldData().worldGenOptions(), structureCheck);

            var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, level, level.registryAccess().registryOrThrow(Registries.BIOME), null);

            generator.createBiomes(randomState, Blender.empty(), structureManager, chunk).join();
            // doFill primes WORLD_SURFACE_WG internally, which is what the surface rules read
            generator.fillFromNoise(Blender.empty(), randomState, structureManager, chunk).join();
            generator.buildSurface(
                    chunk,
                    new WorldGenerationContext(generator, level),
                    randomState,
                    structureManager,
                    // fresh wrapper per chunk: vanilla hands each worldgen context its own BiomeManager
                    new BiomeManager((qx, qy, qz) -> generator.getBiomeSource().getNoiseBiome(qx, qy, qz, randomState.sampler()),
                            BiomeManager.obfuscateSeed(level.getSeed())),
                    level.registryAccess().registryOrThrow(Registries.BIOME),
                    Blender.empty());

            return ingestApproximatedChunk(engine, chunk);
        } catch (Throwable t) {
            if (!(t instanceof java.util.concurrent.CancellationException)) {
                Logger.error("Distant terrain: synthesis failed for chunk " + cx + "," + cz, t);
            }
            return false;
        }
    }

    private static boolean ingestApproximatedChunk(WorldEngine engine, ProtoChunk chunk) {
        var emptyBlockLight = new DataLayer();
        int minSection = chunk.getMinSection();
        var sections = chunk.getSections();

        // Column sky light computed across the entire chunk height so that
        // attenuation carries across section boundaries (light does not reset
        // at section tops). Indexed [column][section*16 + localY], bottom-up.
        int sectionCount = sections.length;
        byte[] columnLight = new byte[16 * 16 * sectionCount * 16];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int light = 15;
                int outBase = (z << 4 | x) * sectionCount * 16;
                for (int sy = sectionCount - 1; sy >= 0; sy--) {
                    var states = sections[sy].getStates();
                    for (int ly = 15; ly >= 0; ly--) {
                        var state = states.get(x, ly, z);
                        int opacity;
                        if (state.isAir()) {
                            opacity = 0;
                        } else {
                            var fluid = state.getFluidState();
                            if (!fluid.isEmpty()) {
                                opacity = fluid.is(Fluids.WATER) ? 3 : 1;
                            } else {
                                opacity = state.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                            }
                        }
                        light = opacity >= 15 ? 0 : Math.max(0, light - opacity);
                        columnLight[outBase + sy * 16 + ly] = (byte) light;
                    }
                }
            }
        }

        boolean any = false;
        for (int i = 0; i < sectionCount; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            byte[] sky = new byte[2048];//nibble packed: idx = (ly<<8)|(z<<4)|x, even idx = low nibble
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int colBase = (z << 4 | x) * sectionCount * 16 + i * 16;
                    for (int ly = 0; ly < 16; ly++) {
                        int v = columnLight[colBase + ly] & 15;
                        int idx = (ly << 8) | (z << 4) | x;
                        if ((idx & 1) == 0) {
                            sky[idx >> 1] = (byte) ((sky[idx >> 1] & 0xF0) | v);
                        } else {
                            sky[idx >> 1] = (byte) ((sky[idx >> 1] & 0x0F) | (v << 4));
                        }
                    }
                }
            }
            if (!VoxelIngestService.rawIngest(engine, section, chunk.getPos().x, minSection + i, chunk.getPos().z, emptyBlockLight, new DataLayer(sky))) {
                break;
            }
            any = true;
        }
        return any;
    }
}
