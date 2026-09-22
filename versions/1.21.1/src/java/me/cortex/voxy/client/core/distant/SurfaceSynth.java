package me.cortex.voxy.client.core.distant;

import com.google.common.collect.ImmutableList;
import me.cortex.voxy.client.config.DistantTerrainConfig;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkDependencies;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStatusTask;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.material.Fluids;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Registry;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/** Fills chunks that were never generated or saved by running the world's own
 *  biome/noise/surface steps on a background thread, plus (optionally) the
 *  vanilla feature-decoration pass so that distant terrain gets trees and
 *  other surface features. Structure placement, carvers, and vanilla lighting
 *  are still skipped. Never touches the server's chunk map or tickets.
 *
 *  Feature decoration runs against a 3x3 window of surface chunks (biomes +
 *  noise + surface, but no features) so that placement logic sees the same
 *  neighbourhood it would during real worldgen. Neighbour results are memoized
 *  and shared between jobs. Decoration only ever writes into the center chunk;
 *  blocks spilling past the chunk border are discarded, which keeps every
 *  worker independent (a feature's result depends only on its own chunk). */
public class SurfaceSynth {

    private static final ConcurrentHashMap<ServerLevel, SynthContext> CONTEXTS = new ConcurrentHashMap<>();

    private static final EnumSet<Heightmap.Types> DECORATION_HEIGHTMAPS = EnumSet.of(
            Heightmap.Types.WORLD_SURFACE, Heightmap.Types.OCEAN_FLOOR,
            Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);

    /** Structure lookups are stubbed to "nothing here" so neither the noise
     *  stage's beardifier nor the decoration step asks the chunk system to
     *  generate real chunks. */
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

    /** Per-world generation state. All fields are safe for concurrent use; the
     *  surface-chunk cache deduplicates neighbour synthesis across workers. */
    private static final class SynthContext {
        private final ServerLevel level;
        private final NoiseBasedChunkGenerator generator;
        private final StructureManager structureManager;
        private final RandomState randomState;
        private final Registry<Biome> biomeRegistry;
        private final ChunkStep surfaceStep;
        private final ChunkStep featuresStep;
        // packed chunk pos -> surface-only chunk (biomes+noise+surface, heightmaps primed)
        private final ConcurrentHashMap<Long, SurfaceChunk> surfaceChunks = new ConcurrentHashMap<>();
        private final AtomicInteger decorErrorBudget = new AtomicInteger(64);
        // Vanilla ore features write across chunk borders through BulkSectionAccess,
        // which acquire/release-locks the PalettedContainers of the *shared* cached
        // neighbours. Two workers decorating adjacent chunks collide on one container:
        // ThreadingDetector throws AND leaks the semaphore permit (its throw path never
        // releases), permanently poisoning that cached chunk - later workers touching it
        // block forever holding a world ref (holes that never heal + exit deadlock).
        // Decoration is vanilla-global-writer-like anyway (spill mutates neighbours),
        // so it runs single-at-a-time; surface generation and ingest stay parallel.
        private final Object decorationLock = new Object();

        // Only consumed by WorldGenRegion for directDependencies()/blockStateWriteRadius();
        // mirrors vanilla's FEATURES pipeline shape (write radius 1 chunk).
        private static final ChunkStatusTask NOOP_TASK = (context, step, cache, chunk) -> CompletableFuture.completedFuture(chunk);

        SynthContext(ServerLevel level, NoiseBasedChunkGenerator generator) {
            this.level = level;
            this.generator = generator;
            this.randomState = level.getChunkSource().randomState();
            this.biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
            MinecraftServer server = level.getServer();
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
            this.structureManager = new StructureFreeManager(level, server.getWorldData().worldGenOptions(), structureCheck);
            this.surfaceStep = new ChunkStep(ChunkStatus.SURFACE,
                    new ChunkDependencies(ImmutableList.of(ChunkStatus.NOISE)),
                    new ChunkDependencies(ImmutableList.of(ChunkStatus.SURFACE)), 1, NOOP_TASK);
            this.featuresStep = new ChunkStep(ChunkStatus.FEATURES,
                    new ChunkDependencies(ImmutableList.of(ChunkStatus.CARVERS)),
                    new ChunkDependencies(ImmutableList.of(ChunkStatus.FEATURES)), 1, NOOP_TASK);
        }

        private static long key(int x, int z) {
            return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
        }

