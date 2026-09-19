package me.cortex.voxy.commonImpl.compat.sable;

import me.cortex.voxy.common.Logger;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class SableClientSkyLightCache {
    private static final long SKY_LIGHT_TTL_TICKS = 400L;
    private static final long SWEEP_INTERVAL_TICKS = 20L;
    private static final int MAX_CACHED_SECTIONS = 4096;

    private static final Map<ClientLevel, CacheState> CACHES = new WeakHashMap<>();
    private static boolean unavailable;

    private SableClientSkyLightCache() {
    }

    // All entry points (packet handling, ClientLevel.tick, entity/particle light
    // sampling) run on the client main thread, so no locking is required. The old
    // synchronized + WeakHashMap lookups ran per entity/particle per frame via
    // revision() and per chunk packet; on contraption-heavy scenes that was measurable.
    private static volatile long revision;

    // The cache is only ever read by the sub-level sky-light fallback. Without Sable
    // installed, unpacking sky sections for every chunk packet was pure waste on the
    // hot network-thread path during world load.
    private static Boolean sablePresent;

    private static boolean isSablePresent() {
        Boolean present = sablePresent;
        if (present == null) {
            try {
                Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
                present = Boolean.TRUE;
            } catch (Throwable t) {
                present = Boolean.FALSE;
            }
            sablePresent = present;
        }
        return present;
    }

    public static void cacheFromPacket(ClientLevel level, ClientboundLevelChunkWithLightPacket packet) {
        if (unavailable || !isSablePresent()) {
            return;
        }

        try {
            PacketLightData lightData = PacketLightData.from(level, packet.getLightData());
            if (lightData == null || !lightData.hasSkyLight()) {
                return;
            }

            CacheState state = CACHES.computeIfAbsent(level, ignored -> new CacheState());
            int chunkX = packet.getX();
            int chunkZ = packet.getZ();
            int minSection = level.getMinSection();
            int sectionCount = level.getSectionsCount();
            long gameTime = level.getGameTime();
            boolean cachedAny = false;

            for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
                DataLayer skyLight = lightData.skyLight(sectionIndex);
                if (skyLight == null) {
                    continue;
                }

                // unpackLayer already produces DataLayers backed by fresh cloned
                // arrays that vanilla never aliases, so caching them directly is
                // safe; the previous extra copy() doubled the per-packet garbage.
                state.skyLightSections.put(
                        SectionPos.asLong(chunkX, minSection + sectionIndex, chunkZ),
                        new CachedSkyLight(skyLight, gameTime)
                );
                cachedAny = true;
            }

            if (cachedAny) {
                revision++;
            }
            enforceMaxSize(state);
        } catch (RuntimeException | LinkageError e) {
            Logger.error("Disabling Sable sky light packet cache after packet light read failed", e);
            unavailable = true;
            CACHES.clear();
        }
    }

    public static int getSkyLight(ClientLevel level, BlockPos pos) {
        CacheState state = CACHES.get(level);
        if (state == null || state.skyLightSections.isEmpty()) {
            return -1;
        }

        int sectionY = SectionPos.blockToSectionCoord(pos.getY());
        if (sectionY < level.getMinSection() || sectionY >= level.getMaxSection()) {
            return -1;
        }

        int chunkX = SectionPos.blockToSectionCoord(pos.getX());
        int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
        long sectionKey = SectionPos.asLong(chunkX, sectionY, chunkZ);
        CachedSkyLight cached = state.skyLightSections.get(sectionKey);
        if (cached == null) {
            return -1;
        }

        long gameTime = level.getGameTime();
        if (gameTime - cached.gameTime > SKY_LIGHT_TTL_TICKS) {
            state.skyLightSections.remove(sectionKey);
            return -1;
        }

        if (cached.skyLight.isEmpty()) {
            return 0;
        }

        return Math.min(15, cached.skyLight.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15));
    }

    public static long revision(ClientLevel level) {
        return revision;
    }

    public static void tick(ClientLevel level) {
        try {
            CacheState state = CACHES.get(level);
            if (state == null) {
                return;
            }

            long gameTime = level.getGameTime();
            if (gameTime < state.nextSweepGameTime) {
                return;
            }

            state.nextSweepGameTime = gameTime + SWEEP_INTERVAL_TICKS;
            Iterator<Map.Entry<Long, CachedSkyLight>> iterator = state.skyLightSections.entrySet().iterator();
            boolean removedAny = false;
            while (iterator.hasNext()) {
                CachedSkyLight cached = iterator.next().getValue();
                if (cached == null || gameTime - cached.gameTime > SKY_LIGHT_TTL_TICKS) {
                    iterator.remove();
                    removedAny = true;
                }
            }

            if (removedAny) {
                revision++;
            }
            if (state.skyLightSections.isEmpty()) {
                CACHES.remove(level);
            }
        } catch (RuntimeException e) {
            Logger.error("Clearing Sable sky light packet cache after sweep failed", e);
            CACHES.remove(level);
        }
    }

    public static void clear(ClientLevel level) {
        CACHES.remove(level);
    }

    private static void enforceMaxSize(CacheState state) {
        Iterator<Long> iterator = state.skyLightSections.keySet().iterator();
        while (state.skyLightSections.size() > MAX_CACHED_SECTIONS && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private static final class CacheState {
        private final Map<Long, CachedSkyLight> skyLightSections = new HashMap<>();
        private long nextSweepGameTime;
    }

    private record CachedSkyLight(DataLayer skyLight, long gameTime) {
    }

    private record PacketLightData(DataLayer[] skyLight) {
        private static @Nullable PacketLightData from(ClientLevel level, ClientboundLightUpdatePacketData lightData) {
            DataLayer[] skyLight = new DataLayer[level.getSectionsCount()];
            boolean hasSkyLight = unpackLayer(
                    level,
                    lightData.getSkyYMask(),
                    lightData.getEmptySkyYMask(),
                    lightData.getSkyUpdates(),
                    skyLight
            );

            if (!hasSkyLight) {
                return null;
            }

            return new PacketLightData(skyLight);
        }

        private static boolean unpackLayer(ClientLevel level, BitSet dataMask, BitSet emptyMask, List<byte[]> updates, DataLayer[] target) {
            LevelLightEngine lightEngine = level.getLightEngine();
            int minLightSection = lightEngine.getMinLightSection();
            int lightSectionCount = lightEngine.getLightSectionCount();
            int minChunkSection = level.getMinSection();
            boolean foundData = false;
            Iterator<byte[]> iterator = updates.iterator();

            for (int lightSectionIndex = 0; lightSectionIndex < lightSectionCount; lightSectionIndex++) {
                int sectionY = minLightSection + lightSectionIndex;
                boolean hasData = dataMask.get(lightSectionIndex);
                boolean empty = emptyMask.get(lightSectionIndex);
                if (!hasData && !empty) {
                    continue;
                }

                DataLayer layer;
                if (hasData) {
                    if (!iterator.hasNext()) {
                        return foundData;
                    }
                    layer = new DataLayer(iterator.next().clone());
                } else {
                    layer = new DataLayer();
                }

                int sectionIndex = sectionY - minChunkSection;
                if (sectionIndex < 0 || sectionIndex >= target.length) {
                    continue;
                }

                target[sectionIndex] = layer;
                foundData = true;
            }

            return foundData;
        }

        private @Nullable DataLayer skyLight(int sectionIndex) {
            return this.skyLight[sectionIndex];
        }

        private boolean hasSkyLight() {
            for (DataLayer layer : this.skyLight) {
                if (layer != null) {
                    return true;
                }
            }
            return false;
        }
    }
}