        /** Surface chunk for (cx,cz) shared with decoration jobs of neighbour chunks.
         *  Returns null if this chunk's surface generation failed (retried later). */
        SurfaceChunk surfaceChunkAt(int cx, int cz) {
            SurfaceChunk existing = surfaceChunks.get(key(cx, cz));
            if (existing != null) {
                return existing;
            }
            // Cap the cache size; neighbours are only referenced while their
            // centre is decorated (a few dozen live at a time), so a wholesale
            // clear is cheap (regenerated on demand) and bounds memory.
            if (surfaceChunks.size() > 512) {
                surfaceChunks.clear();
            }
            SurfaceChunk fresh = new SurfaceChunk(new ChunkPos(cx, cz));
            try {
                generateSurface(fresh);
            } catch (Throwable t) {
                if (!(t instanceof java.util.concurrent.CancellationException)) {
                    Logger.error("Distant terrain: surface synthesis failed for chunk " + cx + "," + cz, t);
                }
                return null;
            }
            SurfaceChunk raced = surfaceChunks.putIfAbsent(key(cx, cz), fresh);
            return raced != null ? raced : fresh;
        }

        private void generateSurface(SurfaceChunk chunk) {
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
                    biomeRegistry,
                    Blender.empty());
            // Vanilla primes these during the carver step; our chunks skip carvers, so
            // prime them from the surface results — feature placement reads them for
            // ground checks, and decoration.setBlock keeps them incrementally updated.
            Heightmap.primeHeightmaps(chunk, DECORATION_HEIGHTMAPS);
        }

        /** Generates one chunk (surface always; features optionally) and returns
         *  a private copy ready for ingest. Null on failure. */
        ChunkAccess synthesizeCenter(int cx, int cz, boolean decorate) {
            SurfaceChunk surface = surfaceChunkAt(cx, cz);
            if (surface == null) {
                return null;
            }
            if (!decorate) {
                return surface;
            }
            ChunkAccess copy;
            DecorationRegion region;
            // One decorator at a time: vanilla features (OreFeature via
            // BulkSectionAccess) write into the shared cached neighbours, whose
            // PalettedContainers are not thread-safe and whose ThreadingDetector
            // throws leak a semaphore permit (poisoned container = permanent hang
            // for the next accessor). The deep copy reads those same cached
            // neighbours, so it is guarded by the same lock (no torn palette
            // reads). See decorationLock comment.
            synchronized (decorationLock) {
                copy = copyForDecoration(surface);
                region = new DecorationRegion(copy, level, featuresStep);
                try {
                    generator.applyBiomeDecoration(region, copy, structureManager);
                } catch (Throwable t) {
                    if (decorErrorBudget.getAndDecrement() > 0) {
                        Logger.error("Distant terrain: decoration failed for chunk " + cx + "," + cz
                                + " (further errors logged with a budget)", t);
                    }
                    //Degrade rather than fail: the copy still holds correct surface
                    //terrain (possibly minus some features). Failing the chunk instead
                    //would just re-queue it into the same crash.
                }
            }
            return copy;
        }

        /** Deep copy so decoration writes never race with neighbour jobs reading
         *  this chunk as surface context (and vice versa). */
        private ChunkAccess copyForDecoration(SurfaceChunk src) {
            // A SurfaceChunk (not a raw ProtoChunk): its reported status must be
            // SURFACE-or-after or vanilla biome reads reject the chunk outright
            // ("Asking for biomes before we have biomes"), which kills every
            // biome-filtered feature on the chunk.
            SurfaceChunk copy = new SurfaceChunk(src.getPos());
            // Biomes first (same order vanilla uses), read straight from the source
            // chunk: this is what biome filters compare against for feature sets.
            copy.fillBiomesFromNoise((x, y, z, sampler) -> src.getNoiseBiome(x, y, z), randomState.sampler());
            LevelChunkSection[] sections = src.getSections();
            int minSection = src.getMinSection();
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            int baseX = src.getPos().getMinBlockX();
            int baseZ = src.getPos().getMinBlockZ();
            for (int i = 0; i < sections.length; i++) {
                LevelChunkSection section = sections[i];
                if (section.hasOnlyAir()) {
                    continue;
                }
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockState state = section.getBlockState(x, y, z);
                            if (!state.isAir()) {
                                copy.setBlockState(cursor.set(baseX + x, (minSection + i) * 16 + y, baseZ + z), state, false);
                            }
                        }
                    }
                }
            }
            // Biomes are copied in (same order vanilla uses): this is what biome
            // filters compare against when deciding which feature sets apply here.
            Heightmap.primeHeightmaps(copy, DECORATION_HEIGHTMAPS);
            return copy;
        }

        void clearCache() {
            surfaceChunks.clear();
        }

        /** A ProtoChunk whose persisted status claims SURFACE: WorldGenRegion.setBlock
         *  branches on this for block-entity blocks (a ProtoChunk with a "PROTO" status
         *  would take a path writing NBT through an optional field vanilla only sets
         *  during the structure-references step). Reported status is only used by that
         *  branch inside our synth flow. */
        private final class SurfaceChunk extends ProtoChunk {
            SurfaceChunk(ChunkPos pos) {
                super(pos, UpgradeData.EMPTY, level, biomeRegistry, null);
            }

            @Override
            public ChunkStatus getPersistedStatus() {
                return ChunkStatus.SURFACE;
            }
        }

        private static final class NullHolder extends GenerationChunkHolder {
            NullHolder(ChunkPos pos) {
                super(pos);
            }

            @Override
            public int getTicketLevel() {
                return 0;
            }

            @Override
            public int getQueueLevel() {
                return 0;
            }
        }

        /** WorldGenRegion is abstract (its chunk-holder cache cannot be populated
         *  off-thread from outside the server chunk system), so we provide the
         *  3x3 window and write confinement directly. */
        private final class DecorationRegion extends WorldGenRegion {
            private final ChunkAccess center;
            private final int centerX;
            private final int centerZ;

            DecorationRegion(ChunkAccess center, ServerLevel lvl, ChunkStep step) {
                // Window size is computed from the radius arg (2*0+1 = 1 entry); the
                // constructor never reads the cache, and our overrides bypass it.
                // (level/featuresStep arrive as params: an inner class may not touch
                //  the enclosing instance while its super() is still running.)
                super(lvl, StaticCache2D.create(center.getPos().x, center.getPos().z, 0,
                                (x, z) -> new NullHolder(new ChunkPos(x, z))),
                        step, center);
                this.center = center;
                this.centerX = center.getPos().x;
                this.centerZ = center.getPos().z;
            }

            @Override
            public ChunkAccess getChunk(int x, int z, ChunkStatus requiredStatus, boolean create) {
                // Some vanilla features (e.g. large dripstone) probe slightly beyond
                // the 3x3 window a normal WorldGenRegion guarantees. Clamp reads to
                // the nearest edge chunk: the surface is continuous, so the terrain
                // they see is right for LoD purposes, and all writes are confined
                // to the center anyway (see setBlock), keeping results stable.
                int cx = Math.max(centerX - 1, Math.min(centerX + 1, x));
                int cz = Math.max(centerZ - 1, Math.min(centerZ + 1, z));
                if (cx == centerX && cz == centerZ) {
                    return center;
                }
                SurfaceChunk neighbour = surfaceChunkAt(cx, cz);
                return neighbour != null ? neighbour : center;
            }

            @Override
            public boolean hasChunk(int x, int z) {
                return Math.abs(x - centerX) <= 1 && Math.abs(z - centerZ) <= 1;
            }

            @Override
            public boolean isOldChunkAround(ChunkPos pos, int radius) {
                return false;
            }

            @Override
            public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
                // Discard cross-chunk spillover: keeps the result independent of
                // which chunk happened to be decorated first.
                if (SectionPos.blockToSectionCoord(pos.getX()) != centerX
                        || SectionPos.blockToSectionCoord(pos.getZ()) != centerZ) {
                    return false;
                }
                return super.setBlock(pos, state, flags, recursionLeft);
            }

            @Override
            public boolean destroyBlock(BlockPos pos, boolean dropBlock) {
                return false;
            }

            @Override
            public boolean removeBlock(BlockPos pos, boolean moving) {
                return false;
            }
        }
    }

    public static boolean isSupported(NoiseBasedChunkGenerator generator) {
        return true;
    }

    /** Call when a world goes away so per-world caches are not retained. */
    public static void clearCache(ServerLevel level) {
        SynthContext ctx = CONTEXTS.remove(level);
        if (ctx != null) {
            ctx.clearCache();
        }
    }

    /** Drop every world's caches (manager shutdown). */
    public static void clearAllCaches() {
        CONTEXTS.values().forEach(SynthContext::clearCache);
        CONTEXTS.clear();
    }

    /** Generates an approximation of one chunk and ingests it. Must be called
     *  from a background thread. Returns true if any sections were ingested. */
    public static boolean synthAndIngest(ServerLevel level, WorldEngine engine, int cx, int cz) {
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
            return false;
        }
        boolean decorate = DistantTerrainConfig.get().decorate_with_features;
        try {
            SynthContext ctx = CONTEXTS.computeIfAbsent(level, l -> new SynthContext(l, generator));
            ChunkAccess result = ctx.synthesizeCenter(cx, cz, decorate);
            if (result == null) {
                return false;
            }
            return ingestApproximatedChunk(engine, result);
        } catch (Throwable t) {
            if (!(t instanceof java.util.concurrent.CancellationException)) {
                Logger.error("Distant terrain: synthesis failed for chunk " + cx + "," + cz, t);
            }
            return false;
        }
    }

    private static boolean ingestApproximatedChunk(WorldEngine engine, ChunkAccess chunk) {
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
                //Ingest failed mid-column; the column is incomplete. Report failure so the
                //chunk is retried instead of being marked done with a permanent hole.
                return false;
            }
            any = true;
        }
        return any;
    }
}
